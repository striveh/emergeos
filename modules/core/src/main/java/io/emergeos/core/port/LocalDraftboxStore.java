package io.emergeos.core.port;

import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.LocalDraft;
import io.emergeos.core.domain.LocalDraftUndoReceipt;
import java.util.Objects;
import java.util.Optional;

public interface LocalDraftboxStore {

  ExecuteResult executeOwned(
      String principalId,
      String attemptId,
      String scopeSchema,
      String scopeHash,
      String proposedDraftId,
      String proposedReceiptId);

  UndoResult undoOwned(
      String principalId,
      String attemptId,
      String undoNonce,
      String scopeSchema,
      String scopeHash,
      String proposedReceiptId);

  Optional<LocalDraftUndoReceipt> findUndoReceiptOwned(
      String principalId, ActionAttempt creationAttempt);

  sealed interface ExecuteResult {

    record Executed(ActionAttempt attempt, LocalDraft draft, boolean created)
        implements ExecuteResult {

      public Executed {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(draft, "draft");
        if (!attempt.attemptId().equals(draft.attemptId())
            || attempt.localDraftReceipt() == null
            || !attempt.localDraftReceipt().draftId().equals(draft.draftId())) {
          throw new IllegalArgumentException(
              "local draft execution must bind attempt, draft and Receipt");
        }
      }
    }

    record Stale() implements ExecuteResult {}

    record Conflict() implements ExecuteResult {}

    record NotFound() implements ExecuteResult {}
  }

  sealed interface UndoResult {

    record Undone(
        ActionAttempt creationAttempt,
        LocalDraftUndoReceipt receipt,
        boolean created) implements UndoResult {

      public Undone {
        Objects.requireNonNull(creationAttempt, "creationAttempt");
        Objects.requireNonNull(receipt, "receipt");
        if (!creationAttempt.attemptId().equals(receipt.creationAttemptId())
            || creationAttempt.localDraftReceipt() == null
            || !creationAttempt.localDraftReceipt().draftId().equals(receipt.draftId())
            || !creationAttempt.localDraftReceipt().receiptId().equals(
                receipt.creationReceiptId())
            || !creationAttempt.plan().artifactId().equals(receipt.artifactId())
            || creationAttempt.plan().artifactVersion() != receipt.artifactVersion()
            || !creationAttempt.plan().artifactHash().equals(receipt.artifactHash())) {
          throw new IllegalArgumentException(
              "logical Undo Receipt must bind its exact Draftbox creation truth");
        }
      }
    }

    record Stale() implements UndoResult {}

    record Conflict() implements UndoResult {}

    record NotFound() implements UndoResult {}
  }
}
