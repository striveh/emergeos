package io.emergeos.core.application;

import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.ResultEnvelope;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.contracts.RunStatus;
import io.emergeos.contracts.TaskEnvelope;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRunOutcome;
import io.emergeos.core.domain.AgentTraceEvent;
import io.emergeos.core.domain.AgentTraceEventType;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.port.AgentKernel;
import io.emergeos.core.port.CancellationSignal;
import io.emergeos.core.port.IdGenerator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentDraftService {

  private static final String SCHEMA_VERSION = "1.0";
  private static final String AGENT_VERSION = "agent-draft-service-v1";
  private static final String VERIFIER_VERSION = "agent-draft-verifier-v1";

  private final AgentKernel kernel;
  private final ArtifactLineageService artifacts;
  private final IdGenerator ids;

  public AgentDraftService(
      AgentKernel kernel,
      ArtifactLineageService artifacts,
      IdGenerator ids) {
    this.kernel = Objects.requireNonNull(kernel, "kernel");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public AgentDraftOutcome draft(AgentDraftCommand command) {
    Objects.requireNonNull(command, "command");
    String captureRef = "capture://" + command.captureId();
    TaskEnvelope task = task(command, captureRef);
    AgentRunOutcome run = kernel.run(task, CancellationSignal.never());

    if (run.status() != RunStatus.SUCCEEDED) {
      return rejected(task, run, run.status(), run.failureReason());
    }
    AgentDraftProposal proposal = run.proposal();
    if (proposal == null || !isValidArtifactContent(proposal.content())) {
      return rejected(task, run, RunStatus.FAILED, "INVALID_STRUCTURED_FINAL");
    }
    if (!run.obtainedEvidenceRefs().contains(captureRef)) {
      return rejected(task, run, RunStatus.FAILED, "MISSING_REQUIRED_EVIDENCE");
    }
    if (!proposal.evidenceRefs().equals(List.of(captureRef))) {
      return rejected(task, run, RunStatus.FAILED, "INVALID_EVIDENCE_CLAIM");
    }

    ArtifactLineage artifact =
        artifacts.create(
            new CreateArtifactCommand(
                command.principalId(), command.captureId(), proposal.content()));
    String artifactRef = "artifact://" + artifact.artifactId();
    List<AgentTraceEvent> trace = new ArrayList<>(run.trace());
    trace.add(
        new AgentTraceEvent(
            trace.size() + 1,
            AgentTraceEventType.ARTIFACT_COMMITTED,
            null,
            "SUCCEEDED",
            artifactRef));
    ResultEnvelope result =
        result(
            task,
            run,
            RunStatus.SUCCEEDED,
            List.of(artifactRef),
            List.of(captureRef),
            null);
    return new AgentDraftOutcome(result, artifact, trace);
  }

  private AgentDraftOutcome rejected(
      TaskEnvelope task,
      AgentRunOutcome run,
      RunStatus status,
      String failureReason) {
    ResultEnvelope result =
        result(task, run, status, List.of(), List.of(), failureReason);
    return new AgentDraftOutcome(result, null, run.trace());
  }

  private ResultEnvelope result(
      TaskEnvelope task,
      AgentRunOutcome run,
      RunStatus status,
      List<String> artifactRefs,
      List<String> evidenceRefs,
      String failureReason) {
    return new ResultEnvelope(
        SCHEMA_VERSION,
        task.id(),
        status,
        artifactRefs,
        evidenceRefs,
        List.of(),
        List.of(),
        List.of(),
        run.resolvedModel(),
        AGENT_VERSION,
        VERIFIER_VERSION,
        run.costUsd(),
        run.latencyMs(),
        null,
        failureReason);
  }

  private TaskEnvelope task(AgentDraftCommand command, String captureRef) {
    return new TaskEnvelope(
        SCHEMA_VERSION,
        ids.next("task"),
        null,
        command.principalId(),
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        command.intent(),
        List.of(captureRef),
        List.of(),
        List.of("text"),
        DataClass.PERSONAL,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of("draft cites the source Capture"),
        false,
        5_000,
        BigDecimal.ZERO,
        null,
        "agent-draft-policy-v1",
        "stage2-s1",
        "ref-only-v1",
        "agent-tools-v1",
        null,
        List.of(),
        List.of(),
        "structured final or non-success");
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
