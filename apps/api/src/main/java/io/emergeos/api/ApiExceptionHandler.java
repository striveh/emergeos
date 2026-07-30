package io.emergeos.api;

import io.emergeos.core.application.ActionIdempotencyConflictException;
import io.emergeos.core.application.CaptureNonceConflictException;
import io.emergeos.core.application.ArtifactRevisionConflictException;
import io.emergeos.adapters.postgres.AgentRunConflictException;
import io.emergeos.adapters.postgres.AgentRunIntegrityException;
import java.net.URI;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

  private static final String PRIVATE_NO_STORE = "private, no-store";

  @ExceptionHandler(AgentRunNotFoundException.class)
  ResponseEntity<ProblemDetail> agentRunNotFound() {
    return privateProblem(
        HttpStatus.NOT_FOUND,
        "urn:emergeos:problem:agent-run-not-found",
        "Agent run not found",
        "Agent run was not found.");
  }

  @ExceptionHandler(AgentRunIntegrityException.class)
  ResponseEntity<ProblemDetail> agentRunIntegrity() {
    return privateProblem(
        HttpStatus.CONFLICT,
        "urn:emergeos:problem:agent-run-integrity",
        "Agent run integrity conflict",
        "Stored agent run cannot be verified.");
  }

  @ExceptionHandler({AgentRunIncompleteException.class, AgentRunConflictException.class})
  ResponseEntity<ProblemDetail> agentRunIncompleteOrConflict() {
    return privateProblem(
        HttpStatus.CONFLICT,
        "urn:emergeos:problem:agent-run-incomplete",
        "Agent run incomplete",
        "Agent run has no completed representation.");
  }

  @ExceptionHandler(ActionIdempotencyConflictException.class)
  ProblemDetail actionIdempotencyConflict(ActionIdempotencyConflictException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("Action idempotency conflict");
    problem.setType(URI.create("urn:emergeos:problem:action-idempotency-conflict"));
    return problem;
  }

  @ExceptionHandler(ArtifactRevisionConflictException.class)
  ProblemDetail artifactRevisionConflict(ArtifactRevisionConflictException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("Artifact revision conflict");
    problem.setType(URI.create("urn:emergeos:problem:artifact-revision-conflict"));
    problem.setProperty("currentVersion", exception.currentVersion());
    return problem;
  }

  @ExceptionHandler(CaptureNonceConflictException.class)
  ProblemDetail captureNonceConflict(CaptureNonceConflictException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("Capture nonce conflict");
    problem.setType(URI.create("urn:emergeos:problem:capture-nonce-conflict"));
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail conflict(IllegalStateException exception) {
    return problem(HttpStatus.CONFLICT, "State conflict", exception.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(NoSuchElementException exception) {
    return problem(HttpStatus.NOT_FOUND, "Not found", exception.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setType(URI.create("urn:emergeos:problem:" + status.value()));
    return problem;
  }

  private static ResponseEntity<ProblemDetail> privateProblem(
      HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(type));
    problem.setTitle(title);
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
        .body(problem);
  }
}
