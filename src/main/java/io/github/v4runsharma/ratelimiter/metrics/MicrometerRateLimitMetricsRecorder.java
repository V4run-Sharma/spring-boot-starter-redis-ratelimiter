package io.github.v4runsharma.ratelimiter.metrics;

import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

/**
 * Micrometer-backed metrics recorder for rate limiter outcomes.
 */
public final class MicrometerRateLimitMetricsRecorder implements RateLimitMetricsRecorder {

  private final MeterRegistry meterRegistry;

  public MicrometerRateLimitMetricsRecorder(MeterRegistry meterRegistry) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
  }

  @Override
  public void recordDecision(String name, RateLimitPolicy policy, RateLimitDecision decision, Duration latency) {
    String outcome = decision.isAllowed() ? "allowed" : "blocked";
    Counter.builder("ratelimiter.requests")
        .tag("name", sanitize(name))
        .tag("scope", policy.getScope().name())
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();

    Timer.builder("ratelimiter.evaluate.latency")
        .tag("name", sanitize(name))
        .tag("scope", policy.getScope().name())
        .register(meterRegistry)
        .record(latency);
  }

  @Override
  public void recordError(String name, RateLimitPolicy policy, Duration latency, Throwable error) {
    Counter.builder("ratelimiter.errors")
        .tag("name", sanitize(name))
        .tag("scope", policy.getScope().name())
        .tag("exception", error == null ? "unknown" : sanitize(error.getClass().getSimpleName()))
        .register(meterRegistry)
        .increment();

    Timer.builder("ratelimiter.evaluate.latency")
        .tag("name", sanitize(name))
        .tag("scope", policy.getScope().name())
        .register(meterRegistry)
        .record(latency);
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value;
  }
}
