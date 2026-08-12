package io.emergeos.grapheval;

import io.emergeos.core.domain.AgentRunLifecycle;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.port.GraphAttemptReader;
import java.util.Objects;

/** Production read-only interpretation of the frozen Pack009 graph prefix. */
final class Pack009GraphVerifier {

  private final GraphAttemptReader store;

  Pack009GraphVerifier(GraphAttemptReader store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  Result verify() {
    GraphAttemptVerification verification =
        store.findVerified(Pack009GraphEvalCatalog.manifest());
    if (verification instanceof GraphAttemptVerification.Missing) {
      throw new Rejected("GRAPH_MISSING", 4);
    }
    if (verification
        instanceof GraphAttemptVerification.Invalid invalid) {
      throw new Rejected(
          "GRAPH_INVALID_" + invalid.reasonCode(), 5);
    }
    GraphAttemptSnapshot snapshot =
        ((GraphAttemptVerification.Valid) verification).snapshot();
    long providerIntents =
        snapshot.events().stream()
            .filter(
                event ->
                    event.type()
                        == GraphAttemptEventType.PROVIDER_INTENT)
            .count();
    if (snapshot.cursor().lastSequence() != 11
        || snapshot.cursor().phase()
            != GraphAttemptPhase.PROVIDER_PENDING
        || snapshot.outcome() != GraphAttemptOutcome.INCOMPLETE
        || snapshot.billingStatus() != GraphBillingStatus.UNKNOWN
        || snapshot.parentRun() == null
        || snapshot.parentRun().lifecycle()
            != AgentRunLifecycle.RUNNING
        || snapshot.childRun() == null
        || snapshot.childRun().lifecycle()
            != AgentRunLifecycle.RUNNING
        || snapshot.terminalSealPresent()
        || providerIntents != 1
        || snapshot.events().getLast().requestOrdinal() == null
        || snapshot.events().getLast().requestOrdinal() != 1
        || !Pack009GraphEvalCatalog.EXPECTED_FIRST_REQUEST_HASH
            .equals(snapshot.events().getLast().requestHash())
        || !Pack009GraphEvalCatalog.workerProfile()
            .modelRequested()
            .equals(
                snapshot.events().getLast().modelRequested())) {
      throw new Rejected("GRAPH_STATE_MISMATCH", 6);
    }
    return new Result(
        "GRAPH_VERIFY verdict=VALID"
            + " outcome=INCOMPLETE"
            + " billing=UNKNOWN"
            + " sequence=11"
            + " phase=PROVIDER_PENDING"
            + " parent=RUNNING"
            + " child=RUNNING"
            + " providerIntents=1"
            + " terminalSeal=false"
            + " attemptId="
            + snapshot.manifest().attemptId()
            + " headHash="
            + snapshot.cursor().headHash());
  }

  record Result(String receipt) {

    Result {
      Objects.requireNonNull(receipt, "receipt");
    }
  }

  static final class Rejected extends RuntimeException {

    private final String code;
    private final int exitCode;

    Rejected(String code, int exitCode) {
      super(code, null, false, false);
      this.code = Objects.requireNonNull(code, "code");
      this.exitCode = exitCode;
    }

    String code() {
      return code;
    }

    int exitCode() {
      return exitCode;
    }
  }
}
