package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "emerge.prototype.principal-id=agent-run-security-owner")
class AgentRunSecurityBoundaryTest extends PostgresApiTest {

  private static final String OWNER = "agent-run-security-owner";
  private static final String FOREIGN_OWNER = "agent-run-security-foreign";
  private static final String RAW_CAPTURE_SENTINEL =
      "RAW_AGENT_RUN_SECURITY_CAPTURE_SENTINEL";
  private static final String REASONING_SENTINEL = "COT_SECURITY_SENTINEL";
  private static final String TAMPER_SENTINEL = "TAMPER_STATUS_SENTINEL";
  private static final List<String> ENDPOINT_SUFFIXES =
      List.of("", "/trace", "/bundle");

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private DataSource dataSource;

  @Autowired private PostgresCaptureStore captureStore;

  @Autowired private AgentDraftService agentDraftService;

  @Autowired private AgentApiCacheControlFilter agentApiCacheControlFilter;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(agentApiCacheControlFilter)
            .build();
    truncateBusinessTruth(dataSource);
  }

  @Test
  void everyFrameworkAndApplicationErrorOnTheAgentApiIsPrivateNoStore()
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agent-drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"));

    mockMvc
        .perform(
            post("/api/v1/agent-drafts")
                .contentType(MediaType.TEXT_PLAIN)
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"));

    mockMvc
        .perform(get("/api/v1/agent-drafts"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"));
  }

  @Test
  void ownerIsolationPrecedesIntegrityVerificationAndErrorsNeverLeak()
      throws Exception {
    String ownedRunId =
        createRun(
            OWNER,
            "agent-run-owned-capture",
            "agent-run-owned-nonce",
            RAW_CAPTURE_SENTINEL + " " + REASONING_SENTINEL);
    String foreignRunId =
        createRun(
            FOREIGN_OWNER,
            "agent-run-foreign-capture",
            "agent-run-foreign-nonce",
            "FOREIGN_" + RAW_CAPTURE_SENTINEL + " " + REASONING_SENTINEL);
    String missingRunId = "agent-run-missing";

    for (String suffix : ENDPOINT_SUFFIXES) {
      MvcResult owned =
          mockMvc
              .perform(get(runPath(ownedRunId, suffix)))
              .andExpect(status().isOk())
              .andExpect(header().string("Cache-Control", "private, no-store"))
              .andExpect(header().doesNotExist("ETag"))
              .andReturn();
      assertSafe(owned);

      assertSameNotFound(
          request(foreignRunId, suffix, 404),
          request(missingRunId, suffix, 404));
    }

    tamperTrace(FOREIGN_OWNER, foreignRunId);
    for (String suffix : ENDPOINT_SUFFIXES) {
      assertSameNotFound(
          request(foreignRunId, suffix, 404),
          request(missingRunId, suffix, 404));
    }

    tamperTrace(OWNER, ownedRunId);
    for (String suffix : ENDPOINT_SUFFIXES) {
      MvcResult damaged = request(ownedRunId, suffix, 409);
      Map<String, Object> problem = normalizedProblem(damaged);
      assertEquals(
          Map.of(
              "type", "urn:emergeos:problem:agent-run-integrity",
              "title", "Agent run integrity conflict",
              "status", 409,
              "detail", "Stored agent run cannot be verified."),
          problem);
      assertSafe(damaged);
    }
  }

  private String createRun(
      String principalId,
      String captureId,
      String nonce,
      String content) {
    Capture capture =
        new Capture(
            captureId,
            principalId,
            nonce,
            CaptureRequestHashes.sha256(
                content,
                CaptureSourceType.TEXT,
                "synthetic://stage2/security-boundary",
                DataClass.PERSONAL),
            content,
            CaptureSourceType.TEXT,
            "synthetic://stage2/security-boundary",
            DataClass.PERSONAL,
            Instant.parse("2026-07-30T00:00:00Z"));
    captureStore.saveOrFindByNonce(capture);
    return agentDraftService
        .draft(
            new AgentDraftCommand(
                principalId,
                captureId,
                "Create an evidence-linked synthetic security draft"))
        .run()
        .runId();
  }

  private MvcResult request(String runId, String suffix, int expectedStatus)
      throws Exception {
    return mockMvc
        .perform(get(runPath(runId, suffix)))
        .andExpect(status().is(expectedStatus))
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andReturn();
  }

  private static String runPath(String runId, String suffix) {
    return "/api/v1/agent-runs/" + runId + suffix;
  }

  private void tamperTrace(String principalId, String runId) {
    assertEquals(
        1,
        JdbcClient.create(dataSource)
            .sql(
                """
                UPDATE agent_trace_events
                SET status = :status
                WHERE principal_id = :principalId
                  AND run_id = :runId
                  AND sequence = 1
                """)
            .param("status", TAMPER_SENTINEL)
            .param("principalId", principalId)
            .param("runId", runId)
            .update());
  }

  private static void assertSameNotFound(MvcResult foreign, MvcResult missing)
      throws Exception {
    Map<String, Object> foreignProblem = normalizedProblem(foreign);
    Map<String, Object> missingProblem = normalizedProblem(missing);
    assertEquals(missingProblem, foreignProblem);
    assertEquals(
        Map.of(
            "type", "urn:emergeos:problem:agent-run-not-found",
            "title", "Agent run not found",
            "status", 404,
            "detail", "Agent run was not found."),
        foreignProblem);
    assertSafe(foreign);
    assertSafe(missing);
  }

  private static void assertSafe(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    for (String sentinel :
        List.of(RAW_CAPTURE_SENTINEL, REASONING_SENTINEL, TAMPER_SENTINEL)) {
      assertFalse(body.contains(sentinel), sentinel);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> normalizedProblem(MvcResult result)
      throws Exception {
    Map<String, Object> parsed =
        JsonPath.read(result.getResponse().getContentAsString(), "$");
    Map<String, Object> normalized = new LinkedHashMap<>(parsed);
    normalized.remove("instance");
    return normalized;
  }
}
