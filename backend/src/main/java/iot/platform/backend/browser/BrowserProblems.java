package iot.platform.backend.browser;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice(basePackageClasses = BrowserProblems.class)
class BrowserProblems {
  private final JsonMapper json;

  BrowserProblems(JsonMapper json) {
    this.json = json;
  }

  void write(HttpServletResponse response, Failure failure) throws IOException {
    response.setStatus(failure.status.value());
    response.setContentType("application/json");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    json.writeValue(response.getOutputStream(), problem(failure));
  }

  @ExceptionHandler(Failure.class)
  ResponseEntity<Problem> failure(Failure failure) {
    var response =
        ResponseEntity.status(failure.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (failure.status == HttpStatus.TOO_MANY_REQUESTS) {
      response.header(HttpHeaders.RETRY_AFTER, "60");
    }
    return response.body(problem(failure));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    ServletRequestBindingException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<Problem> invalid() {
    return failure(bad("INVALID_REQUEST", "Check the request parameters."));
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<Problem> unavailable() {
    return failure(
        new Failure(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SERVICE_UNAVAILABLE",
            "The service is temporarily unavailable."));
  }

  static Failure bad(String code, String message) {
    return new Failure(HttpStatus.BAD_REQUEST, code, message);
  }

  static Failure unauthenticated() {
    return new Failure(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Sign in to continue.");
  }

  static Failure forbidden() {
    return new Failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to this tenant is unavailable.");
  }

  static Failure missing() {
    return new Failure(
        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "A requested sensor is unavailable.");
  }

  private static Problem problem(Failure failure) {
    return new Problem(failure.code, failure.getMessage(), UUID.randomUUID().toString());
  }

  record Problem(String code, String message, String requestId) {}

  static final class Failure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    Failure(HttpStatus status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }
}
