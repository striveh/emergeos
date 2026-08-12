package io.emergeos.grapheval;

import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphRunRole;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.port.GraphAttemptReader;
import java.util.Objects;
import java.util.stream.Collectors;

/** Production read-only, hash-only projector for Pack010 graph evidence. */
final class Pack010GraphTerminalVerifier {

  private static final String ABSENT = "ABSENT";

  private final GraphAttemptReader reader;

  Pack010GraphTerminalVerifier(GraphAttemptReader reader) {
    this.reader = Objects.requireNonNull(reader, "reader");
  }

  Result verify(
      GraphAttemptManifest expected, Checkpoint checkpoint) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(checkpoint, "checkpoint");
    GraphAttemptVerification verification =
        reader.findVerified(expected);
    if (verification instanceof GraphAttemptVerification.Missing) {
      throw new Rejected("GRAPH_MISSING", 4);
    }
    if (verification instanceof GraphAttemptVerification.Invalid) {
      throw new Rejected("GRAPH_INVALID", 5);
    }
    GraphAttemptSnapshot snapshot =
        ((GraphAttemptVerification.Valid) verification)
            .snapshot();
    try {
      requireCheckpoint(snapshot, expected, checkpoint);
      requireShape(snapshot, checkpoint);
    } catch (RuntimeException mismatch) {
      throw new Rejected("TRUTH_SHAPE_MISMATCH", 6);
    }
    return new Result(receipt(snapshot, checkpoint));
  }

  private static void requireShape(
      GraphAttemptSnapshot snapshot,
      Checkpoint checkpoint) {
    if (!snapshot.manifest().attemptId().equals(
            snapshot.manifest().manifestHash())
        || snapshot.billingStatus() != checkpoint.billingStatus
        || snapshot.parentRun() == null
        || snapshot.childRun() == null
        || !snapshot.parentRun().runId().equals(
            snapshot.manifest().parentSelection().runId())
        || !snapshot.childRun().runId().equals(
            snapshot.manifest().childSelection().runId())) {
      throw new IllegalStateException(
          "Pack010 graph identity drifted");
    }
    switch (checkpoint) {
      case SEQ13 -> {
        if (snapshot.parentRun().lifecycle().terminal()
            || snapshot.childRun().lifecycle().terminal()
            || !snapshot.terminalBindings().isEmpty()
            || snapshot.candidate() != null
            || snapshot.workerResult() != null
            || snapshot.artifact() != null
            || snapshot.terminalSealPresent()
            || snapshot.outcome()
                != GraphAttemptOutcome.INCOMPLETE
            || snapshot.events().getLast().type()
                != GraphAttemptEventType.PROVIDER_INTENT
            || snapshot.events().getLast().requestOrdinal() == null
            || snapshot.events().getLast().requestOrdinal() != 2) {
          throw new IllegalStateException(
              "Pack010 seq13 truth drifted");
        }
      }
      case SEQ14 -> {
        if (snapshot.parentRun().lifecycle().terminal()
            || snapshot.childRun().lifecycle().terminal()
            || !snapshot.terminalBindings().isEmpty()
            || snapshot.candidate() != null
            || snapshot.workerResult() != null
            || snapshot.artifact() != null
            || snapshot.terminalSealPresent()
            || snapshot.outcome()
                != GraphAttemptOutcome.INCOMPLETE) {
          throw new IllegalStateException(
              "Pack010 seq14 truth drifted");
        }
      }
      case SEQ15 -> {
        GraphTerminalBinding child =
            binding(snapshot, GraphRunRole.CHILD);
        if (snapshot.parentRun().lifecycle().terminal()
            || snapshot.childRun().result().status()
                != RunStatus.SUCCEEDED
            || snapshot.terminalBindings().size() != 1
            || snapshot.candidate() == null
            || snapshot.workerResult() == null
            || snapshot.artifact() != null
            || snapshot.terminalSealPresent()
            || snapshot.outcome()
                != GraphAttemptOutcome.INCOMPLETE
            || !snapshot
                .candidate()
                .sourceResponseHash()
                .equals(
                    snapshot
                        .providerAttributions()
                        .get(1)
                        .responseHash())
            || !snapshot.candidate().contentHash().equals(
                snapshot.workerResult().contentHash())
            || !child.effectHash().equals(
                snapshot.workerResult().integrityHash())) {
          throw new IllegalStateException(
              "Pack010 seq15 truth drifted");
        }
      }
      case SEQ17 -> {
        GraphTerminalBinding child =
            binding(snapshot, GraphRunRole.CHILD);
        GraphTerminalBinding parent =
            binding(snapshot, GraphRunRole.PARENT);
        if (snapshot.parentRun().result().status()
                != RunStatus.SUCCEEDED
            || snapshot.childRun().result().status()
                != RunStatus.SUCCEEDED
            || snapshot.terminalBindings().size() != 2
            || snapshot.candidate() == null
            || snapshot.workerResult() == null
            || snapshot.artifact() == null
            || !snapshot.terminalSealPresent()
            || snapshot.terminalSeal() == null
            || snapshot.outcome()
                != GraphAttemptOutcome.SUCCEEDED
            || !snapshot.candidate().contentHash().equals(
                snapshot.workerResult().contentHash())
            || !snapshot.candidate().contentHash().equals(
                snapshot.artifact().current().contentHash())
            || !child.effectHash().equals(
                snapshot.workerResult().integrityHash())
            || !parent.effectHash().equals(
                snapshot.artifact().current().contentHash())
            || !snapshot.cursor().headHash().equals(
                snapshot.terminalSeal().finalHeadHash())) {
          throw new IllegalStateException(
              "Pack010 seq17 truth drifted");
        }
      }
    }
  }

  private static String receipt(
      GraphAttemptSnapshot snapshot,
      Checkpoint checkpoint) {
    AgentRun parent = snapshot.parentRun();
    AgentRun child = snapshot.childRun();
    GraphTerminalBinding parentTerminal =
        optionalBinding(snapshot, GraphRunRole.PARENT);
    GraphTerminalBinding childTerminal =
        optionalBinding(snapshot, GraphRunRole.CHILD);
    String attributionHashes =
        snapshot.providerAttributions().stream()
            .map(attribution -> attribution.attributionHash())
            .collect(Collectors.joining(","));
    String candidateRef =
        snapshot.candidate() == null
            ? ABSENT
            : snapshot.candidate().candidateRef();
    String candidateHash =
        snapshot.candidate() == null
            ? ABSENT
            : snapshot.candidate().integrityHash();
    String sourceResponseHash =
        snapshot.candidate() == null
            ? ABSENT
            : snapshot.candidate().sourceResponseHash();
    String contentHash =
        snapshot.candidate() == null
            ? ABSENT
            : snapshot.candidate().contentHash();
    String workerRef =
        snapshot.workerResult() == null
            ? ABSENT
            : snapshot.workerResult().workerResultRef();
    String workerHash =
        snapshot.workerResult() == null
            ? ABSENT
            : snapshot.workerResult().integrityHash();
    String artifactRef =
        parentTerminal == null
            ? ABSENT
            : parentTerminal.effectRef();
    var seal = snapshot.terminalSeal();
    return "PACK010_GRAPH_VERIFY"
        + " version=1"
        + " verdict=VALID"
        + " checkpoint="
        + checkpoint.name()
        + " protocol="
        + snapshot.manifest().graphProtocolVersion()
        + " executionSlotId="
        + snapshot.manifest().executionSlotId()
        + " attemptId="
        + snapshot.manifest().attemptId()
        + " manifestHash="
        + snapshot.manifest().manifestHash()
        + " sequence="
        + snapshot.cursor().lastSequence()
        + " phase="
        + snapshot.cursor().phase()
        + " headHash="
        + snapshot.cursor().headHash()
        + " outcome="
        + snapshot.outcome()
        + " billing="
        + snapshot.billingStatus()
        + " providerAttributionCount="
        + snapshot.providerAttributions().size()
        + " providerAttributionHashes="
        + attributionHashes
        + runReceipt("parent", parent, parentTerminal)
        + runReceipt("child", child, childTerminal)
        + " terminalBindingCount="
        + snapshot.terminalBindings().size()
        + " candidateRef="
        + candidateRef
        + " candidateIntegrityHash="
        + candidateHash
        + " sourceResponseHash="
        + sourceResponseHash
        + " sharedContentHash="
        + contentHash
        + " workerResultRef="
        + workerRef
        + " workerResultIntegrityHash="
        + workerHash
        + " artifactRef="
        + artifactRef
        + " sealFinalSequence="
        + (seal == null ? ABSENT : seal.finalSequence())
        + " preSealHeadHash="
        + (seal == null ? ABSENT : seal.preSealHeadHash())
        + " finalHeadHash="
        + (seal == null ? ABSENT : seal.finalHeadHash())
        + " sealHash="
        + (seal == null ? ABSENT : seal.sealHash())
        + " sealedAt="
        + (seal == null ? ABSENT : seal.sealedAt());
  }

  private static String runReceipt(
      String prefix,
      AgentRun run,
      GraphTerminalBinding binding) {
    return " "
        + prefix
        + "State="
        + run.lifecycle()
        + " "
        + prefix
        + "RunId="
        + run.runId()
        + " "
        + prefix
        + "BundleHash="
        + (run.bundle() == null
            ? ABSENT
            : run.bundle().integrityHash())
        + " "
        + prefix
        + "FailureCode="
        + (run.result() == null
                || run.result().failureReason() == null
            ? ABSENT
            : run.result().failureReason())
        + " "
        + prefix
        + "TerminalHash="
        + (binding == null ? ABSENT : binding.terminalHash());
  }

  private static GraphTerminalBinding binding(
      GraphAttemptSnapshot snapshot, GraphRunRole role) {
    GraphTerminalBinding binding =
        optionalBinding(snapshot, role);
    if (binding == null) {
      throw new IllegalStateException(
          "Pack010 terminal binding is absent");
    }
    return binding;
  }

  private static GraphTerminalBinding optionalBinding(
      GraphAttemptSnapshot snapshot, GraphRunRole role) {
    return snapshot.terminalBindings().stream()
        .filter(binding -> binding.role() == role)
        .findFirst()
        .orElse(null);
  }

  private static void requireCheckpoint(
      GraphAttemptSnapshot snapshot,
      GraphAttemptManifest expected,
      Checkpoint checkpoint) {
    if (!expected.equals(snapshot.manifest())
        || snapshot.cursor().lastSequence() != checkpoint.sequence
        || snapshot.cursor().phase() != checkpoint.phase
        || snapshot.providerAttributions().size()
            != checkpoint.attributionCount) {
      throw new IllegalStateException(
          "Pack010 checkpoint truth drifted");
    }
  }

  enum Checkpoint {
    SEQ13(
        13,
        GraphAttemptPhase.PROVIDER_PENDING,
        GraphBillingStatus.UNKNOWN,
        1),
    SEQ14(
        14,
        GraphAttemptPhase.PROVIDER_ATTRIBUTED,
        GraphBillingStatus.ATTRIBUTED,
        2),
    SEQ15(
        15,
        GraphAttemptPhase.CHILD_TERMINAL,
        GraphBillingStatus.ATTRIBUTED,
        2),
    SEQ17(
        17,
        GraphAttemptPhase.TERMINAL,
        GraphBillingStatus.ATTRIBUTED,
        2);

    private final int sequence;
    private final GraphAttemptPhase phase;
    private final GraphBillingStatus billingStatus;
    private final int attributionCount;

    Checkpoint(
        int sequence,
        GraphAttemptPhase phase,
        GraphBillingStatus billingStatus,
        int attributionCount) {
      this.sequence = sequence;
      this.phase = phase;
      this.billingStatus = billingStatus;
      this.attributionCount = attributionCount;
    }
  }

  record Result(String receipt) {
    Result {
      if (receipt == null
          || !receipt.startsWith("PACK010_GRAPH_VERIFY ")
          || receipt.indexOf('\n') >= 0
          || receipt.indexOf('\r') >= 0) {
        throw new IllegalArgumentException(
            "Pack010 verifier receipt is invalid");
      }
    }
  }

  static final class Rejected extends RuntimeException {

    private final String code;
    private final int exitCode;

    Rejected(String code, int exitCode) {
      super(null, null, false, false);
      this.code = code;
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
