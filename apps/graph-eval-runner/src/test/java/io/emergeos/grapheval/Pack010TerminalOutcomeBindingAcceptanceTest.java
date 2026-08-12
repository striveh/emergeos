package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.postgres.PostgresGraphRuntimeWriters;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Acceptance gate for actual model outcome provenance before TX-B. */
class Pack010TerminalOutcomeBindingAcceptanceTest {

  private static final Set<Class<?>> RAW_PAYLOAD_TYPES =
      Set.of(String.class, byte[].class, Map.class);

  @Test
  void providerSessionReturnsOpaqueAttributedOutcomeNotPublicModelStep()
      throws Exception {
    Method next =
        Pack010ProviderSessionComposer.ProviderSession.class
            .getDeclaredMethod(
                "next",
                AgentModel.Turn.class,
                AgentModel.ModelCallContext.class);

    assertNotEquals(AgentModel.ModelStep.class, next.getReturnType());
    assertEquals(
        "Pack010AttributedModelOutcome",
        next.getReturnType().getSimpleName());
    assertFalse(Modifier.isPublic(next.getReturnType().getModifiers()));
    assertTrue(
        Arrays.stream(next.getReturnType().getDeclaredConstructors())
            .allMatch(
                constructor ->
                    Modifier.isPrivate(constructor.getModifiers())));
  }

  @Test
  void productionRuntimeHasOnlyTypedChildCommandAndNoRawStringAbi() {
    List<Method> childMethods =
        Arrays.stream(
                Pack010PostgresRuntimeComposition.class
                    .getDeclaredMethods())
            .filter(method -> method.getName().equals("completeChild"))
            .toList();

    assertEquals(1, childMethods.size());
    assertEquals(1, childMethods.getFirst().getParameterCount());
    assertNotEquals(
        String.class,
        childMethods.getFirst().getParameterTypes()[0]);
    assertEquals(
        "Pack010ChildTerminalCommand",
        childMethods.getFirst().getParameterTypes()[0].getSimpleName());
  }

  @Test
  void productionRuntimeHasOnlyTypedParentCommandAndNoRawStringAbi() {
    List<Method> parentMethods =
        Arrays.stream(
                Pack010PostgresRuntimeComposition.class
                    .getDeclaredMethods())
            .filter(
                method ->
                    method.getName().equals(
                        "completeParentAndSeal"))
            .toList();

    assertEquals(1, parentMethods.size());
    assertEquals(1, parentMethods.getFirst().getParameterCount());
    assertNotEquals(
        String.class,
        parentMethods.getFirst().getParameterTypes()[0]);
    assertEquals(
        "Pack010ParentTerminalCommand",
        parentMethods
            .getFirst()
            .getParameterTypes()[0]
            .getSimpleName());
  }

  @Test
  void productionPreparationAndWriterOwnCanonicalPayloadWithoutRawStringAbi() {
    List<Method> preparationMethods =
        Arrays.stream(
                Pack010PostgresRuntimeComposition.class
                    .getDeclaredMethods())
            .filter(
                method ->
                    method.getName().equals("prepareChild")
                        || method
                            .getName()
                            .equals("prepareParentAndSeal"))
            .toList();
    List<Method> writerTerminalMethods =
        Arrays.stream(PostgresGraphRuntimeWriters.class.getMethods())
            .filter(
                method ->
                    method.getName().equals("completeChild")
                        || method
                            .getName()
                            .equals("completeParentAndSeal"))
            .toList();

    assertEquals(2, preparationMethods.size());
    assertTrue(
        preparationMethods.stream()
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .noneMatch(Pack010TerminalOutcomeBindingAcceptanceTest::isRawPayloadType));
    assertEquals(2, writerTerminalMethods.size());
    assertTrue(
        writerTerminalMethods.stream()
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .noneMatch(Pack010TerminalOutcomeBindingAcceptanceTest::isRawPayloadType));
    assertTrue(
        Arrays.stream(PostgresGraphRuntimeWriters.class.getMethods())
            .noneMatch(method -> method.getName().equals("prefixWriter")));
  }

