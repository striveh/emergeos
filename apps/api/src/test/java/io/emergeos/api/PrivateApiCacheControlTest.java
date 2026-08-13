package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.CaptureThoughtCommand;
import io.emergeos.core.application.ManifestationService;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "emerge.prototype.principal-id=private-cache-owner")
class PrivateApiCacheControlTest extends PostgresApiTest {

  private static final String PRIVATE_NO_STORE = "private, no-store";
  private static final String SUBMITTED_CONTENT =
      "private-cache-submitted-content-must-not-echo";

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private DataSource dataSource;

  @Autowired private ManifestationService manifestationService;

  @Autowired private AgentApiCacheControlFilter cacheControlFilter;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilter(cacheControlFilter).build();
    truncateBusinessTruth(dataSource);
  }

  @Test
  void captureArtifactManifestationAndAgentSuccessesArePrivateAndNotStored()
      throws Exception {
    MvcResult capture = createCapture("private-cache-capture", "capture success content");
    String captureId = JsonPath.read(capture.getResponse().getContentAsString(), "$.captureId");

    MvcResult artifact =
        mockMvc
            .perform(
                post("/api/v1/artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "%s",
                          "content": "artifact success content"
                        }
                        """
                            .formatted(captureId)))
            .andExpect(status().isCreated())
            .andReturn();

    MvcResult manifestation =
        mockMvc
            .perform(
                post("/api/v1/manifestations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "content": "manifestation success content",
                          "sourceType": "TEXT",
                          "sourceRef": "private-cache-test",
                          "dataClass": "PERSONAL"
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();

    MvcResult agentCapture = createCapture("private-cache-agent", "agent success content");
    String agentCaptureId =
        JsonPath.read(agentCapture.getResponse().getContentAsString(), "$.captureId");
    MvcResult agentDraft =
        mockMvc
            .perform(
                post("/api/v1/agent-drafts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "%s",
                          "intent": "Create an evidence-linked draft"
                        }
                        """
                            .formatted(agentCaptureId)))
            .andExpect(status().isCreated())
            .andReturn();
    String runId = JsonPath.read(agentDraft.getResponse().getContentAsString(), "$.runId");
    MvcResult agentRun =
        mockMvc
            .perform(get("/api/v1/agent-runs/{runId}", runId))
            .andExpect(status().isOk())
            .andReturn();

    assertPrivateNoStore(capture);
    assertPrivateNoStore(artifact);
    assertPrivateNoStore(manifestation);
    assertPrivateNoStore(agentCapture);
    assertPrivateNoStore(agentDraft);
    assertPrivateNoStore(agentRun);
  }

  @Test
  void badRequestIsPrivateAndDoesNotEchoSubmittedContent() throws Exception {
    expectPrivateError(
        post("/api/v1/captures")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "principalId": "attacker",
                  "clientNonce": "private-cache-bad-request",
                  "content": "%s",
                  "sourceType": "TEXT",
                  "sourceRef": "private-cache-test",
                  "dataClass": "PERSONAL"
                }
                """
                    .formatted(SUBMITTED_CONTENT)),
        status().isBadRequest());
  }

  @Test
  void notFoundIsPrivateAndDoesNotEchoStoredContent() throws Exception {
    var foreign =
        manifestationService.capture(
            new CaptureThoughtCommand(
                "foreign-private-cache-owner",
                SUBMITTED_CONTENT,
                "TEXT",
                "private-cache-test",
                DataClass.PERSONAL));

    expectPrivateError(
        get("/api/v1/manifestations/{id}", foreign.manifestationId()), status().isNotFound());
  }

  @Test
  void methodNotAllowedIsPrivateAndDoesNotEchoSubmittedContent() throws Exception {
    expectPrivateError(
        delete("/api/v1/captures/not-deletable")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"" + SUBMITTED_CONTENT + "\"}"),
        status().isMethodNotAllowed());
  }

  @Test
  void unsupportedMediaTypeIsPrivateAndDoesNotEchoSubmittedContent() throws Exception {
    expectPrivateError(
        post("/api/v1/artifacts")
            .contentType(MediaType.TEXT_PLAIN)
            .content(SUBMITTED_CONTENT),
        status().isUnsupportedMediaType());
  }

  @Test
  void malformedJsonIsPrivateAndDoesNotEchoSubmittedContent() throws Exception {
    expectPrivateError(
        post("/api/v1/manifestations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"" + SUBMITTED_CONTENT + "\""),
        status().isBadRequest());
  }

  private MvcResult createCapture(String nonce, String content) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/captures")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientNonce": "%s",
                      "content": "%s",
                      "sourceType": "TEXT",
                      "sourceRef": "private-cache-test",
                      "dataClass": "PERSONAL"
                    }
                    """
                        .formatted(nonce, content)))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private MvcResult expectPrivateError(RequestBuilder request, ResultMatcher expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(request)
            .andExpect(expectedStatus)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andReturn();
    assertFalse(result.getResponse().getContentAsString().contains(SUBMITTED_CONTENT));
    return result;
  }

  private static void assertPrivateNoStore(MvcResult result) {
    org.junit.jupiter.api.Assertions.assertEquals(
        PRIVATE_NO_STORE, result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
  }
}
