package io.emergeos.core.domain;

import java.util.Objects;

/** Safe semantic material known before PostgreSQL mints the one-shot challenge. */
public record GraphProviderValidationStatement(
    GraphProviderAttribution attribution,
    String executionBindingHash,
    String transportProfileHash,
    String parserProfileHash,
    String schemaProfileHash,
    GraphProviderValidationDecision decision,
    String decisionHash,
    GraphAttributedFailureCode failureCode) {

  public GraphProviderValidationStatement {
    attribution = Objects.requireNonNull(attribution, "attribution");
    decision = Objects.requireNonNull(decision, "decision");
    if (attribution.requestOrdinal() != 2
        || (decision == GraphProviderValidationDecision.STRUCTURED_FINAL
            && failureCode != null)
        || (decision == GraphProviderValidationDecision.FAILED
            && failureCode == null)) {
      throw new IllegalArgumentException(
          "provider validation statement is invalid");
    }
    executionBindingHash =
        GraphAttemptDomains.hash(
            executionBindingHash, "executionBindingHash");
    transportProfileHash =
        GraphAttemptDomains.hash(
            transportProfileHash, "transportProfileHash");
    parserProfileHash =
        GraphAttemptDomains.hash(parserProfileHash, "parserProfileHash");
    schemaProfileHash =
        GraphAttemptDomains.hash(schemaProfileHash, "schemaProfileHash");
    decisionHash =
        GraphAttemptDomains.hash(decisionHash, "decisionHash");
  }
}
