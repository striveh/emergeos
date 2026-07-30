package io.emergeos.core.application;

/**
 * Product verifier that accepts only a durable, reference-grounded Agent draft candidate.
 */
final class ReferenceGroundingAgentDraftVerifier implements AgentDraftVerifier {

  static final String VERSION =
      AgentDraftReferenceGrounding.VERIFIER_VERSION;
  static final ReferenceGroundingAgentDraftVerifier INSTANCE =
      new ReferenceGroundingAgentDraftVerifier();

  private ReferenceGroundingAgentDraftVerifier() {}

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public AgentDraftReferenceGrounding.Verification verify(
      AgentDraftReferenceGrounding.Candidate candidate) {
    return AgentDraftReferenceGrounding.verify(candidate);
  }
}
