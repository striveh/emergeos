package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "emerge.prototype.principal-id=capture-http-owner")
class CaptureHttpTest extends PostgresApiTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private DataSource dataSource;

  @Autowired private PostgresCaptureStore captureStore;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    JdbcClient.create(dataSource)
        .sql("TRUNCATE TABLE artifact_versions, artifacts, captures")
        .update();
  }

  @Test
  void returnsTheOriginalCaptureForReplayAndAnExplicitNonLeakingConflict() throws Exception {
    String request =
        """
        {
          "clientNonce": "http-replay-1",
          "content": "synthetic replay thought",
          "sourceType": "TEXT",
          "sourceRef": "capture-http-test",
          "dataClass": "PERSONAL"
        }
        """;
    MvcResult first =
        mockMvc
            .perform(
                post("/api/v1/captures")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/captures/")))
            .andExpect(jsonPath("$.principalId").value("capture-http-owner"))
            .andExpect(
                jsonPath("$.requestHash")
                    .value(
                        CaptureRequestHashes.sha256(
                            "synthetic replay thought",
                            CaptureSourceType.TEXT,
                            "capture-http-test",
                            DataClass.PERSONAL)))
            .andReturn();

    String originalBody = first.getResponse().getContentAsString();
    mockMvc
        .perform(
            post("/api/v1/captures")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(content().json(originalBody, JsonCompareMode.STRICT));

    mockMvc
        .perform(
            post("/api/v1/captures")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.replace("synthetic replay thought", "changed thought")))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:emergeos:problem:capture-nonce-conflict"))
        .andExpect(jsonPath("$.title").value("Capture nonce conflict"))
        .andExpect(
            jsonPath("$.detail")
                .value("clientNonce was already used for a different Capture request"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic replay thought"))));

    assertEquals(
        1,
        JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM captures")
            .query(Integer.class)
            .single());
  }

  @Test
  void derivesPrincipalAndRequestHashOnlyFromServerOwnedInputs() throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/captures")
                    .header("X-Principal-Id", "attacker")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientNonce": "server-owned-inputs",
                          "content": "synthetic configured identity",
                          "sourceType": "TEXT",
                          "sourceRef": "capture-http-test",
                          "dataClass": "PERSONAL"
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.principalId").value("capture-http-owner"))
            .andReturn();
    String serverHash = JsonPath.read(created.getResponse().getContentAsString(), "$.requestHash");
    assertNotEquals("0".repeat(64), serverHash);

    mockMvc
        .perform(
            post("/api/v1/captures")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "principalId": "attacker",
                      "clientNonce": "body-principal",
                      "content": "synthetic configured identity",
                      "sourceType": "TEXT",
                      "sourceRef": "capture-http-test",
                      "dataClass": "PERSONAL"
                    }
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/captures")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requestHash": "0000000000000000000000000000000000000000000000000000000000000000",
                      "clientNonce": "body-request-hash",
                      "content": "synthetic configured identity",
                      "sourceType": "TEXT",
                      "sourceRef": "capture-http-test",
                      "dataClass": "PERSONAL"
                    }
                    """))
        .andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(strings = {"TEXT", "LINK", "VOICE_FILE"})
  void roundTripsEachS1CaptureSourceType(String sourceType) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/captures")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientNonce": "source-%s",
                      "content": "synthetic source capture",
                      "sourceType": "%s",
                      "sourceRef": "synthetic-local-reference",
                      "dataClass": "PUBLIC"
                    }
                    """
                        .formatted(sourceType.toLowerCase(), sourceType)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sourceType").value(sourceType))
        .andExpect(jsonPath("$.sourceRef").value("synthetic-local-reference"));
  }

  @Test
  void rejectsSensitiveAndSecretContentAtThePersistentBoundary() throws Exception {
    for (String dataClass : new String[] {"SENSITIVE", "SECRET"}) {
      mockMvc
          .perform(
              post("/api/v1/captures")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "clientNonce": "rejected-%s",
                        "content": "synthetic protected value",
                        "sourceType": "TEXT",
                        "sourceRef": "capture-http-test",
                        "dataClass": "%s"
                      }
                      """
                          .formatted(dataClass.toLowerCase(), dataClass)))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void foreignOwnerAndMissingCaptureUseTheSameProblemShape() throws Exception {
    Capture foreign =
        new Capture(
            "cap-foreign",
            "another-owner",
            "foreign-nonce",
            CaptureRequestHashes.sha256(
                "synthetic foreign capture",
                CaptureSourceType.TEXT,
                "capture-http-test",
                DataClass.PERSONAL),
            "synthetic foreign capture",
            CaptureSourceType.TEXT,
            "capture-http-test",
            DataClass.PERSONAL,
            Instant.parse("2026-07-28T07:00:00Z"));
    captureStore.saveOrFindByNonce(foreign);

    MvcResult foreignResult =
        mockMvc
            .perform(get("/api/v1/captures/{id}", foreign.captureId()))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult missingResult =
        mockMvc
            .perform(get("/api/v1/captures/missing-capture"))
            .andExpect(status().isNotFound())
            .andReturn();

    assertEquals(
        normalizedProblem(foreignResult.getResponse().getContentAsString()),
        normalizedProblem(missingResult.getResponse().getContentAsString()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizedProblem(String json) {
    Map<String, Object> problem = JsonPath.parse(json).read("$");
    problem.remove("instance");
    return problem;
  }
}
