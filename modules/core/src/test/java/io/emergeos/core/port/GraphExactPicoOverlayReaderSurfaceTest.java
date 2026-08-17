package io.emergeos.core.port;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphExactPicoOverlayAttribution;
import io.emergeos.core.domain.GraphExactPicoOverlayRequirement;
import io.emergeos.core.domain.GraphExactPicoOverlaySnapshot;
import io.emergeos.core.domain.GraphExactPicoOverlayVerification;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.domain.GraphProviderValidationDecision;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GraphExactPicoOverlayReaderSurfaceTest {

  @Test
  void readerIsOneExactReadOnlyCapability() {
    assertTrue(GraphExactPicoOverlayReader.class.isInterface());
    Method[] methods = GraphExactPicoOverlayReader.class.getDeclaredMethods();
    assertEquals(1, methods.length);
    Method findVerified = methods[0];
    assertEquals("findVerified", findVerified.getName());
    assertArrayEquals(
        new Class<?>[] {GraphAttemptManifest.class},
        findVerified.getParameterTypes());
    assertEquals(
        GraphExactPicoOverlayVerification.class,
        findVerified.getReturnType());
    assertTrue(Modifier.isPublic(findVerified.getModifiers()));
    assertTrue(Modifier.isAbstract(findVerified.getModifiers()));
    assertFalse(findVerified.isDefault());
    assertFalse(Modifier.isStatic(findVerified.getModifiers()));
  }

  @Test
  void verificationIsAnExactFourWayResult() {
    assertTrue(GraphExactPicoOverlayVerification.class.isInterface());
    assertTrue(GraphExactPicoOverlayVerification.class.isSealed());
    assertEquals(
        Set.of(
            GraphExactPicoOverlayVerification.Missing.class,
            GraphExactPicoOverlayVerification.Required.class,
            GraphExactPicoOverlayVerification.Attributed.class,
            GraphExactPicoOverlayVerification.Invalid.class),
        Set.of(
            GraphExactPicoOverlayVerification.class
                .getPermittedSubclasses()));
    assertArrayEquals(
        new GraphExactPicoOverlayVerification.InvalidReason[] {
          GraphExactPicoOverlayVerification.InvalidReason
              .EXPECTED_MANIFEST_MISMATCH,
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_REQUIREMENT_INVALID,
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_OVERLAY_PARTIAL,
          GraphExactPicoOverlayVerification.InvalidReason
              .EXACT_OVERLAY_INVALID
        },
        GraphExactPicoOverlayVerification.InvalidReason.values());
    assertRecord(
        GraphExactPicoOverlayVerification.Missing.class,
        new String[] {},
        new Class<?>[] {});
    assertRecord(
        GraphExactPicoOverlayVerification.Required.class,
        new String[] {"requirement"},
        new Class<?>[] {GraphExactPicoOverlayRequirement.class});
    assertRecord(
        GraphExactPicoOverlayVerification.Attributed.class,
        new String[] {"snapshot"},
        new Class<?>[] {GraphExactPicoOverlaySnapshot.class});
    assertRecord(
        GraphExactPicoOverlayVerification.Invalid.class,
        new String[] {"reason"},
        new Class<?>[] {
          GraphExactPicoOverlayVerification.InvalidReason.class
        });
  }

  @Test
  void recordsExposeOnlyBoundedHashesAndSemanticMetadata() {
    assertRecord(
        GraphExactPicoOverlayRequirement.class,
        new String[] {
          "protocolVersion",
          "principalId",
          "attemptId",
          "manifestHash",
          "requirementHash",
          "baseSequence",
          "baseHeadHash",
          "requestOrdinal",
          "requestHash",
          "providerProfileId",
          "providerProfileHash",
          "requiredAt"
        },
        new Class<?>[] {
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          int.class,
          String.class,
          int.class,
          String.class,
          String.class,
          String.class,
          Instant.class
        });
    assertRecord(
        GraphExactPicoOverlayAttribution.class,
        new String[] {
          "responseHash",
          "providerActor",
          "providerId",
          "providerProtocol",
          "providerProfileId",
          "providerProfileHash",
          "baseExecutionPricingFingerprint",
          "transportProfileHash",
          "parserProfileHash",
          "schemaProfileHash",
          "modelRequested",
          "modelResolvedHash",
          "modelResolutionProfileHash",
          "pricingProfileId",
          "pricingProviderId",
          "pricingProfileFingerprint",
          "pricingSourceHash",
          "uncachedInputPicoUsdPerToken",
          "cachedInputPicoUsdPerToken",
          "outputPicoUsdPerToken",
          "inputTokens",
          "cachedInputTokens",
          "outputTokens",
          "reasoningOutputTokens",
          "totalTokens",
          "observedCostPicoUsd",
          "decision",
          "decisionHash",
          "attributedAt"
        },
        new Class<?>[] {
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          long.class,
          long.class,
          long.class,
          long.class,
          long.class,
          long.class,
          long.class,
          long.class,
          BigInteger.class,
          GraphProviderValidationDecision.class,
          String.class,
          Instant.class
        });
    assertRecord(
        GraphExactPicoOverlaySnapshot.class,
        new String[] {
          "requirement",
          "attribution",
          "baseValidationPolicyHash",
          "sessionIntentHash",
          "keyId",
          "keyFingerprint",
          "challengeHash",
          "signatureHash",
          "issuedAt",
          "expiresAt",
          "validatedAt",
          "consumedAt",
          "receipt"
        },
        new Class<?>[] {
          GraphExactPicoOverlayRequirement.class,
          GraphExactPicoOverlayAttribution.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          String.class,
          Instant.class,
          Instant.class,
          Instant.class,
          Instant.class,
          GraphExactPicoProviderValidationReceipt.class
        });

    Set<String> forbidden =
        Set.of(
            "signature",
            "publicKeyDer",
            "validationNonce",
            "databaseOid",
            "schemaOid",
            "attestorRoleOid");
    for (Class<?> type :
        new Class<?>[] {
          GraphExactPicoOverlayRequirement.class,
          GraphExactPicoOverlayAttribution.class,
          GraphExactPicoOverlaySnapshot.class
        }) {
      for (RecordComponent component : type.getRecordComponents()) {
        assertFalse(forbidden.contains(component.getName()));
        assertFalse(component.getType().equals(byte[].class));
        assertFalse(component.getType().equals(UUID.class));
      }
    }
  }

  private static void assertRecord(
      Class<?> type, String[] expectedNames, Class<?>[] expectedTypes) {
    assertTrue(type.isRecord());
    assertTrue(Modifier.isPublic(type.getModifiers()));
    assertTrue(Modifier.isFinal(type.getModifiers()));
    RecordComponent[] components = type.getRecordComponents();
    assertArrayEquals(
        expectedNames,
        Arrays.stream(components)
            .map(RecordComponent::getName)
            .toArray(String[]::new));
    assertArrayEquals(
        expectedTypes,
        Arrays.stream(components)
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new));
    assertEquals(
        Set.of(expectedNames),
        Arrays.stream(type.getDeclaredMethods())
            .filter(method -> method.getParameterCount() == 0)
            .filter(
                method ->
                    Arrays.stream(expectedNames)
                        .anyMatch(method.getName()::equals))
            .map(Method::getName)
            .collect(Collectors.toSet()));
  }
}
