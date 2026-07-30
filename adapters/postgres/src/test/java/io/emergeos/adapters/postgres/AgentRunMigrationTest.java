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
  void freshInstallCreatesTheV4AgentRunAggregate() {
    assertEquals(
        "4",
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
            "agent_run_resource_bindings",
            "agent_runs",
            "agent_trace_events"),
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
