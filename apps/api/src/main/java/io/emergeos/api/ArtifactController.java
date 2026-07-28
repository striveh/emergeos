package io.emergeos.api;

import io.emergeos.core.application.ArtifactLineageService;
import io.emergeos.core.application.CreateArtifactCommand;
import io.emergeos.core.application.ReviseArtifactLineageCommand;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/artifacts")
class ArtifactController {

  private final ArtifactLineageService artifactService;
  private final String prototypePrincipalId;

  ArtifactController(
      ArtifactLineageService artifactService,
      @Value("${emerge.prototype.principal-id}") String prototypePrincipalId) {
    this.artifactService = artifactService;
    this.prototypePrincipalId = prototypePrincipalId;
  }

  @PostMapping
  ResponseEntity<ArtifactResponse> create(@RequestBody CreateArtifactRequest request) {
    ArtifactLineage created =
        artifactService.create(
            new CreateArtifactCommand(
                prototypePrincipalId, request.captureId(), request.content()));
    return ResponseEntity.created(URI.create("/api/v1/artifacts/" + created.artifactId()))
        .body(ArtifactResponse.from(created));
  }

  @PutMapping("/{artifactId}")
  ArtifactResponse revise(
      @PathVariable("artifactId") String artifactId,
      @RequestBody ReviseArtifactRequest request) {
    if (request.expectedBaseVersion() == null) {
      throw new IllegalArgumentException("expectedBaseVersion must not be null");
    }
    ArtifactLineage revised =
        artifactService.revise(
            new ReviseArtifactLineageCommand(
                prototypePrincipalId,
                artifactId,
                request.content(),
                request.expectedBaseVersion(),
                request.expectedBaseHash()));
    return ArtifactResponse.from(revised);
  }

  @GetMapping("/{artifactId}")
  ArtifactResponse get(@PathVariable("artifactId") String artifactId) {
    return ArtifactResponse.from(artifactService.get(prototypePrincipalId, artifactId));
  }

  record CreateArtifactRequest(String captureId, String content) {}

  record ReviseArtifactRequest(
      String content, Integer expectedBaseVersion, String expectedBaseHash) {}

  record ArtifactResponse(
      String artifactId,
      String principalId,
      String captureId,
      int currentVersion,
      String currentHash,
      List<ArtifactVersionResponse> versions) {

    static ArtifactResponse from(ArtifactLineage lineage) {
      return new ArtifactResponse(
          lineage.artifactId(),
          lineage.principalId(),
          lineage.sourceCaptureId(),
          lineage.current().version(),
          lineage.current().contentHash(),
          lineage.versions().stream().map(ArtifactVersionResponse::from).toList());
    }
  }

  record ArtifactVersionResponse(
      int version,
      String content,
      String contentHash,
      Integer baseVersion,
      String baseHash,
      Instant createdAt) {

    static ArtifactVersionResponse from(ArtifactLineageEntry entry) {
      return new ArtifactVersionResponse(
          entry.version(),
          entry.content(),
          entry.contentHash(),
          entry.baseVersion(),
          entry.baseHash(),
          entry.createdAt());
    }
  }
}
