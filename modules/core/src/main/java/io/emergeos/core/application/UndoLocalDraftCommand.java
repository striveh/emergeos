package io.emergeos.core.application;

import io.emergeos.core.domain.LocalDraftUndoScope;

public record UndoLocalDraftCommand(
    String attemptId, String undoNonce, String scopeSchema, String scopeHash) {

  public UndoLocalDraftCommand {
    CreateArtifactCommand.requireIdentifier(attemptId, "attemptId");
    CreateArtifactCommand.requireIdentifier(undoNonce, "undoNonce");
    CreateArtifactCommand.requireIdentifier(scopeSchema, "scopeSchema");
    if (!LocalDraftUndoScope.SCHEMA.equals(scopeSchema)) {
      throw new ApprovalStaleException();
    }
    if (scopeHash == null || !scopeHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("scopeHash must be a lowercase SHA-256 hash");
    }
  }
}
