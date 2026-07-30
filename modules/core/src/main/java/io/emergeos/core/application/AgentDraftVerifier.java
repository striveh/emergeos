package io.emergeos.core.application;

/**
 * Internal verification seam between a sanitized Agent candidate and product-owned persistence.
 *
 * <p>The seam is deliberately package-private: production callers cannot select a weaker
 * verifier through an API, Spring bean or runtime property. The public {@link AgentDraftService}
 * constructors bind the reference-grounding verifier.
 */
interface AgentDraftVerifier {

  String version();

  AgentDraftReferenceGrounding.Verification verify(
      AgentDraftReferenceGrounding.Candidate candidate);
}
