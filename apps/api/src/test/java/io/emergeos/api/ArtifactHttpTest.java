package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.ArtifactLineageService;
import io.emergeos.core.application.CreateArtifactCommand;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "emerge.prototype.principal-id=artifact-http-owner")
class ArtifactHttpTest extends PostgresApiTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private DataSource dataSource;

  @Autowired private PostgresCaptureStore captureStore;

  @Autowired private ArtifactLineageService artifactService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    JdbcClient.create(dataSource)
        .sql("TRUNCATE TABLE artifact_versions, artifacts, captures")
        .update();
  }

  @Test
  void revisesOnlyTheExpectedBaseAndReturnsANonLeakingDedicatedConflict() throws Exception {
    String captureId = createOwnedCapture("artifact-http-source");
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/artifacts")
                    .header("X-Principal-Id", "attacker")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "%s",
                          "content": "artifact version one"
                        }
                        """
                            .formatted(captureId)))
            .andExpect(status().isCreated())
            .andExpect(
                header()
                    .string(
                        "Location",
                        org.hamcrest.Matchers.startsWith("/api/v1/artifacts/")))
            .andExpect(jsonPath("$.principalId").value("artifact-http-owner"))
            .andExpect(jsonPath("$.captureId").value(captureId))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.versions.length()").value(1))
            .andExpect(jsonPath("$.versions[0].baseVersion").doesNotExist())
            .andExpect(jsonPath("$.versions[0].baseHash").doesNotExist())
            .andReturn();
    String artifactId = JsonPath.read(created.getResponse().getContentAsString(), "$.artifactId");
    String h1 = JsonPath.read(created.getResponse().getContentAsString(), "$.currentHash");

    mockMvc
        .perform(
            put("/api/v1/artifacts/{id}", artifactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "content": "wrong-hash revision body",
                      "expectedBaseVersion": 1,
                      "expectedBaseHash": "%s"
                    }
                    """
                        .formatted("0".repeat(64))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("urn:emergeos:problem:artifact-revision-conflict"))
        .andExpect(jsonPath("$.title").value("Artifact revision conflict"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Artifact changed since the expected base; reload the latest Artifact before revising"))
        .andExpect(jsonPath("$.currentVersion").value(1))
        .andExpect(jsonPath("$.currentHash").doesNotExist())
        .andExpect(jsonPath("$.content").doesNotExist());

    mockMvc
        .perform(
            put("/api/v1/artifacts/{id}", artifactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "content": "artifact version two",
                      "expectedBaseVersion": 1,
                      "expectedBaseHash": "%s"
                    }
                    """
                        .formatted(h1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(2))
        .andExpect(jsonPath("$.currentHash").value(ContentHashes.sha256("artifact version two")))
        .andExpect(jsonPath("$.versions.length()").value(2))
        .andExpect(jsonPath("$.versions[1].version").value(2))
        .andExpect(jsonPath("$.versions[1].baseVersion").value(1))
        .andExpect(jsonPath("$.versions[1].baseHash").value(h1));

    mockMvc
        .perform(
            put("/api/v1/artifacts/{id}", artifactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "content": "stale overwrite body",
                      "expectedBaseVersion": 1,
                      "expectedBaseHash": "%s"
                    }
                    """
                        .formatted(h1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.currentVersion").value(2))
        .andExpect(jsonPath("$.currentHash").doesNotExist())
        .andExpect(
            jsonPath("$.detail")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("artifact version two"))));
  }

  @Test
  void rejectsClientOwnedIdentityAndHashFieldsAndInvalidBaseInputs() throws Exception {
    String captureId = createOwnedCapture("artifact-http-boundaries");

    for (String extra :
        new String[] {
          "\"principalId\":\"attacker\",",
          "\"contentHash\":\"" + "0".repeat(64) + "\",",
          "\"currentHash\":\"" + "0".repeat(64) + "\","
        }) {
      mockMvc
          .perform(
              post("/api/v1/artifacts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        %s
                        "captureId": "%s",
                        "content": "unknown create field"
                      }
                      """
                          .formatted(extra, captureId)))
          .andExpect(status().isBadRequest());
    }

    String artifactId = createArtifact(captureId, "artifact boundary v1");
    for (String body :
        new String[] {
          """
          {
            "principalId": "attacker",
            "content": "unknown revise identity",
            "expectedBaseVersion": 1,
            "expectedBaseHash": "%s"
          }
          """
              .formatted(ContentHashes.sha256("artifact boundary v1")),
          """
          {
            "content": "zero base",
            "expectedBaseVersion": 0,
            "expectedBaseHash": "%s"
          }
          """
              .formatted(ContentHashes.sha256("artifact boundary v1")),
          """
          {
            "content": "missing version",
            "expectedBaseHash": "%s"
          }
          """
              .formatted(ContentHashes.sha256("artifact boundary v1")),
          """
          {
            "content": "invalid hash",
            "expectedBaseVersion": 1,
            "expectedBaseHash": "ABC"
          }
          """
        }) {
      mockMvc
          .perform(
              put("/api/v1/artifacts/{id}", artifactId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void foreignAndMissingCaptureSourcesUseTheSameProblemShape() throws Exception {
    captureStore.saveOrFindByNonce(capture("cap-foreign-source", "another-owner", "foreign-source"));

    MvcResult foreign =
        mockMvc
            .perform(
                post("/api/v1/artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "cap-foreign-source",
                          "content": "foreign source probe"
                        }
                        """))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult missing =
        mockMvc
            .perform(
                post("/api/v1/artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "missing-capture",
                          "content": "missing source probe"
                        }
                        """))
            .andExpect(status().isNotFound())
            .andReturn();

    assertEquals(
        normalizedProblem(foreign.getResponse().getContentAsString()),
        normalizedProblem(missing.getResponse().getContentAsString()));
  }

  @Test
  void foreignAndMissingArtifactReadsAndExactBaseRevisionsUseTheSameProblemShape()
      throws Exception {
    captureStore.saveOrFindByNonce(capture("cap-other-owner", "another-owner", "other-owner"));
    var foreign =
        artifactService.create(
            new CreateArtifactCommand(
                "another-owner", "cap-other-owner", "foreign artifact version one"));

    MvcResult foreignGet =
        mockMvc
            .perform(get("/api/v1/artifacts/{id}", foreign.artifactId()))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult missingGet =
        mockMvc
            .perform(get("/api/v1/artifacts/missing-artifact"))
            .andExpect(status().isNotFound())
            .andReturn();
    assertEquals(
        normalizedProblem(foreignGet.getResponse().getContentAsString()),
        normalizedProblem(missingGet.getResponse().getContentAsString()));

    String exactBaseRevision =
        """
        {
          "content": "foreign exact-base overwrite",
          "expectedBaseVersion": 1,
          "expectedBaseHash": "%s"
        }
        """
            .formatted(foreign.current().contentHash());
    MvcResult foreignPut =
        mockMvc
            .perform(
                put("/api/v1/artifacts/{id}", foreign.artifactId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(exactBaseRevision))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult missingPut =
        mockMvc
            .perform(
                put("/api/v1/artifacts/missing-artifact")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(exactBaseRevision))
            .andExpect(status().isNotFound())
            .andReturn();
    assertEquals(
        normalizedProblem(foreignPut.getResponse().getContentAsString()),
        normalizedProblem(missingPut.getResponse().getContentAsString()));
    assertEquals(
        1,
        JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM artifact_versions")
            .query(Integer.class)
            .single());
  }

  private String createOwnedCapture(String nonce) throws Exception {
    MvcResult captured =
        mockMvc
            .perform(
                post("/api/v1/captures")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientNonce": "%s",
                          "content": "synthetic Artifact source thought",
                          "sourceType": "TEXT",
                          "sourceRef": "artifact-http-test",
                          "dataClass": "PERSONAL"
                        }
                        """
                            .formatted(nonce)))
            .andExpect(status().isCreated())
            .andReturn();
    return JsonPath.read(captured.getResponse().getContentAsString(), "$.captureId");
  }

  private String createArtifact(String captureId, String content) throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "%s",
                          "content": "%s"
                        }
                        """
                            .formatted(captureId, content)))
            .andExpect(status().isCreated())
            .andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.artifactId");
  }

  private static Capture capture(String captureId, String principalId, String nonce) {
    String content = "synthetic foreign source thought";
    return new Capture(
        captureId,
        principalId,
        nonce,
        CaptureRequestHashes.sha256(
            content, CaptureSourceType.TEXT, "artifact-http-test", DataClass.PERSONAL),
        content,
        CaptureSourceType.TEXT,
        "artifact-http-test",
        DataClass.PERSONAL,
        Instant.parse("2026-07-28T07:00:00Z"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizedProblem(String json) {
    Map<String, Object> problem = JsonPath.parse(json).read("$");
    problem.remove("instance");
    return problem;
  }
}
