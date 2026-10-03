package io.github.v4runsharma.ratelimiter.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the compile-time contract: only declared {@code RateLimitScope} values are accepted.
 */
class RateLimitScopeCompileTest {

  @TempDir
  Path outputDir;

  @Test
  void compilesWithDeclaredScope() throws Exception {
    CompileResult result = compile("scope = RateLimitScope.USER");

    assertThat(result.errorCodes()).isEmpty();
    assertThat(result.success()).isTrue();
  }

  @Test
  void rejectsStringScope() throws Exception {
    CompileResult result = compile("scope = \"TENANT\"");

    assertThat(result.success()).isFalse();
    assertThat(result.errorCodes()).contains("compiler.err.prob.found.req"); // incompatible types
  }

  @Test
  void rejectsUndeclaredScope() throws Exception {
    CompileResult result = compile("scope = RateLimitScope.TENANT");

    assertThat(result.success()).isFalse();
    assertThat(result.errorCodes()).contains("compiler.err.cant.resolve.location"); // cannot find symbol
  }

  private CompileResult compile(String scopeAttribute) throws Exception {
    String code = """
        import io.github.v4runsharma.ratelimiter.annotation.RateLimit;
        import io.github.v4runsharma.ratelimiter.model.RateLimitScope;

        class Sample {
          @RateLimit(%s, limit = 1, duration = 1)
          void call() {
          }
        }
        """.formatted(scopeAttribute);
    JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///Sample.java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return code;
      }
    };
    String classpath = Path.of(RateLimit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    boolean success = compiler.getTask(
        null,
        null,
        diagnostics,
        List.of("-proc:none", "-classpath", classpath, "-d", outputDir.toString()),
        null,
        List.of(source)
    ).call();

    List<String> errorCodes = diagnostics.getDiagnostics().stream()
        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
        .map(Diagnostic::getCode)
        .toList();
    return new CompileResult(success, errorCodes);
  }

  private record CompileResult(boolean success, List<String> errorCodes) {
  }
}
