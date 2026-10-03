package compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimitEnforcer;
import io.github.v4runsharma.ratelimiter.metrics.MicrometerRateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.metrics.RateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;

/**
 * Smoke test of the published starter on a real Spring Boot app (version chosen by the build),
 * backed by a real Redis. Uses only APIs that exist on both Spring Boot 3 and 4.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Localhost is a trusted proxy, so X-Forwarded-For simulates different clients.
    properties = "server.forward-headers-strategy=native"
)
class StarterCompatibilityTest {

  private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @SpringBootApplication
  static class App {

    @Bean
    LimitedController limitedController() {
      return new LimitedController();
    }
  }

  @RestController
  public static class LimitedController {

    // Hour-long windows keep a window boundary from landing mid-test.
    @GetMapping("/global")
    @RateLimit(name = "compat-global", limit = 2, duration = 1, timeUnit = TimeUnit.HOURS)
    public String global() {
      return "ok";
    }

    @GetMapping("/ip")
    @RateLimit(name = "compat-ip", scope = RateLimitScope.IP, limit = 1, duration = 1, timeUnit = TimeUnit.HOURS)
    public String ip() {
      return "ok";
    }
  }

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired
  private ApplicationContext context;

  @Autowired
  private StringRedisTemplate redis;

  @Autowired
  private MeterRegistry meterRegistry;

  @Test
  void wiresRateLimiterOnThisSpringBootVersion() {
    System.out.println("Spring Boot " + SpringBootVersion.getVersion());

    assertThat(context.getBeanNamesForType(RateLimitEnforcer.class)).hasSize(1);
    assertThat(context.containsBean("rateLimitAdvisor")).isTrue();
    assertThat(AopUtils.isAopProxy(context.getBean(LimitedController.class))).isTrue();
    assertThat(context.getBean(RateLimitMetricsRecorder.class)).isInstanceOf(MicrometerRateLimitMetricsRecorder.class);
  }

  @Test
  void returns429WithHeadersOnceGlobalLimitIsExceeded() throws Exception {
    assertThat(get("/global", null).statusCode()).isEqualTo(200);
    assertThat(get("/global", null).statusCode()).isEqualTo(200);

    HttpResponse<String> denied = get("/global", null);
    assertThat(denied.statusCode()).isEqualTo(429);
    assertThat(denied.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
    assertThat(denied.headers().firstValue("RateLimit-Limit")).hasValue("2");
    assertThat(denied.body()).contains("Rate limit exceeded").contains("\"name\":\"compat-global\"");
  }

  @Test
  void limitsPerClientIpUsingRedis() throws Exception {
    assertThat(get("/ip", "203.0.113.1").statusCode()).isEqualTo(200);
    assertThat(get("/ip", "203.0.113.1").statusCode()).isEqualTo(429);
    assertThat(get("/ip", "203.0.113.2").statusCode()).isEqualTo(200);

    assertThat(redis.keys("ratelimiter:ip:203.0.113.1:*")).hasSize(1);
    assertThat(meterRegistry.get("ratelimiter.requests").tag("name", "compat-ip").tag("outcome", "blocked").counter().count())
        .isEqualTo(1.0);
  }

  private HttpResponse<String> get(String path, String forwardedFor) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (forwardedFor != null) {
      request.header("X-Forwarded-For", forwardedFor);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
