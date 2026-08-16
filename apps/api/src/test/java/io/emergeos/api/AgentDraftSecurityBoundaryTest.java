package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(properties = "emerge.prototype.principal-id=agent-draft-security-owner")
class AgentDraftSecurityBoundaryTest extends PostgresApiTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private DataSource dataSource;

  @Autowired private PostgresCaptureStore captureStore;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    truncateBusinessTruth(dataSource);
  }

  @Test
  void authorityAndExecutionPolicyComeOnlyFromServerConfiguration() throws Exception {
    MvcResult captured =
        mockMvc
            .perform(
                post("/api/v1/captures")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientNonce": "agent-draft-security-source",
                          "content": "synthetic server-owned agent source",
                          "sourceType": "TEXT",
                          "sourceRef": "agent-draft-security-test",
                          "dataClass": "PERSONAL"
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String captureId =
        JsonPath.read(captured.getResponse().getContentAsString(), "$.captureId");

    mockMvc
        .perform(
            post("/api/v1/agent-drafts")
                .header("X-Principal-Id", "attacker")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest(captureId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.result.resolvedModel").value("fake-model-v1"));

    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(
        List.of("agent-draft-security-owner"),
        jdbc.sql("SELECT principal_id FROM artifacts").query(String.class).list());

    for (String spoofField :
        List.of(
            "\"principalId\":\"attacker\"",
            "\"requiredTools\":[\"danger.write\"]",
            "\"model\":\"attacker-model\"",
            "\"schemaVersion\":\"1.1\"",
            "\"modelProvider\":\"openai.responses\"",
            "\"modelRequested\":\"gpt-5.6-sol\"",
            "\"pricingProfile\":\"attacker-pricing-v1\"",
            "\"budgetUsd\":999",
            "\"deadlineMs\":86400000",
            "\"maxModelSteps\":128",
            "\"environmentSnapshotRef\":\"environment://sha256:%s\""
                .formatted("a".repeat(64)),
            "\"capabilityRefs\":[\"capability://model-egress/attacker\"]",
            "\"componentVersions\":{\"model-adapter\":\"attacker\"}",
            "\"apiKey\":\"must-not-enter-command\"",
            "\"baseUrl\":\"https://attacker.invalid\"",
            "\"live\":true")) {
      mockMvc
          .perform(
              post("/api/v1/agent-drafts")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "captureId": "%s",
                        "intent": "Create an evidence-linked draft",
                        %s
                      }
                      """
                          .formatted(captureId, spoofField)))
          .andExpect(status().isBadRequest());
    }
    assertEquals(
        1,
        jdbc.sql("SELECT count(*) FROM artifacts").query(Integer.class).single());
    assertEquals(
        0,
        jdbc.sql(
                "SELECT (SELECT count(*) FROM action_attempts) "
                    + "+ (SELECT count(*) FROM action_receipts)")
            .query(Integer.class)
            .single());
  }

  @Test
  void foreignAndMissingCaptureHaveTheSameNonLeakingFailureShape() throws Exception {
    String captureId = "agent-draft-hidden-capture";
    String hiddenContent = "synthetic foreign capture content must remain hidden";
    captureStore.saveOrFindByNonce(
        new Capture(
            captureId,
            "another-owner",
            "agent-draft-foreign",
            CaptureRequestHashes.sha256(
                hiddenContent,
                CaptureSourceType.TEXT,
                "agent-draft-security-test",
                DataClass.PERSONAL),
            hiddenContent,
            CaptureSourceType.TEXT,
            "agent-draft-security-test",
            DataClass.PERSONAL,
            Instant.parse("2026-07-30T00:00:00Z")));

    MvcResult foreign =
        mockMvc
            .perform(
                post("/api/v1/agent-drafts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequest(captureId)))
            .andExpect(status().isUnprocessableContent())
            .andReturn();
    JdbcClient.create(dataSource)
        .sql("DELETE FROM captures WHERE capture_id = :captureId")
        .param("captureId", captureId)
        .update();
    MvcResult missing =
        mockMvc
            .perform(
                post("/api/v1/agent-drafts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validRequest(captureId)))
            .andExpect(status().isUnprocessableContent())
            .andReturn();

    String foreignBody = foreign.getResponse().getContentAsString();
    String missingBody = missing.getResponse().getContentAsString();
    for (String path :
        List.of(
            "$.result.status",
            "$.result.failureReason",
            "$.result.artifactRefs",
            "$.result.evidenceRefs",
            "$.trace[*].type",
            "$.trace[*].status",
            "$.trace[*].toolName")) {
      Object foreignValue = JsonPath.read(foreignBody, path);
      Object missingValue = JsonPath.read(missingBody, path);
      assertEquals(foreignValue, missingValue, path);
    }
    assertEquals("FAILED", JsonPath.read(foreignBody, "$.result.status"));
    assertEquals(
        "HANDOFF_CHILD_FAILED",
        JsonPath.read(foreignBody, "$.result.failureReason"));
    assertFalse(foreignBody.contains(hiddenContent));
    assertFalse(foreignBody.contains("another-owner"));
    assertEquals(
        0,
        JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM artifacts")
            .query(Integer.class)
            .single());
  }

  private static String validRequest(String captureId) {
    return """
        {
          "captureId": "%s",
          "intent": "Create an evidence-linked draft"
        }
        """
        .formatted(captureId);
  }
}
