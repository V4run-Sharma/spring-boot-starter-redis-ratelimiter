# Changelog

All notable changes to this project will be documented in this file.

## [2.0.0] - 2026-10-03

### Breaking
- `@RateLimit.scope` is now a `RateLimitScope` enum (default `GLOBAL`); unknown scopes fail compilation. Replace `scope = "USER"` with `scope = RateLimitScope.USER`.
- `RateLimitPolicy` takes and returns `RateLimitScope` instead of `String`.
- `IP` and `USER` scopes now resolve the caller from the current HTTP request, which changes their Redis keys. They throw if no request is bound to the thread, or (`USER`) if the request has no authenticated principal.
- Class-level `@RateLimit` now limits every method of the class (in 1.x it only took effect on methods that were also annotated).

### Added
- Built-in per-IP (IPv6 grouped by /64 prefix) and per-user key resolution in `DefaultRateLimitKeyResolver`.
- `@RateLimit` is repeatable (`@RateLimits`) to stack limits; all must pass, enforced in declaration order.

### Fixed
- `@RateLimit` on a method of an un-annotated class is now enforced (the pointcut previously required both).
- Rate-limit advisor is now picked up without `aspectjweaver` on the classpath (infrastructure role).
- Micrometer recorder now activates when the `MeterRegistry` comes from Actuator auto-configuration.
- Custom `RateLimitKeyResolver` beans behind AOP proxies are now resolved by their declared type.
- Class-level `@RateLimit` no longer charges `java.lang.Object` methods (`toString`, `equals`, `hashCode`).
- `Retry-After` and `RateLimit-Reset` are rounded up to whole seconds instead of truncated.

## [1.0.1] - 2026-02-27

### Added
- Public API surface for rate-limiter annotation, model, and core contracts.
- Redis-backed fixed-window `RateLimiter` implementation.
- Spring Boot 3 auto-configuration via `AutoConfiguration.imports`.
- HTTP 429 exception handler with optional `RateLimit-*` headers.
- Micrometer metrics recorder integration.
- Auto-configuration tests and Redis limiter tests.
- Docker-optional integration tests for Redis behavior and concurrency.
