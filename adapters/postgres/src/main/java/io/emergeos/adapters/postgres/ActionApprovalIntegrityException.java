package io.emergeos.adapters.postgres;

/** Signals that a stored action approval cannot be reconstructed as canonical domain state. */
public final class ActionApprovalIntegrityException extends RuntimeException {

  public static final String PUBLIC_MESSAGE = "Stored action approval cannot be verified.";

  public ActionApprovalIntegrityException() {
    super(PUBLIC_MESSAGE);
  }

  ActionApprovalIntegrityException(Throwable cause) {
    super(PUBLIC_MESSAGE, cause);
  }
}
