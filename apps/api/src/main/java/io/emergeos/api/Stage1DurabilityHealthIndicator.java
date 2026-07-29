package io.emergeos.api;

import io.emergeos.adapters.postgres.PostgresStage1OperationsProbe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class Stage1DurabilityHealthIndicator implements HealthIndicator {

  private static final Map<String, String> RECOVERY_ENTRIES =
      Map.of(
          "PLANNED",
          "replay the exact approval request",
          "UNKNOWN",
          "POST /api/v1/actions/{attemptId}/reconcile, subject to persisted authority and budget",
          "DISPATCHING",
          "NO_SAFE_STALE_CLAIM_RECOVERY",
          "RECONCILING",
          "NO_SAFE_STALE_CLAIM_RECOVERY");

  private final Flyway flyway;
  private final PostgresStage1OperationsProbe operations;
  private final String principalId;

  Stage1DurabilityHealthIndicator(
      Flyway flyway,
      PostgresStage1OperationsProbe operations,
      String principalId) {
    this.flyway = Objects.requireNonNull(flyway, "flyway");
    this.operations = Objects.requireNonNull(operations, "operations");
    if (principalId == null || principalId.isBlank()) {
      throw new IllegalArgumentException("principalId must not be blank");
    }
    this.principalId = principalId;
  }

  @Override
  public Health health() {
    try {
      ValidateResult validation = flyway.validateWithResult();
      MigrationInfoService migrationInfo = flyway.info();
      MigrationInfo current = migrationInfo.current();
      int pending = migrationInfo.pending().length;
      PostgresStage1OperationsProbe.Snapshot actions =
          operations.snapshot(principalId);

      Map<String, Object> details = new LinkedHashMap<>();
      details.put("database", "REACHABLE");
      details.put(
          "migration",
          Map.of(
              "valid", validation.validationSuccessful,
              "current", current == null ? "NONE" : current.getVersion().toString(),
              "pending", pending));
      details.put(
          "actions",
          Map.of(
              "principalScope", "SERVER_CONFIGURED",
              "unresolved", actions.unresolved(),
              "statusCounts", actions.statusCounts(),
              "recoveryEntries", RECOVERY_ENTRIES));
      details.put("realConnectorGate", "BLOCKED_ADR_0004_PROPOSED");

      if (!validation.validationSuccessful || current == null || pending != 0) {
        return Health.down()
            .withDetail("faultCode", "MIGRATION_NOT_CURRENT")
            .withDetails(details)
            .build();
      }
      if (actions.unresolved() != 0) {
        return Health.outOfService()
            .withDetail("faultCode", "ACTION_UNRESOLVED")
            .withDetails(details)
            .build();
      }
      return Health.up().withDetails(details).build();
    } catch (RuntimeException unavailable) {
      return Health.down()
          .withDetail("faultCode", "DATABASE_OR_MIGRATION_UNAVAILABLE")
          .build();
    }
  }
}
