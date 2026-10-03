package io.github.v4runsharma.ratelimiter.key;

import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Principal;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Caller identity taken from the servlet request bound to the current thread.
 */
final class ServletRequestIdentity {

  private final HttpServletRequest request;
  private final RateLimitScope scope;

  private ServletRequestIdentity(HttpServletRequest request, RateLimitScope scope) {
    this.request = request;
    this.scope = scope;
  }

  static ServletRequestIdentity current(RateLimitScope scope) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
      throw new IllegalStateException(scope + "-scoped rate limit requires an HTTP request bound to the current "
          + "thread (not available in @Async, scheduled, or messaging threads)");
    }
    return new ServletRequestIdentity(servletAttributes.getRequest(), scope);
  }

  /**
   * Client address as seen by the servlet container. Behind a proxy or load balancer this is only the
   * real client when {@code server.forward-headers-strategy} is configured; X-Forwarded-For is never
   * read directly because clients can forge it.
   */
  String clientIp() {
    return normalizeClientIp(request.getRemoteAddr(), scope);
  }

  String userName() {
    Principal principal = request.getUserPrincipal();
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new IllegalStateException(scope + "-scoped rate limit requires an authenticated principal on the request");
    }
    return principal.getName();
  }

  /**
   * IPv4 is used as-is. IPv6 is reduced to its /64 prefix, since a single client usually controls a whole
   * /64 and could otherwise rotate addresses to escape the limit.
   */
  static String normalizeClientIp(String remoteAddr, RateLimitScope scope) {
    if (remoteAddr == null || remoteAddr.isBlank()) {
      throw new IllegalStateException(scope + "-scoped rate limit could not determine the client IP address");
    }
    String address = remoteAddr.trim();
    if (address.indexOf(':') < 0) {
      return address;
    }
    if (address.startsWith("[") && address.endsWith("]")) {
      address = address.substring(1, address.length() - 1);
    }
    int zoneIndex = address.indexOf('%');
    if (zoneIndex >= 0) {
      address = address.substring(0, zoneIndex);
    }

    InetAddress inet;
    try {
      // Literal containing ':' is parsed as IPv6; no DNS lookup happens.
      inet = InetAddress.getByName(address);
    } catch (UnknownHostException ex) {
      return address;
    }
    if (inet instanceof Inet4Address) {
      return inet.getHostAddress(); // IPv4-mapped IPv6, e.g. ::ffff:203.0.113.7
    }

    byte[] bytes = inet.getAddress();
    return String.format("%x:%x:%x:%x::/64",
        hextet(bytes, 0), hextet(bytes, 2), hextet(bytes, 4), hextet(bytes, 6));
  }

  private static int hextet(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
  }
}
