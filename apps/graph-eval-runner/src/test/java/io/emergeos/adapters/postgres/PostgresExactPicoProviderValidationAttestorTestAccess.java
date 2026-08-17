package io.emergeos.adapters.postgres;

import java.util.Objects;
import javax.sql.DataSource;

/** Test-only access to the V18 typed transaction probe boundary. */
public final class PostgresExactPicoProviderValidationAttestorTestAccess {

  public enum ProbePoint {
    AFTER_STAGE_MAPPED,
    AFTER_SIGNATURE_VERIFIED,
    AFTER_COMMIT_RECEIPT_VERIFIED
  }

  @FunctionalInterface
  public interface Probe {
    void hit(ProbePoint point);
  }

  private PostgresExactPicoProviderValidationAttestorTestAccess() {}

  public static PostgresExactPicoProviderValidationAttestor open(
      DataSource dataSource, Probe probe) {
    Objects.requireNonNull(probe, "probe");
    return new PostgresExactPicoProviderValidationAttestor(
        dataSource,
        point -> probe.hit(ProbePoint.valueOf(point.name())));
  }
}
