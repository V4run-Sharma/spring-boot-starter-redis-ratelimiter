package io.github.v4runsharma.ratelimiter.support;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimitContext;
import io.github.v4runsharma.ratelimiter.core.RateLimitEnforcer;
import io.github.v4runsharma.ratelimiter.core.RateLimitPolicyProvider;
import io.github.v4runsharma.ratelimiter.core.RateLimiter;
import io.github.v4runsharma.ratelimiter.exception.RateLimitExceededException;
import io.github.v4runsharma.ratelimiter.key.RateLimitKeyResolver;
import io.github.v4runsharma.ratelimiter.metrics.NoOpRateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.metrics.RateLimitMetricsRecorder;
import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.aop.framework.AopProxyUtils;

/**
 * Default orchestration implementation for rate-limit evaluation and enforcement.
 */
public final class DefaultRateLimitEnforcer implements RateLimitEnforcer {

  private final RateLimiter rateLimiter;
  private final RateLimitPolicyProvider policyProvider;
  private final RateLimitKeyResolver defaultKeyResolver;
  private final RateLimitMetricsRecorder metricsRecorder;
  private final Map<Class<? extends RateLimitKeyResolver>, RateLimitKeyResolver> keyResolversByType;

  public DefaultRateLimitEnforcer(
      RateLimiter rateLimiter,
      RateLimitPolicyProvider policyProvider,
      RateLimitKeyResolver defaultKeyResolver,
      List<RateLimitKeyResolver> keyResolvers
  ) {
    this(
        rateLimiter,
        policyProvider,
        defaultKeyResolver,
        keyResolvers,
        new NoOpRateLimitMetricsRecorder()
    );
  }

  public DefaultRateLimitEnforcer(
      RateLimiter rateLimiter,
      RateLimitPolicyProvider policyProvider,
      RateLimitKeyResolver defaultKeyResolver,
      List<RateLimitKeyResolver> keyResolvers,
      RateLimitMetricsRecorder metricsRecorder
  ) {
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
    this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider must not be null");
    this.defaultKeyResolver = Objects.requireNonNull(defaultKeyResolver, "defaultKeyResolver must not be null");
    this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder must not be null");

    Map<Class<? extends RateLimitKeyResolver>, RateLimitKeyResolver> resolverMap = new HashMap<>();
    if (keyResolvers != null) {
      for (RateLimitKeyResolver resolver : keyResolvers) {
        if (resolver != null) {
          resolverMap.put(resolverType(resolver), resolver);
        }
      }
    }
    resolverMap.putIfAbsent(resolverType(defaultKeyResolver), defaultKeyResolver);
    this.keyResolversByType = Map.copyOf(resolverMap);
  }

  @Override
  public RateLimitDecision evaluate(RateLimitContext context) {
    return execute(context).decision();
  }

  @Override
  public void enforce(RateLimitContext context) throws RateLimitExceededException {
    Evaluation evaluation = execute(context);
    if (!evaluation.decision().isAllowed()) {
      throw new RateLimitExceededException(
          emptyToNull(evaluation.annotation().name()),
          evaluation.key(),
          evaluation.policy(),
          evaluation.decision()
      );
    }
  }

  private Evaluation execute(RateLimitContext context) {
    Objects.requireNonNull(context, "context must not be null");
    RateLimit annotation = Objects.requireNonNull(context.getAnnotation(), "annotation must not be null");

    RateLimitPolicy policy = Objects.requireNonNull(
        policyProvider.resolvePolicy(context),
        "policyProvider must return a policy"
    );

    RateLimitKeyResolver keyResolver = resolveKeyResolver(annotation.keyResolver());
    String key = requireNonBlank(keyResolver.resolveKey(context));

    long startNanos = System.nanoTime();
    RateLimitDecision decision;
    try {
      decision = Objects.requireNonNull(
          rateLimiter.evaluate(key, policy),
          "rateLimiter must return a decision"
      );
    } catch (RuntimeException ex) {
      Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
      metricsRecorder.recordError(resolveMetricName(context, annotation), policy, latency, ex);
      throw ex;
    }
    Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
    metricsRecorder.recordDecision(resolveMetricName(context, annotation), policy, decision, latency);

    return new Evaluation(annotation, policy, key, decision);
  }

  private RateLimitKeyResolver resolveKeyResolver(Class<? extends RateLimitKeyResolver> resolverType) {
    if (resolverType == null || resolverType == RateLimitKeyResolver.class) {
      return defaultKeyResolver;
    }

    RateLimitKeyResolver resolver = keyResolversByType.get(resolverType);
    if (resolver == null) {
      throw new IllegalStateException("No RateLimitKeyResolver registered for type: " + resolverType.getName());
    }
    return resolver;
  }

  // Key by the user's class, not a CGLIB/JDK proxy class, so @RateLimit(keyResolver = X.class) still matches.
  @SuppressWarnings("unchecked")
  private static Class<? extends RateLimitKeyResolver> resolverType(RateLimitKeyResolver resolver) {
    return (Class<? extends RateLimitKeyResolver>) AopProxyUtils.ultimateTargetClass(resolver);
  }

  private static String requireNonBlank(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("resolved key must not be blank");
    }
    return value;
  }

  private static String emptyToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  private static String resolveMetricName(RateLimitContext context, RateLimit annotation) {
    String annotationName = emptyToNull(annotation.name());
    if (annotationName != null) {
      return annotationName;
    }
    return context.getTargetClass().getSimpleName() + "#" + context.getMethod().getName();
  }

  private record Evaluation(
      RateLimit annotation,
      RateLimitPolicy policy,
      String key,
      RateLimitDecision decision
  ) {
  }
}
