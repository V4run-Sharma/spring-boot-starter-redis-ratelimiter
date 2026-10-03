package io.github.v4runsharma.ratelimiter.model;

import java.time.Duration;
import java.util.Objects;

// Immutable rate limit policy: N requests per T time window, with optional scope (e.g., per user, per IP)
public class RateLimitPolicy {

  private final int limit; // Max requests allowed
  private final Duration window; // Time window for the limit
  private final RateLimitScope scope; // Who the limit applies to (global, per IP, per user)

  public RateLimitPolicy(int limit, Duration window, RateLimitScope scope) {
    if (limit <= 0) {
      throw new IllegalArgumentException("Limit must be greater than 0");
    }
    if (window == null || window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("Window must be a positive duration");
    }

    this.limit = limit;
    this.window = window;
    this.scope = Objects.requireNonNull(scope, "scope must not be null");
  }

  public int getLimit() {
    return limit;
  }

  public Duration getWindow() {
    return window;
  }

  public RateLimitScope getScope() {
    return scope;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RateLimitPolicy that)) return false;

    return this.limit == that.limit &&
        this.window.equals(that.window) &&
        this.scope.equals(that.scope);
  }

  @Override
  public int hashCode() {
    return Objects.hash(limit, window, scope);
  }

  @Override
  public String toString() {
    return "RateLimitPolicy{" +
        "limit=" + limit +
        ", window=" + window +
        ", scope=" + scope +
        '}';
  }
}
