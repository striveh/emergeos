package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.postgres.PostgresGraphRuntimeWriters;
import io.emergeos.core.domain.AgentRun;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Acceptance gate for the exact attributed, pre-Candidate failure branch.
 *
 * <p>This deliberately does not authorize a transport or provider failure
 * without complete durable attribution. V9 requires both provider receipts;
 * any earlier failure needs a forward-only protocol rather than synthetic
 * attribution.
 */
class Pack010AttributedFailureTerminalAcceptanceTest {

  private static final Set<Class<?>> RAW_PAYLOAD_TYPES =
      Set.of(String.class, byte[].class, Map.class);

  @Test
  void composerExposesReviewThenOneShotClaimForOpaqueFailureOutcome() {
    Method review = exactComposerMethod("reviewAttributedPreCandidateFailure");
    Method claim = exactComposerMethod("claimAttributedPreCandidateFailure");

    assertEquals(
        "AttributedPreCandidateFailure", review.getReturnType().getSimpleName());
    assertEquals(review.getReturnType(), claim.getReturnType());
    assertFalse(Modifier.isPublic(review.getReturnType().getModifiers()));
    assertTrue(
        Arrays.stream(review.getReturnType().getDeclaredConstructors())
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    assertTrue(
        Arrays.stream(review.getParameterTypes())
            .noneMatch(Pack010AttributedFailureTerminalAcceptanceTest::isRawPayloadType));
    assertTrue(
        Arrays.stream(claim.getParameterTypes())
            .noneMatch(Pack010AttributedFailureTerminalAcceptanceTest::isRawPayloadType));
  }

  @Test
  void runtimeOwnsTypedFailedChildAndParentPreparationWithoutPayloadAbi() {
    Method child =
        exactRuntimeMethod("preparePreCandidateFailureChild", AgentRun.class);
    Method parent =
        exactRuntimeMethod(
            "preparePreCandidateFailureParentAndSeal", AgentRun.class);

    assertEquals(
        "Pack010FailedChildTerminalCommand", child.getReturnType().getSimpleName());
    assertEquals(
        "Pack010FailedParentTerminalCommand", parent.getReturnType().getSimpleName());
    assertTrue(
        Arrays.stream(child.getParameterTypes())
            .noneMatch(Pack010AttributedFailureTerminalAcceptanceTest::isRawPayloadType));
    assertTrue(
        Arrays.stream(parent.getParameterTypes())
            .noneMatch(Pack010AttributedFailureTerminalAcceptanceTest::isRawPayloadType));
  }

  @Test
  void postgresWriterProjectsFailureTruthFromTypedAgentRunsOnly() {
    List<Method> methods =
        Arrays.stream(PostgresGraphRuntimeWriters.class.getMethods())
            .filter(
                method ->
                    method.getName().equals("preparePreCandidateFailureChild")
                        || method
                            .getName()
                            .equals("preparePreCandidateFailureParentAndSeal"))
            .toList();

    assertEquals(2, methods.size());
    assertTrue(
        methods.stream()
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .noneMatch(Pack010AttributedFailureTerminalAcceptanceTest::isRawPayloadType));
    assertTrue(
        methods.stream()
            .allMatch(
                method ->
                    Arrays.asList(method.getParameterTypes()).contains(AgentRun.class)));
  }

  @Test
  void existingOpaqueCommandsRemainPrivateConstructorOneShotCapabilities() {
    for (String name :
        List.of(
            "Pack010FailedChildTerminalCommand",
            "Pack010FailedParentTerminalCommand")) {
      Class<?> command =
          Arrays.stream(Pack010PostgresRuntimeComposition.class.getDeclaredClasses())
              .filter(type -> type.getSimpleName().equals(name))
              .findFirst()
              .orElseThrow();
      Constructor<?>[] constructors = command.getDeclaredConstructors();
      assertTrue(constructors.length > 0);
      assertTrue(
          Arrays.stream(constructors)
              .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
      assertTrue(
          Arrays.stream(command.getDeclaredFields())
              .anyMatch(field -> field.getName().equals("consumed")));
    }
  }

  private static Method exactComposerMethod(String name) {
    List<Method> methods =
        Arrays.stream(Pack010ProviderSessionComposer.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .toList();
    assertEquals(1, methods.size(), name);
    return methods.getFirst();
  }

  private static Method exactRuntimeMethod(
      String name, Class<?> requiredTypedParameter) {
    List<Method> methods =
        Arrays.stream(Pack010PostgresRuntimeComposition.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .toList();
    assertEquals(1, methods.size(), name);
    assertTrue(
        Arrays.asList(methods.getFirst().getParameterTypes())
            .contains(requiredTypedParameter));
    return methods.getFirst();
  }

  private static boolean isRawPayloadType(Class<?> type) {
    return RAW_PAYLOAD_TYPES.contains(type)
        || type.getName().equals("com.fasterxml.jackson.databind.JsonNode")
        || type.getName().equals("tools.jackson.databind.JsonNode");
  }
}
