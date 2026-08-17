package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AgentRunMigrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("emerge_agent_run_migration")
          .withUsername("emerge")
          .withPassword("synthetic-test-password");

  private static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    DataSource dataSource = dataSource();
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @Test
  void freshInstallCreatesTheCurrentGraphBoundAgentRunAggregate() {
    assertEquals(
        "16",
        jdbc.sql(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank DESC
                LIMIT 1
                """)
            .query(String.class)
            .single());
    assertEquals(
        List.of(
            "agent_graph_attempt_candidates",
            "agent_graph_attempt_events",
            "agent_graph_attempt_heads",
            "agent_graph_attempt_provider_attributions",
            "agent_graph_attempt_run_bindings",
            "agent_graph_attempt_seals",
            "agent_graph_attempt_terminal_bindings",
            "agent_graph_attempts",
            "agent_graph_attributed_failure_outcomes",
            "agent_graph_attributed_failure_terminal_resumes",
            "agent_graph_exact_attempt_events_v16",
            "agent_graph_exact_attempt_heads_v16",
            "agent_graph_exact_provider_attributions_v16",
            "agent_graph_exact_provider_validations_v16",
            "agent_graph_exact_tx_a_requirements_v15",
            "agent_graph_provider_profiles_v14",
            "agent_graph_provider_session_intents",
            "agent_graph_provider_validation_keys",
            "agent_graph_provider_validations",
            "agent_run_resource_bindings",
            "agent_runs",
            "agent_trace_events",
            "agent_worker_results"),
        jdbc.sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name LIKE 'agent_%'
                ORDER BY table_name
                """)
            .query(String.class)
            .list());
  }

  private static DataSource dataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }
}
