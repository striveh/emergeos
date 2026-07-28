package io.emergeos.api;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.ApproveActionCommand;
import io.emergeos.core.application.CaptureThoughtCommand;
import io.emergeos.core.application.ManifestationService;
import io.emergeos.core.application.ManifestationView;
import io.emergeos.core.application.ReviseArtifactCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manifestations")
class ManifestationController {

  private final ManifestationService service;
  private final String prototypePrincipalId;

  ManifestationController(
      ManifestationService service,
      @Value("${emerge.prototype.principal-id}") String prototypePrincipalId) {
    this.service = service;
    this.prototypePrincipalId = prototypePrincipalId;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ManifestationView capture(@RequestBody CaptureThoughtRequest request) {
    return service.capture(
        new CaptureThoughtCommand(
            prototypePrincipalId,
            request.content(),
            request.sourceType(),
            request.sourceRef(),
            request.dataClass()));
  }

  @PutMapping("/{manifestationId}/artifact")
  ManifestationView revise(
      @PathVariable("manifestationId") String manifestationId,
      @RequestBody ReviseArtifactRequest request) {
    return service.revise(
        manifestationId, new ReviseArtifactCommand(prototypePrincipalId, request.content()));
  }

  @PostMapping("/{manifestationId}/approve")
  ManifestationView approve(
      @PathVariable("manifestationId") String manifestationId,
      @RequestBody ApproveActionRequest request) {
    return service.approve(
        manifestationId,
        new ApproveActionCommand(prototypePrincipalId, request.artifactHash()));
  }

  @GetMapping("/{manifestationId}")
  ManifestationView get(@PathVariable("manifestationId") String manifestationId) {
    return service.get(manifestationId, prototypePrincipalId);
  }

  record CaptureThoughtRequest(
      String content,
      String sourceType,
      String sourceRef,
      DataClass dataClass) {}

  record ReviseArtifactRequest(String content) {}

  record ApproveActionRequest(String artifactHash) {}
}
