package io.github.v4runsharma.ratelimiter.exception;

import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Converts {@link RateLimitExceededException} into HTTP 429 responses.
 */
@ControllerAdvice
public final class RateLimitExceptionHandler {

  private final boolean includeHttpHeaders;

  public RateLimitExceptionHandler(@Value("${ratelimiter.include-http-headers:true}") boolean includeHttpHeaders) {
    this.includeHttpHeaders = includeHttpHeaders;
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
    Objects.requireNonNull(ex, "ex must not be null");

    ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
    detail.setTitle("Rate limit exceeded");
    detail.setDetail(ex.getMessage());
    detail.setProperty("timestamp", Instant.now().toString());
    detail.setProperty("key", ex.getKey());
    detail.setProperty("limit", ex.getPolicy().getLimit());
    detail.setProperty("windowSeconds", ex.getPolicy().getWindow().toSeconds());
    if (ex.getName() != null) {
      detail.setProperty("name", ex.getName());
    }

    long retryAfterSeconds = resolveRetryAfterSeconds(ex.getDecision(), ex.getPolicy().getWindow());
    detail.setProperty("retryAfterSeconds", retryAfterSeconds);

    HttpHeaders headers = new HttpHeaders();
    if (includeHttpHeaders) {
      headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
      headers.set("RateLimit-Limit", Integer.toString(ex.getPolicy().getLimit()));
      headers.set("RateLimit-Remaining", "0");
      headers.set("RateLimit-Reset", Long.toString(retryAfterSeconds));
    }

    // Builder rather than the (body, headers, status) constructor: Spring 7's HttpHeaders is no longer a
    // MultiValueMap, so that constructor call would break when this class runs on Spring Boot 4.
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(detail);
  }

  private static long resolveRetryAfterSeconds(RateLimitDecision decision, Duration fallbackWindow) {
    Duration retryAfter = decision.getRetryAfter().orElse(fallbackWindow);
    // Round up: retrying after a truncated value would land before the window resets.
    long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
    return Math.max(1L, seconds);
  }
}
