package io.emergeos.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ArtifactLineage(
    String artifactId,
    String principalId,
    String sourceCaptureId,
    List<ArtifactLineageEntry> versions) {

  public ArtifactLineage {
    requireText(artifactId, "artifactId");
    requireText(principalId, "principalId");
    requireText(sourceCaptureId, "sourceCaptureId");
    versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
    if (versions.isEmpty()) {
      throw new IllegalArgumentException("versions must not be empty");
    }
    for (int index = 0; index < versions.size(); index++) {
      ArtifactLineageEntry entry = versions.get(index);
      if (entry.version() != index + 1) {
        throw new IllegalArgumentException("versions must be continuous and ordered");
      }
      if (index > 0) {
        ArtifactLineageEntry previous = versions.get(index - 1);
        if (!Objects.equals(entry.baseVersion(), previous.version())
            || !Objects.equals(entry.baseHash(), previous.contentHash())) {
          throw new IllegalArgumentException(
              "each Artifact version must name the immediately preceding version and hash");
        }
      }
    }
  }

  public ArtifactLineageEntry current() {
    return versions.getLast();
  }

  public ArtifactLineage append(ArtifactLineageEntry entry) {
    Objects.requireNonNull(entry, "entry");
    var appended = new ArrayList<>(versions);
    appended.add(entry);
    return new ArtifactLineage(artifactId, principalId, sourceCaptureId, appended);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > 200) {
      throw new IllegalArgumentException(name + " must be at most 200 characters");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " must not contain NUL");
    }
  }
}
