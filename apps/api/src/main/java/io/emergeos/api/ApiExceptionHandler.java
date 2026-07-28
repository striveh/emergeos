package io.emergeos.api;

import java.net.URI;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

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
