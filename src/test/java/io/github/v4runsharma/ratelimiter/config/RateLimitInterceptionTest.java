package io.github.v4runsharma.ratelimiter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimiter;
import io.github.v4runsharma.ratelimiter.exception.RateLimitExceededException;
import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies {@code @RateLimit} is actually enforced through Spring AOP proxies, both with
 * aspectjweaver on the classpath (AspectJ auto-proxy creator) and without it
 * (Boot's infrastructure-only auto-proxy creator).
 */
class RateLimitInterceptionTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, RateLimiterAutoConfiguration.class))
      .withUserConfiguration(InterceptionTestConfiguration.class);

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void usesExpectedAutoProxyCreator(boolean aspectj) {
    runner(aspectj).run(context -> {
      Object creator = context.getBean(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME);
      assertThat(creator).isInstanceOf(aspectj
          ? AnnotationAwareAspectJAutoProxyCreator.class
          : InfrastructureAdvisorAutoProxyCreator.class);
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void enforcesMethodLevelAnnotation(boolean aspectj) {
    runner(aspectj).run(context -> {
      MethodLevelService service = context.getBean(MethodLevelService.class);
      assertThat(AopUtils.isAopProxy(service)).isTrue();

      service.limited();
      service.limited();
      assertThatThrownBy(service::limited).isInstanceOf(RateLimitExceededException.class);
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void doesNotLimitUnannotatedMethodOnAdvisedBean(boolean aspectj) {
    runner(aspectj).run(context -> {
      MethodLevelService service = context.getBean(MethodLevelService.class);
      assertThatCode(() -> {
        for (int i = 0; i < 5; i++) {
          service.unlimited();
        }
      }).doesNotThrowAnyException();
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void enforcesClassLevelAnnotationPerMethod(boolean aspectj) {
    runner(aspectj).run(context -> {
      ClassLevelService service = context.getBean(ClassLevelService.class);
      assertThat(AopUtils.isAopProxy(service)).isTrue();

      service.first();
      assertThatThrownBy(service::first).isInstanceOf(RateLimitExceededException.class);
      // Default key includes the method name, so each method gets its own bucket.
      service.second();
      assertThatThrownBy(service::second).isInstanceOf(RateLimitExceededException.class);
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void methodLevelAnnotationOverridesClassLevel(boolean aspectj) {
    runner(aspectj).run(context -> {
      ClassLevelService service = context.getBean(ClassLevelService.class);

      service.overridden();
      service.overridden();
      service.overridden();
      assertThatThrownBy(service::overridden).isInstanceOf(RateLimitExceededException.class);
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void doesNotLimitObjectMethodsUnderClassLevelAnnotation(boolean aspectj) {
    runner(aspectj).run(context -> {
      ClassLevelService service = context.getBean(ClassLevelService.class);
      CountingRateLimiter limiter = context.getBean(CountingRateLimiter.class);

      for (int i = 0; i < 3; i++) {
        service.toString();
        service.hashCode();
        service.equals(service);
      }
      assertThat(limiter.evaluations()).isZero();
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void enforcesEveryStackedMethodLimitAndStopsAtFirstDenial(boolean aspectj) {
    runner(aspectj).run(context -> {
      StackedService service = context.getBean(StackedService.class);
      CountingRateLimiter limiter = context.getBean(CountingRateLimiter.class);

      service.call();
      assertThat(limiter.evaluations()).isEqualTo(2);

      // "narrow" (limit 1) is declared first and denies, so "wide" is not charged.
      assertThatThrownBy(service::call)
          .isInstanceOf(RateLimitExceededException.class)
          .hasMessageContaining("narrow");
      assertThat(limiter.evaluations()).isEqualTo(3);
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void enforcesStackedClassLevelLimits(boolean aspectj) {
    runner(aspectj).run(context -> {
      StackedClassService service = context.getBean(StackedClassService.class);
      assertThat(AopUtils.isAopProxy(service)).isTrue();

      service.call();
      service.call();
      assertThatThrownBy(service::call)
          .isInstanceOf(RateLimitExceededException.class)
          .hasMessageContaining("class-narrow");
    });
  }

  @ParameterizedTest(name = "aspectj={0}")
  @ValueSource(booleans = {true, false})
  void overridingMethodLimitReplacesInheritedOne(boolean aspectj) {
    runner(aspectj).run(context -> {
      ChildService service = context.getBean(ChildService.class);

      // Parent declares limit 1; child override declares limit 3. Only the child's applies.
      service.limited();
      service.limited();
      service.limited();
      assertThatThrownBy(service::limited).isInstanceOf(RateLimitExceededException.class);
    });
  }

  private ApplicationContextRunner runner(boolean aspectj) {
    return aspectj ? contextRunner : contextRunner.withClassLoader(new FilteredClassLoader("org.aspectj"));
  }

  @Configuration(proxyBeanMethods = false)
  static class InterceptionTestConfiguration {

    @Bean
    CountingRateLimiter countingRateLimiter() {
      return new CountingRateLimiter();
    }

    @Bean
    MethodLevelService methodLevelService() {
      return new MethodLevelService();
    }

    @Bean
    ClassLevelService classLevelService() {
      return new ClassLevelService();
    }

    @Bean
    StackedService stackedService() {
      return new StackedService();
    }

    @Bean
    StackedClassService stackedClassService() {
      return new StackedClassService();
    }

    @Bean
    ChildService childService() {
      return new ChildService();
    }
  }

  // Public: without aspectj the proxy class is defined in FilteredClassLoader and must see its superclass.
  public static class MethodLevelService {

    @RateLimit(limit = 2, duration = 1, timeUnit = TimeUnit.MINUTES)
    public String limited() {
      return "ok";
    }

    public String unlimited() {
      return "ok";
    }
  }

  @RateLimit(limit = 1, duration = 1, timeUnit = TimeUnit.MINUTES)
  public static class ClassLevelService {

    public String first() {
      return "ok";
    }

    public String second() {
      return "ok";
    }

    @RateLimit(limit = 3, duration = 1, timeUnit = TimeUnit.MINUTES)
    public String overridden() {
      return "ok";
    }
  }

  public static class StackedService {

    @RateLimit(name = "narrow", key = "narrow", limit = 1, duration = 1, timeUnit = TimeUnit.MINUTES)
    @RateLimit(name = "wide", key = "wide", limit = 5, duration = 1, timeUnit = TimeUnit.MINUTES)
    public String call() {
      return "ok";
    }
  }

  @RateLimit(name = "class-wide", key = "class-wide", limit = 5, duration = 1, timeUnit = TimeUnit.MINUTES)
  @RateLimit(name = "class-narrow", key = "class-narrow", limit = 2, duration = 1, timeUnit = TimeUnit.MINUTES)
  public static class StackedClassService {

    public String call() {
      return "ok";
    }
  }

  public static class ParentService {

    @RateLimit(limit = 1, duration = 1, timeUnit = TimeUnit.MINUTES)
    public String limited() {
      return "parent";
    }
  }

  public static class ChildService extends ParentService {

    @Override
    @RateLimit(limit = 3, duration = 1, timeUnit = TimeUnit.MINUTES)
    public String limited() {
      return "child";
    }
  }

  /**
   * In-memory limiter: counts hits per key and allows up to the policy limit.
   */
  static class CountingRateLimiter implements RateLimiter {

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final AtomicInteger evaluations = new AtomicInteger();

    @Override
    public RateLimitDecision evaluate(String key, RateLimitPolicy policy) {
      evaluations.incrementAndGet();
      int count = counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      boolean allowed = count <= policy.getLimit();
      Duration reset = policy.getWindow();
      return new RateLimitDecision(allowed, allowed ? 0L : reset.toMillis(), allowed ? null : reset, reset);
    }

    int evaluations() {
      return evaluations.get();
    }
  }
}
