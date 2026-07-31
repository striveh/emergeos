package io.emergeos.api;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

record EmergeDatabaseSnapshot(
    List<String> captures,
    List<String> artifacts,
    List<String> artifactVersions,
    List<String> actionAttempts,
    List<String> actionAttemptTransitions,
    List<String> actionReceipts,
    List<String> agentRuns,
    List<String> agentTraceEvents,
    List<String> agentRunResourceBindings,
    List<String> agentWorkerResults,
    List<String> flywaySchemaHistory) {

  static EmergeDatabaseSnapshot capture(JdbcClient jdbc) {
    return new EmergeDatabaseSnapshot(
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, capture_id, xmin::text
            )::text
            FROM captures
            ORDER BY principal_id, capture_id
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, artifact_id, xmin::text
            )::text
            FROM artifacts
            ORDER BY principal_id, artifact_id
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, artifact_id, version, xmin::text
            )::text
            FROM artifact_versions
            ORDER BY principal_id, artifact_id, version
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, attempt_id, xmin::text
            )::text
            FROM action_attempts
            ORDER BY principal_id, attempt_id
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, attempt_id, sequence, xmin::text
            )::text
            FROM action_attempt_transitions
            ORDER BY principal_id, attempt_id, sequence
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, attempt_id, xmin::text
            )::text
            FROM action_receipts
            ORDER BY principal_id, attempt_id
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, run_id, xmin::text
            )::text
            FROM agent_runs
            ORDER BY principal_id, run_id
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, run_id, sequence, xmin::text
            )::text
            FROM agent_trace_events
            ORDER BY principal_id, run_id, sequence
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, run_id, role, ordinal, xmin::text
            )::text
            FROM agent_run_resource_bindings
            ORDER BY principal_id, run_id, role, ordinal
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              principal_id, child_run_id, xmin::text
            )::text
            FROM agent_worker_results
            ORDER BY principal_id, child_run_id
            """),
        rows(
            jdbc,
            """
            SELECT jsonb_build_array(
              installed_rank, xmin::text
            )::text
            FROM flyway_schema_history
            ORDER BY installed_rank
            """));
  }

  private static List<String> rows(JdbcClient jdbc, String sql) {
    return List.copyOf(
        jdbc.sql(sql).query(String.class).list());
  }
}
