package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureRequestHashes;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.port.ArtifactLineageStore;
import io.emergeos.core.port.CaptureStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArtifactLineageServiceTest {

  private final InMemoryCaptureStore captureStore = new InMemoryCaptureStore();
  private final InMemoryArtifactLineageStore lineageStore = new InMemoryArtifactLineageStore();
  private final ArtifactLineageService service =
      new ArtifactLineageService(
          lineageStore,
          captureStore,
          prefix -> prefix + "-1",
          Clock.fixed(Instant.parse("2026-07-28T08:00:00Z"), ZoneOffset.UTC));

  @BeforeEach
  void ownSourceCapture() {
    captureStore.put(capture("cap-1", "owner-a"));
  }

  @Test
  void createsVersionOneOnlyFromAnOwnedCapture() {
    ArtifactLineage lineage =
        service.create(new CreateArtifactCommand("owner-a", "cap-1", "artifact version one"));

    assertEquals("art-1", lineage.artifactId());
    assertEquals("owner-a", lineage.principalId());
    assertEquals("cap-1", lineage.sourceCaptureId());
    assertEquals(1, lineage.current().version());
    assertEquals("artifact version one", lineage.current().content());
    assertEquals(ContentHashes.sha256("artifact version one"), lineage.current().contentHash());
    assertEquals(null, lineage.current().baseVersion());
    assertEquals(null, lineage.current().baseHash());
    assertEquals(lineage, service.get("owner-a", "art-1"));
    assertThrows(
        NoSuchElementException.class,
        () ->
            service.create(
                new CreateArtifactCommand("owner-b", "cap-1", "foreign source probe")));
  }

  @Test
  void passesExpectedVersionAndHashToCompareAndSwapAndReportsAStaleBase() {
    ArtifactLineage created =
        service.create(new CreateArtifactCommand("owner-a", "cap-1", "artifact version one"));

    ArtifactLineage revised =
        service.revise(
            new ReviseArtifactLineageCommand(
                "owner-a",
                created.artifactId(),
                "artifact version two",
                1,
                created.current().contentHash()));

    assertEquals(2, revised.current().version());
    assertEquals(1, revised.current().baseVersion());
    assertEquals(created.current().contentHash(), revised.current().baseHash());
    assertEquals(2, revised.versions().size());
    ArtifactRevisionConflictException conflict =
        assertThrows(
            ArtifactRevisionConflictException.class,
            () ->
                service.revise(
                    new ReviseArtifactLineageCommand(
                        "owner-a",
                        created.artifactId(),
                        "stale overwrite",
                        1,
                        created.current().contentHash())));
    assertEquals(2, conflict.currentVersion());
    assertThrows(
        NoSuchElementException.class,
        () ->
            service.revise(
                new ReviseArtifactLineageCommand(
                    "owner-b",
                    created.artifactId(),
                    "foreign overwrite",
                    2,
                    revised.current().contentHash())));
  }

  private static Capture capture(String captureId, String principalId) {
    String content = "synthetic source thought";
    return new Capture(
        captureId,
        principalId,
        "s2-source",
        CaptureRequestHashes.sha256(
            content, CaptureSourceType.TEXT, "s2-core-test", DataClass.PERSONAL),
        content,
        CaptureSourceType.TEXT,
        "s2-core-test",
        DataClass.PERSONAL,
        Instant.parse("2026-07-28T07:00:00Z"));
  }

  private static String key(String principalId, String id) {
    return principalId + "\n" + id;
  }

  private static final class InMemoryCaptureStore implements CaptureStore {
    private final Map<String, Capture> captures = new HashMap<>();

    void put(Capture capture) {
      captures.put(key(capture.principalId(), capture.captureId()), capture);
    }

    @Override
    public SaveResult saveOrFindByNonce(Capture proposed) {
      throw new UnsupportedOperationException("not used by this focused test");
    }

    @Override
    public Optional<Capture> findOwned(String principalId, String captureId) {
      return Optional.ofNullable(captures.get(key(principalId, captureId)));
    }
  }

  private static final class InMemoryArtifactLineageStore implements ArtifactLineageStore {
    private final Map<String, ArtifactLineage> lineages = new HashMap<>();

    @Override
    public ArtifactLineage create(ArtifactLineage proposed) {
      lineages.put(key(proposed.principalId(), proposed.artifactId()), proposed);
      return proposed;
    }

    @Override
    public RevisionResult compareAndSwap(
        String principalId,
        String artifactId,
        int expectedBaseVersion,
        String expectedBaseHash,
        ArtifactLineageEntry proposed) {
      String key = key(principalId, artifactId);
      ArtifactLineage current = lineages.get(key);
      if (current == null) {
        return new RevisionResult.NotFound();
      }
      if (current.current().version() != expectedBaseVersion
          || !current.current().contentHash().equals(expectedBaseHash)) {
        return new RevisionResult.Conflict(current.current().version());
      }
      ArtifactLineage revised = current.append(proposed);
      lineages.put(key, revised);
      return new RevisionResult.Revised(revised);
    }

    @Override
    public Optional<ArtifactLineage> findOwned(String principalId, String artifactId) {
      return Optional.ofNullable(lineages.get(key(principalId, artifactId)));
    }
  }
}
