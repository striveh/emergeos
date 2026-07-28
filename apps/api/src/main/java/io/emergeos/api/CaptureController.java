package io.emergeos.api;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.application.CaptureCommand;
import io.emergeos.core.application.CaptureService;
import io.emergeos.core.domain.Capture;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/captures")
class CaptureController {

  private final CaptureService captureService;
  private final String prototypePrincipalId;

  CaptureController(
      CaptureService captureService,
      @Value("${emerge.prototype.principal-id}") String prototypePrincipalId) {
    this.captureService = captureService;
    this.prototypePrincipalId = prototypePrincipalId;
  }

  @PostMapping
  ResponseEntity<Capture> capture(@RequestBody CaptureRequest request) {
    var outcome =
        captureService.capture(
            new CaptureCommand(
                prototypePrincipalId,
                request.clientNonce(),
                request.content(),
                request.sourceType(),
                request.sourceRef(),
                request.dataClass()));
    if (outcome.created()) {
      return ResponseEntity.created(
              URI.create("/api/v1/captures/" + outcome.capture().captureId()))
          .body(outcome.capture());
    }
    return ResponseEntity.status(HttpStatus.OK).body(outcome.capture());
  }

  @GetMapping("/{captureId}")
  Capture get(@PathVariable("captureId") String captureId) {
    return captureService.get(prototypePrincipalId, captureId);
  }

  record CaptureRequest(
      String clientNonce,
      String content,
      String sourceType,
      String sourceRef,
      DataClass dataClass) {}
}
