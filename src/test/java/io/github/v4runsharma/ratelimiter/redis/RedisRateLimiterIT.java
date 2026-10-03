package io.github.v4runsharma.ratelimiter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimiterIT {

  private static final Instant FIXED_TIME = Instant.ofEpochMilli(1_700_000_005_123L);

  @Container
  private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
      .withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redisTemplate;

  @BeforeAll
  static void setUp() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate();
    redisTemplate.setConnectionFactory(connectionFactory);
    redisTemplate.afterPropertiesSet();
  }

  @AfterAll
  static void tearDown() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void evaluatesAgainstRealRedis() {
    RedisRateLimiter rateLimiter = new RedisRateLimiter(
        redisTemplate,
        Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
        "integration",
        false
    );
    RateLimitPolicy policy = new RateLimitPolicy(2, Duration.ofMinutes(1), RateLimitScope.GLOBAL);
    String key = "serial-" + UUID.randomUUID();

    assertThat(rateLimiter.evaluate(key, policy).isAllowed()).isTrue();
    assertThat(rateLimiter.evaluate(key, policy).isAllowed()).isTrue();
    assertThat(rateLimiter.evaluate(key, policy).isAllowed()).isFalse();
  }

  @Test
  void enforcesLimitUnderConcurrentAccess() throws Exception {
    int limit = 20;
    int requests = 50;

    RedisRateLimiter rateLimiter = new RedisRateLimiter(
        redisTemplate,
        Clock.fixed(FIXED_TIME, ZoneOffset.UTC),
        "integration",
        false
    );
    RateLimitPolicy policy = new RateLimitPolicy(limit, Duration.ofMinutes(1), RateLimitScope.GLOBAL);
    String key = "concurrent-" + UUID.randomUUID();

    CountDownLatch startLatch = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(16);
    try {
      List<Callable<Boolean>> tasks = IntStream.range(0, requests)
          .mapToObj(i -> (Callable<Boolean>) () -> {
            startLatch.await(5, TimeUnit.SECONDS);
            return rateLimiter.evaluate(key, policy).isAllowed();
          })
          .toList();

      List<Future<Boolean>> futures = new ArrayList<>(requests);
      for (Callable<Boolean> task : tasks) {
        futures.add(executor.submit(task));
      }
      startLatch.countDown();

      int allowed = 0;
      int blocked = 0;
      for (Future<Boolean> future : futures) {
        if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) {
          allowed++;
        } else {
          blocked++;
        }
      }

      assertThat(allowed).isEqualTo(limit);
      assertThat(blocked).isEqualTo(requests - limit);
    } finally {
      executor.shutdownNow();
    }
  }
}
