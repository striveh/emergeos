package io.emergeos.core.application;

import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.port.ArtifactLineageStore;
import io.emergeos.core.port.CaptureStore;
import io.emergeos.core.port.IdGenerator;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class ArtifactLineageService {

  private final ArtifactLineageStore lineageStore;
  private final CaptureStore captureStore;
  private final IdGenerator idGenerator;
  private final Clock clock;

  public ArtifactLineageService(
      ArtifactLineageStore lineageStore,
      CaptureStore captureStore,
      IdGenerator idGenerator,
      Clock clock) {
    this.lineageStore = Objects.requireNonNull(lineageStore, "lineageStore");
    this.captureStore = Objects.requireNonNull(captureStore, "captureStore");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ArtifactLineage create(CreateArtifactCommand command) {
    Objects.requireNonNull(command, "command");
    captureStore
        .findOwned(command.principalId(), command.sourceCaptureId())
        .orElseThrow(() -> new NoSuchElementException("Capture not found"));
    var initial =
        new ArtifactLineageEntry(
            1,
            command.content(),
            ContentHashes.sha256(command.content()),
            null,
            null,
            clock.instant());
    var proposed =
        new ArtifactLineage(
            idGenerator.next("art"),
            command.principalId(),
            command.sourceCaptureId(),
            List.of(initial));
    return lineageStore.create(proposed);
  }

  public ArtifactLineage revise(ReviseArtifactLineageCommand command) {
    Objects.requireNonNull(command, "command");
    var proposed =
        new ArtifactLineageEntry(
            command.expectedBaseVersion() + 1,
            command.content(),
            ContentHashes.sha256(command.content()),
            command.expectedBaseVersion(),
            command.expectedBaseHash(),
            clock.instant());
    ArtifactLineageStore.RevisionResult result =
        lineageStore.compareAndSwap(
            command.principalId(),
            command.artifactId(),
            command.expectedBaseVersion(),
            command.expectedBaseHash(),
            proposed);
    if (result instanceof ArtifactLineageStore.RevisionResult.Revised revised) {
      return revised.lineage();
    }
    if (result instanceof ArtifactLineageStore.RevisionResult.Conflict conflict) {
      throw new ArtifactRevisionConflictException(conflict.currentVersion());
    }
    throw new NoSuchElementException("Artifact not found");
  }

  public ArtifactLineage get(String principalId, String artifactId) {
    CreateArtifactCommand.requireIdentifier(principalId, "principalId");
    CreateArtifactCommand.requireIdentifier(artifactId, "artifactId");
    return lineageStore
        .findOwned(principalId, artifactId)
        .orElseThrow(() -> new NoSuchElementException("Artifact not found"));
  }
}
