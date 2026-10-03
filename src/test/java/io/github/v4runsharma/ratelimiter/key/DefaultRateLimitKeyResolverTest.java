package io.github.v4runsharma.ratelimiter.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimitContext;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import io.github.v4runsharma.ratelimiter.support.DefaultRateLimitContext;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class DefaultRateLimitKeyResolverTest {

  private static final String OPERATION = Samples.class.getName();

  private final DefaultRateLimitKeyResolver resolver = new DefaultRateLimitKeyResolver();

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void globalScopeUsesClassAndMethod() throws Exception {
    assertThat(resolver.resolveKey(context("global"))).isEqualTo("global:" + OPERATION + "#global");
  }

  @Test
  void globalScopeUsesStaticKeyWhenSet() throws Exception {
    assertThat(resolver.resolveKey(context("globalWithKey"))).isEqualTo("global:invoice-create");
  }

  @Test
  void globalScopeDoesNotNeedRequest() throws Exception {
    assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    assertThat(resolver.resolveKey(context("global"))).startsWith("global:");
  }

  @Test
  void ipScopeUsesClientAddress() throws Exception {
    bindRequest("203.0.113.7", null);

    assertThat(resolver.resolveKey(context("ip"))).isEqualTo("ip:203.0.113.7:" + OPERATION + "#ip");
  }

  @Test
  void ipScopeCombinesClientAddressWithStaticKey() throws Exception {
    bindRequest("203.0.113.7", null);

    assertThat(resolver.resolveKey(context("ipWithKey"))).isEqualTo("ip:203.0.113.7:search");
  }

  @Test
  void ipScopeGroupsIpv6ByPrefix() throws Exception {
    bindRequest("2001:db8:abcd:12:1:2:3:4", null);
    String first = resolver.resolveKey(context("ip"));
    bindRequest("2001:db8:abcd:12:ffff:ffff:ffff:ffff", null);
    String sameNetwork = resolver.resolveKey(context("ip"));
    bindRequest("2001:db8:abcd:13::1", null);
    String otherNetwork = resolver.resolveKey(context("ip"));

    assertThat(first).isEqualTo("ip:2001:db8:abcd:12::/64:" + OPERATION + "#ip");
    assertThat(sameNetwork).isEqualTo(first);
    assertThat(otherNetwork).isNotEqualTo(first);
  }

  @Test
  void ipScopeTreatsIpv4MappedAddressAsIpv4() throws Exception {
    bindRequest("::ffff:203.0.113.7", null);

    assertThat(resolver.resolveKey(context("ip"))).isEqualTo("ip:203.0.113.7:" + OPERATION + "#ip");
  }

  @Test
  void ipScopeIgnoresIpv6ZoneId() throws Exception {
    bindRequest("fe80::1%eth0", null);

    assertThat(resolver.resolveKey(context("ip"))).isEqualTo("ip:fe80:0:0:0::/64:" + OPERATION + "#ip");
  }

  @Test
  void ipScopeFailsWithoutRequest() throws Exception {
    RateLimitContext context = context("ip");

    assertThatThrownBy(() -> resolver.resolveKey(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IP-scoped rate limit requires an HTTP request");
  }

  @Test
  void userScopeUsesPrincipalName() throws Exception {
    bindRequest("203.0.113.7", "alice");

    assertThat(resolver.resolveKey(context("user"))).isEqualTo("user:alice:" + OPERATION + "#user");
  }

  @Test
  void userScopeFailsWithoutPrincipal() throws Exception {
    bindRequest("203.0.113.7", null);
    RateLimitContext context = context("user");

    assertThatThrownBy(() -> resolver.resolveKey(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires an authenticated principal");
  }

  @Test
  void userScopeFailsWithoutRequest() throws Exception {
    RateLimitContext context = context("user");

    assertThatThrownBy(() -> resolver.resolveKey(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("USER-scoped rate limit requires an HTTP request");
  }

  private static void bindRequest(String remoteAddr, String userName) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddr);
    if (userName != null) {
      request.setUserPrincipal(() -> userName);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private static RateLimitContext context(String methodName) throws NoSuchMethodException {
    Method method = Samples.class.getMethod(methodName);
    return new DefaultRateLimitContext(method.getAnnotation(RateLimit.class), Samples.class, method, new Object[0], null);
  }

  static class Samples {

    @RateLimit(limit = 1, duration = 60)
    public void global() {
    }

    @RateLimit(limit = 1, duration = 60, key = "invoice-create")
    public void globalWithKey() {
    }

    @RateLimit(scope = RateLimitScope.IP, limit = 1, duration = 60)
    public void ip() {
    }

    @RateLimit(scope = RateLimitScope.IP, limit = 1, duration = 60, key = "search")
    public void ipWithKey() {
    }

    @RateLimit(scope = RateLimitScope.USER, limit = 1, duration = 60)
    public void user() {
    }
  }
}
