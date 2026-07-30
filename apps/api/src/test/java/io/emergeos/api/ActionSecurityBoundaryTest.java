package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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

@SpringBootTest(properties = "emerge.prototype.principal-id=action-http-owner")
class ActionSecurityBoundaryTest extends PostgresApiTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    JdbcClient.create(dataSource)
        .sql(
            "TRUNCATE TABLE agent_trace_events, agent_run_resource_bindings, agent_runs, "
                + "action_receipts, action_attempt_transitions, action_attempts, "
                + "artifact_versions, artifacts, captures")
        .update();
  }

  @Test
  void authorityComesOnlyFromServerConfigurationAndSpoofFieldsAreRejected()
      throws Exception {
    MvcResult captured =
        mockMvc
            .perform(
                post("/api/v1/captures")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientNonce": "action-security-source",
                          "content": "synthetic action authority source",
                          "sourceType": "TEXT",
                          "sourceRef": "action-security-test",
                          "dataClass": "PERSONAL"
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String captureId =
        JsonPath.read(captured.getResponse().getContentAsString(), "$.captureId");
    MvcResult artifact =
        mockMvc
            .perform(
                post("/api/v1/artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "captureId": "%s",
                          "content": "synthetic approved security draft"
                        }
                        """
                            .formatted(captureId)))
            .andExpect(status().isCreated())
            .andReturn();
    String artifactBody = artifact.getResponse().getContentAsString();
    String artifactId = JsonPath.read(artifactBody, "$.artifactId");
    String artifactHash = JsonPath.read(artifactBody, "$.currentHash");

    mockMvc
        .perform(
            post("/api/v1/artifacts/" + artifactId + "/actions")
                .header("X-Principal-Id", "attacker")
                .header("X-Connector", "attacker-connector")
                .contentType(MediaType.APPLICATION_JSON)
                .content(actionBody(artifactHash, "configured-authority-key")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("UNKNOWN"));

    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(
        List.of(
            "action-http-owner",
            "simulated.local-draft",
            "adapter:simulated-provider",
            "simulated-account:action-http-owner"),
        jdbc.sql(
                """
                SELECT principal_id, connector, capability_audience, account_ref
                FROM action_attempts
                WHERE idempotency_key = 'configured-authority-key'
                """)
            .query(
                (resultSet, rowNumber) ->
                    List.of(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4)))
            .single());

    for (String spoofField :
        List.of(
            "\"principalId\":\"attacker\"",
            "\"connector\":\"attacker-connector\"",
            "\"audience\":\"attacker-audience\"",
            "\"accountRef\":\"attacker-account\"",
            "\"actionPlanHash\":\"" + "0".repeat(64) + "\"")) {
      mockMvc
          .perform(
              post("/api/v1/artifacts/" + artifactId + "/actions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "approvedArtifactHash": "%s",
                        "idempotencyKey": "spoofed-authority-key",
                        %s
                      }
                      """
                          .formatted(artifactHash, spoofField)))
          .andExpect(status().isBadRequest());
    }
    assertEquals(
        1,
        jdbc.sql("SELECT count(*) FROM action_attempts")
            .query(Integer.class)
            .single());
  }

  private static String actionBody(String artifactHash, String key) {
    return """
        {
          "approvedArtifactHash": "%s",
          "idempotencyKey": "%s"
        }
        """
        .formatted(artifactHash, key);
  }
}
