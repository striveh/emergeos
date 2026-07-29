package io.emergeos.adapters.postgres;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class PostgresStage1OperationsProbe {

  private final JdbcClient jdbc;

  public PostgresStage1OperationsProbe(DataSource dataSource) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
  }

  public Snapshot snapshot(String principalId) {
    requirePrincipal(principalId);
    return jdbc.sql(
            """
            SELECT
                count(*) FILTER (WHERE status = 'PLANNED') AS planned,
                count(*) FILTER (WHERE status = 'DISPATCHING') AS dispatching,
                count(*) FILTER (WHERE status = 'UNKNOWN') AS unknown,
                count(*) FILTER (WHERE status = 'RECONCILING') AS reconciling,
                count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                count(*) FILTER (WHERE status = 'FAILED') AS failed
            FROM action_attempts
            WHERE principal_id = :principalId
            """)
        .param("principalId", principalId)
        .query(
            (resultSet, rowNumber) ->
                new Snapshot(
                    resultSet.getInt("planned"),
                    resultSet.getInt("dispatching"),
                    resultSet.getInt("unknown"),
                    resultSet.getInt("reconciling"),
                    resultSet.getInt("succeeded"),
                    resultSet.getInt("failed")))
        .single();
  }

  private static void requirePrincipal(String principalId) {
    if (principalId == null
        || principalId.isBlank()
        || principalId.length() > 200
        || principalId.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("principalId is invalid");
    }
  }

  public record Snapshot(
      int planned,
      int dispatching,
      int unknown,
      int reconciling,
      int succeeded,
      int failed) {

    public Snapshot {
      if (planned < 0
          || dispatching < 0
          || unknown < 0
          || reconciling < 0
          || succeeded < 0
          || failed < 0) {
        throw new IllegalArgumentException("Action state counts must not be negative");
      }
    }

    public int unresolved() {
      return Math.addExact(
          Math.addExact(planned, dispatching),
          Math.addExact(unknown, reconciling));
    }

    public Map<String, Integer> statusCounts() {
      Map<String, Integer> counts = new LinkedHashMap<>();
      counts.put("PLANNED", planned);
      counts.put("DISPATCHING", dispatching);
      counts.put("UNKNOWN", unknown);
      counts.put("RECONCILING", reconciling);
      counts.put("SUCCEEDED", succeeded);
      counts.put("FAILED", failed);
      return Collections.unmodifiableMap(counts);
    }
  }
}
