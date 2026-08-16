package io.emergeos.grapheval;

import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphProviderAttribution;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Fresh-JVM V19 acceptance harness. Emits bounded, credential-free receipts. */
public final class Pack010ExactPicoOverlayReaderMain {

  private static final String ADAPTER =
      "io.emergeos.adapters.postgres.PostgresExactPicoOverlayReader";
  private static final String PORT =
      "io.emergeos.core.port.GraphExactPicoOverlayReader";
  private static final String VERIFICATION =
      "io.emergeos.core.domain.GraphExactPicoOverlayVerification";
  private static final String REQUIREMENT =
      "io.emergeos.core.domain.GraphExactPicoOverlayRequirement";
  private static final String ATTRIBUTION =
      "io.emergeos.core.domain.GraphExactPicoOverlayAttribution";
  private static final String SNAPSHOT =
      "io.emergeos.core.domain.GraphExactPicoOverlaySnapshot";
  private Pack010ExactPicoOverlayReaderMain() {}

  public static void main(String[] args) {
    int exit = run(args);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  private static int run(String[] args) {
    try {
      if (args.length != 7 || !"verify".equals(args[0])) {
        throw new IllegalArgumentException("arguments are invalid");
      }
      int repetition = Integer.parseInt(args[1]);
      String expectedState = args[2];
      if (!validExpectation(repetition, expectedState)) {
        throw new IllegalArgumentException("state expectation is invalid");
      }
      String jdbcUrl = Pack009ProcessSupport.requireLoopbackPostgres(args[3]);
      String username =
          Pack009ProcessSupport.requireBounded(args[4], "database username");
      Path appJar = Pack009ProcessSupport.absoluteRegularFile(args[5]);
      Path testClasses = Pack009ProcessSupport.absoluteDirectory(args[6]);

      String password;
      try (DataInputStream input = new DataInputStream(System.in)) {
        password =
            Pack009ProcessSupport.readSecretFrame(input, "database password");
        Pack009ProcessSupport.requireEndOfInput(input, "standard input");
      }

      Class<?> adapter;
      Class<?> port;
      Class<?> verification;
      Class<?> requirement;
      Class<?> attribution;
      Class<?> snapshot;
      try {
        adapter = Class.forName(ADAPTER);
        port = Class.forName(PORT);
        verification = Class.forName(VERIFICATION);
        requirement = Class.forName(REQUIREMENT);
        attribution = Class.forName(ATTRIBUTION);
        snapshot = Class.forName(SNAPSHOT);
      } catch (ClassNotFoundException missing) {
        System.out.println(
            "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=SURFACE_MISSING");
        return 5;
      }

      assertCodeSource(
          Pack010ExactPicoOverlayReaderMain.class, testClasses, "test harness");
      assertCodeSource(Pack009ProcessSupport.class, testClasses, "test support");
      assertCodeSource(Pack010GraphEvalCatalog.class, appJar, "catalog");
      assertCodeSource(GraphAttemptManifest.class, appJar, "manifest");
      for (Class<?> production :
          List.of(
              adapter,
              port,
              verification,
              requirement,
              attribution,
              snapshot)) {
        assertCodeSource(production, appJar, "V19 production surface");
      }
      verifySurface(
          adapter,
          port,
          verification,
          requirement,
          attribution,
          snapshot);

      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(jdbcUrl);
      dataSource.setUser(username);
      dataSource.setPassword(password);
      password = null;

      Method open = adapter.getDeclaredMethod("open", DataSource.class);
      Object reader = open.invoke(null, dataSource);
      Method findVerified =
          port.getDeclaredMethod("findVerified", GraphAttemptManifest.class);
      Object result =
          findVerified.invoke(reader, Pack010GraphEvalCatalog.manifest(repetition));
      GraphAttemptManifest expected =
          Pack010GraphEvalCatalog.manifest(repetition);
      if (!verifyExpectedState(
          result,
          verification,
          requirement,
          attribution,
          snapshot,
          expected,
          repetition,
          expectedState)) {
        System.out.println(
            "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=UNEXPECTED_STATE");
        return 4;
      }
      String verdict =
          expectedState.startsWith("EXACT_")
              ? "INVALID reason=" + expectedState
              : expectedState;
      System.out.println(
          "PACK010_V19_EXACT_OVERLAY_VERIFY version=1 verdict="
              + verdict
              + " repetition="
              + repetition
              + " legacySequence=13");
      return 0;
    } catch (IllegalArgumentException failure) {
      System.out.println(
          "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=INVALID_INPUT");
      return 2;
    } catch (ReflectiveOperationException | IllegalStateException failure) {
      System.out.println(
          "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=SURFACE_OR_VERIFICATION_INVALID");
      return 3;
    } catch (Exception failure) {
      System.out.println(
          "PACK010_V19_EXACT_OVERLAY_VERIFY_REJECTED version=1 reason=BOUNDED_FAILURE");
      return 6;
    }
  }

  private static boolean verifyExpectedState(
      Object result,
      Class<?> verification,
      Class<?> requirement,
      Class<?> attribution,
      Class<?> snapshot,
      GraphAttemptManifest expected,
      int repetition,
      String expectedState) throws ReflectiveOperationException {
    if (result == null || !verification.isInstance(result)) {
      return false;
    }
    if ("MISSING".equals(expectedState)) {
      return "Missing".equals(result.getClass().getSimpleName());
    }
    if (expectedState.startsWith("EXACT_")) {
      if (!"Invalid".equals(result.getClass().getSimpleName())) {
        return false;
      }
      Object reason =
          result.getClass().getDeclaredMethod("reason").invoke(result);
      return expectedState.equals(reason.toString());
    }
    if ("ATTRIBUTED".equals(expectedState)) {
      if (!"Attributed".equals(result.getClass().getSimpleName())) {
        return false;
      }
      Object projectedSnapshot =
          result.getClass().getDeclaredMethod("snapshot").invoke(result);
      if (!snapshot.isInstance(projectedSnapshot)) {
        return false;
      }
      Object projectedRequirement =
          snapshot
              .getDeclaredMethod("requirement")
              .invoke(projectedSnapshot);
      Object projectedAttribution =
          snapshot
              .getDeclaredMethod("attribution")
              .invoke(projectedSnapshot);
      Object receipt =
          snapshot.getDeclaredMethod("receipt").invoke(projectedSnapshot);
      GraphProviderAttribution expectedAttribution =
          Pack010GraphTerminalFixture.catalogAttribution(repetition, 2);
      return verifyRequirement(
              projectedRequirement,
              requirement,
              expected,
              repetition)
          && attribution.isInstance(projectedAttribution)
          && expectedAttribution.responseHash().equals(
              attribution
                  .getDeclaredMethod("responseHash")
                  .invoke(projectedAttribution))
          && expected.childActor().equals(
              attribution
                  .getDeclaredMethod("providerActor")
                  .invoke(projectedAttribution))
          && "pack010-synthetic".equals(
              attribution
                  .getDeclaredMethod("providerId")
                  .invoke(projectedAttribution))
          && "pack010.synthetic.responses".equals(
              attribution
                  .getDeclaredMethod("providerProtocol")
                  .invoke(projectedAttribution))
          && profileId(repetition).equals(
              attribution
                  .getDeclaredMethod("providerProfileId")
                  .invoke(projectedAttribution))
          && keyId(repetition).equals(
              snapshot.getDeclaredMethod("keyId").invoke(projectedSnapshot))
          && snapshotHashesExact(snapshot, projectedSnapshot)
          && snapshotTimesExact(snapshot, projectedSnapshot)
          && receipt != null
          && "GraphExactPicoProviderValidationReceipt"
              .equals(receipt.getClass().getSimpleName())
          && Integer.valueOf(14).equals(
              receipt.getClass()
                  .getDeclaredMethod("overlaySequence")
                  .invoke(receipt))
          && Long.valueOf(1L).equals(
              receipt.getClass()
                  .getDeclaredMethod("overlayStateVersion")
                  .invoke(receipt))
          && "CONSUMED".equals(
              receipt.getClass()
                  .getDeclaredMethod("validationState")
                  .invoke(receipt)
                  .toString());
    }
    if (!"Required".equals(result.getClass().getSimpleName())) {
      return false;
    }
    Object projected =
        result.getClass().getDeclaredMethod("requirement").invoke(result);
    return verifyRequirement(projected, requirement, expected, repetition);
  }

  private static boolean verifyRequirement(
      Object projected,
      Class<?> requirement,
      GraphAttemptManifest expected,
      int repetition) throws ReflectiveOperationException {
    if (!requirement.isInstance(projected)) {
      return false;
    }
    Object requestHash =
        requirement.getDeclaredMethod("requestHash").invoke(projected);
    return "PICO_OVERLAY_V1".equals(
            requirement.getDeclaredMethod("protocolVersion").invoke(projected))
        && expected.principalId().equals(
            requirement.getDeclaredMethod("principalId").invoke(projected))
        && expected.attemptId().equals(
            requirement.getDeclaredMethod("attemptId").invoke(projected))
        && expected.manifestHash().equals(
            requirement.getDeclaredMethod("manifestHash").invoke(projected))
        && Integer.valueOf(13).equals(
            requirement.getDeclaredMethod("baseSequence").invoke(projected))
        && Integer.valueOf(2).equals(
            requirement.getDeclaredMethod("requestOrdinal").invoke(projected))
        && Pack010GraphTerminalFixture.catalogIntent(repetition, 2)
            .requestHash()
            .equals(requestHash)
        && profileId(repetition).equals(
            requirement
                .getDeclaredMethod("providerProfileId")
                .invoke(projected))
        && hash(
            requirement.getDeclaredMethod("requirementHash").invoke(projected))
        && hash(
            requirement.getDeclaredMethod("baseHeadHash").invoke(projected))
        && hash(
            requirement
                .getDeclaredMethod("providerProfileHash")
                .invoke(projected))
        && requirement.getDeclaredMethod("requiredAt").invoke(projected)
            instanceof java.time.Instant;
  }

  private static boolean snapshotHashesExact(
      Class<?> snapshot, Object projected) throws ReflectiveOperationException {
    return hash(
            snapshot
                .getDeclaredMethod("baseValidationPolicyHash")
                .invoke(projected))
        && hash(
            snapshot.getDeclaredMethod("sessionIntentHash").invoke(projected))
        && hash(
            snapshot.getDeclaredMethod("keyFingerprint").invoke(projected))
        && hash(
            snapshot.getDeclaredMethod("challengeHash").invoke(projected))
        && hash(
            snapshot.getDeclaredMethod("signatureHash").invoke(projected));
  }

  private static boolean snapshotTimesExact(
      Class<?> snapshot, Object projected) throws ReflectiveOperationException {
    for (String component :
        List.of("issuedAt", "expiresAt", "validatedAt", "consumedAt")) {
      if (!(snapshot.getDeclaredMethod(component).invoke(projected)
          instanceof java.time.Instant)) {
        return false;
      }
    }
    return true;
  }

  private static boolean validExpectation(
      int repetition, String expectedState) {
    return switch (repetition) {
      case 1 -> "MISSING".equals(expectedState);
      case 2 -> "REQUIRED".equals(expectedState);
      case 3 ->
          Set.of(
                  "ATTRIBUTED",
                  "EXACT_OVERLAY_PARTIAL",
                  "EXACT_OVERLAY_INVALID")
              .contains(expectedState);
      default -> false;
    };
  }

  private static String profileId(int repetition) {
    return "pack010-v19-r"
        + repetition
        + "-synthetic-exact-pico-v1";
  }

  private static String keyId(int repetition) {
    return "pack010-v19-r" + repetition + "-ed25519-key";
  }

  private static boolean hash(Object value) {
    return value instanceof String text && text.matches("[0-9a-f]{64}");
  }

  private static void verifySurface(
      Class<?> adapter,
      Class<?> port,
      Class<?> verification,
      Class<?> requirement,
      Class<?> attribution,
      Class<?> snapshot) {
    require(
        Modifier.isPublic(adapter.getModifiers())
            && Modifier.isFinal(adapter.getModifiers()),
        "adapter visibility");
    require(port.isInterface(), "reader port kind");
    require(port.isAssignableFrom(adapter), "adapter port");
    require(verification.isSealed(), "verification must be sealed");
    require(requirement.isRecord(), "requirement must be a record");
    require(attribution.isRecord(), "attribution must be a record");
    require(snapshot.isRecord(), "snapshot must be a record");
    require(adapter.getConstructors().length == 0, "adapter constructors");

    Set<String> publicDeclaredAdapterMethods =
        Arrays.stream(adapter.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toUnmodifiableSet());
    require(
        publicDeclaredAdapterMethods.equals(Set.of("open", "findVerified")),
        "adapter public methods");
    try {
      Method open = adapter.getDeclaredMethod("open", DataSource.class);
      require(
          Modifier.isPublic(open.getModifiers())
              && Modifier.isStatic(open.getModifiers())
              && open.getReturnType().equals(port),
          "open signature");
      Method findVerified =
          port.getDeclaredMethod("findVerified", GraphAttemptManifest.class);
      require(
          port.getDeclaredMethods().length == 1
              && Modifier.isPublic(findVerified.getModifiers())
              && findVerified.getReturnType().equals(verification),
          "reader signature");
    } catch (NoSuchMethodException failure) {
      throw new IllegalStateException("reader method surface");
    }

    Set<String> states =
        Arrays.stream(verification.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toUnmodifiableSet());
    require(
        states.equals(Set.of("Missing", "Required", "Attributed", "Invalid")),
        "verification states");
    Class<?> invalidReason;
    try {
      invalidReason = Class.forName(VERIFICATION + "$InvalidReason");
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException("invalid reason surface");
    }
    require(invalidReason.isEnum(), "invalid reason kind");
    Set<String> reasons =
        Arrays.stream(invalidReason.getEnumConstants())
            .map(Object::toString)
            .collect(Collectors.toUnmodifiableSet());
    require(
        reasons.equals(
            Set.of(
                "EXPECTED_MANIFEST_MISMATCH",
                "EXACT_REQUIREMENT_INVALID",
                "EXACT_OVERLAY_PARTIAL",
                "EXACT_OVERLAY_INVALID")),
        "invalid reasons");

    assertRecordComponents(
        requirement,
        List.of(
            "protocolVersion:String",
            "principalId:String",
            "attemptId:String",
            "manifestHash:String",
            "requirementHash:String",
            "baseSequence:int",
            "baseHeadHash:String",
            "requestOrdinal:int",
            "requestHash:String",
            "providerProfileId:String",
            "providerProfileHash:String",
            "requiredAt:Instant"));
    assertRecordComponents(
        attribution,
        List.of(
            "responseHash:String",
            "providerActor:String",
            "providerId:String",
            "providerProtocol:String",
            "providerProfileId:String",
            "providerProfileHash:String",
            "baseExecutionPricingFingerprint:String",
            "transportProfileHash:String",
            "parserProfileHash:String",
            "schemaProfileHash:String",
            "modelRequested:String",
            "modelResolvedHash:String",
            "modelResolutionProfileHash:String",
            "pricingProfileId:String",
            "pricingProviderId:String",
            "pricingProfileFingerprint:String",
            "pricingSourceHash:String",
            "uncachedInputPicoUsdPerToken:long",
            "cachedInputPicoUsdPerToken:long",
            "outputPicoUsdPerToken:long",
            "inputTokens:long",
            "cachedInputTokens:long",
            "outputTokens:long",
            "reasoningOutputTokens:long",
            "totalTokens:long",
            "observedCostPicoUsd:BigInteger",
            "decision:GraphProviderValidationDecision",
            "decisionHash:String",
            "attributedAt:Instant"));
    assertRecordComponents(
        snapshot,
        List.of(
            "requirement:GraphExactPicoOverlayRequirement",
            "attribution:GraphExactPicoOverlayAttribution",
            "baseValidationPolicyHash:String",
            "sessionIntentHash:String",
            "keyId:String",
            "keyFingerprint:String",
            "challengeHash:String",
            "signatureHash:String",
            "issuedAt:Instant",
            "expiresAt:Instant",
            "validatedAt:Instant",
            "consumedAt:Instant",
            "receipt:GraphExactPicoProviderValidationReceipt"));
  }

  private static void assertRecordComponents(
      Class<?> record, List<String> expected) {
    List<String> actual =
        Arrays.stream(record.getRecordComponents())
            .map(
                component ->
                    component.getName()
                        + ":"
                        + component.getType().getSimpleName())
            .toList();
    require(actual.equals(expected), "record components");
  }

  private static void assertCodeSource(
      Class<?> type, Path expected, String label) {
    try {
      Path actual =
          Path.of(
                  type.getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI())
              .toRealPath();
      if (!actual.equals(expected.toRealPath())) {
        throw new IllegalStateException(label + " code source");
      }
    } catch (URISyntaxException | java.io.IOException failure) {
      throw new IllegalStateException(label + " code source");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
