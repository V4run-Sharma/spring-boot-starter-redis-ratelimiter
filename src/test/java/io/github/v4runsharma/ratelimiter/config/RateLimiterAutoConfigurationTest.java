package io.github.v4runsharma.ratelimiter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.v4runsharma.ratelimiter.aspect.RateLimitAspect;
import io.github.v4runsharma.ratelimiter.core.RateLimitEnforcer;
import io.github.v4runsharma.ratelimiter.core.RateLimiter;
import io.github.v4runsharma.ratelimiter.exception.RateLimitExceptionHandler;
import io.github.v4runsharma.ratelimiter.metrics.MicrometerRateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.metrics.NoOpRateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.metrics.RateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.redis.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RateLimiterAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(RateLimiterAutoConfiguration.class));

  private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(RateLimiterAutoConfiguration.class));

  @Test
  void doesNotCreateRateLimiterWithoutRedisTemplate() {
    contextRunner.run(context -> {
      assertThat(context).doesNotHaveBean(RateLimiter.class);
      assertThat(context).doesNotHaveBean(RateLimitEnforcer.class);
      assertThat(context).doesNotHaveBean(RateLimitAspect.class);
      assertThat(context).doesNotHaveBean(Advisor.class);
    });
  }

  @Test
  void createsCoreBeansWhenRedisTemplatePresent() {
    contextRunner.withUserConfiguration(RedisTemplateTestConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(RateLimiter.class);
          assertThat(context).hasSingleBean(RateLimitEnforcer.class);
          assertThat(context).hasSingleBean(RateLimitAspect.class);
          assertThat(context).hasSingleBean(Advisor.class);
        });
  }

  @Test
  void disablesAllAutoConfiguredBeansWhenFeatureFlagOff() {
    contextRunner
        .withUserConfiguration(RedisTemplateTestConfiguration.class)
        .withPropertyValues("ratelimiter.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(RateLimiter.class);
          assertThat(context).doesNotHaveBean(RateLimitEnforcer.class);
          assertThat(context).doesNotHaveBean(RateLimitAspect.class);
          assertThat(context).doesNotHaveBean(Advisor.class);
          assertThat(context).doesNotHaveBean(RateLimitExceptionHandler.class);
        });
  }

  @Test
  void propagatesFailOpenPropertyToRedisRateLimiter() {
    contextRunner
        .withUserConfiguration(RedisTemplateTestConfiguration.class)
        .withPropertyValues("ratelimiter.fail-open=true")
        .run(context -> {
          RedisRateLimiter limiter = (RedisRateLimiter) context.getBean(RateLimiter.class);
          Object failOpen = ReflectionTestUtils.getField(limiter, "failOpen");
          assertThat(failOpen).isEqualTo(true);
        });
  }

  @Test
  void createsMicrometerMetricsRecorderWhenRegistryExists() {
    contextRunner
        .withUserConfiguration(MeterRegistryTestConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(MicrometerRateLimitMetricsRecorder.class));
  }

  @Test
  void createsMicrometerMetricsRecorderWhenActuatorProvidesRegistry() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(
            MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class,
            SimpleMetricsExportAutoConfiguration.class
        ))
        .run(context -> {
          assertThat(context).hasSingleBean(MeterRegistry.class);
          assertThat(context).hasSingleBean(MicrometerRateLimitMetricsRecorder.class);
        });
  }

  @Test
  void fallsBackToNoopMetricsRecorderWhenMetricsDisabled() {
    contextRunner
        .withUserConfiguration(MeterRegistryTestConfiguration.class)
        .withPropertyValues("ratelimiter.metrics-enabled=false")
        .run(context -> {
          assertThat(context).hasSingleBean(RateLimitMetricsRecorder.class);
          assertThat(context.getBean(RateLimitMetricsRecorder.class)).isInstanceOf(NoOpRateLimitMetricsRecorder.class);
        });
  }

  @Test
  void createsHttpExceptionHandlerInServletWebContext() {
    webContextRunner
        .withUserConfiguration(RedisTemplateTestConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(RateLimitExceptionHandler.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class RedisTemplateTestConfiguration {

    @Bean
    StringRedisTemplate stringRedisTemplate() {
      return new StringRedisTemplate(mock(RedisConnectionFactory.class));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class MeterRegistryTestConfiguration {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
