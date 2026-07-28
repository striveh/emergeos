package io.emergeos.core.port;

import io.emergeos.core.domain.ActionAttempt;
import io.emergeos.core.domain.ActionReceipt;
import java.time.Instant;
import java.util.Optional;

public interface ActionAttemptStore {

  PlanResult planOrFind(ActionAttempt proposed);

  ClaimResult claimDispatch(ActionAttempt expected, Instant now);

  ClaimResult claimReconciliation(ActionAttempt expected, Instant now);

  ActionAttempt markUnknown(ActionAttempt claimed, Instant now);

  ActionAttempt complete(ActionAttempt claimed, ActionReceipt receipt, Instant now);

  Optional<ActionAttempt> findOwned(String principalId, String attemptId);

  sealed interface PlanResult {

    record Accepted(ActionAttempt attempt) implements PlanResult {}

    record Conflict() implements PlanResult {}
  }

  sealed interface ClaimResult {

    record Claimed(ActionAttempt attempt) implements ClaimResult {}

    record Observed(ActionAttempt attempt) implements ClaimResult {}

    record Rejected() implements ClaimResult {}

    record NotFound() implements ClaimResult {}
  }
}
