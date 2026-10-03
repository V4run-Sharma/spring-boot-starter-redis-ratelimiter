package io.github.v4runsharma.ratelimiter.config;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.annotation.RateLimits;
import io.github.v4runsharma.ratelimiter.aspect.RateLimitAspect;
import io.github.v4runsharma.ratelimiter.core.RateLimitEnforcer;
import io.github.v4runsharma.ratelimiter.core.RateLimitPolicyProvider;
import io.github.v4runsharma.ratelimiter.core.RateLimiter;
import io.github.v4runsharma.ratelimiter.exception.RateLimitExceptionHandler;
import io.github.v4runsharma.ratelimiter.key.DefaultRateLimitKeyResolver;
import io.github.v4runsharma.ratelimiter.key.RateLimitKeyResolver;
import io.github.v4runsharma.ratelimiter.metrics.MicrometerRateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.metrics.NoOpRateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.metrics.RateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.redis.RedisRateLimiter;
import io.github.v4runsharma.ratelimiter.support.AnnotationRateLimitPolicyProvider;
import io.github.v4runsharma.ratelimiter.support.DefaultRateLimitEnforcer;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.http.ProblemDetail;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Auto-configuration for redis-backed rate limiting.
 */
@AutoConfiguration(
    after = {RedisAutoConfiguration.class, AopAutoConfiguration.class},
    // Run after actuator registers its MeterRegistry so @ConditionalOnBean(MeterRegistry) can see it.
    afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
    }
)
@ConditionalOnClass({StringRedisTemplate.class, Advisor.class, MethodInterceptor.class})
@EnableConfigurationProperties(RateLimiterProperties.class)
@ConditionalOnProperty(prefix = "ratelimiter", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimiterAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(RateLimitPolicyProvider.class)
  public RateLimitPolicyProvider rateLimitPolicyProvider() {
    return new AnnotationRateLimitPolicyProvider();
  }

  @Bean("defaultRateLimitKeyResolver")
  @ConditionalOnMissingBean(name = "defaultRateLimitKeyResolver")
  public RateLimitKeyResolver defaultRateLimitKeyResolver() {
    return new DefaultRateLimitKeyResolver();
  }

  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  @ConditionalOnBean(StringRedisTemplate.class)
  public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties) {
    return new RedisRateLimiter(
        redisTemplate,
        java.time.Clock.systemUTC(),
        properties.getRedisKeyPrefix(),
        properties.isFailOpen()
    );
  }

  @Bean
  @ConditionalOnMissingBean(RateLimitEnforcer.class)
  @ConditionalOnBean({RateLimiter.class, RateLimitPolicyProvider.class})
  public RateLimitEnforcer rateLimitEnforcer(
      RateLimiter rateLimiter,
      RateLimitPolicyProvider policyProvider,
      @Qualifier("defaultRateLimitKeyResolver") RateLimitKeyResolver defaultKeyResolver,
      ObjectProvider<RateLimitKeyResolver> keyResolversProvider,
      RateLimitMetricsRecorder metricsRecorder
  ) {
    List<RateLimitKeyResolver> keyResolvers = keyResolversProvider.orderedStream().toList();
    return new DefaultRateLimitEnforcer(
        rateLimiter,
        policyProvider,
        defaultKeyResolver,
        keyResolvers,
        metricsRecorder
    );
  }

  @Bean
  @ConditionalOnMissingBean(RateLimitAspect.class)
  @ConditionalOnBean(RateLimitEnforcer.class)
  public RateLimitAspect rateLimitInterceptor(RateLimitEnforcer enforcer) {
    return new RateLimitAspect(enforcer);
  }

  /**
   * Infrastructure role lets Boot's default auto-proxy creator pick this advisor up even without
   * aspectjweaver. The interceptor is resolved lazily so advisor lookup during post-processor
   * registration does not eagerly initialize the enforcer chain (Redis, MeterRegistry, ...).
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  @ConditionalOnMissingBean(name = "rateLimitAdvisor")
  @ConditionalOnBean(RateLimitEnforcer.class)
  public static Advisor rateLimitAdvisor(ObjectProvider<RateLimitAspect> interceptorProvider) {
    // Repeated @RateLimit is stored in the @RateLimits container, so match both.
    Pointcut pointcut = annotatedTypeOrMethod(RateLimit.class).union(annotatedTypeOrMethod(RateLimits.class));
    Supplier<RateLimitAspect> interceptor = SingletonSupplier.of(interceptorProvider::getObject);
    MethodInterceptor advice = invocation -> interceptor.get().invoke(invocation);
    return new DefaultPointcutAdvisor(pointcut, advice);
  }

  // Matches an annotated type (all its methods) or an annotated method.
  private static ComposablePointcut annotatedTypeOrMethod(Class<? extends Annotation> annotationType) {
    return new ComposablePointcut(new AnnotationMatchingPointcut(annotationType, true))
        .union(new AnnotationMatchingPointcut(null, annotationType, true));
  }

  @Bean
  @ConditionalOnMissingBean(RateLimitExceptionHandler.class)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass({ProblemDetail.class, ExceptionHandler.class})
  public RateLimitExceptionHandler rateLimitExceptionHandler(RateLimiterProperties properties) {
    return new RateLimitExceptionHandler(properties.isIncludeHttpHeaders());
  }

  @Bean
  @ConditionalOnMissingBean(RateLimitMetricsRecorder.class)
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnProperty(prefix = "ratelimiter", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
  public RateLimitMetricsRecorder micrometerRateLimitMetricsRecorder(MeterRegistry meterRegistry) {
    return new MicrometerRateLimitMetricsRecorder(meterRegistry);
  }

  @Bean
  @ConditionalOnMissingBean(RateLimitMetricsRecorder.class)
  public RateLimitMetricsRecorder noOpRateLimitMetricsRecorder() {
    return new NoOpRateLimitMetricsRecorder();
  }
}
