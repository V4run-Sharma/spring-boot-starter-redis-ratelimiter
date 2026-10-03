package io.github.v4runsharma.ratelimiter.annotation;

import io.github.v4runsharma.ratelimiter.key.RateLimitKeyResolver;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Declares a rate limit for a method or a type.
 * Repeat the annotation to stack limits (e.g., per IP and per user); every limit must pass.
 * This annotation is part of the public API only:
 * - It carries configuration metadata.
 * - Enforcement is done elsewhere (aspect/interceptor + RateLimiter).
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Repeatable(RateLimits.class)
public @interface RateLimit {

  /**
   * Optional logical name for the limit.
   * Common uses: metrics tags, configuration override lookup, or documentation.
   */
  String name() default "";

  /**
   * Who the limit applies to when using the default key resolver:
   * <ul>
   *   <li>{@link RateLimitScope#GLOBAL}: one bucket shared by all callers.</li>
   *   <li>{@link RateLimitScope#IP}: one bucket per client IP (IPv6 grouped by /64) of the current HTTP request.</li>
   *   <li>{@link RateLimitScope#USER}: one bucket per authenticated principal of the current HTTP request.</li>
   * </ul>
   * A custom {@link #keyResolver()} decides identity itself; the scope is then only a label.
   */
  RateLimitScope scope() default RateLimitScope.GLOBAL;

  /**
   * Maximum number of allowed requests within the window.
   */
  int limit();

  /**
   * Window size (in {@link #timeUnit()} units).
   */
  long duration();

  /**
   * Time unit for {@link #duration()}.
   */
  TimeUnit timeUnit() default TimeUnit.SECONDS;

  /**
   * Key resolver type to compute the rate limit key for this annotation.
   * Note: defaulting to the interface type acts as a sentinel meaning
   * "not explicitly set"; the starter can substitute a default implementation.
   */
  Class<? extends RateLimitKeyResolver> keyResolver() default RateLimitKeyResolver.class;

  /**
   * Optional static key suffix to disambiguate limits without writing a custom resolver.
   */
  String key() default "";

  /**
   * Feature flag to disable enforcement without removing the annotation.
   */
  boolean enabled() default true;
}
