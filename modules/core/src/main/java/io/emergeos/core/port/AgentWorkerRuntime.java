package io.emergeos.core.port;

import io.emergeos.contracts.ContractValueDomains;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.domain.AgentWorkerExecution;
import io.emergeos.core.domain.WorkerHandoffRequest;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Provider-neutral, durable Worker runtime seam.
 *
 * <p>Preparation must not start a child Run, Model or Tool. A prepared execution is one-shot and
 * owns all durable child lifecycle work.
 */
public interface AgentWorkerRuntime {

  String registryVersion();

  String profileFingerprint();

  Preparation prepare(
      AgentRunContext parent,
      WorkerHandoffRequest request,
      ExecutionWindow window);

  record ExecutionWindow(
      long remainingDeadlineMs,
      BigDecimal remainingBudgetUsd,
      CancellationSignal cancellation) {

    public ExecutionWindow {
      ContractValueDomains.requireDuration(
          remainingDeadlineMs, "remainingDeadlineMs", false);
      ContractValueDomains.requireUsd(
          remainingBudgetUsd, "remainingBudgetUsd");
      Objects.requireNonNull(cancellation, "cancellation");
    }
  }

  record Preparation(
      PreparedHandoff prepared,
      RunStatus rejectionStatus,
      String failureReason) {

    public Preparation {
      if ((prepared == null)
          == (rejectionStatus == null || failureReason == null)) {
        throw new IllegalArgumentException(
            "Preparation must be exactly prepared or rejected");
      }
      if (prepared != null) {
        if (rejectionStatus != null || failureReason != null) {
          throw new IllegalArgumentException(
              "Prepared Handoff cannot contain rejection truth");
        }
      } else if (rejectionStatus != RunStatus.BLOCKED
          || !java.util.Set.of(
                  "HANDOFF_NOT_ALLOWED",
                  "HANDOFF_CONTEXT_POLICY_DRIFT",
                  "HANDOFF_AUTHORITY_ESCALATION")
              .contains(failureReason)) {
        throw new IllegalArgumentException("Handoff rejection is invalid");
      }
    }

    public static Preparation prepared(PreparedHandoff value) {
      return new Preparation(
          Objects.requireNonNull(value, "prepared"), null, null);
    }

    public static Preparation rejected(
        RunStatus status, String failureReason) {
      return new Preparation(null, status, failureReason);
    }

    public boolean accepted() {
      return prepared != null;
    }
  }

  interface PreparedHandoff {

    String workerName();

    String childRunRef();

    AgentWorkerExecution execute();
  }

  static AgentWorkerRuntime disabled() {
    return Disabled.INSTANCE;
  }

  final class Disabled implements AgentWorkerRuntime {

    private static final Disabled INSTANCE = new Disabled();

    private Disabled() {}

    @Override
    public String registryVersion() {
      return "agent-workers-disabled";
    }

    @Override
    public String profileFingerprint() {
      return null;
    }

    @Override
    public Preparation prepare(
        AgentRunContext parent,
        WorkerHandoffRequest request,
        ExecutionWindow window) {
      return Preparation.rejected(
          RunStatus.BLOCKED, "HANDOFF_NOT_ALLOWED");
    }
  }
}
