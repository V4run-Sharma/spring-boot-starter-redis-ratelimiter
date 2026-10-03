package io.github.v4runsharma.ratelimiter.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimitContext;
import io.github.v4runsharma.ratelimiter.core.RateLimiter;
import io.github.v4runsharma.ratelimiter.key.DefaultRateLimitKeyResolver;
import io.github.v4runsharma.ratelimiter.key.RateLimitKeyResolver;
import io.github.v4runsharma.ratelimiter.model.RateLimitDecision;
import io.github.v4runsharma.ratelimiter.model.RateLimitPolicy;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;

class DefaultRateLimitEnforcerTest {

  private final RecordingRateLimiter rateLimiter = new RecordingRateLimiter();

  @Test
  void usesDefaultKeyResolverWhenNoneDeclared() throws Exception {
    DefaultRateLimitEnforcer enforcer = enforcer(List.of());

    enforcer.enforce(context("defaultResolver"));

    assertThat(rateLimiter.lastKey).isEqualTo("global:" + Samples.class.getName() + "#defaultResolver");
  }

  @Test
  void resolvesCustomKeyResolverByDeclaredType() throws Exception {
    DefaultRateLimitEnforcer enforcer = enforcer(List.of(new UserKeyResolver()));

    enforcer.enforce(context("customResolver"));

    assertThat(rateLimiter.lastKey).isEqualTo("user:42");
  }

  @Test
  void resolvesCustomKeyResolverBehindCglibProxy() throws Exception {
    ProxyFactory proxyFactory = new ProxyFactory(new UserKeyResolver());
    proxyFactory.setProxyTargetClass(true);
    RateLimitKeyResolver proxied = (RateLimitKeyResolver) proxyFactory.getProxy();
    assertThat(AopUtils.isCglibProxy(proxied)).isTrue();

    enforcer(List.of(proxied)).enforce(context("customResolver"));

    assertThat(rateLimiter.lastKey).isEqualTo("user:42");
  }

  @Test
  void resolvesCustomKeyResolverBehindJdkProxy() throws Exception {
    RateLimitKeyResolver proxied = (RateLimitKeyResolver) new ProxyFactory(new UserKeyResolver()).getProxy();
    assertThat(AopUtils.isJdkDynamicProxy(proxied)).isTrue();

    enforcer(List.of(proxied)).enforce(context("customResolver"));

    assertThat(rateLimiter.lastKey).isEqualTo("user:42");
  }

  @Test
  void failsWhenDeclaredKeyResolverIsNotRegistered() throws Exception {
    DefaultRateLimitEnforcer enforcer = enforcer(List.of());

    assertThatThrownBy(() -> enforcer.enforce(context("customResolver")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(UserKeyResolver.class.getName());
  }

  private DefaultRateLimitEnforcer enforcer(List<RateLimitKeyResolver> keyResolvers) {
    return new DefaultRateLimitEnforcer(
        rateLimiter,
        new AnnotationRateLimitPolicyProvider(),
        new DefaultRateLimitKeyResolver(),
        keyResolvers
    );
  }

  private static RateLimitContext context(String methodName) throws NoSuchMethodException {
    Method method = Samples.class.getMethod(methodName);
    return new DefaultRateLimitContext(method.getAnnotation(RateLimit.class), Samples.class, method, new Object[0], null);
  }

  static class Samples {

    @RateLimit(limit = 5, duration = 60)
    public void defaultResolver() {
    }

    @RateLimit(limit = 5, duration = 60, keyResolver = UserKeyResolver.class)
    public void customResolver() {
    }
  }

  public static class UserKeyResolver implements RateLimitKeyResolver {

    @Override
    public String resolveKey(RateLimitContext context) {
      return "user:42";
    }
  }

  static class RecordingRateLimiter implements RateLimiter {

    private String lastKey;

    @Override
    public RateLimitDecision evaluate(String key, RateLimitPolicy policy) {
      this.lastKey = key;
      return new RateLimitDecision(true, 0L, null, Duration.ofSeconds(1));
    }
  }
}
