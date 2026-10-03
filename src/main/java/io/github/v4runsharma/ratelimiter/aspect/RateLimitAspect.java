package io.github.v4runsharma.ratelimiter.aspect;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimitEnforcer;
import io.github.v4runsharma.ratelimiter.support.DefaultRateLimitContext;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.RepeatableContainers;

/**
 * Method interceptor entrypoint that enforces {@link RateLimit} on methods and classes.
 * <p>Method-level limits replace class-level limits. When several limits are declared, each is
 * enforced in declaration order and the first denial stops the call.
 */
public final class RateLimitAspect implements MethodInterceptor {

  private final RateLimitEnforcer rateLimitEnforcer;

  public RateLimitAspect(RateLimitEnforcer rateLimitEnforcer) {
    this.rateLimitEnforcer = Objects.requireNonNull(rateLimitEnforcer, "rateLimitEnforcer must not be null");
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Method interfaceMethod = invocation.getMethod();
    Class<?> targetClass = resolveTargetClass(invocation.getThis(), interfaceMethod.getDeclaringClass());
    Method method = AopUtils.getMostSpecificMethod(interfaceMethod, targetClass);

    for (RateLimit annotation : resolveAnnotations(method, targetClass)) {
      if (!annotation.enabled()) {
        continue;
      }
      DefaultRateLimitContext context = new DefaultRateLimitContext(
          annotation,
          targetClass,
          method,
          invocation.getArguments(),
          invocation.getThis()
      );
      rateLimitEnforcer.enforce(context);
    }
    return invocation.proceed();
  }

  private static Class<?> resolveTargetClass(Object target, Class<?> fallback) {
    if (target == null) {
      return fallback;
    }
    return AopUtils.getTargetClass(target);
  }

  private static List<RateLimit> resolveAnnotations(Method method, Class<?> targetClass) {
    // Class-level limits should not charge toString/equals/hashCode etc.
    if (method.getDeclaringClass() == Object.class) {
      return List.of();
    }
    List<RateLimit> methodLevel = findNearest(method);
    if (!methodLevel.isEmpty()) {
      return methodLevel;
    }
    return findNearest(targetClass);
  }

  /**
   * All {@link RateLimit}s (direct, repeated, or meta-present) from the closest declaration in the
   * type hierarchy, so an override replaces rather than adds to an inherited declaration.
   */
  private static List<RateLimit> findNearest(AnnotatedElement element) {
    List<MergedAnnotation<RateLimit>> found = MergedAnnotations
        .from(element, SearchStrategy.TYPE_HIERARCHY, RepeatableContainers.standardRepeatables())
        .stream(RateLimit.class)
        .toList();
    if (found.isEmpty()) {
      return List.of();
    }
    int nearest = found.stream().mapToInt(MergedAnnotation::getAggregateIndex).min().getAsInt();
    return found.stream()
        .filter(annotation -> annotation.getAggregateIndex() == nearest)
        .map(MergedAnnotation::synthesize)
        .toList();
  }
}
