package io.emergeos.grapheval;

import io.emergeos.adapters.postgres.PostgresExactPicoOverlayReader;
import io.emergeos.core.domain.GraphAttemptIntegrityException;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphExactPicoOverlayVerification;
import io.emergeos.core.port.GraphExactPicoOverlayReader;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only packaged V21 connection-loss verifier. */
public final class Pack010V21ExactPicoOverlayReaderConnectionLossMain {

  private static final String MODE = "verify-loss";
  private static final int REPETITION = 3;
  private static final String READER_ROLE =
      "emergeos_exact_overlay_reader_v19";
  private static final String APPLICATION_NAME_QUERY = "?ApplicationName=";
  private static final String RECEIPT =
      "PACK010_V21_EXACT_OVERLAY_CONNECTION_LOSS version=1"
          + " verdict=FAIL_CLOSED cause=GRAPH_ATTEMPT_INTEGRITY";
  private static final String REJECTED =
      "PACK010_V21_EXACT_OVERLAY_CONNECTION_LOSS_REJECTED version=1";

  private Pack010V21ExactPicoOverlayReaderConnectionLossMain() {}

  public static void main(String[] args) {
    int exit;
    try {
      exit = run(args);
    } catch (Throwable failure) {
      if (hasIntegrityCause(failure)) {
        System.out.println(RECEIPT);
        exit = 21;
      } else {
        System.out.println(REJECTED + " reason=UNEXPECTED_FAILURE");
        exit = 6;
      }
    }
    System.out.flush();
    if (exit != 0) {
      System.exit(exit);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null
        || args.length != 6
        || !MODE.equals(args[0])) {
      return rejectedInput();
    }
    int repetition;
    try {
      repetition = Integer.parseInt(args[1]);
    } catch (NumberFormatException failure) {
      return rejectedInput();
    }
    if (repetition != REPETITION) {
      return rejectedInput();
    }
    String jdbcUrl;
    String username;
    Path appJar;
    Path testClasses;
    try {
      jdbcUrl = requireFaultJdbcUrl(args[2]);
      username =
          Pack009ProcessSupport.requireBounded(
              args[3], "database username");
      if (!READER_ROLE.equals(username)) {
        return rejectedInput();
      }
      appJar = Pack009ProcessSupport.absoluteRegularFile(args[4]);
      testClasses = Pack009ProcessSupport.absoluteDirectory(args[5]);
    } catch (IllegalArgumentException failure) {
      return rejectedInput();
    }

    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010V21ExactPicoOverlayReaderConnectionLossMain.class);
    verifySurface();

    String password;
    try (DataInputStream input = new DataInputStream(System.in)) {
      password =
          Pack009ProcessSupport.readSecretFrame(
              input, "database password");
      Pack009ProcessSupport.requireEndOfInput(
          input, "standard input");
    }

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(jdbcUrl);
    dataSource.setUser(username);
    dataSource.setPassword(password);
    password = null;

    GraphExactPicoOverlayReader reader =
        PostgresExactPicoOverlayReader.open(dataSource);
    GraphAttemptManifest manifest =
        Pack010GraphEvalCatalog.manifest(repetition);
    GraphExactPicoOverlayVerification unexpected =
        reader.findVerified(manifest);
    if (unexpected != null) {
      System.out.println(REJECTED + " reason=UNEXPECTED_RETURN");
      return 4;
    }
    System.out.println(REJECTED + " reason=NULL_RETURN");
    return 4;
  }

  private static int rejectedInput() {
    System.out.println(REJECTED + " reason=INPUT_REJECTED");
    return 2;
  }

  private static String requireFaultJdbcUrl(String value) {
    if (value == null) {
      throw new IllegalArgumentException("database URL is required");
    }
    int query = value.indexOf(APPLICATION_NAME_QUERY);
    if (query < 1
        || query != value.lastIndexOf(APPLICATION_NAME_QUERY)) {
      throw new IllegalArgumentException("application name is required");
    }
    String base = value.substring(0, query);
    String applicationName =
        value.substring(query + APPLICATION_NAME_QUERY.length());
    if (base.indexOf('?') >= 0) {
      throw new IllegalArgumentException("database query is invalid");
    }
    Pack009ProcessSupport.requireLoopbackPostgres(base);
    if (!applicationName.matches("[a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException("application name is invalid");
    }
    return value;
  }

  private static void verifySurface() throws NoSuchMethodException {
    Class<?> adapter = PostgresExactPicoOverlayReader.class;
    Class<?> port = GraphExactPicoOverlayReader.class;
    Class<?> verification = GraphExactPicoOverlayVerification.class;
    require(
        Modifier.isPublic(adapter.getModifiers())
            && Modifier.isFinal(adapter.getModifiers()),
        "reader visibility");
    require(port.isInterface(), "reader port kind");
    require(port.isAssignableFrom(adapter), "reader port binding");
    require(verification.isSealed(), "verification kind");
    require(adapter.getConstructors().length == 0, "reader constructors");

    Set<String> publicMethods =
        Arrays.stream(adapter.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toUnmodifiableSet());
    require(
        publicMethods.equals(Set.of("open", "findVerified")),
        "reader methods");
    Method open = adapter.getDeclaredMethod("open", DataSource.class);
    require(
        Modifier.isPublic(open.getModifiers())
            && Modifier.isStatic(open.getModifiers())
            && open.getReturnType().equals(port),
        "reader open signature");
    Method findVerified =
        port.getDeclaredMethod(
            "findVerified", GraphAttemptManifest.class);
    require(
        port.getDeclaredMethods().length == 1
            && Modifier.isPublic(findVerified.getModifiers())
            && findVerified.getReturnType().equals(verification),
        "reader verification signature");
    Set<String> states =
        Arrays.stream(verification.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toUnmodifiableSet());
    require(
        states.equals(Set.of("Missing", "Required", "Attributed", "Invalid")),
        "verification states");
  }

  private static boolean hasIntegrityCause(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 32; depth++) {
      if (current instanceof GraphAttemptIntegrityException) {
        return true;
      }
      if (current.getCause() == current) {
        return false;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
