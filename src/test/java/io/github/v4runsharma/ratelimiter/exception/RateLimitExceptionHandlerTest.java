package io.github.v4runsharma.ratelimiter.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class RateLimitExceptionHandlerTest {

  private static final RateLimitPolicy POLICY = new RateLimitPolicy(10, Duration.ofMinutes(1), RateLimitScope.GLOBAL);

  @ParameterizedTest(name = "{0} ms -> {1} s")
  @CsvSource({"1, 1", "999, 1", "1000, 1", "1001, 2", "1999, 2", "34000, 34", "34001, 35"})
  void roundsRetryAfterUpToWholeSeconds(long retryAfterMillis, String expectedSeconds) {
    ResponseEntity<ProblemDetail> response = new RateLimitExceptionHandler(true).handleRateLimitExceeded(denied(retryAfterMillis));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(expectedSeconds);
    assertThat(response.getHeaders().getFirst("RateLimit-Reset")).isEqualTo(expectedSeconds);
    assertThat(response.getBody().getProperties()).containsEntry("retryAfterSeconds", Long.parseLong(expectedSeconds));
  }

  @Test
  void omitsHeadersWhenDisabled() {
    ResponseEntity<ProblemDetail> response = new RateLimitExceptionHandler(false).handleRateLimitExceeded(denied(1500));

    assertThat(response.getHeaders()).isEmpty();
    assertThat(response.getBody().getProperties()).containsEntry("retryAfterSeconds", 2L);
  }

  private static RateLimitExceededException denied(long retryAfterMillis) {
    Duration retryAfter = Duration.ofMillis(retryAfterMillis);
    RateLimitDecision decision = new RateLimitDecision(false, retryAfterMillis, retryAfter, retryAfter);
    return new RateLimitExceededException("invoice-create", "global:invoice-create", POLICY, decision);
  }
}
