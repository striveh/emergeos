package io.emergeos.api;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

abstract class PostgresApiTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_api")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  static void truncateBusinessTruth(DataSource dataSource) {
    JdbcClient.create(dataSource)
        .sql(
            """
            TRUNCATE TABLE
              agent_graph_exact_attempt_heads_v16,
              agent_graph_exact_attempt_events_v16,
              agent_graph_exact_provider_attributions_v16,
              agent_graph_exact_provider_validations_v16,
              agent_graph_exact_tx_a_requirements_v15,
              agent_graph_provider_validations,
              agent_graph_provider_validation_keys,
              agent_graph_attributed_failure_terminal_resumes,
              agent_graph_attributed_failure_outcomes,
              agent_graph_attempt_terminal_bindings,
              agent_graph_attempt_candidates,
              agent_graph_attempt_provider_attributions,
              agent_graph_provider_session_intents,
              agent_graph_attempt_seals,
              agent_graph_attempt_heads,
              agent_graph_attempt_events,
              agent_graph_attempt_run_bindings,
              agent_graph_attempts,
              agent_trace_events,
              agent_run_resource_bindings,
              agent_worker_results,
              agent_runs,
              action_receipts,
              action_attempt_transitions,
              local_draft_undo_receipts,
              local_draft_creation_receipts,
              local_drafts,
              action_attempts,
              artifact_versions,
              artifacts,
              captures
            """)
        .update();
  }
}
