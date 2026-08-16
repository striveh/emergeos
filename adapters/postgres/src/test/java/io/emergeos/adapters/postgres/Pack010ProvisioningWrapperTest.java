package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class Pack010ProvisioningWrapperTest {

  private static final Path REPOSITORY_ROOT = repositoryRoot();

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine")
          .withDatabaseName("pack010_wrapper")
          .withUsername("emerge")
          .withPassword(UUID.randomUUID().toString())
          .withFileSystemBind(
              REPOSITORY_ROOT.toString(),
              REPOSITORY_ROOT.toString(),
              BindMode.READ_ONLY);

  @TempDir Path temporaryDirectory;

  @Test
  void applyIsDefaultDenyAndRunsMutationAndAuditAtomically()
      throws Exception {
    Path root = repositoryRoot();
    Path log = temporaryDirectory.resolve("psql-arguments.log");
    Path fakePsql = fakePsql(log);
    Path wrapper =
        root.resolve("scripts/provision-pack010-postgres-roles.sh");

    Process denied =
        process(wrapper, fakePsql, log, "--apply", false).start();
    assertEquals(4, denied.waitFor());
    assertFalse(Files.exists(log));

    Process applied =
        process(wrapper, fakePsql, log, "--apply", true).start();
    assertEquals(0, applied.waitFor());
    List<String> arguments = Files.readAllLines(log);
    assertEquals(1L, arguments.stream().filter("CALL"::equals).count());
    assertTrue(arguments.contains("--single-transaction"));
    assertEquals(
        2L,
        arguments.stream()
            .filter(argument -> argument.startsWith("--file="))
            .count());
    assertTrue(
        arguments.stream()
            .anyMatch(argument ->
                argument.endsWith("pack010_runtime_roles.sql")));
    assertTrue(
        arguments.stream()
            .anyMatch(argument ->
                argument.endsWith("pack010_runtime_roles_check.sql")));
  }

  @Test
  void realAuditFailureRollsBackBootstrapMutation() throws Exception {
    PGSimpleDataSource admin = new PGSimpleDataSource();
    admin.setURL(POSTGRES.getJdbcUrl());
    admin.setUser(POSTGRES.getUsername());
    admin.setPassword(POSTGRES.getPassword());
    Flyway.configure().dataSource(admin).load().migrate();
    JdbcClient jdbc = JdbcClient.create(admin);
    String driftRole =
        "pack010_wrapper_drift_"
            + UUID.randomUUID().toString().replace("-", "");
    jdbc.sql("CREATE ROLE " + driftRole + " NOLOGIN").update();
    jdbc.sql(
            "ALTER DEFAULT PRIVILEGES IN SCHEMA public "
                + "GRANT SELECT ON TABLES TO "
                + driftRole)
        .update();
    try {
      Path wrapper =
          REPOSITORY_ROOT.resolve(
              "scripts/provision-pack010-postgres-roles.sh");
      Path psql = dockerPsql();
      ProcessBuilder builder =
          new ProcessBuilder(wrapper.toString(), "--apply")
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD);
      builder.environment().put("PSQL_BIN", psql.toString());
      builder.environment().put(
          "EMERGEOS_PROVISION_CONFIRM", "PACK010_ROLE_APPLY");
      builder.environment().put(
          "PACK010_TEST_CONTAINER", POSTGRES.getContainerId());
      builder.environment().put(
          "PACK010_DOCKER_BIN", dockerBinary().toString());
      Process failed = builder.start();
      assertEquals(3, failed.waitFor());
      assertEquals(
          0L,
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM pg_catalog.pg_roles
                  WHERE rolname IN (
                    'emergeos_pack010_schema_owner',
                    'emergeos_terminal_owner',
                    'emergeos_graph_executor',
                    'emergeos_failure_resumer',
                    'emergeos_provider_attestor',
                    'emergeos_provider_attestor_v14',
                    'emergeos_provider_attestor_v15',
                    'emergeos_provider_attestor_v16',
                    'emergeos_graph_prefix_writer',
                    'emergeos_graph_reader',
                    'emergeos_exact_overlay_reader_v19')
                  """)
              .query(Long.class)
              .single());
    } finally {
      jdbc.sql(
              "ALTER DEFAULT PRIVILEGES IN SCHEMA public "
                  + "REVOKE SELECT ON TABLES FROM "
                  + driftRole)
          .update();
      jdbc.sql("DROP ROLE " + driftRole).update();
    }
  }

  private ProcessBuilder process(
      Path wrapper,
      Path fakePsql,
      Path log,
      String mode,
      boolean confirmed) {
    ProcessBuilder builder =
        new ProcessBuilder(wrapper.toString(), mode)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("PSQL_BIN", fakePsql.toString());
    builder.environment().put("PACK010_PSQL_LOG", log.toString());
    if (confirmed) {
      builder.environment().put(
          "EMERGEOS_PROVISION_CONFIRM", "PACK010_ROLE_APPLY");
    } else {
      builder.environment().remove("EMERGEOS_PROVISION_CONFIRM");
    }
    return builder;
  }

  private Path fakePsql(Path log) throws IOException {
    Path script = temporaryDirectory.resolve("psql");
    Files.writeString(
        script,
        "#!/bin/sh\n"
            + "printf '%s\\n' CALL >> \"$PACK010_PSQL_LOG\"\n"
            + "printf '%s\\n' \"$@\" >> \"$PACK010_PSQL_LOG\"\n",
        StandardCharsets.UTF_8);
    if (!script.toFile().setExecutable(true, true)) {
      throw new IOException("test psql is not executable");
    }
    return script;
  }

  private Path dockerPsql() throws IOException {
    Path script = temporaryDirectory.resolve("docker-psql");
    Files.writeString(
        script,
        "#!/bin/sh\n"
            + "exec \"$PACK010_DOCKER_BIN\" exec "
            + "\"$PACK010_TEST_CONTAINER\" "
            + "psql -U emerge -d pack010_wrapper \"$@\"\n",
        StandardCharsets.UTF_8);
    if (!script.toFile().setExecutable(true, true)) {
      throw new IOException("test docker psql is not executable");
    }
    return script;
  }

  private static Path dockerBinary() throws IOException {
    Process process =
        new ProcessBuilder("sh", "-c", "command -v docker")
            .redirectErrorStream(true)
            .start();
    try {
      String output =
          new String(
              process.getInputStream().readAllBytes(),
              StandardCharsets.UTF_8)
              .trim();
      if (process.waitFor() != 0 || output.isBlank()) {
        throw new IOException("docker CLI is unavailable");
      }
      return Path.of(output);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("docker CLI lookup interrupted", interrupted);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("mvnw"))
          && Files.isDirectory(candidate.resolve("adapters/postgres"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
