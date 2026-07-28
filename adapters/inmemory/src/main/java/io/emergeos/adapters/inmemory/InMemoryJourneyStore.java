package io.emergeos.adapters.inmemory;

import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.EvidenceEvent;
import io.emergeos.core.domain.Manifestation;
import io.emergeos.core.domain.Receipt;
import io.emergeos.core.domain.WorkingSelf;
import io.emergeos.core.port.ArtifactRepository;
import io.emergeos.core.port.EvidenceLedger;
import io.emergeos.core.port.ManifestationRepository;
import io.emergeos.core.port.ReceiptLedger;
import io.emergeos.core.port.WorkingSelfRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryJourneyStore
    implements EvidenceLedger,
        ArtifactRepository,
        ManifestationRepository,
        ReceiptLedger,
        WorkingSelfRepository {

  private final Map<String, EvidenceEvent> evidence = new ConcurrentHashMap<>();
  private final Map<String, ArtifactVersion> artifacts = new ConcurrentHashMap<>();
  private final Map<String, Manifestation> manifestations = new ConcurrentHashMap<>();
  private final Map<String, Receipt> receiptsByIdempotencyKey = new ConcurrentHashMap<>();
  private final Map<String, WorkingSelf> workingSelfSnapshots = new ConcurrentHashMap<>();

  @Override
  public void append(EvidenceEvent event) {
    var existing = evidence.putIfAbsent(event.id(), event);
    if (existing != null && !existing.equals(event)) {
      throw new IllegalStateException("evidence is append-only: " + event.id());
    }
  }

  @Override
  public Optional<EvidenceEvent> findEvidence(String evidenceId) {
    return Optional.ofNullable(evidence.get(evidenceId));
  }

  @Override
  public void save(ArtifactVersion artifact) {
    var key = artifactKey(artifact.artifactId(), artifact.version());
    var existing = artifacts.putIfAbsent(key, artifact);
    if (existing != null && !existing.equals(artifact)) {
      throw new IllegalStateException("artifact versions are immutable: " + key);
    }
  }

  @Override
  public Optional<ArtifactVersion> find(String artifactId, int version) {
    return Optional.ofNullable(artifacts.get(artifactKey(artifactId, version)));
  }

  @Override
  public void save(Manifestation manifestation) {
    manifestations.put(manifestation.id(), manifestation);
  }

  @Override
  public Optional<Manifestation> findManifestation(
      String principalId, String manifestationId) {
    return Optional.ofNullable(manifestations.get(manifestationId))
        .filter(manifestation -> manifestation.principalId().equals(principalId));
  }

  @Override
  public void append(Receipt receipt) {
    var existing = receiptsByIdempotencyKey.putIfAbsent(receipt.idempotencyKey(), receipt);
    if (existing != null && !existing.equals(receipt)) {
      throw new IllegalStateException(
          "one idempotency key cannot identify two receipts: " + receipt.idempotencyKey());
    }
  }

  @Override
  public Optional<Receipt> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(receiptsByIdempotencyKey.get(idempotencyKey));
  }

  @Override
  public void save(WorkingSelf workingSelf) {
    var existing = workingSelfSnapshots.putIfAbsent(workingSelf.snapshotId(), workingSelf);
    if (existing != null && !existing.equals(workingSelf)) {
      throw new IllegalStateException(
          "Working Self snapshots are immutable: " + workingSelf.snapshotId());
    }
  }

  @Override
  public Optional<WorkingSelf> findWorkingSelf(String snapshotId) {
    return Optional.ofNullable(workingSelfSnapshots.get(snapshotId));
  }

  private static String artifactKey(String artifactId, int version) {
    return artifactId + ":" + version;
  }
}
