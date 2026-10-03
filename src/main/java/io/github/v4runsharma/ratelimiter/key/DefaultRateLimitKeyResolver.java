package io.github.v4runsharma.ratelimiter.key;

import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
import io.github.v4runsharma.ratelimiter.core.RateLimitContext;
import io.github.v4runsharma.ratelimiter.model.RateLimitScope;
import java.util.Locale;
import java.util.Objects;
import org.springframework.util.ClassUtils;

/**
 * Default resolver that builds keys from the scope, the caller identity, and the operation.
 * <p>Key formats ({@code operation} is the annotation {@code key}, or {@code fully.qualified.Class#method}):
 * <p>- GLOBAL: {@code global:<operation>}
 * <p>- IP: {@code ip:<client-ip>:<operation>} (IPv6 grouped by /64 prefix)
 * <p>- USER: {@code user:<principal-name>:<operation>}
 * <p>IP and USER read the current servlet request and fail if none is bound to the thread,
 * or (USER) if the request has no authenticated principal.
 */
public final class DefaultRateLimitKeyResolver implements RateLimitKeyResolver {

  private static final boolean SERVLET_PRESENT = ClassUtils.isPresent(
      "jakarta.servlet.http.HttpServletRequest", DefaultRateLimitKeyResolver.class.getClassLoader());

  @Override
  public String resolveKey(RateLimitContext context) {
    Objects.requireNonNull(context, "context must not be null");
    RateLimit annotation = Objects.requireNonNull(context.getAnnotation(), "annotation must not be null");

    RateLimitScope scope = annotation.scope();
    String prefix = scope.name().toLowerCase(Locale.ROOT) + ":";
    String operation = resolveOperation(annotation, context);

    return switch (scope) {
      case GLOBAL -> prefix + operation;
      case IP -> prefix + requireServlet(scope).clientIp() + ":" + operation;
      case USER -> prefix + requireServlet(scope).userName() + ":" + operation;
    };
  }

  private static String resolveOperation(RateLimit annotation, RateLimitContext context) {
    if (annotation.key() != null && !annotation.key().isBlank()) {
      return annotation.key().trim();
    }
    return context.getTargetClass().getName() + "#" + context.getMethod().getName();
  }

  // Servlet types live in a separate class so apps without the servlet API never load them.
  private static ServletRequestIdentity requireServlet(RateLimitScope scope) {
    if (!SERVLET_PRESENT) {
      throw new IllegalStateException(scope + "-scoped rate limits require a servlet web application");
    }
    return ServletRequestIdentity.current(scope);
  }
}
