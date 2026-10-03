package io.github.v4runsharma.ratelimiter.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.v4runsharma.ratelimiter.exception.RateLimiterBackendException;
import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRateLimiterTest {

  private static final Instant FIXED_TIME = Instant.ofEpochMilli(1_700_000_005_123L);

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOperations;
  private RedisRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    Clock fixedClock = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    rateLimiter = new RedisRateLimiter(redisTemplate, fixedClock, "ratelimiter");
  }

  @Test
  void evaluateAllowsRequestWithinLimit() {
    RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofSeconds(10), RateLimitScope.GLOBAL);
    when(valueOperations.increment(anyString())).thenReturn(1L);
    when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

    RateLimitDecision decision = rateLimiter.evaluate("customer-1", policy);

    assertThat(decision.isAllowed()).isTrue();
    assertThat(decision.getRemainingTime()).isEqualTo(0L);
    assertThat(decision.getRetryAfter()).isEmpty();
    assertThat(decision.getResetAfter()).isPresent();
    verify(valueOperations).increment("ratelimiter:customer-1:1700000000000");
    verify(redisTemplate).expire("ratelimiter:customer-1:1700000000000", Duration.ofSeconds(11));
  }

  @Test
  void evaluateBlocksRequestWhenLimitExceeded() {
    RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofSeconds(10), RateLimitScope.GLOBAL);
    when(valueOperations.increment(anyString())).thenReturn(3L);

    RateLimitDecision decision = rateLimiter.evaluate("customer-1", policy);

    assertThat(decision.isAllowed()).isFalse();
    assertThat(decision.getRemainingTime()).isGreaterThan(0L);
    assertThat(decision.getRetryAfter()).isPresent();
    assertThat(decision.getResetAfter()).isPresent();
    verify(redisTemplate, times(0)).expire(anyString(), any(Duration.class));
  }

  @Test
  void evaluateSetsTtlOnlyOnCounterCreation() {
    RateLimitPolicy policy = new RateLimitPolicy(5, Duration.ofSeconds(10), RateLimitScope.GLOBAL);
    when(valueOperations.increment(anyString())).thenReturn(1L, 2L);
    when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

    rateLimiter.evaluate("customer-2", policy);
    rateLimiter.evaluate("customer-2", policy);

    verify(redisTemplate, times(1)).expire("ratelimiter:customer-2:1700000000000", Duration.ofSeconds(11));
  }

  @Test
  void evaluateThrowsWhenRedisFailsInFailClosedMode() {
    RedisRateLimiter failClosedLimiter = new RedisRateLimiter(
        redisTemplate,
        Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
        "ratelimiter",
        false
    );
    RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofSeconds(10), RateLimitScope.GLOBAL);
    when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));

    assertThatThrownBy(() -> failClosedLimiter.evaluate("customer-3", policy))
        .isInstanceOf(RateLimiterBackendException.class)
        .hasMessageContaining("Redis rate limiter backend failure");
  }

  @Test
  void evaluateAllowsWhenRedisFailsInFailOpenMode() {
    RedisRateLimiter failOpenLimiter = new RedisRateLimiter(
        redisTemplate,
        Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
        "ratelimiter",
        true
    );
    RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofSeconds(10), RateLimitScope.GLOBAL);
    when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));

    RateLimitDecision decision = failOpenLimiter.evaluate("customer-4", policy);

    assertThat(decision.isAllowed()).isTrue();
    assertThat(decision.getRemainingTime()).isEqualTo(RateLimitDecision.REMAINING_TIME_UNKNOWN);
    assertThat(decision.getRetryAfter()).isEmpty();
    assertThat(decision.getResetAfter()).isPresent();
  }
}
