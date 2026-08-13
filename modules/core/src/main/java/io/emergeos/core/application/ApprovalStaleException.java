package io.emergeos.core.application;

public final class ApprovalStaleException extends IllegalStateException {

  public ApprovalStaleException() {
    super("approved Artifact is not the current version");
  }
}
