package io.emergeos.core.application;

import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.TaskEnvelope;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Provider-neutral, server-owned identity for one billable Model route.
 *
 * <p>The interface deliberately exposes policy and metering facts, not SDK
 * types or credentials. A Model adapter must reject a Task that is not bound
 * to the exact profile before it can create a provider request.
 */
public interface ModelExecutionProfile {

  String id();

  String fingerprint();

  String taskSchemaVersion();

  boolean modelBound();

  String modelProvider();

  String modelRequested();

  String modelAdapterVersion();

  PricingProfile pricing();

  HarnessExperiment experiment();

  int maxModelSteps();

  int maxToolCalls();

  long deadlineMs();

  BigDecimal budgetUsd();

  long maxInputTokensPerStep();

  long maxOutputTokensPerStep();

  default BigDecimal reservationUsd() {
    if (!modelBound()) {
      return BigDecimal.ZERO;
    }
    return pricing()
        .reserveCostUsd(
            maxModelSteps(),
            maxInputTokensPerStep(),
            maxOutputTokensPerStep());
  }

  String environmentSnapshotRef();

  String taskIdempotencyKey(String taskId);

  void requireTaskBinding(TaskEnvelope task);

  Map<String, String> modelComponentVersions();
}
