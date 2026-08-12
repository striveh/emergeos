package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pack010ProviderCapabilityBytecodeGateTest {

  @TempDir Path tempDir;

  @Test
  void shippingJarOwnsDormantExactCredentialAndSessionCapabilities()
      throws Exception {
    Class<?> broker =
        Class.forName(
            "io.emergeos.grapheval.Pack010ProviderCredentialBroker");
    Class<?> composer =
        Class.forName(
            "io.emergeos.grapheval.Pack010ProviderSessionComposer");
    Class<?> runtimeComposition =
        Class.forName(
            "io.emergeos.grapheval.Pack010PostgresRuntimeComposition");
    assertNotNull(broker);
    assertNotNull(composer);
    assertPackagePrivateFinal(broker);
    assertPackagePrivateFinal(composer);
    assertPackagePrivateFinal(runtimeComposition);

    Class<?> lease =
        Class.forName(
            "io.emergeos.grapheval."
                + "Pack010ProviderCredentialBroker$CredentialLease");
    assertTrue(Modifier.isFinal(lease.getModifiers()));
    for (Constructor<?> constructor : lease.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    assertTrue(
        Stream.of(lease.getDeclaredMethods())
            .noneMatch(method -> Modifier.isPublic(method.getModifiers())));
    assertTrue(
        Stream.of(lease.getDeclaredFields())
            .anyMatch(field -> field.getName().equals("expiresAt")),
        "credential lease must retain the owner-approved expiry");
    assertTrue(
        Stream.of(lease.getDeclaredFields())
            .anyMatch(field ->
                field.getType().equals(
                    io.emergeos.adapters.postgres
                        .OwnerTtyGraphAuthority.class)),
        "credential lease must retain the exact owner authority");
    Class<?> providerSession =
        Class.forName(
            "io.emergeos.grapheval."
                + "Pack010ProviderSessionComposer$ProviderSession");
    assertTrue(
        Stream.of(providerSession.getDeclaredFields())
            .anyMatch(field -> field.getType().equals(lease)),
        "provider session must retain the exact credential lease");
    assertTrue(
        Stream.of(providerSession.getDeclaredFields())
            .anyMatch(field -> field.getType().equals(java.time.Clock.class)),
        "provider session must retain the exact expiry clock");

    for (Class<?> type : List.of(broker, composer)) {
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        assertFalse(Modifier.isPublic(constructor.getModifiers()));
        assertFalse(Modifier.isProtected(constructor.getModifiers()));
      }
      for (Method method : type.getDeclaredMethods()) {
        assertFalse(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isProtected(method.getModifiers()));
      }
    }

    Constructor<?>[] preflightTokens =
        Pack010GraphPreflight.Result.class.getDeclaredConstructors();
    assertEquals(1, preflightTokens.length);
    assertTrue(Modifier.isPrivate(preflightTokens[0].getModifiers()));
  }

  @Test
  void exactMemberReferencesHaveOneReviewedOwnerAndMainCannotReachThem()
      throws Exception {
    Map<String, List<GraphEvalBytecodeGate.MemberReference>> references =
        appMemberReferences();

    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters",
        "open",
        "(Ljavax/sql/DataSource;Ljavax/sql/DataSource;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority;)"
            + "Lio/emergeos/adapters/postgres/"
            + "PostgresGraphRuntimeWriters;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "bindTerminal",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$TerminalCapability;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;)"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "claimChildTerminal",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$TerminalCapability;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;)"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ChildTerminalClaim;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "claimParentTerminal",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$TerminalCapability;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;)"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ParentTerminalClaim;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters",
        "completeChild",
        "(Lio/emergeos/adapters/postgres/OwnerTtyGraphAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ChildTerminalClaim;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;"
            + "Lio/emergeos/adapters/postgres/"
            + "PostgresGraphRuntimeWriters$"
            + "ChildTerminalTransition;)Ljava/lang/String;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters",
        "completeParentAndSeal",
        "(Lio/emergeos/adapters/postgres/OwnerTtyGraphAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ParentTerminalClaim;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;"
            + "Lio/emergeos/adapters/postgres/"
            + "PostgresGraphRuntimeWriters$"
            + "ParentTerminalTransition;)Ljava/lang/String;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "open",
        "(Ljavax/sql/DataSource;Ljavax/sql/DataSource;"
            + "Lio/emergeos/adapters/postgres/OwnerTtyGraphAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$TerminalCapability;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "completeChild",
        "(Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010ChildTerminalCommand;)Ljava/lang/String;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "prepareParentAndSeal",
        "(Lio/emergeos/core/domain/AgentRun;"
            + "Lio/emergeos/core/domain/ArtifactLineage;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010ParentTerminalCommand;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "completeParentAndSeal",
        "(Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010ParentTerminalCommand;)Ljava/lang/String;",
        List.of());
    assertConsumers(
        references,
        "java/lang/System",
        "getenv",
        "(Ljava/lang/String;)Ljava/lang/String;",
        List.of(
            "Pack010ProviderCredentialBroker$CredentialLease.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "claimProviderSessionIntent",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ApprovedHandoff;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;"
            + "Lio/emergeos/core/domain/GraphProviderIntent;)"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ProviderSessionIntent;",
        List.of("Pack010ProviderCredentialBroker.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "consumeProviderSessionIntent",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ProviderSessionIntent;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ApprovedHandoff;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;)"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;",
        List.of("Pack010ProviderCredentialBroker.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "requireProviderSessionFresh",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ProviderSessionIntent;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;)"
            + "Ljava/time/Instant;",
        List.of(
            "Pack010ProviderCredentialBroker$CredentialLease.class",
            "Pack010ProviderCredentialBroker.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/OwnerTtyGraphAuthority",
        "consumeEgress",
        "(Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ApprovedHandoff;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;)"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;",
        List.of("Pack010ProviderCredentialBroker.class"));
    assertConsumers(
        references,
        "io/emergeos/core/application/GraphAttemptCoordinator",
        "credentialReadStarted",
        "(Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Instant;)V",
        List.of("Pack010ProviderCredentialBroker.class"));
    assertConsumers(
        references,
        "io/emergeos/core/application/GraphAttemptCoordinator",
        "requireEgressManifest",
        "(Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;)V",
        List.of(
            "Pack010ProviderCredentialBroker.class",
            "Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/grapheval/"
            + "Pack010ProviderCredentialBroker$CredentialLease",
        "claim",
        "(Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;)"
            + "Ljava/lang/String;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/grapheval/"
            + "Pack010ProviderCredentialBroker$CredentialLease",
        "requireFresh",
        "(Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;)V",
        List.of(
            "Pack010ProviderCredentialBroker$CredentialLease.class",
            "Pack010ProviderSessionComposer$ProviderSession.class",
            "Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/openai/ReviewedOpenAiClient",
        "defaultCodecNoRetry",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/net/Proxy;"
            + "Ljava/time/Duration;Lcom/openai/core/LogLevel;)"
            + "Lio/emergeos/adapters/openai/ReviewedOpenAiClient;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/openai/OpenAiResponsesModel",
        "withExactResponseAttribution",
        "(Lio/emergeos/core/application/ModelExecutionProfile;"
            + "Lio/emergeos/adapters/openai/ReviewedOpenAiClient;"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderInvocationObserver;"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ExactProviderAttributionObserver;)"
            + "Lio/emergeos/adapters/openai/OpenAiResponsesModel;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/adapters/openai/OpenAiResponsesModel",
        "withExactResponseOutcome",
        "(Lio/emergeos/core/application/ModelExecutionProfile;"
            + "Lio/emergeos/adapters/openai/ReviewedOpenAiClient;"
            + "Ljava/lang/String;"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderInvocationObserver;"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ExactProviderOutcomeObserver;)"
            + "Lio/emergeos/adapters/openai/OpenAiResponsesModel;",
        List.of("Pack010ProviderSessionComposer.class"));
    for (String method :
        List.of("clientCreated", "modelCreated", "providerIntent")) {
      assertConsumers(
          references,
          "io/emergeos/core/application/GraphAttemptCoordinator",
          method,
          method.equals("providerIntent")
              ? "(Lio/emergeos/core/application/"
                  + "GraphAttemptCoordinator$EgressAuthority;"
                  + "Lio/emergeos/core/domain/GraphProviderIntent;"
                  + "Ljava/time/Instant;)V"
              : "(Lio/emergeos/core/application/"
                  + "GraphAttemptCoordinator$EgressAuthority;"
                  + "Ljava/time/Instant;)V",
          List.of("Pack010ProviderSessionComposer.class"));
    }
    assertConsumers(
        references,
        "io/emergeos/core/application/GraphAttemptCoordinator",
        "providerFailureAttributed",
        "(Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/core/domain/GraphProviderAttribution;"
            + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
            + "Ljava/time/Instant;)V",
        List.of("Pack010ProviderSessionComposer.class"));

    assertConsumers(
        references,
        "java/net/Proxy",
        "NO_PROXY",
        "Ljava/net/Proxy;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/core/application/"
            + "ModelBoundReadOnlyWorkerExecutionProfile",
        "deadlineMs",
        "()J",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "java/time/Duration",
        "ofMillis",
        "(J)Ljava/time/Duration;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "com/openai/core/LogLevel",
        "OFF",
        "Lcom/openai/core/LogLevel;",
        List.of("Pack010ProviderSessionComposer.class"));

    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010ProviderCredentialBroker",
        "readAfterDurableEgress",
        "(Lio/emergeos/grapheval/Pack010GraphPreflight$Result;"
            + "Lio/emergeos/adapters/postgres/OwnerTtyGraphAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ApprovedHandoff;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;"
            + "Ljava/time/Clock;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderCredentialBroker$CredentialLease;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010ProviderSessionComposer",
        "compose",
        "(Lio/emergeos/grapheval/Pack010GraphPreflight$Result;"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderCredentialBroker$CredentialLease;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$ProviderSession;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010ProviderSessionComposer",
        "durableIntentObserver",
        "(Lio/emergeos/core/application/"
            + "ModelBoundReadOnlyWorkerExecutionProfile;I"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderCredentialBroker$CredentialLease;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;)"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderInvocationObserver;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010ProviderSessionComposer",
        "durableAttributionObserver",
        "(Lio/emergeos/core/application/"
            + "ModelBoundReadOnlyWorkerExecutionProfile;I"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;)"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ExactProviderAttributionObserver;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010ProviderSessionComposer",
        "durableAttributionObserver",
        "(Lio/emergeos/core/application/"
            + "ModelBoundReadOnlyWorkerExecutionProfile;I"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$OutcomeLedger;)"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ExactProviderAttributionObserver;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010ProviderSessionComposer",
        "durableOutcomeObserver",
        "(Lio/emergeos/core/application/"
            + "ModelBoundReadOnlyWorkerExecutionProfile;I"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Ljava/time/Clock;"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$OutcomeLedger;)"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ExactProviderOutcomeObserver;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/core/domain/GraphProviderAttribution",
        "create",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphPricingSnapshot;"
            + "JJJJJLjava/math/BigDecimal;)"
            + "Lio/emergeos/core/domain/GraphProviderAttribution;",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/core/application/GraphAttemptCoordinator",
        "providerAttributed",
        "(Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/core/domain/GraphProviderAttribution;"
            + "Ljava/time/Instant;)V",
        List.of("Pack010ProviderSessionComposer.class"));
    assertConsumers(
        references,
        "io/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$ProviderSession",
        "next",
        "(Lio/emergeos/adapters/agentloop/AgentModel$Turn;"
            + "Lio/emergeos/adapters/agentloop/"
            + "AgentModel$ModelCallContext;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$"
            + "Pack010AttributedModelOutcome;",
        List.of());
    String structuredFinalDescriptor =
        "(Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$"
            + "Pack010AttributedModelOutcome;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;"
            + "Lio/emergeos/contracts/HarnessCandidateEnvelope;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$StructuredFinalBinding;";
    for (String method :
        List.of("reviewStructuredFinal", "claimStructuredFinal")) {
      assertConsumers(
          references,
          "io/emergeos/grapheval/Pack010ProviderSessionComposer",
          method,
          structuredFinalDescriptor,
          List.of("Pack010PostgresRuntimeComposition.class"));
    }
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "prepareChild",
        "(Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$"
            + "Pack010AttributedModelOutcome;"
            + "Lio/emergeos/contracts/HarnessCandidateEnvelope;"
            + "Lio/emergeos/core/domain/AgentRun;"
            + "Lio/emergeos/contracts/WorkerResultEnvelope;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010ChildTerminalCommand;",
        List.of());
    String attributedFailureDescriptor =
        "(Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$"
            + "Pack010AttributedModelOutcome;"
            + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
            + "Lio/emergeos/core/application/"
            + "GraphAttemptCoordinator$EgressAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$Pack010Revision;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$"
            + "AttributedPreCandidateFailure;";
    for (String method :
        List.of(
            "reviewAttributedPreCandidateFailure",
            "claimAttributedPreCandidateFailure")) {
      assertConsumers(
          references,
          "io/emergeos/grapheval/Pack010ProviderSessionComposer",
          method,
          attributedFailureDescriptor,
          List.of("Pack010PostgresRuntimeComposition.class"));
    }
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "preparePreCandidateFailureChild",
        "(Lio/emergeos/grapheval/"
            + "Pack010ProviderSessionComposer$"
            + "Pack010AttributedModelOutcome;"
            + "Lio/emergeos/core/domain/AgentRun;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010FailedChildTerminalCommand;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "completePreCandidateFailureChild",
        "(Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010FailedChildTerminalCommand;)Ljava/lang/String;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "preparePreCandidateFailureParentAndSeal",
        "(Lio/emergeos/core/domain/AgentRun;)"
            + "Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010FailedParentTerminalCommand;",
        List.of());
    assertConsumers(
        references,
        "io/emergeos/grapheval/Pack010PostgresRuntimeComposition",
        "completePreCandidateFailureParentAndSeal",
        "(Lio/emergeos/grapheval/"
            + "Pack010PostgresRuntimeComposition$"
            + "Pack010FailedParentTerminalCommand;)Ljava/lang/String;",
        List.of());
    for (String method :
        List.of(
            "preparePreCandidateFailureChild",
            "preparePreCandidateFailureParentAndSeal")) {
      assertConsumers(
          references,
          "io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters",
          method,
          "(Lio/emergeos/core/domain/GraphAttemptManifest;"
              + "Lio/emergeos/core/domain/AgentRun;)"
              + "Lio/emergeos/adapters/postgres/"
              + "PostgresGraphRuntimeWriters$"
              + (method.endsWith("Child")
                  ? "ChildFailureTransition;"
                  : "ParentFailureTransition;"),
          List.of("Pack010PostgresRuntimeComposition.class"));
    }
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters",
        "completePreCandidateFailureChild",
        "(Lio/emergeos/adapters/postgres/OwnerTtyGraphAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ChildTerminalClaim;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;"
            + "Lio/emergeos/adapters/postgres/"
            + "PostgresGraphRuntimeWriters$ChildFailureTransition;)"
            + "Ljava/lang/String;",
        List.of("Pack010PostgresRuntimeComposition.class"));
    assertConsumers(
        references,
        "io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters",
        "completePreCandidateFailureParentAndSeal",
        "(Lio/emergeos/adapters/postgres/OwnerTtyGraphAuthority;"
            + "Lio/emergeos/adapters/postgres/"
            + "OwnerTtyGraphAuthority$ParentTerminalClaim;"
            + "Lio/emergeos/core/domain/GraphAttemptManifest;"
            + "Lio/emergeos/adapters/postgres/"
            + "PostgresGraphRuntimeWriters$ParentFailureTransition;)"
            + "Ljava/lang/String;",
        List.of("Pack010PostgresRuntimeComposition.class"));

    assertNoOwner(references, "com/openai/client/okhttp/OpenAIOkHttpClient");
    assertNoMember(references, "com/openai/client/OpenAIClient", "responses");
    assertNoMember(references, "java/lang/Class", "forName");
    assertNoMember(references, "java/lang/ClassLoader", "loadClass");
    assertNoOwner(references, "java/lang/invoke/MethodHandles");
    assertNoOwner(references, "java/lang/invoke/MethodHandle");
    assertNoOwner(references, "java/lang/invoke/ConstantBootstraps");
    assertConsumers(
        references,
        "java/lang/reflect/Method",
        "invoke",
        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
        List.of("HarnessEvaluationReportJson.class"));
    assertTrue(
        references.get("GraphEvalMain.class").stream()
            .noneMatch(
                reference ->
                    reference.owner().contains(
                            "Pack010ProviderCredentialBroker")
                        || reference.owner().contains(
                            "Pack010ProviderSessionComposer")
                        || reference.owner().contains(
                            "Pack010PostgresRuntimeComposition")));
  }

  @Test
  void compiledInvocationOrderConsumesHandoffAndPersistsBeforeEffects()
      throws Exception {
    String broker = javap("Pack010ProviderCredentialBroker");
    assertOrdered(
        broker,
        "OwnerTtyGraphAuthority.claimProviderSessionIntent:",
        "OwnerTtyGraphAuthority.consumeProviderSessionIntent:",
        "OwnerTtyGraphAuthority.requireProviderSessionFresh:",
        "OwnerTtyGraphAuthority.consumeEgress:",
        "GraphAttemptCoordinator.requireEgressManifest:",
        "GraphAttemptCoordinator.credentialReadStarted:");

    String lease =
        javap("Pack010ProviderCredentialBroker$CredentialLease");
    assertOrdered(
        lease,
        "Method requireFresh:",
        "java/lang/System.getenv:");
    assertTrue(
        lease.indexOf(
            "OwnerTtyGraphAuthority.requireProviderSessionFresh:")
            >= 0);

    String composer = javap("Pack010ProviderSessionComposer");
    assertOrdered(
        composer,
        "CredentialLease.claim:",
        "ReviewedOpenAiClient.defaultCodecNoRetry:",
        "GraphAttemptCoordinator.clientCreated:",
        "OpenAiResponsesModel.withExactResponseOutcome:",
        "GraphAttemptCoordinator.modelCreated:",
        "OpenAiResponsesModel.open:");
    assertTrue(
        composer.indexOf("GraphAttemptCoordinator.providerIntent:")
            >= 0);
    String providerSession =
        javap("Pack010ProviderSessionComposer$ProviderSession");
    assertOrdered(
        providerSession,
        "CredentialLease.requireFresh:",
        "AgentModel$Session.next:");
    String observerLambda =
        composer.substring(
            composer.indexOf("lambda$durableIntentObserver$0"));
    assertOrdered(
        observerLambda,
        "CredentialLease.requireFresh:",
        "AtomicInteger.getAndIncrement:",
        "GraphAttemptCoordinator.providerIntent:");
    String outcomeLambda =
        composer.substring(
            composer.indexOf("lambda$durableOutcomeObserver$2"));
    assertOrdered(
        outcomeLambda,
        "GraphProviderAttribution.create:",
        "GraphAttemptCoordinator.providerFailureAttributed:");
    assertTrue(
        outcomeLambda.indexOf("GraphAttemptCoordinator.providerAttributed:")
            >= 0);
    assertOrdered(
        composer,
        "String https://api.openai.com/v1",
        "Field java/net/Proxy.NO_PROXY:Ljava/net/Proxy;",
        "ModelBoundReadOnlyWorkerExecutionProfile.deadlineMs:()J",
        "java/time/Duration.ofMillis:(J)Ljava/time/Duration;",
        "Field com/openai/core/LogLevel.OFF:Lcom/openai/core/LogLevel;",
        "ReviewedOpenAiClient.defaultCodecNoRetry:");
    assertFalse(composer.contains("OpenAIClient.responses:"));
    assertFalse(composer.contains("ResponseService.create:"));

    String runtimeComposition =
        javap("Pack010PostgresRuntimeComposition");
    assertOrdered(
        runtimeComposition,
        "PostgresGraphRuntimeWriters.open:",
        "OwnerTtyGraphAuthority.bindTerminal:",
        "Method requireCheckpoint:",
        "OwnerTtyGraphAuthority.claimChildTerminal:",
        "PostgresGraphRuntimeWriters.completeChild:",
        "Method requireCheckpoint:",
        "OwnerTtyGraphAuthority.claimParentTerminal:",
        "PostgresGraphRuntimeWriters.completeParentAndSeal:");

    String reviewedClient =
        javap(
            ReviewedOpenAiClient.class.getName(),
            Path.of(
                ReviewedOpenAiClient.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()));
    assertTrue(
        reviewedClient.matches(
            "(?s).*iconst_0\\s+\\d+: invokevirtual .*"
                + "OpenAIOkHttpClient\\$Builder.maxRetries:"
                + "\\(I\\).*"),
        "reviewed client must compile maxRetries(0)");
  }

  @Test
  void negativeFixtureProvesExactMemberParserSeesCalls()
      throws Exception {
    Path source = tempDir.resolve("ExactCalls.java");
    Path classes = tempDir.resolve("classes");
    Files.createDirectories(classes);
    Files.writeString(
        source,
        """
        final class ExactCalls {
          String read() { return System.getenv("SYNTHETIC_NAME"); }
        }
        """,
        StandardCharsets.UTF_8);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-d",
                classes.toString(),
                source.toString()));
    List<GraphEvalBytecodeGate.MemberReference> parsed =
        GraphEvalBytecodeGate.memberReferences(
            Files.readAllBytes(classes.resolve("ExactCalls.class")));
    assertTrue(
        parsed.stream()
            .anyMatch(
                reference ->
                    reference.owner().equals("java/lang/System")
                        && reference.name().equals("getenv")
                        && reference.descriptor().equals(
                            "(Ljava/lang/String;)Ljava/lang/String;")));
  }

  @Test
  void directoryAndJarRejectEveryUnreviewedFailureCapabilityConsumer()
      throws Exception {
    Path sourceRoot = tempDir.resolve("failure-consumer-src");
    Path output = tempDir.resolve("failure-consumer-classes");
    Path source =
        sourceRoot.resolve(
            "io/emergeos/grapheval/InjectedFailureConsumer.java");
    Path adapterSource =
        sourceRoot.resolve(
            "io/emergeos/adapters/postgres/"
                + "InjectedWriterFailureConsumer.java");
    Files.createDirectories(source.getParent());
    Files.createDirectories(adapterSource.getParent());
    Files.createDirectories(output);
    Files.writeString(
        source,
        """
        package io.emergeos.grapheval;

        import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
        import io.emergeos.adapters.postgres.PostgresGraphRuntimeWriters;
        import io.emergeos.adapters.openai.OpenAiResponsesModel;
        import io.emergeos.core.application.GraphAttemptCoordinator;
        import io.emergeos.core.domain.AgentRun;
        import io.emergeos.core.domain.GraphAttemptManifest;

        final class InjectedFailureConsumer {
          void consume(
              Pack010ProviderSessionComposer.Pack010AttributedModelOutcome outcome,
              GraphAttemptCoordinator coordinator,
              GraphAttemptCoordinator.EgressAuthority egress,
              OwnerTtyGraphAuthority.Pack010Revision revision,
              GraphAttemptManifest manifest,
              AgentRun run,
              Pack010PostgresRuntimeComposition runtime,
              PostgresGraphRuntimeWriters writers,
              OwnerTtyGraphAuthority owner,
              OwnerTtyGraphAuthority.ChildTerminalClaim childClaim,
              OwnerTtyGraphAuthority.ParentTerminalClaim parentClaim) {
            Pack010ProviderSessionComposer.reviewAttributedPreCandidateFailure(
                outcome, coordinator, egress, revision, manifest);
            Pack010ProviderSessionComposer.claimAttributedPreCandidateFailure(
                outcome, coordinator, egress, revision, manifest);
            runtime.preparePreCandidateFailureChild(outcome, run);
            runtime.completePreCandidateFailureChild(null);
            runtime.preparePreCandidateFailureParentAndSeal(run);
            runtime.completePreCandidateFailureParentAndSeal(null);
            writers.preparePreCandidateFailureChild(manifest, run);
            writers.preparePreCandidateFailureParentAndSeal(manifest, run);
            writers.completePreCandidateFailureChild(
                owner, childClaim, manifest, null);
            writers.completePreCandidateFailureParentAndSeal(
                owner, parentClaim, manifest, null);
            OpenAiResponsesModel.withExactResponseOutcome(
                null, null, null, null, null);
            coordinator.providerFailureAttributed(
                egress, null, null, null);
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        adapterSource,
        """
        package io.emergeos.adapters.postgres;

        import io.emergeos.core.domain.AgentRun;
        import io.emergeos.core.domain.GraphAttemptManifest;
        import io.emergeos.core.port.GraphAttemptStore;
        import javax.sql.DataSource;

        final class InjectedWriterFailureConsumer {
          void consume(
              PostgresGraphRuntimeWriters writers,
              GraphAttemptManifest manifest,
              AgentRun run,
              OwnerTtyGraphAuthority owner,
              OwnerTtyGraphAuthority.ChildTerminalClaim childClaim,
              OwnerTtyGraphAuthority.ParentTerminalClaim parentClaim,
              DataSource dataSource,
              GraphAttemptStore graphStore,
              PostgresGraphAttemptStore concreteStore,
              PostgresAttributedFailureResumeStore.DurableFailureCursor cursor) {
            writers.preparePreCandidateFailureChild(manifest, run);
            writers.preparePreCandidateFailureParentAndSeal(manifest, run);
            writers.completePreCandidateFailureChild(
                owner, childClaim, manifest, null);
            writers.completePreCandidateFailureParentAndSeal(
                owner, parentClaim, manifest, null);
            PostgresAttributedFailureResumeStore resume =
                new PostgresAttributedFailureResumeStore(dataSource);
            resume.load(manifest);
            resume.claim(manifest, cursor, null, null, null);
            resume.completeClaimedFailureChild(
                manifest, cursor, dataSource, run);
            resume.completeClaimedFailureParentAndSeal(
                manifest, cursor, dataSource, run);
            graphStore.providerFailureAttributed(
                manifest, null, null, null, null);
            concreteStore.providerFailureAttributed(
                manifest, null, null, null, null);
          }
        }
        """,
        StandardCharsets.UTF_8);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                output.toString(),
                source.toString(),
                adapterSource.toString()));
    byte[] injected =
        Files.readAllBytes(
            output.resolve(
                "io/emergeos/grapheval/"
                    + "InjectedFailureConsumer.class"));
    byte[] injectedAdapter =
        Files.readAllBytes(
            output.resolve(
                "io/emergeos/adapters/postgres/"
                    + "InjectedWriterFailureConsumer.class"));
    String reviewedLogicalName =
        "io/emergeos/grapheval/"
            + "Pack010ProviderCredentialBroker.class";
    Path directory = tempDir.resolve("failure-consumer-directory");
    Path reviewedPath = directory.resolve(reviewedLogicalName);
    Files.createDirectories(reviewedPath.getParent());
    Files.write(reviewedPath, injected);
    String adapterLogicalName =
        "io/emergeos/adapters/postgres/"
            + "InjectedWriterFailureConsumer.class";
    Path adapterPath = directory.resolve(adapterLogicalName);
    Files.createDirectories(adapterPath.getParent());
    Files.write(adapterPath, injectedAdapter);
    List<String> expectedMembers =
        List.of(
            "reviewAttributedPreCandidateFailure",
            "claimAttributedPreCandidateFailure",
            "preparePreCandidateFailureChild",
            "completePreCandidateFailureChild",
            "preparePreCandidateFailureParentAndSeal",
            "completePreCandidateFailureParentAndSeal",
            "withExactResponseOutcome",
            "providerFailureAttributed");
    assertUnreviewedFailureConsumers(
        GraphEvalBytecodeGate.directoryViolations(directory),
        expectedMembers);
    List<String> expectedWriterMembers =
        List.of(
            "preparePreCandidateFailureChild",
            "completePreCandidateFailureChild",
            "preparePreCandidateFailureParentAndSeal",
            "completePreCandidateFailureParentAndSeal",
            "<init>",
            "load",
            "claim",
            "completeClaimedFailureChild",
            "completeClaimedFailureParentAndSeal",
            "io/emergeos/core/port/GraphAttemptStore."
                + "providerFailureAttributed("
                + "Lio/emergeos/core/domain/GraphAttemptManifest;"
                + "Lio/emergeos/core/domain/GraphAttemptCursor;"
                + "Lio/emergeos/core/domain/GraphProviderAttribution;"
                + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
                + "Ljava/time/Instant;)"
                + "Lio/emergeos/core/domain/GraphAttemptCursor;",
            "io/emergeos/adapters/postgres/PostgresGraphAttemptStore."
                + "providerFailureAttributed("
                + "Lio/emergeos/core/domain/GraphAttemptManifest;"
                + "Lio/emergeos/core/domain/GraphAttemptCursor;"
                + "Lio/emergeos/core/domain/GraphProviderAttribution;"
                + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
                + "Ljava/time/Instant;)"
                + "Lio/emergeos/core/domain/GraphAttemptCursor;");
    assertUnreviewedFailureConsumers(
        GraphEvalBytecodeGate.directoryViolations(directory),
        adapterLogicalName,
        expectedWriterMembers);

    Path jarPath = tempDir.resolve("failure-consumer.jar");
    try (JarOutputStream jar =
        new JarOutputStream(Files.newOutputStream(jarPath))) {
      jar.putNextEntry(new JarEntry(reviewedLogicalName));
      jar.write(injected);
      jar.closeEntry();
      jar.putNextEntry(new JarEntry(adapterLogicalName));
      jar.write(injectedAdapter);
      jar.closeEntry();
    }
    assertUnreviewedFailureConsumers(
        GraphEvalBytecodeGate.shippingJarViolations(jarPath),
        expectedMembers);
    assertUnreviewedFailureConsumers(
        GraphEvalBytecodeGate.shippingJarViolations(jarPath),
        adapterLogicalName,
        expectedWriterMembers);
  }

  @Test
  void reviewedFirstPartyReflectionRequiresExactConsumerAndMember()
      throws Exception {
    String canonicalName =
        "io/emergeos/contracts/CanonicalEncoding.class";
    String ownerTypesName =
        "io/emergeos/core/application/"
            + "GraphAttemptCoordinator$OwnerAuthorityTypes.class";
    byte[] canonical = classResourceBytes(canonicalName);
    byte[] ownerTypes = classResourceBytes(ownerTypesName);
    var methodInvoke =
        new GraphEvalBytecodeGate.MemberReference(
            GraphEvalBytecodeGate.MemberKind.METHOD,
            "java/lang/reflect/Method",
            "invoke",
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    var classForName =
        new GraphEvalBytecodeGate.MemberReference(
            GraphEvalBytecodeGate.MemberKind.METHOD,
            "java/lang/Class",
            "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");

    assertEquals(
        List.of(methodInvoke),
        GraphEvalBytecodeGate.memberReferences(canonical).stream()
            .filter(reference ->
                reference.owner().equals("java/lang/reflect/Method"))
            .toList());
    assertEquals(
        List.of(classForName),
        GraphEvalBytecodeGate.memberReferences(ownerTypes).stream()
            .filter(reference ->
                reference.owner().equals("java/lang/Class")
                    && reference.name().equals("forName"))
            .toList());
    assertNoIndirectViolation(
        GraphEvalBytecodeGate.classViolations(canonicalName, canonical));
    assertNoIndirectViolation(
        GraphEvalBytecodeGate.classViolations(ownerTypesName, ownerTypes));

    assertIndirectViolation(
        GraphEvalBytecodeGate.classViolations(ownerTypesName, canonical),
        "java/lang/reflect/Method.invoke");
    assertIndirectViolation(
        GraphEvalBytecodeGate.classViolations(canonicalName, ownerTypes),
        "java/lang/Class.forName");
    assertIndirectViolation(
        GraphEvalBytecodeGate.classViolations(
            "io/emergeos/contracts/CanonicalEncodingCopy.class", canonical),
        "java/lang/reflect/Method.invoke");
    assertIndirectViolation(
        GraphEvalBytecodeGate.classViolations(
            "io/emergeos/core/application/"
                + "GraphAttemptCoordinator$OtherAuthorityTypes.class",
            ownerTypes),
        "java/lang/Class.forName");
  }

  @Test
  void negativeFixtureRejectsFirstPartyAdapterReflectionAroundResumeSink()
      throws Exception {
    Path source = tempDir.resolve("InjectedReflectiveResumeConsumer.java");
    Path classes = tempDir.resolve("reflective-resume-classes");
    Files.createDirectories(classes);
    Files.writeString(
        source,
        """
        package io.emergeos.adapters.postgres;

        import io.emergeos.core.domain.AgentRun;
        import io.emergeos.core.domain.GraphAttemptManifest;
        import javax.sql.DataSource;

        final class InjectedReflectiveResumeConsumer {
          Object consume(
              PostgresAttributedFailureResumeStore resume,
              GraphAttemptManifest manifest,
              PostgresAttributedFailureResumeStore.DurableFailureCursor cursor,
              DataSource reader,
              AgentRun run) throws Exception {
            return resume.getClass()
                .getMethod(
                    "completeClaimedFailureChild",
                    GraphAttemptManifest.class,
                    PostgresAttributedFailureResumeStore
                        .DurableFailureCursor.class,
                    DataSource.class,
                    AgentRun.class)
                .invoke(resume, manifest, cursor, reader, run);
          }
        }
        """,
        StandardCharsets.UTF_8);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                source.toString()));
    String logicalName =
        "io/emergeos/adapters/postgres/"
            + "InjectedReflectiveResumeConsumer.class";
    Path classFile = classes.resolve(logicalName);
    List<String> directoryViolations =
        GraphEvalBytecodeGate.directoryViolations(classes);
    assertReflectiveResumeRejected(directoryViolations, logicalName);

    Path jarPath = tempDir.resolve("reflective-resume-consumer.jar");
    try (JarOutputStream jar =
        new JarOutputStream(Files.newOutputStream(jarPath))) {
      jar.putNextEntry(new JarEntry(logicalName));
      jar.write(Files.readAllBytes(classFile));
      jar.closeEntry();
    }
    assertReflectiveResumeRejected(
        GraphEvalBytecodeGate.shippingJarViolations(jarPath), logicalName);
  }

  @Test
  void v13AttestationUsesOnlyReviewedPublicVerificationCapabilities()
      throws Exception {
    String attestorName =
        "io/emergeos/adapters/postgres/"
            + "PostgresProviderValidationAttestor.class";
    String attestationName =
        "io/emergeos/core/domain/"
            + "GraphProviderValidationAttestation.class";
    String reviewedClientName =
        "io/emergeos/adapters/openai/ReviewedOpenAiClient.class";
    String deepSeekProbeName =
        "io/emergeos/adapters/openai/"
            + "DeepSeekV4FlashResponsesProbe.class";
    String modelName =
        "io/emergeos/adapters/openai/OpenAiResponsesModel.class";
    String responsesSessionName =
        "io/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ResponsesSession.class";
    String outcomeReceiptName =
        "io/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderOutcomeReceipt.class";
    String validationStatementsName =
        "io/emergeos/adapters/openai/"
            + "OpenAiProviderValidationStatements.class";
    byte[] attestor = classResourceBytes(attestorName);
    byte[] attestation = classResourceBytes(attestationName);
    byte[] deepSeekProbe = classResourceBytes(deepSeekProbeName);

    for (String logicalName :
        List.of(
            attestorName,
            attestationName,
            reviewedClientName,
            deepSeekProbeName,
            modelName,
            responsesSessionName,
            outcomeReceiptName,
            validationStatementsName)) {
      assertEquals(
          List.of(),
          GraphEvalBytecodeGate.classViolations(
              logicalName, classResourceBytes(logicalName)),
          logicalName);
    }

    List<GraphEvalBytecodeGate.MemberReference> attestorReferences =
        GraphEvalBytecodeGate.memberReferences(attestor);
    assertReference(
        attestorReferences,
        "io/emergeos/core/port/GraphProviderValidationSigner",
        "attest",
        "(Lio/emergeos/core/domain/"
            + "GraphProviderValidationTranscript;)"
            + "Lio/emergeos/core/domain/"
            + "GraphProviderValidationAttestation;");
    assertReference(
        attestorReferences,
        "io/emergeos/core/domain/GraphProviderValidationTranscript",
        "create",
        "(Lio/emergeos/core/domain/GraphProviderValidationChallenge;"
            + "Lio/emergeos/core/domain/GraphAttemptCursor;"
            + "Lio/emergeos/core/domain/GraphProviderAttribution;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;"
            + "Lio/emergeos/core/domain/"
            + "GraphProviderValidationDecision;Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphAttributedFailureCode;)"
            + "Lio/emergeos/core/domain/"
            + "GraphProviderValidationTranscript;");
    assertReference(
        attestorReferences,
        "io/emergeos/core/domain/GraphProviderValidationAttestation",
        "verifiesWith",
        "([B)Z");

    List<GraphEvalBytecodeGate.MemberReference> attestationReferences =
        GraphEvalBytecodeGate.memberReferences(attestation);
    assertReference(
        attestationReferences,
        "java/security/Signature",
        "initVerify",
        "(Ljava/security/PublicKey;)V");
    assertReference(
        attestationReferences,
        "java/security/Signature",
        "verify",
        "([B)Z");
    assertTrue(
        attestationReferences.stream()
            .noneMatch(
                reference ->
                    reference.owner().equals("java/security/Signature")
                        && (reference.name().equals("initSign")
                            || reference.name().equals("sign"))));
    assertReference(
        GraphEvalBytecodeGate.memberReferences(
            classResourceBytes(responsesSessionName)),
        "io/emergeos/adapters/openai/ReviewedOpenAiClient",
        "createReviewedResponse",
        "(Lcom/openai/models/responses/ResponseCreateParams;"
            + "Lcom/openai/core/RequestOptions;)"
            + "Lio/emergeos/adapters/openai/"
            + "ReviewedOpenAiClient$ReviewedResponse;");
    assertReference(
        GraphEvalBytecodeGate.memberReferences(
            deepSeekProbe),
        "io/emergeos/adapters/openai/ReviewedOpenAiClient",
        "defaultCodecNoRetryNoRedirect",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/net/Proxy;"
            + "Ljava/time/Duration;Lcom/openai/core/LogLevel;)"
            + "Lio/emergeos/adapters/openai/ReviewedOpenAiClient;");
    assertReference(
        GraphEvalBytecodeGate.memberReferences(
            deepSeekProbe),
        "io/emergeos/adapters/openai/ReviewedOpenAiClient",
        "createReviewedResponse",
        "(Lcom/openai/models/responses/ResponseCreateParams;"
            + "Lcom/openai/core/RequestOptions;)"
            + "Lio/emergeos/adapters/openai/"
            + "ReviewedOpenAiClient$ReviewedResponse;");
    byte[] endpointDrift = deepSeekProbe.clone();
    replaceExactUtf8(
        endpointDrift,
        "https://api.deepseek.com",
        "https://bad.deepseek.com");
    assertTrue(
        GraphEvalBytecodeGate.classViolations(
                deepSeekProbeName, endpointDrift)
            .stream()
            .anyMatch(
                value ->
                    value.contains(
                        "capability:deepseek-production-url-drift")));
    byte[] modelDrift = deepSeekProbe.clone();
    replaceExactUtf8(
        modelDrift,
        "deepseek-v4-flash",
        "deepseek-v4-proxx");
    assertTrue(
        GraphEvalBytecodeGate.classViolations(
                deepSeekProbeName, modelDrift)
            .stream()
            .anyMatch(
                value ->
                    value.contains("capability:deepseek-model-drift")));
    assertReference(
        GraphEvalBytecodeGate.memberReferences(
            classResourceBytes(responsesSessionName)),
        "io/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderOutcomeReceipt",
        "<init>",
        "(Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderAttributionReceipt;"
            + "Ljava/lang/String;"
            + "Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderOutcomeKind;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    assertReference(
        GraphEvalBytecodeGate.memberReferences(
            classResourceBytes(validationStatementsName)),
        "io/emergeos/core/domain/GraphProviderValidationStatement",
        "<init>",
        "(Lio/emergeos/core/domain/GraphProviderAttribution;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;"
            + "Lio/emergeos/core/domain/"
            + "GraphProviderValidationDecision;Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphAttributedFailureCode;)V");

    Map<String, List<GraphEvalBytecodeGate.MemberReference>> app =
        appMemberReferences();
    assertConsumers(
        app,
        "io/emergeos/adapters/postgres/"
            + "PostgresProviderValidationAttestor",
        "open",
        "(Ljavax/sql/DataSource;)"
            + "Lio/emergeos/adapters/postgres/"
            + "PostgresProviderValidationAttestor;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/adapters/postgres/"
            + "PostgresProviderValidationAttestor",
        "completeValidation",
        "(Lio/emergeos/core/domain/GraphAttemptManifest;"
            + "Lio/emergeos/core/domain/GraphAttemptCursor;"
            + "Lio/emergeos/core/domain/"
            + "GraphProviderValidationStatement;"
            + "Lio/emergeos/core/port/GraphProviderValidationSigner;)"
            + "Lio/emergeos/core/domain/GraphAttemptCursor;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/adapters/openai/"
            + "OpenAiProviderValidationStatements",
        "policy",
        "()Lio/emergeos/adapters/openai/"
            + "OpenAiProviderValidationStatements$Policy;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/adapters/openai/"
            + "OpenAiProviderValidationStatements",
        "fromExactOutcome",
        "(Lio/emergeos/adapters/openai/"
            + "OpenAiResponsesModel$ProviderOutcomeReceipt;"
            + "Lio/emergeos/core/domain/GraphProviderAttribution;"
            + "Ljava/lang/String;)"
            + "Lio/emergeos/core/domain/"
            + "GraphProviderValidationStatement;",
        List.of());
  }

  @Test
  void v17VerifierIsDormantAndUsesOnlyReviewedPublicVerification()
      throws Exception {
    String challengeName =
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge.class";
    String verifierName =
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderSignatureVerifier.class";
    byte[] challenge = classResourceBytes(challengeName);
    byte[] verifier = classResourceBytes(verifierName);

    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.classViolations(
            challengeName, challenge),
        challengeName);
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.classViolations(
            verifierName, verifier),
        verifierName);

    List<GraphEvalBytecodeGate.MemberReference> references =
        GraphEvalBytecodeGate.memberReferences(verifier);
    assertReference(
        references,
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge",
        "keyFingerprint",
        "()Ljava/lang/String;");
    assertReference(
        references,
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge",
        "signatureMaterial",
        "()[B");
    assertReference(
        references,
        "java/security/MessageDigest",
        "getInstance",
        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    assertReference(
        references,
        "java/security/MessageDigest",
        "isEqual",
        "([B[B)Z");
    for (String member :
        List.of(
            "getInstance",
            "initVerify",
            "update",
            "verify")) {
      assertTrue(
          references.stream()
              .anyMatch(
                  reference ->
                      reference.owner().equals("java/security/Signature")
                          && reference.name().equals(member)),
          member);
    }
    assertTrue(
        references.stream()
            .noneMatch(
                reference ->
                    reference.owner().equals("java/security/Signature")
                        && (reference.name().equals("initSign")
                            || reference.name().equals("sign"))));
    assertTrue(
        references.stream()
            .noneMatch(
                reference ->
                    reference.owner().equals("java/security/KeyFactory")
                        && reference.name().equals("generatePrivate")));

    Path mainClasses = classesRoot();
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.directoryViolations(mainClasses));
  }

  @Test
  void v18TypedAttestorIsTheOnlyReviewedV16MutationAuthorityConsumer()
      throws Exception {
    String adapterName =
        "io/emergeos/adapters/postgres/"
            + "PostgresExactPicoProviderValidationAttestor.class";
    byte[] adapter = classResourceBytes(adapterName);
    assertEquals(
        List.of(),
        GraphEvalBytecodeGate.classViolations(adapterName, adapter),
        adapterName);
    List<GraphEvalBytecodeGate.MemberReference> references =
        GraphEvalBytecodeGate.memberReferences(adapter);
    assertReference(
        references,
        "io/emergeos/core/port/GraphExactPicoProviderValidationSigner",
        "sign",
        "(Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge;)"
            + "Ljava/lang/String;");
    assertReference(
        references,
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderSignatureVerifier",
        "verifyOrThrow",
        "(Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge;[B"
            + "Ljava/lang/String;)V");
    assertReference(
        references,
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJJ"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/util/UUID;Ljava/time/Instant;"
            + "Ljava/time/Instant;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphProviderValidationDecision;"
            + "Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
            + "Ljava/lang/String;Ljava/lang/String;)V");
    assertReference(
        references,
        "io/emergeos/core/domain/GraphExactPicoProviderValidationReceipt",
        "<init>",
        "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationReceipt$ValidationState;)V");
    for (String authority :
        List.of(
            "agent_graph_stage_exact_tx_a_v16",
            "agent_graph_commit_exact_tx_a_v16",
            "agent_graph_assert_exact_tx_a_overlay_v16",
            "agent_graph_exact_provider_validations_v16",
            "agent_graph_exact_provider_attributions_v16",
            "agent_graph_exact_attempt_events_v16",
            "agent_graph_exact_attempt_heads_v16",
            "emergeos_provider_attestor_v16")) {
      assertTrue(
          GraphEvalBytecodeGate.utf8Constants(adapter).stream()
              .anyMatch(value -> value.contains(authority)),
          authority);
    }
    for (String prerequisiteRelation :
        List.of(
            "agent_graph_provider_validation_keys",
            "agent_graph_provider_validations",
            "agent_graph_provider_profiles_v14",
            "agent_graph_exact_tx_a_requirements_v15")) {
      assertTrue(
          GraphEvalBytecodeGate.utf8Constants(adapter).stream()
              .anyMatch(value -> value.contains(prerequisiteRelation)),
          prerequisiteRelation);
    }
    Map<String, List<GraphEvalBytecodeGate.MemberReference>> app =
        appMemberReferences();
    assertConsumers(
        app,
        "io/emergeos/adapters/postgres/"
            + "PostgresExactPicoProviderValidationAttestor",
        "open",
        "(Ljavax/sql/DataSource;)Lio/emergeos/adapters/postgres/"
            + "PostgresExactPicoProviderValidationAttestor;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/adapters/postgres/"
            + "PostgresExactPicoProviderValidationAttestor",
        "complete",
        "(Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationCommand;"
            + "Lio/emergeos/core/port/"
            + "GraphExactPicoProviderValidationSigner;)"
            + "Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationReceipt;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/core/port/GraphExactPicoProviderValidationAttestor",
        "complete",
        "(Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationCommand;"
            + "Lio/emergeos/core/port/"
            + "GraphExactPicoProviderValidationSigner;)"
            + "Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationReceipt;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/core/port/GraphExactPicoProviderValidationSigner",
        "sign",
        "(Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge;)Ljava/lang/String;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/core/domain/GraphExactPicoProviderValidationCommand",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;JJJLjava/lang/String;"
            + "Ljava/lang/String;Ljava/time/Duration;)V",
        List.of());
  }

  @Test
  void v19ReaderHasOneExactReadOnlySurfaceAndNoAppConsumer()
      throws Exception {
    String readerName =
        "io/emergeos/adapters/postgres/"
            + "PostgresExactPicoOverlayReader.class";
    String validationRowName =
        "io/emergeos/adapters/postgres/"
            + "PostgresExactPicoOverlayReader$ValidationRow.class";
    List<String> readerClasses =
        List.of(
            readerName,
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$EventRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$HeadRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$KeyRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$LegacyAttributionRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$ManifestRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$OverlayAttributionRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$OverlayEventRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$OverlayHeadRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$PolicyRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$ProfileRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$RequirementRow.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$RuntimeIdentity.class",
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$SessionRow.class",
            validationRowName,
            "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoOverlayReader$VerifiedBase.class");
    for (String className : readerClasses) {
      assertEquals(
          List.of(),
          GraphEvalBytecodeGate.classViolations(
              className, classResourceBytes(className)),
          className);
    }

    byte[] reader = classResourceBytes(readerName);
    List<String> constants = GraphEvalBytecodeGate.utf8Constants(reader);
    assertTrue(constants.contains("emergeos_exact_overlay_reader_v19"));
    for (String relation :
        List.of(
            "agent_graph_attempts",
            "agent_graph_attempt_heads",
            "agent_graph_attempt_events",
            "agent_graph_attempt_provider_attributions",
            "agent_graph_provider_session_intents",
            "agent_graph_provider_validation_keys",
            "agent_graph_provider_validations",
            "agent_graph_provider_profiles_v14",
            "agent_graph_exact_tx_a_requirements_v15",
            "agent_graph_exact_provider_validations_v16",
            "agent_graph_exact_provider_attributions_v16",
            "agent_graph_exact_attempt_events_v16",
            "agent_graph_exact_attempt_heads_v16")) {
      assertTrue(
          constants.stream()
              .anyMatch(value -> value.contains("FROM public." + relation)),
          relation);
    }
    for (String forbidden :
        List.of(
            "agent_graph_require_provider_validation_v13",
            "agent_graph_stage_provider_validation_v13",
            "agent_graph_commit_provider_validation_v13",
            "agent_graph_assert_provider_statement_v14",
            "agent_graph_require_exact_tx_a_v15",
            "agent_graph_assert_exact_tx_a_requirement_v15",
            "agent_graph_stage_exact_tx_a_v16",
            "agent_graph_commit_exact_tx_a_v16",
            "agent_graph_assert_exact_tx_a_overlay_v16",
            "PostgresProviderValidationAttestor",
            "PostgresExactPicoProviderValidationAttestor",
            "GraphProviderValidationSigner",
            "GraphExactPicoProviderValidationSigner",
            "GraphExactPicoProviderValidationCommand")) {
      assertTrue(
          constants.stream().noneMatch(value -> value.contains(forbidden)),
          forbidden);
    }

    assertReference(
        GraphEvalBytecodeGate.memberReferences(reader),
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderSignatureVerifier",
        "verifyOrThrow",
        "(Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge;[B"
            + "Ljava/lang/String;)V");
    assertReference(
        GraphEvalBytecodeGate.memberReferences(reader),
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationReceipt",
        "<init>",
        "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Lio/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationReceipt$ValidationState;)V");
    assertReference(
        GraphEvalBytecodeGate.memberReferences(
            classResourceBytes(validationRowName)),
        "io/emergeos/core/domain/"
            + "GraphExactPicoProviderValidationChallenge",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;JJJ"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/util/UUID;Ljava/time/Instant;"
            + "Ljava/time/Instant;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphProviderValidationDecision;"
            + "Ljava/lang/String;"
            + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
            + "Ljava/lang/String;Ljava/lang/String;)V");

    Map<String, List<GraphEvalBytecodeGate.MemberReference>> app =
        appMemberReferences();
    String readDescriptor =
        "(Lio/emergeos/core/domain/GraphAttemptManifest;)"
            + "Lio/emergeos/core/domain/"
            + "GraphExactPicoOverlayVerification;";
    assertConsumers(
        app,
        "io/emergeos/adapters/postgres/PostgresExactPicoOverlayReader",
        "open",
        "(Ljavax/sql/DataSource;)Lio/emergeos/core/port/"
            + "GraphExactPicoOverlayReader;",
        List.of());
    assertConsumers(
        app,
        "io/emergeos/adapters/postgres/PostgresExactPicoOverlayReader",
        "findVerified",
        readDescriptor,
        List.of());
    assertConsumers(
        app,
        "io/emergeos/core/port/GraphExactPicoOverlayReader",
        "findVerified",
        readDescriptor,
        List.of());
  }

  @Test
  void negativeFixtureRejectsV19RoleMutationAndWriterExpansion()
      throws Exception {
    Path readerSource =
        tempDir.resolve("PostgresExactPicoOverlayReader.java");
    Path roleSource = tempDir.resolve("InjectedV19RoleConsumer.java");
    Path classes = tempDir.resolve("v19-reader-capability-classes");
    Files.createDirectories(classes);
    Files.writeString(
        readerSource,
        """
        package io.emergeos.adapters.postgres;

        import io.emergeos.core.port.GraphExactPicoProviderValidationAttestor;
        import javax.sql.DataSource;

        public final class PostgresExactPicoOverlayReader {
          private static final String ROLE =
              "emergeos_exact_overlay_reader_v19";

          String sql() {
            return String.join(
                " ",
                "SELECT * FROM public.agent_graph_attempts;",
                "SELECT * FROM public.agent_graph_attempt_heads;",
                "SELECT * FROM public.agent_graph_attempt_events;",
                "SELECT * FROM public.agent_graph_attempt_provider_attributions;",
                "SELECT * FROM public.agent_graph_provider_session_intents;",
                "SELECT * FROM public.agent_graph_provider_validation_keys;",
                "SELECT * FROM public.agent_graph_provider_validations;",
                "SELECT * FROM public.agent_graph_provider_profiles_v14;",
                "SELECT * FROM public.agent_graph_exact_tx_a_requirements_v15;",
                "SELECT * FROM public.agent_graph_exact_provider_validations_v16;",
                "SELECT * FROM public.agent_graph_exact_provider_attributions_v16;",
                "SELECT * FROM public.agent_graph_exact_attempt_events_v16;",
                "SELECT * FROM public.agent_graph_exact_attempt_heads_v16;",
                "UPDATE public.agent_graph_attempts SET case_id = case_id;",
                "SELECT public.agent_graph_stage_exact_tx_a_v16();");
          }

          GraphExactPicoProviderValidationAttestor writer(DataSource source) {
            return PostgresExactPicoProviderValidationAttestor.open(source);
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        roleSource,
        """
        package io.emergeos.adapters.postgres;

        final class InjectedV19RoleConsumer {
          String role() {
            return "emergeos_exact_overlay_reader_v19";
          }
        }
        """,
        StandardCharsets.UTF_8);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                readerSource.toString(),
                roleSource.toString()));

    String readerName =
        "io/emergeos/adapters/postgres/"
            + "PostgresExactPicoOverlayReader.class";
    List<String> readerViolations =
        GraphEvalBytecodeGate.classViolations(
            readerName, Files.readAllBytes(classes.resolve(readerName)));
    for (String expected :
        List.of(
            "capability:v19-reader-mutating-sql-forbidden",
            "capability:v19-reader-unreviewed-sql-surface:"
                + "agent_graph_stage_exact_tx_a_v16",
            "capability:unreviewed-v16-exact-pico-authority:"
                + "agent_graph_stage_exact_tx_a_v16",
            "capability:v19-reader-writer-type-forbidden:"
                + "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoProviderValidationAttestor",
            "capability:unreviewed-consumer:"
                + "io/emergeos/adapters/postgres/"
                + "PostgresExactPicoProviderValidationAttestor.open")) {
      assertTrue(
          readerViolations.stream().anyMatch(value -> value.contains(expected)),
          expected + " " + readerViolations);
    }

    String roleName =
        "io/emergeos/adapters/postgres/InjectedV19RoleConsumer.class";
    List<String> roleViolations =
        GraphEvalBytecodeGate.classViolations(
            roleName, Files.readAllBytes(classes.resolve(roleName)));
    assertTrue(
        roleViolations.contains(
            roleName
                + " -> capability:unreviewed-v19-reader-role:"
                + "emergeos_exact_overlay_reader_v19"),
        roleViolations.toString());
  }

  @Test
  void negativeFixtureRejectsV13AuthoritySigningAndRawBodyConsumers()
      throws Exception {
    Path postgresSource =
        tempDir.resolve("InjectedProviderValidationConsumer.java");
    Path openAiSource = tempDir.resolve("InjectedRawResponseConsumer.java");
    Path classes = tempDir.resolve("v13-capability-classes");
    Files.createDirectories(classes);
    Files.writeString(
        postgresSource,
        """
        package io.emergeos.adapters.postgres;

        import io.emergeos.core.domain.*;
        import io.emergeos.core.port.*;
        import java.lang.invoke.MethodHandles;
        import java.lang.invoke.MethodType;
        import java.security.*;
        import java.security.spec.PKCS8EncodedKeySpec;
        import java.security.spec.X509EncodedKeySpec;
        import java.time.Duration;
        import javax.sql.DataSource;

        final class InjectedProviderValidationConsumer {
          Object consume(
              DataSource dataSource,
              GraphAttemptManifest manifest,
              GraphAttemptCursor cursor,
              GraphProviderValidationStatement statement,
              GraphProviderValidationSigner signer,
              GraphExactPicoProviderValidationSigner exactSigner)
              throws Throwable {
            new PostgresProviderValidationAttestor(
                dataSource, ignored -> {});
            PostgresProviderValidationAttestor concrete =
                PostgresProviderValidationAttestor.open(dataSource);
            concrete.requireValidation(
                manifest, cursor, "r1", "0".repeat(64),
                "1".repeat(64), "2".repeat(64),
                Duration.ofSeconds(1));
            concrete.completeValidation(
                manifest, cursor, statement, signer);
            GraphProviderValidationAttestor port = concrete;
            port.requireValidation(
                manifest, cursor, "r1", "0".repeat(64),
                "1".repeat(64), "2".repeat(64),
                Duration.ofSeconds(1));
            port.completeValidation(manifest, cursor, statement, signer);
            concrete.getClass()
                .getMethod(
                    "completeValidation",
                    GraphAttemptManifest.class,
                    GraphAttemptCursor.class,
                    GraphProviderValidationStatement.class,
                    GraphProviderValidationSigner.class)
                .invoke(concrete, manifest, cursor, statement, signer);
            MethodHandles.lookup().findStatic(
                PostgresProviderValidationAttestor.class,
                "open",
                MethodType.methodType(
                    PostgresProviderValidationAttestor.class,
                    DataSource.class));
            GraphProviderValidationAttestation signed =
                signer.attest((GraphProviderValidationTranscript) null);
            signed.verifiesWith(new byte[0]);
            signed.transcript();
            signed.signatureHex();
            GraphProviderValidationTranscript transcript =
                GraphProviderValidationTranscript.create(
                    null, null, null, null, null, null, null, null, null, null);
            transcript.signatureMaterial();
            new GraphProviderValidationStatement(
                null, null, null, null, null, null, null, null);
            new GraphProviderValidationTranscript(
                null, null, null, null, null, null, null, null, null, null,
                null);
            new GraphProviderValidationChallenge(
                null, null, 0, 0, 0, null, null, null, null, null,
                null, 0, null, null, null, null, null, null, null,
                null, null, null, null);
            GraphExactPicoProviderValidationChallenge exactChallenge =
                new GraphExactPicoProviderValidationChallenge(
                    null, null, 0, 0, 0, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            exactChallenge.signatureMaterial();
            GraphExactPicoProviderSignatureVerifier.verifyOrThrow(
                exactChallenge, new byte[0], "0".repeat(128));
            GraphExactPicoProviderValidationCommand exactCommand =
                new GraphExactPicoProviderValidationCommand(
                    null, null, null, null, null, null, null, null,
                    0, 0, 0, null, null, Duration.ofSeconds(1));
            PostgresExactPicoProviderValidationAttestor exactConcrete =
                new PostgresExactPicoProviderValidationAttestor(
                    dataSource, ignored -> {});
            PostgresExactPicoProviderValidationAttestor.open(dataSource);
            exactConcrete.complete(exactCommand, exactSigner);
            GraphExactPicoProviderValidationAttestor exactPort =
                exactConcrete;
            exactPort.complete(exactCommand, exactSigner);
            exactSigner.sign(exactChallenge);
            new GraphExactPicoProviderValidationReceipt(
                null, 14, 1, null, null, null, null, null, null,
                GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED);
            return new GraphProviderValidationAttestation(
                null, "0".repeat(128));
          }

          byte[] sign(PrivateKey key, byte[] input) throws Exception {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(input);
            return signature.sign();
          }

          PrivateKey decode(byte[] encoded) throws Exception {
            return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
          }

          boolean verify(
              byte[] publicKey, byte[] input, byte[] signed)
              throws Exception {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(
                KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicKey)));
            signature.update(input);
            return signature.verify(signed);
          }

          PrivateKey generate() throws Exception {
            return KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair().getPrivate();
          }

          String rawSql(int index) {
            return java.util.List.of(
                "agent_graph_require_provider_validation_v13",
                "agent_graph_stage_provider_validation_v13",
                "agent_graph_commit_provider_validation_v13",
                "agent_graph_provider_validation_keys",
                "agent_graph_provider_validations",
                "emergeos_provider_attestor",
                "agent_graph_assert_provider_statement_v14",
                "agent_graph_provider_profiles_v14",
                "emergeos_provider_attestor_v14",
                "agent_graph_require_exact_tx_a_v15",
                "agent_graph_assert_exact_tx_a_requirement_v15",
                "agent_graph_exact_tx_a_requirements_v15",
                "emergeos_provider_attestor_v15",
                "agent_graph_stage_exact_tx_a_v16",
                "agent_graph_commit_exact_tx_a_v16",
                "agent_graph_assert_exact_tx_a_overlay_v16",
                "agent_graph_exact_provider_validations_v16",
                "agent_graph_exact_provider_attributions_v16",
                "agent_graph_exact_attempt_events_v16",
                "agent_graph_exact_attempt_heads_v16",
                "emergeos_provider_attestor_v16").get(index);
          }
        }
        """,
        StandardCharsets.UTF_8);
    Files.writeString(
        openAiSource,
        """
        package io.emergeos.adapters.openai;

        import com.openai.core.RequestOptions;
        import com.openai.core.http.HttpResponseFor;
        import com.openai.models.responses.ResponseCreateParams;
        import io.emergeos.core.domain.GraphProviderAttribution;
        import java.io.InputStream;

        final class InjectedRawResponseConsumer {
          InputStream body(HttpResponseFor<?> response) {
            return response.body();
          }

          Object route(
              ReviewedOpenAiClient client,
              ResponseCreateParams request,
              RequestOptions options) {
            return client.createReviewedResponse(request, options);
          }

          Object validation(
              OpenAiResponsesModel.ProviderOutcomeReceipt outcome,
              GraphProviderAttribution attribution) {
            OpenAiProviderValidationStatements.policy();
            return OpenAiProviderValidationStatements.fromExactOutcome(
                outcome, attribution, "0".repeat(64));
          }

          Object deepSeek(String key) {
            return DeepSeekV4FlashResponsesProbe.execute(key);
          }

          Object deepSeekLoopback(String key) {
            return DeepSeekV4FlashResponsesProbe.executeForTest(
                key, "http://127.0.0.1:1");
          }
        }
        """,
        StandardCharsets.UTF_8);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                postgresSource.toString(),
                openAiSource.toString()));

    String postgresName =
        "io/emergeos/adapters/postgres/"
            + "InjectedProviderValidationConsumer.class";
    String openAiName =
        "io/emergeos/adapters/openai/InjectedRawResponseConsumer.class";
    List<String> directory =
        GraphEvalBytecodeGate.directoryViolations(classes);
    assertV13NegativeViolations(directory, postgresName, openAiName);

    String testAccessName =
        "io/emergeos/adapters/postgres/"
            + "PostgresProviderValidationAttestorTestAccess.class";
    String deepSeekMainName =
        "io/emergeos/adapters/openai/"
            + "DeepSeekV4FlashResponsesProbeMain.class";
    Path jarPath = tempDir.resolve("v13-capability-consumers.jar");
    try (JarOutputStream jar =
        new JarOutputStream(Files.newOutputStream(jarPath))) {
      for (String logicalName : List.of(postgresName, openAiName)) {
        jar.putNextEntry(new JarEntry(logicalName));
        jar.write(Files.readAllBytes(classes.resolve(logicalName)));
        jar.closeEntry();
      }
      jar.putNextEntry(new JarEntry(testAccessName));
      jar.write(classResourceBytes(testAccessName));
      jar.closeEntry();
      jar.putNextEntry(new JarEntry(deepSeekMainName));
      jar.write(Files.readAllBytes(classes.resolve(openAiName)));
      jar.closeEntry();
    }
    List<String> jar =
        GraphEvalBytecodeGate.shippingJarViolations(jarPath);
    assertV13NegativeViolations(jar, postgresName, openAiName);
    assertTrue(
        jar.contains(
            deepSeekMainName
                + " -> archive:io/emergeos/adapters/openai/"
                + "DeepSeekV4FlashResponsesProbeMain"),
        jar.toString());
    assertTrue(
        jar.stream()
            .anyMatch(
                violation ->
                    violation.startsWith(testAccessName + " -> archive:")),
        jar.toString());
    assertTrue(
        jar.stream()
            .anyMatch(
                violation ->
                    violation.startsWith(testAccessName + " -> ")
                        && violation.contains(
                            "capability:unreviewed-consumer:"
                                + "io/emergeos/adapters/postgres/"
                                + "PostgresProviderValidationAttestor."
                                + "<init>")),
        jar.toString());
  }

  @Test
  void negativeFixtureRejectsAnUnreviewedInvokeDynamicBootstrap()
      throws Exception {
    Path source = tempDir.resolve("LambdaFixture.java");
    Path classes = tempDir.resolve("lambda-classes");
    Files.createDirectories(classes);
    Files.writeString(
        source,
        """
        final class LambdaFixture {
          Runnable route() { return () -> System.nanoTime(); }
        }
        """,
        StandardCharsets.UTF_8);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "21",
                "-d",
                classes.toString(),
                source.toString()));
    byte[] original =
        Files.readAllBytes(classes.resolve("LambdaFixture.class"));
    assertTrue(
        GraphEvalBytecodeGate.bootstrapReferences(original).stream()
            .anyMatch(
                bootstrap ->
                    bootstrap.bootstrap().owner().equals(
                        "java/lang/invoke/LambdaMetafactory")));

    byte[] unreviewed = original.clone();
    byte[] reviewedName =
        "java/lang/invoke/LambdaMetafactory"
            .getBytes(StandardCharsets.UTF_8);
    byte[] unreviewedName =
        "java/lang/invoke/LambdaMetafactorx"
            .getBytes(StandardCharsets.UTF_8);
    int offset = indexOf(unreviewed, reviewedName);
    assertTrue(offset >= 0);
    System.arraycopy(
        unreviewedName,
        0,
        unreviewed,
        offset,
        unreviewedName.length);

    String logicalName =
        "io/emergeos/adapters/postgres/"
            + "InjectedProviderValidationBootstrap.class";
    Path directory = tempDir.resolve("lambda-directory");
    Path injected = directory.resolve(logicalName);
    Files.createDirectories(injected.getParent());
    Files.write(injected, unreviewed);
    assertUnreviewedBootstrapRejected(
        GraphEvalBytecodeGate.directoryViolations(directory), logicalName);

    Path jarPath = tempDir.resolve("lambda-consumer.jar");
    try (JarOutputStream jar =
        new JarOutputStream(Files.newOutputStream(jarPath))) {
      jar.putNextEntry(new JarEntry(logicalName));
      jar.write(unreviewed);
      jar.closeEntry();
    }
    assertUnreviewedBootstrapRejected(
        GraphEvalBytecodeGate.shippingJarViolations(jarPath),
        logicalName);
  }

  private static void assertPackagePrivateFinal(Class<?> type) {
    assertFalse(Modifier.isPublic(type.getModifiers()));
    assertFalse(Modifier.isProtected(type.getModifiers()));
    assertTrue(Modifier.isFinal(type.getModifiers()));
  }

  private static void assertUnreviewedFailureConsumers(
      List<String> violations, List<String> expectedMembers) {
    assertUnreviewedFailureConsumers(
        violations, null, expectedMembers);
  }

  private static void assertReflectiveResumeRejected(
      List<String> violations, String logicalName) {
    List<String> matching =
        violations.stream()
            .filter(value -> value.startsWith(logicalName + " -> "))
            .filter(
                value ->
                    value.contains(
                        "capability:unreviewed-indirect-access:"))
            .toList();
    assertEquals(2, matching.size(), matching.toString());
    assertTrue(
        matching.stream()
            .anyMatch(value -> value.contains("java/lang/Class.getMethod")));
    assertTrue(
        matching.stream()
            .anyMatch(
                value -> value.contains("java/lang/reflect/Method.invoke")));
  }

  private static void assertV13NegativeViolations(
      List<String> violations,
      String postgresName,
      String openAiName) {
    for (String expected :
        List.of(
            "PostgresProviderValidationAttestor.open",
            "PostgresProviderValidationAttestor.<init>",
            "PostgresProviderValidationAttestor.requireValidation",
            "PostgresProviderValidationAttestor.completeValidation",
            "GraphProviderValidationAttestor.requireValidation",
            "GraphProviderValidationAttestor.completeValidation",
            "GraphProviderValidationSigner.attest",
            "GraphProviderValidationTranscript.create",
            "GraphProviderValidationTranscript.signatureMaterial",
            "GraphProviderValidationTranscript.<init>",
            "GraphProviderValidationChallenge.<init>",
            "GraphProviderValidationStatement.<init>",
            "GraphProviderValidationAttestation.verifiesWith",
            "GraphProviderValidationAttestation.transcript",
            "GraphProviderValidationAttestation.signatureHex",
            "GraphProviderValidationAttestation.<init>",
            "GraphExactPicoProviderValidationChallenge.<init>",
            "GraphExactPicoProviderValidationChallenge.signatureMaterial",
            "GraphExactPicoProviderSignatureVerifier.verifyOrThrow",
            "PostgresExactPicoProviderValidationAttestor.<init>",
            "PostgresExactPicoProviderValidationAttestor.open",
            "PostgresExactPicoProviderValidationAttestor.complete",
            "GraphExactPicoProviderValidationAttestor.complete",
            "GraphExactPicoProviderValidationSigner.sign",
            "GraphExactPicoProviderValidationCommand.<init>",
            "GraphExactPicoProviderValidationReceipt.<init>")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.startsWith(postgresName + " -> ")
                          && value.contains(
                              "capability:unreviewed-consumer:")
                          && value.contains(expected)),
          expected + " " + violations);
    }
    for (String member :
        List.of(
            "java/security/Signature.getInstance",
            "java/security/Signature.initVerify",
            "java/security/Signature.update",
            "java/security/Signature.verify",
            "java/security/KeyFactory.getInstance",
            "java/security/KeyFactory.generatePublic",
            "java/security/spec/X509EncodedKeySpec.<init>")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.startsWith(postgresName + " -> ")
                          && value.contains(
                              "capability:unreviewed-consumer:")
                          && value.contains(member)),
          member + " " + violations);
    }
    for (String expected :
        List.of("capability:unreviewed-indirect-access:")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.startsWith(postgresName + " -> ")
                          && value.contains(expected)),
          expected + " " + violations);
    }
    for (String member :
        List.of(
            "java/security/Signature.initSign",
            "java/security/Signature.sign",
            "java/security/KeyFactory.generatePrivate",
            "java/security/KeyPairGenerator.getInstance",
            "java/security/KeyPairGenerator.generateKeyPair",
            "java/security/KeyPair.getPrivate")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.startsWith(postgresName + " -> ")
                          && value.contains(
                              "capability:private-signing-forbidden:")
                          && value.contains(member)),
          member + " " + violations);
    }
    for (String signingType :
        List.of(
            "java/security/PrivateKey",
            "java/security/KeyPair",
            "java/security/KeyPairGenerator",
            "java/security/spec/PKCS8EncodedKeySpec")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.equals(
                          postgresName
                              + " -> capability:"
                              + "private-signing-type-forbidden:"
                              + signingType)),
          signingType + " " + violations);
    }
    for (String authorityConstant :
        List.of(
            "agent_graph_require_provider_validation_v13",
            "agent_graph_stage_provider_validation_v13",
            "agent_graph_commit_provider_validation_v13",
            "agent_graph_provider_validation_keys",
            "agent_graph_provider_validations",
            "emergeos_provider_attestor")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.equals(
                          postgresName
                              + " -> capability:"
                              + "unreviewed-v13-semantic-function:"
                              + authorityConstant)),
          authorityConstant + " " + violations);
    }
    for (String authorityConstant :
        List.of(
            "agent_graph_assert_provider_statement_v14",
            "agent_graph_provider_profiles_v14",
            "emergeos_provider_attestor_v14")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.equals(
                          postgresName
                              + " -> capability:"
                              + "unreviewed-v14-profile-authority:"
                              + authorityConstant)),
          authorityConstant + " " + violations);
    }
    for (String authorityConstant :
        List.of(
            "agent_graph_require_exact_tx_a_v15",
            "agent_graph_assert_exact_tx_a_requirement_v15",
            "agent_graph_exact_tx_a_requirements_v15",
            "emergeos_provider_attestor_v15")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.equals(
                          postgresName
                              + " -> capability:"
                              + "unreviewed-v15-exact-tx-a-authority:"
                              + authorityConstant)),
          authorityConstant + " " + violations);
    }
    for (String authorityConstant :
        List.of(
            "agent_graph_stage_exact_tx_a_v16",
            "agent_graph_commit_exact_tx_a_v16",
            "agent_graph_assert_exact_tx_a_overlay_v16",
            "agent_graph_exact_provider_validations_v16",
            "agent_graph_exact_provider_attributions_v16",
            "agent_graph_exact_attempt_events_v16",
            "agent_graph_exact_attempt_heads_v16",
            "emergeos_provider_attestor_v16")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.equals(
                          postgresName
                              + " -> capability:"
                              + "unreviewed-v16-exact-pico-authority:"
                              + authorityConstant)),
          authorityConstant + " " + violations);
    }
    for (String expected :
        List.of(
            "HttpResponseFor.body",
            "ReviewedOpenAiClient.createReviewedResponse",
            "OpenAiProviderValidationStatements.policy",
            "OpenAiProviderValidationStatements.fromExactOutcome",
            "DeepSeekV4FlashResponsesProbe.execute",
            "DeepSeekV4FlashResponsesProbe.executeForTest")) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  value ->
                      value.startsWith(openAiName + " -> ")
                          && value.contains(
                              "capability:unreviewed-consumer:")
                          && value.contains(expected)),
          expected + " " + violations);
    }
  }

  private static void assertReference(
      List<GraphEvalBytecodeGate.MemberReference> references,
      String owner,
      String name,
      String descriptor) {
    assertTrue(
        references.stream()
            .anyMatch(
                reference ->
                    reference.owner().equals(owner)
                        && reference.name().equals(name)
                        && reference.descriptor().equals(descriptor)),
        owner + "." + name + descriptor);
  }

  private static void assertUnreviewedBootstrapRejected(
      List<String> violations, String logicalName) {
    assertTrue(
        violations.stream()
            .anyMatch(
                violation ->
                    violation.startsWith(logicalName + " -> ")
                        && violation.contains(
                            "capability:unreviewed-invokedynamic-bootstrap:")),
        violations.toString());
  }

  private static byte[] classResourceBytes(String logicalName)
      throws IOException {
    try (var input =
        Pack010ProviderCapabilityBytecodeGateTest.class
            .getClassLoader()
            .getResourceAsStream(logicalName)) {
      assertNotNull(input, logicalName);
      return input.readAllBytes();
    }
  }

  private static void assertNoIndirectViolation(List<String> violations) {
    assertTrue(
        violations.stream()
            .noneMatch(value ->
                value.contains("capability:unreviewed-indirect-access:")),
        violations.toString());
  }

  private static void assertIndirectViolation(
      List<String> violations, String member) {
    assertTrue(
        violations.stream()
            .anyMatch(value ->
                value.contains("capability:unreviewed-indirect-access:")
                    && value.contains(member)),
        violations.toString());
  }

  private static void assertUnreviewedFailureConsumers(
      List<String> violations,
      String expectedLogicalName,
      List<String> expectedMembers) {
    for (String member : expectedMembers) {
      assertTrue(
          violations.stream()
              .anyMatch(
                  violation ->
                      (expectedLogicalName == null
                              || violation.startsWith(
                                  expectedLogicalName + " -> "))
                          && violation.contains(
                          "capability:unreviewed-consumer:")
                          && violation.contains(member)),
          member + " must be rejected for an unreviewed consumer");
    }
  }

  private static int indexOf(byte[] source, byte[] target) {
    for (int offset = 0;
        offset <= source.length - target.length;
        offset++) {
      boolean matches = true;
      for (int index = 0; index < target.length; index++) {
        if (source[offset + index] != target[index]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return offset;
      }
    }
    return -1;
  }

  private static void replaceExactUtf8(
      byte[] classBytes, String expected, String replacement) {
    byte[] source = expected.getBytes(StandardCharsets.UTF_8);
    byte[] target = replacement.getBytes(StandardCharsets.UTF_8);
    assertEquals(source.length, target.length);
    int offset = indexOf(classBytes, source);
    assertTrue(offset >= 0, expected);
    System.arraycopy(target, 0, classBytes, offset, target.length);
  }

  private static Map<String, List<GraphEvalBytecodeGate.MemberReference>>
      appMemberReferences() throws IOException {
    Path root = classesRoot().resolve("io/emergeos/grapheval");
    Map<String, List<GraphEvalBytecodeGate.MemberReference>> result =
        new TreeMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path :
          paths.filter(Files::isRegularFile)
              .filter(value -> value.toString().endsWith(".class"))
              .toList()) {
        result.put(
            root.relativize(path).toString().replace('\\', '/'),
            GraphEvalBytecodeGate.memberReferences(
                Files.readAllBytes(path)));
      }
    }
    return Map.copyOf(result);
  }

  private static void assertConsumers(
      Map<String, List<GraphEvalBytecodeGate.MemberReference>> references,
      String owner,
      String member,
      String descriptor,
      List<String> expected) {
    List<String> actual = new ArrayList<>();
    references.forEach(
        (className, classReferences) -> {
          if (classReferences.stream()
              .anyMatch(
                  reference ->
                      reference.owner().equals(owner)
                          && reference.name().equals(member)
                          && reference.descriptor().equals(descriptor))) {
            actual.add(className);
          }
        });
    actual.sort(Comparator.naturalOrder());
    assertEquals(
        expected, actual, owner + "." + member + descriptor);
  }

  private static void assertNoOwner(
      Map<String, List<GraphEvalBytecodeGate.MemberReference>> references,
      String owner) {
    assertTrue(
        references.values().stream()
            .flatMap(List::stream)
            .noneMatch(reference -> reference.owner().equals(owner)),
        owner);
  }

  private static void assertNoMember(
      Map<String, List<GraphEvalBytecodeGate.MemberReference>> references,
      String owner,
      String member) {
    assertTrue(
        references.values().stream()
            .flatMap(List::stream)
            .noneMatch(
                reference ->
                    reference.owner().equals(owner)
                        && reference.name().equals(member)),
        owner + "." + member);
  }

  private static String javap(String simpleName) throws Exception {
    return javap(
        "io.emergeos.grapheval." + simpleName, classesRoot());
  }

  private static String javap(String className, Path classpath)
      throws Exception {
    Path executable =
        Path.of(System.getProperty("java.home"), "bin", "javap");
    Process process =
        new ProcessBuilder(
                executable.toString(),
                "-c",
                "-p",
                "-classpath",
                classpath.toString(),
                className)
            .redirectErrorStream(true)
            .start();
    byte[] output = process.getInputStream().readAllBytes();
    assertEquals(0, process.waitFor());
    return new String(output, StandardCharsets.UTF_8);
  }

  private static void assertOrdered(
      String bytecode, String... instructions) {
    int cursor = -1;
    for (String instruction : instructions) {
      int next = bytecode.indexOf(instruction, cursor + 1);
      assertTrue(next > cursor, instruction + " missing or out of order");
      cursor = next;
    }
  }

  private static Path classesRoot() {
    return Path.of(System.getProperty("emerge.graph.classes"))
        .toAbsolutePath()
        .normalize();
  }
}
