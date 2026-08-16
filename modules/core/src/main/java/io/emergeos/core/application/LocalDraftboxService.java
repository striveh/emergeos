package io.emergeos.core.application;

import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.LocalDraftUndoReceipt;
import io.emergeos.core.port.IdGenerator;
import io.emergeos.core.port.LocalDraftboxStore;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Executes one exact owner approval as an atomic, provider-free local database effect. */
public final class LocalDraftboxService {

  private final LocalDraftboxStore drafts;
  private final IdGenerator ids;
  private final LocalDraftboxAuthority authority;

  public LocalDraftboxService(
      LocalDraftboxStore drafts, IdGenerator ids, LocalDraftboxAuthority authority) {
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.authority = Objects.requireNonNull(authority, "authority");
  }

  public Execution execute(ExecuteLocalDraftCommand command) {
    Objects.requireNonNull(command, "command");
    LocalDraftboxStore.ExecuteResult result =
        drafts.executeOwned(
            authority.principalId(),
            command.attemptId(),
            command.scopeSchema(),
            command.scopeHash(),
            ids.next("draft"),
            ids.next("rcpt"));
    if (result instanceof LocalDraftboxStore.ExecuteResult.Executed executed) {
      return new Execution(executed.attempt(), executed.draft(), executed.created());
    }
    if (result instanceof LocalDraftboxStore.ExecuteResult.Stale) {
      throw new ApprovalStaleException();
    }
    if (result instanceof LocalDraftboxStore.ExecuteResult.NotFound) {
      throw new NoSuchElementException("Action approval not found");
    }
    throw new IllegalStateException("Action approval cannot be executed from its current state");
  }

  public Undo undo(UndoLocalDraftCommand command) {
    Objects.requireNonNull(command, "command");
    LocalDraftboxStore.UndoResult result =
        drafts.undoOwned(
            authority.principalId(),
            command.attemptId(),
            command.undoNonce(),
            command.scopeSchema(),
            command.scopeHash(),
            ids.next("rcpt"));
    if (result instanceof LocalDraftboxStore.UndoResult.Undone undone) {
      return new Undo(undone.creationAttempt(), undone.receipt(), undone.created());
    }
    if (result instanceof LocalDraftboxStore.UndoResult.Stale) {
      throw new ApprovalStaleException();
    }
    if (result instanceof LocalDraftboxStore.UndoResult.NotFound) {
      throw new NoSuchElementException("ActionAttempt not found");
    }
    throw new LocalDraftUndoConflictException();
  }

  public Projection project(ActionAttempt creationAttempt) {
    Objects.requireNonNull(creationAttempt, "creationAttempt");
    LocalDraftUndoReceipt undoReceipt =
        drafts
            .findUndoReceiptOwned(authority.principalId(), creationAttempt)
            .orElse(null);
    return new Projection(creationAttempt, undoReceipt);
  }

  public record Execution(
      io.emergeos.core.domain.ActionAttempt attempt,
      io.emergeos.core.domain.LocalDraft draft,
      boolean created) {

    public Execution {
      Objects.requireNonNull(attempt, "attempt");
      Objects.requireNonNull(draft, "draft");
    }
  }

  public record Undo(
      ActionAttempt creationAttempt,
      LocalDraftUndoReceipt receipt,
      boolean created) {

    public Undo {
      Objects.requireNonNull(creationAttempt, "creationAttempt");
      Objects.requireNonNull(receipt, "receipt");
      if (!exactUndoBinding(creationAttempt, receipt)) {
        throw new IllegalArgumentException(
            "logical Undo must bind its exact creation truth");
      }
    }
  }

  public record Projection(
      ActionAttempt creationAttempt, LocalDraftUndoReceipt undoReceipt) {

    public Projection {
      Objects.requireNonNull(creationAttempt, "creationAttempt");
      if (undoReceipt != null
          && !exactUndoBinding(creationAttempt, undoReceipt)) {
        throw new IllegalArgumentException(
            "logical Undo projection must bind its exact creation truth");
      }
    }
  }

  private static boolean exactUndoBinding(
      ActionAttempt creationAttempt, LocalDraftUndoReceipt receipt) {
    return creationAttempt.attemptId().equals(receipt.creationAttemptId())
        && creationAttempt.localDraftReceipt() != null
        && creationAttempt.localDraftReceipt().draftId().equals(receipt.draftId())
        && creationAttempt.localDraftReceipt().receiptId().equals(
            receipt.creationReceiptId())
        && creationAttempt.plan().artifactId().equals(receipt.artifactId())
        && creationAttempt.plan().artifactVersion() == receipt.artifactVersion()
        && creationAttempt.plan().artifactHash().equals(receipt.artifactHash());
  }
}
