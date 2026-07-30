package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Cross-runtime value domains used by Java, JSON Schema, Node verification, and PostgreSQL.
 *
 * <p>The USD profile deliberately stays below the precision where a JSON number parsed by an
 * IEEE-754 runtime can silently change the canonical decimal text.
 */
public final class ContractValueDomains {

  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  public static final long MAX_DURATION_MS = 86_400_000L;
  public static final int MAX_EXECUTION_LIMIT = 128;
  public static final int MAX_EXPERIMENT_REPETITION = 1_000_000;
  public static final int USD_MAX_SCALE = 6;
  public static final BigDecimal MAX_USD = new BigDecimal("999999.999999");

  private ContractValueDomains() {}

  public static BigDecimal requireUsd(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    if (normalized.signum() < 0
        || normalized.scale() > USD_MAX_SCALE
        || normalized.compareTo(MAX_USD) > 0) {
      throw new IllegalArgumentException(
          name + " must be between 0 and " + MAX_USD.toPlainString()
              + " with at most " + USD_MAX_SCALE + " decimal places");
    }
    return value;
  }

  public static long requireDuration(long value, String name, boolean allowZero) {
    long minimum = allowZero ? 0 : 1;
    if (value < minimum || value > MAX_DURATION_MS) {
      throw new IllegalArgumentException(
          name + " must be between " + minimum + " and " + MAX_DURATION_MS);
    }
    return value;
  }

  public static long requireSafeCount(long value, String name) {
    if (value < 0 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(
          name + " must be a non-negative JavaScript-safe integer");
    }
    return value;
  }

  public static int requireExecutionLimit(int value, String name) {
    if (value < 1 || value > MAX_EXECUTION_LIMIT) {
      throw new IllegalArgumentException(
          name + " must be between 1 and " + MAX_EXECUTION_LIMIT);
    }
    return value;
  }
}
