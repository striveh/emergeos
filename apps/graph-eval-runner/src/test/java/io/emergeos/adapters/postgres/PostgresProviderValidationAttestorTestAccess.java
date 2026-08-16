package io.emergeos.adapters.postgres;

import java.util.Objects;
import javax.sql.DataSource;

/** Test-only access to the V13 transaction probe boundary. */
public final class PostgresProviderValidationAttestorTestAccess {

  public enum ProbePoint {
    AFTER_PROVIDER_VALIDATION_STAGED,
    AFTER_SIGNATURE_VERIFIED,
    AFTER_PROVIDER_VALIDATION_COMMITTED
  }

  @FunctionalInterface
  public interface Probe {
    void hit(ProbePoint point);
  }

  private PostgresProviderValidationAttestorTestAccess() {}

  public static PostgresProviderValidationAttestor open(
      DataSource dataSource, Probe probe) {
    Objects.requireNonNull(probe, "probe");
    return new PostgresProviderValidationAttestor(
        dataSource,
        point -> probe.hit(ProbePoint.valueOf(point.name())));
  }
}