  @Test
  void terminalExecutorHasNoCallableRawPayloadOverload() throws Exception {
    Class<?> executor =
        Class.forName(
            "io.emergeos.adapters.postgres.PostgresGraphTerminalExecutor");

    assertTrue(
        Arrays.stream(executor.getDeclaredMethods())
            .filter(
                method ->
                    !Modifier.isPrivate(method.getModifiers())
                        && (method.getName().equals("completeChild")
                            || method
                                .getName()
                                .equals("completeParentAndSeal")))
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .noneMatch(Pack010TerminalOutcomeBindingAcceptanceTest::isRawPayloadType));
  }

  @Test
  void attributedOutcomeAndTerminalCommandsHaveNoPublicConstructor() {
    List<Class<?>> reviewed =
        List.of(
            Arrays.stream(
                    Pack010ProviderSessionComposer.class
                        .getDeclaredClasses())
                .filter(
                    type ->
                        type.getSimpleName().equals(
                            "Pack010AttributedModelOutcome"))
                .findFirst()
                .orElseThrow(),
            Arrays.stream(
                    Pack010PostgresRuntimeComposition.class
                        .getDeclaredClasses())
                .filter(
                    type ->
                        type.getSimpleName().equals(
                            "Pack010ChildTerminalCommand"))
                .findFirst()
                .orElseThrow(),
            Arrays.stream(
                    Pack010PostgresRuntimeComposition.class
                        .getDeclaredClasses())
                .filter(
                    type ->
                        type.getSimpleName().equals(
                            "Pack010ParentTerminalCommand"))
                .findFirst()
                .orElseThrow(),
            PostgresGraphRuntimeWriters.ChildTerminalTransition.class,
            PostgresGraphRuntimeWriters.ParentTerminalTransition.class);

    assertEquals(5, reviewed.size());
    for (Class<?> type : reviewed) {
      Constructor<?>[] constructors = type.getDeclaredConstructors();
      assertTrue(constructors.length > 0);
      assertTrue(
          Arrays.stream(constructors)
              .allMatch(
                  constructor ->
                      Modifier.isPrivate(
                          constructor.getModifiers())));
    }
  }

  @Test
  void parentCommandBindsExactRuntimeAndDurableChildTruth() {
    Class<?> command =
        Arrays.stream(
                Pack010PostgresRuntimeComposition.class
                    .getDeclaredClasses())
            .filter(
                type ->
                    type.getSimpleName().equals(
                        "Pack010ParentTerminalCommand"))
            .findFirst()
            .orElseThrow();
    Set<String> fields =
        Arrays.stream(command.getDeclaredFields())
            .map(field -> field.getName())
            .collect(Collectors.toUnmodifiableSet());

    assertEquals(
        Set.of(
            "owner",
            "terminalCapability",
            "coordinator",
            "egress",
            "revision",
            "manifest",
            "cursor",
            "attributionHashes",
            "candidateIntegrityHash",
            "workerResultIntegrityHash",
            "childTerminalHash",
            "parentBundleHash",
            "artifactHash",
            "transition",
            "consumed"),
        fields);
    Method prepare =
        Arrays.stream(
                Pack010PostgresRuntimeComposition.class
                    .getDeclaredMethods())
            .filter(
                method ->
                    method.getName().equals(
                        "prepareParentAndSeal"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        Arrays.stream(prepare.getParameterTypes())
            .noneMatch(Pack010TerminalOutcomeBindingAcceptanceTest::isRawPayloadType));
    assertEquals(command, prepare.getReturnType());
  }

  private static boolean isRawPayloadType(Class<?> type) {
    return RAW_PAYLOAD_TYPES.contains(type)
        || type.getName().equals("com.fasterxml.jackson.databind.JsonNode")
        || type.getName().equals("tools.jackson.databind.JsonNode");
  }
}
