package io.emergeos.core.application;

public final class CaptureNonceConflictException extends RuntimeException {

  public CaptureNonceConflictException() {
    super("clientNonce was already used for a different Capture request");
  }
}
