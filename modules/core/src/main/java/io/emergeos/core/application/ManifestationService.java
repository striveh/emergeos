package io.emergeos.core.application;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.EvidenceEvent;
import io.emergeos.core.domain.Manifestation;
import io.emergeos.core.domain.ManifestationStatus;
import io.emergeos.core.domain.Receipt;
import io.emergeos.core.domain.ReflectionCandidate;
import io.emergeos.core.port.ActionExecutor;
import io.emergeos.core.port.ActionPolicy;
import io.emergeos.core.port.ArtifactGenerator;
import io.emergeos.core.port.ArtifactRepository;
import io.emergeos.core.port.EvidenceLedger;
import io.emergeos.core.port.IdGenerator;
import io.emergeos.core.port.ManifestationRepository;
import io.emergeos.core.port.ReceiptLedger;
import io.emergeos.core.port.ReflectionProposer;
import io.emergeos.core.port.WorkingSelfProjector;
import io.emergeos.core.port.WorkingSelfRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class ManifestationService {

  private final EvidenceLedger evidenceLedger;
  private final ArtifactRepository artifactRepository;
  private final ManifestationRepository manifestationRepository;
  private final ReceiptLedger receiptLedger;
  private final WorkingSelfRepository workingSelfRepository;
  private final WorkingSelfProjector workingSelfProjector;
  private final ArtifactGenerator artifactGenerator;
  private final ActionPolicy actionPolicy;
  private final ActionExecutor actionExecutor;
  private final ReflectionProposer reflectionProposer;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public ManifestationService(
      EvidenceLedger evidenceLedger,
      ArtifactRepository artifactRepository,
      ManifestationRepository manifestationRepository,
      ReceiptLedger receiptLedger,
      WorkingSelfRepository workingSelfRepository,
      WorkingSelfProjector workingSelfProjector,
      ArtifactGenerator artifactGenerator,
      ActionPolicy actionPolicy,
      ActionExecutor actionExecutor,
      ReflectionProposer reflectionProposer,
      IdGenerator idGenerator,
      Clock clock) {
    this.evidenceLedger = Objects.requireNonNull(evidenceLedger, "evidenceLedger");
    this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
    this.manifestationRepository =
        Objects.requireNonNull(manifestationRepository, "manifestationRepository");
    this.receiptLedger = Objects.requireNonNull(receiptLedger, "receiptLedger");
    this.workingSelfRepository =
        Objects.requireNonNull(workingSelfRepository, "workingSelfRepository");
    this.workingSelfProjector =
        Objects.requireNonNull(workingSelfProjector, "workingSelfProjector");
    this.artifactGenerator = Objects.requireNonNull(artifactGenerator, "artifactGenerator");
    this.actionPolicy = Objects.requireNonNull(actionPolicy, "actionPolicy");
    this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
    this.reflectionProposer = Objects.requireNonNull(reflectionProposer, "reflectionProposer");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public synchronized ManifestationView capture(CaptureThoughtCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.dataClass() == DataClass.SENSITIVE || command.dataClass() == DataClass.SECRET) {
      throw new IllegalArgumentException(
          "the in-memory prototype does not accept SENSITIVE or SECRET content");
    }
    Instant now = clock.instant();

    var evidence =
        new EvidenceEvent(
            idGenerator.next("evi"),
            command.principalId(),
            "THOUGHT_SEED",
            command.content(),
            ContentHashes.sha256(command.content()),
            command.sourceType(),
            command.sourceRef(),
            command.dataClass(),
            now);
    evidenceLedger.append(evidence);

    var workingSelf =
        workingSelfProjector.project(
            idGenerator.next("ws"), command.principalId(), evidence, now);
    workingSelfRepository.save(workingSelf);
    var artifact =
        artifactGenerator.generate(idGenerator.next("art"), evidence, workingSelf, now);
    artifactRepository.save(artifact);

    String manifestationId = idGenerator.next("man");
    ActionPlan plan =
        actionPolicy.plan(
            idGenerator.next("plan"), manifestationId, command.principalId(), artifact, now);
    var manifestation =
        Manifestation.awaitingApproval(
            manifestationId,
            command.principalId(),
            evidence.id(),
            workingSelf,
            artifact,
            plan,
            now);
    manifestationRepository.save(manifestation);
    return toView(manifestation, artifact);
  }

  public synchronized ManifestationView revise(
      String manifestationId, ReviseArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    var manifestation = requireManifestation(command.principalId(), manifestationId);

    var current = requireArtifact(manifestation.artifactId(), manifestation.currentArtifactVersion());
    var revised =
        new ArtifactVersion(
            current.artifactId(),
            current.version() + 1,
            command.content(),
            ContentHashes.sha256(command.content()),
            current.evidenceRefs(),
            current.workingSelfSnapshotId(),
            "human-edit",
            current.version(),
            clock.instant());
    artifactRepository.save(revised);

    var revisedPlan =
        actionPolicy.plan(
            idGenerator.next("plan"),
            manifestation.id(),
            manifestation.principalId(),
            revised,
            clock.instant());
    manifestation.revise(revised, revisedPlan, clock.instant());
    manifestationRepository.save(manifestation);
    return toView(manifestation, revised);
  }

  public synchronized ManifestationView approve(
      String manifestationId, ApproveActionCommand command) {
    Objects.requireNonNull(command, "command");
    var manifestation = requireManifestation(command.approvedBy(), manifestationId);
    var current = requireArtifact(manifestation.artifactId(), manifestation.currentArtifactVersion());

    if (manifestation.status() == ManifestationStatus.COMPLETED_WITH_RECEIPT) {
      manifestation.assertApprovalReplay(command.approvedBy(), command.artifactHash());
      return toView(manifestation, current);
    }

    Instant now = clock.instant();
    ApprovalDecision approval;
    if (manifestation.status() == ManifestationStatus.AWAITING_APPROVAL) {
      approval =
          new ApprovalDecision(
              idGenerator.next("approval"),
              manifestation.actionPlan().planId(),
              manifestation.actionPlan().planHash(),
              command.artifactHash(),
              "APPROVED",
              command.approvedBy(),
              now);
    } else if (manifestation.status() == ManifestationStatus.EXECUTING) {
      manifestation.assertApprovalReplay(command.approvedBy(), command.artifactHash());
      approval = manifestation.approvalDecision();
    } else {
      throw new IllegalStateException(
          "cannot approve a manifestation in state " + manifestation.status());
    }
    var capability =
        actionPolicy.authorize(
            idGenerator.next("cap"),
            manifestation.actionPlan(),
            current,
            approval,
            now);
    if (manifestation.status() == ManifestationStatus.AWAITING_APPROVAL) {
      manifestation.beginExecution(approval, now);
      manifestationRepository.save(manifestation);
    }

    Receipt receipt =
        receiptLedger
            .findByIdempotencyKey(manifestation.actionPlan().idempotencyKey())
            .orElseGet(
                () -> {
                  var executed =
                      actionExecutor.execute(manifestation.actionPlan(), current, capability, now);
                  receiptLedger.append(executed);
                  return executed;
                });

    var generated = requireArtifact(manifestation.artifactId(), manifestation.initialArtifactVersion());
    manifestation.completeWithReceipt(receipt, clock.instant());
    manifestationRepository.save(manifestation);
    try {
      ReflectionCandidate reflection =
          reflectionProposer.propose(
              idGenerator.next("reflection"),
              manifestation.principalId(),
              generated,
              current,
              receipt,
              manifestation.id(),
              clock.instant());
      manifestation.attachReflection(reflection, clock.instant());
      manifestationRepository.save(manifestation);
    } catch (RuntimeException reflectionFailure) {
      manifestation.markReflectionFailed(
          reflectionFailure.getClass().getSimpleName(), clock.instant());
      manifestationRepository.save(manifestation);
    }
    return toView(manifestation, current);
  }

  public synchronized ManifestationView get(String manifestationId, String principalId) {
    var manifestation = requireManifestation(principalId, manifestationId);
    var artifact =
        requireArtifact(manifestation.artifactId(), manifestation.currentArtifactVersion());
    return toView(manifestation, artifact);
  }

  private Manifestation requireManifestation(String principalId, String id) {
    return manifestationRepository
        .findManifestation(principalId, id)
        .orElseThrow(() -> new NoSuchElementException("manifestation not found: " + id));
  }

  private ArtifactVersion requireArtifact(String id, int version) {
    return artifactRepository
        .find(id, version)
        .orElseThrow(
            () -> new NoSuchElementException("artifact not found: " + id + " v" + version));
  }

  private static ManifestationView toView(
      Manifestation manifestation, ArtifactVersion artifact) {
    var plan = manifestation.actionPlan();
    var receipt = manifestation.receipt();
    var reflection = manifestation.reflectionCandidate();
    return new ManifestationView(
        manifestation.id(),
        manifestation.principalId(),
        manifestation.status().name(),
        manifestation.seedEvidenceId(),
        manifestation.workingSelfSnapshotId(),
        new ManifestationView.ArtifactView(
            artifact.artifactId(),
            artifact.version(),
            artifact.content(),
            artifact.contentHash(),
            artifact.generatedBy()),
        new ManifestationView.ActionView(
            plan.planId(),
            plan.actionType(),
            plan.targetRef(),
            plan.risk().name(),
            plan.policyVersion(),
            plan.idempotencyKey(),
            plan.expiresAt().toString(),
            manifestation.approvalDecision() == null
                ? null
                : manifestation.approvalDecision().decisionId(),
            manifestation.status() == ManifestationStatus.AWAITING_APPROVAL),
        receipt == null ? null : toReceiptView(receipt),
        manifestation.reflectionStatus(),
        manifestation.reflectionFailure(),
        reflection == null ? null : toReflectionView(reflection));
  }

  private static ManifestationView.ReceiptView toReceiptView(Receipt receipt) {
    return new ManifestationView.ReceiptView(
        receipt.receiptId(),
        receipt.status(),
        receipt.externalId(),
        receipt.artifactHash(),
        receipt.occurredAt().toString());
  }

  private static ManifestationView.ReflectionView toReflectionView(
      ReflectionCandidate candidate) {
    return new ManifestationView.ReflectionView(
        candidate.candidateId(),
        candidate.status(),
        candidate.claimOrRule(),
        candidate.confidence(),
        candidate.changesSelfModel());
  }
}
