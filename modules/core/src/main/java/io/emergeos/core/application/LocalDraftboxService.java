package io.emergeos.core.application;

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

  public record Execution(
      io.emergeos.core.domain.ActionAttempt attempt,
      io.emergeos.core.domain.LocalDraft draft,
      boolean created) {

    public Execution {
      Objects.requireNonNull(attempt, "attempt");
      Objects.requireNonNull(draft, "draft");
    }
  }
}
