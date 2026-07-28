package io.emergeos.api;

import io.emergeos.core.application.ActionIdempotencyConflictException;
import io.emergeos.core.application.CaptureNonceConflictException;
import io.emergeos.core.application.ArtifactRevisionConflictException;
import java.net.URI;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

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
}
