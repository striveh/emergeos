package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.core.application.CreateArtifactCommand;
import io.emergeos.core.application.ReviseArtifactLineageCommand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactLineageInvariantTest {

  private static final Instant NOW = Instant.parse("2026-07-28T08:00:00Z");

  @Test
  void contentHashAndContentBoundariesAreServerVerifiable() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactLineageEntry(1, "content", "0".repeat(64), null, null, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArtifactLineageEntry(
                1,
                "content\0with-nul",
                ContentHashes.sha256("content\0with-nul"),
                null,
                null,
                NOW));
    String oversized = "x".repeat(65_537);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArtifactLineageEntry(
                1, oversized, ContentHashes.sha256(oversized), null, null, NOW));
  }

  @Test
  void everyLaterVersionRequiresACompleteImmediateBasePair() {
    String content = "version two";
    String hash = ContentHashes.sha256(content);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactLineageEntry(2, content, hash, null, null, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactLineageEntry(2, content, hash, 1, null, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArtifactLineageEntry(3, content, hash, 1, "0".repeat(64), NOW));
  }

  @Test
  void lineageRejectsGapsAndAParentHashThatDoesNotNameThePreviousVersion() {
    ArtifactLineageEntry first = entry(1, "version one", null, null);
    ArtifactLineageEntry wrongParent = entry(2, "version two", 1, "0".repeat(64));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArtifactLineage(
                "art-1", "owner-a", "cap-1", List.of(first, wrongParent)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArtifactLineage(
                "art-1",
                "owner-a",
                "cap-1",
                List.of(entry(2, "version two", 1, first.contentHash()))));
  }

  @Test
  void commandsRejectInvalidIdentityExpectedVersionAndExpectedHash() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreateArtifactCommand("owner-a", "cap-1", "content\0"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReviseArtifactLineageCommand(
                "owner-a", "art-1", "revision", 0, "0".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReviseArtifactLineageCommand(
                "owner-a", "art-1", "revision", 1, "ABC"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReviseArtifactLineageCommand(
                "owner-a", "art-1", "revision", Integer.MAX_VALUE, "0".repeat(64)));
  }

  private static ArtifactLineageEntry entry(
      int version, String content, Integer baseVersion, String baseHash) {
    return new ArtifactLineageEntry(
        version, content, ContentHashes.sha256(content), baseVersion, baseHash, NOW);
  }
}
