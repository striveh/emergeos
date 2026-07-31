package io.emergeos.core.domain;

/**
 * Safe durable evidence written before an SDK provider call.
 *
 * <p>No credential, header, full prompt, response body or contact data may
 * appear here.
 */
public record GraphProviderIntent(
    int requestOrdinal,
    String requestHash,
    String modelRequested) {

  public GraphProviderIntent {
    if (requestOrdinal < 1 || requestOrdinal > 128) {
      throw new IllegalArgumentException(
          "requestOrdinal is outside the reviewed domain");
    }
    requestHash =
        GraphAttemptDomains.hash(requestHash, "requestHash");
    modelRequested =
        GraphAttemptDomains.modelIdentifier(
            modelRequested, "modelRequested");
  }
}
