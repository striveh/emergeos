package io.emergeos.core.domain;

/**
 * Graph lifecycle axis. TERMINAL does not imply business success; the
 * terminal seal carries that separate outcome.
 */
public enum GraphAttemptOutcome {
  INCOMPLETE,
  SUCCEEDED,
  FAILED
}
