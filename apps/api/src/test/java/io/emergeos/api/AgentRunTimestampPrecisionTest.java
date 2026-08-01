package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "emerge.prototype.principal-id=agent-run-time-owner")
@Import(AgentRunTimestampPrecisionTest.NanosecondClockConfiguration.class)
class AgentRunTimestampPrecisionTest extends PostgresApiTest {

  private static final String OWNER = "agent-run-time-owner";
  private static final Instant NANOSECOND_TIME =
      Instant.parse("2026-08-01T01:02:03.123456789Z");
  private static final Instant MICROSECOND_TIME =
      Instant.parse("2026-08-01T01:02:03.123456Z");

  @Autowired private DataSource dataSource;

  @Autowired private PostgresCaptureStore captureStore;

  @Autowired private AgentDraftService agentDraftService;

  @BeforeEach
  void clean() {
    JdbcClient.create(dataSource)
        .sql(
            "TRUNCATE TABLE agent_trace_events, agent_run_resource_bindings, "
                + "agent_worker_results, agent_runs, "
                + "action_receipts, action_attempt_transitions, action_attempts, "
                + "artifact_versions, artifacts, captures")
        .update();
  }

  @Test
  void canonicalizesNanosecondClockBeforePersistingParentAndWorkerRuns() {
    String captureId = "agent-run-time-capture";
    String content = "synthetic timestamp precision evidence";
    captureStore.saveOrFindByNonce(
        new Capture(
            captureId,
            OWNER,
            "agent-run-time-nonce",
            CaptureRequestHashes.sha256(
                content,
                CaptureSourceType.TEXT,
                "synthetic://agent-run-time",
                DataClass.PERSONAL),
            content,
            CaptureSourceType.TEXT,
            "synthetic://agent-run-time",
            DataClass.PERSONAL,
            MICROSECOND_TIME));

    AgentDraftOutcome outcome =
        agentDraftService.draft(
            new AgentDraftCommand(
                OWNER,
                captureId,
                "Create one evidence-linked synthetic draft"));

    assertEquals(MICROSECOND_TIME, outcome.run().startedAt());
    assertEquals(MICROSECOND_TIME, outcome.run().completedAt());
    assertEquals(MICROSECOND_TIME, outcome.artifact().current().createdAt());
    assertEquals(
        List.of(
            new PersistedTimes(MICROSECOND_TIME, MICROSECOND_TIME),
            new PersistedTimes(MICROSECOND_TIME, MICROSECOND_TIME)),
        JdbcClient.create(dataSource)
            .sql(
                "SELECT started_at, completed_at "
                    + "FROM agent_runs ORDER BY parent_run_id NULLS LAST")
            .query(
                (resultSet, rowNumber) ->
                    new PersistedTimes(
                        resultSet.getTimestamp("started_at").toInstant(),
                        resultSet.getTimestamp("completed_at").toInstant()))
            .list());
    assertEquals(
        List.of(MICROSECOND_TIME),
        JdbcClient.create(dataSource)
            .sql("SELECT created_at FROM artifact_versions")
            .query(
                (resultSet, rowNumber) ->
                    resultSet.getTimestamp("created_at").toInstant())
            .list());
  }

  private record PersistedTimes(Instant startedAt, Instant completedAt) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class NanosecondClockConfiguration {

    @Bean
    @Primary
    Clock nanosecondClock() {
      return Clock.fixed(NANOSECOND_TIME, ZoneOffset.UTC);
    }
  }
}
