package io.emergeos.core.application;

import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.ReferenceGroundingPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Pure, deterministic H1 reference-grounding verification for Agent draft candidates.
 *
 * <p>This facade exists so an offline evaluator can reuse the exact production H1 algorithm and
 * version without gaining a way to select the verifier used by {@link AgentDraftService}. It has
 * no I/O, mutable state, runtime configuration or weaker verification mode.
 */
public final class AgentDraftReferenceGrounding {

  public static final String VERIFIER_VERSION = "agent-draft-verifier-v1";

  private AgentDraftReferenceGrounding() {}

  public static Verification verify(Candidate candidate) {
    Objects.requireNonNull(candidate, "candidate");
    AgentDraftProposal proposal = candidate.proposal();
    if (proposal == null || !isValidArtifactContent(proposal.content())) {
      return rejected(Failure.INVALID_STRUCTURED_FINAL);
    }
    ReferenceGroundingPolicy.Failure failure =
        ReferenceGroundingPolicy.failure(
            proposal.evidenceRefs(),
            candidate.obtainedEvidenceRefs(),
            candidate.requiredEvidenceRef(),
            candidate.requiredEvidenceAvailable());
    return failure == null
        ? new Verification(null)
        : rejected(Failure.valueOf(failure.name()));
  }

  public record Candidate(
      AgentDraftProposal proposal,
      List<String> obtainedEvidenceRefs,
      String requiredEvidenceRef,
      boolean requiredEvidenceAvailable) {

    public Candidate {
      obtainedEvidenceRefs =
          List.copyOf(
              Objects.requireNonNull(
                  obtainedEvidenceRefs, "obtainedEvidenceRefs"));
      Objects.requireNonNull(requiredEvidenceRef, "requiredEvidenceRef");
      if (!requiredEvidenceRef.matches(
          "capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}")) {
        throw new IllegalArgumentException(
            "requiredEvidenceRef must name one Capture");
      }
    }
  }

  public enum Failure {
    INVALID_STRUCTURED_FINAL("INVALID_STRUCTURED_FINAL"),
    MISSING_REQUIRED_EVIDENCE("MISSING_REQUIRED_EVIDENCE"),
    UNSAFE_EVIDENCE_BINDING("UNSAFE_EVIDENCE_BINDING"),
    INVALID_EVIDENCE_CLAIM("INVALID_EVIDENCE_CLAIM"),
    REQUIRED_EVIDENCE_NOT_FOUND("REQUIRED_EVIDENCE_NOT_FOUND");

    private final String code;

    Failure(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  public record Verification(Failure failure) {

    public boolean accepted() {
      return failure == null;
    }

    public String failureCode() {
      return failure == null ? null : failure.code();
    }
  }

  private static Verification rejected(Failure failure) {
    return new Verification(Objects.requireNonNull(failure, "failure"));
  }

  private static boolean isValidArtifactContent(String content) {
    try {
      ArtifactLineageEntry.requireContent(content);
      return true;
    } catch (IllegalArgumentException invalidContent) {
      return false;
    }
  }
}
