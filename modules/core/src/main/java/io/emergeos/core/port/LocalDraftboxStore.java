package io.emergeos.core.port;

import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.LocalDraft;
import java.util.Objects;

public interface LocalDraftboxStore {

  ExecuteResult executeOwned(
      String principalId,
      String attemptId,
      String scopeSchema,
      String scopeHash,
      String proposedDraftId,
      String proposedReceiptId);

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
}
