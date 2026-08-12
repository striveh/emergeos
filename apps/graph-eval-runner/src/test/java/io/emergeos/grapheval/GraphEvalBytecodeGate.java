package io.emergeos.grapheval;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic classfile gate for the Pack009 composition boundary.
 *
 * <p>Maven Enforcer rejects forbidden artifacts. This gate independently
 * rejects first-party bytecode references and forbidden shaded package
 * entries, including references introduced without a normal Maven dependency.
 */
final class GraphEvalBytecodeGate {

  private static final String MULTI_RELEASE_ROOT =
      "META-INF/versions/";
  private static final String APP_CLASS_ROOT =
      "io/emergeos/grapheval/";
  private static final String FIRST_PARTY_ROOT =
      "io/emergeos/";
  private static final String BROKER_CLASS =
      APP_CLASS_ROOT + "Pack010ProviderCredentialBroker.class";
  private static final String CREDENTIAL_LEASE_CLASS =
      APP_CLASS_ROOT
          + "Pack010ProviderCredentialBroker$CredentialLease.class";
  private static final String COMPOSER_CLASS =
      APP_CLASS_ROOT + "Pack010ProviderSessionComposer.class";
  private static final String COMPOSER_SESSION_CLASS =
      APP_CLASS_ROOT
          + "Pack010ProviderSessionComposer$ProviderSession.class";
  private static final String ATTRIBUTED_OUTCOME_DESCRIPTOR =
      "Lio/emergeos/grapheval/"
          + "Pack010ProviderSessionComposer$Pack010AttributedModelOutcome;";
  private static final String STRUCTURED_FINAL_BINDING_DESCRIPTOR =
      "Lio/emergeos/grapheval/"
          + "Pack010ProviderSessionComposer$StructuredFinalBinding;";
  private static final String ATTRIBUTED_FAILURE_DESCRIPTOR =
      "Lio/emergeos/grapheval/"
          + "Pack010ProviderSessionComposer$"
          + "AttributedPreCandidateFailure;";
  private static final String RUNTIME_COMPOSITION_CLASS =
      APP_CLASS_ROOT + "Pack010PostgresRuntimeComposition.class";
  private static final String REPORT_JSON_CLASS =
      APP_CLASS_ROOT + "HarnessEvaluationReportJson.class";
  private static final String CANONICAL_ENCODING_CLASS =
      "io/emergeos/contracts/CanonicalEncoding.class";
  private static final String OWNER_AUTHORITY_TYPES_CLASS =
      "io/emergeos/core/application/"
          + "GraphAttemptCoordinator$OwnerAuthorityTypes.class";
  private static final String PROVIDER_VALIDATION_ATTESTOR_CLASS =
      "io/emergeos/adapters/postgres/"
          + "PostgresProviderValidationAttestor.class";
  private static final String PROVIDER_VALIDATION_ATTESTATION_CLASS =
      "io/emergeos/core/domain/"
          + "GraphProviderValidationAttestation.class";
  private static final String PROVIDER_VALIDATION_TRANSCRIPT_CLASS =
      "io/emergeos/core/domain/"
          + "GraphProviderValidationTranscript.class";
  private static final String EXACT_PICO_VALIDATION_CHALLENGE_CLASS =
      "io/emergeos/core/domain/"
          + "GraphExactPicoProviderValidationChallenge.class";
  private static final String EXACT_PICO_SIGNATURE_VERIFIER_CLASS =
      "io/emergeos/core/domain/"
          + "GraphExactPicoProviderSignatureVerifier.class";
  private static final String EXACT_PICO_TYPED_ATTESTOR_CLASS =
      "io/emergeos/adapters/postgres/"
          + "PostgresExactPicoProviderValidationAttestor.class";
  private static final String EXACT_PICO_OVERLAY_READER_CLASS =
      "io/emergeos/adapters/postgres/"
          + "PostgresExactPicoOverlayReader.class";
  private static final String EXACT_PICO_OVERLAY_READER_PREFIX =
      "io/emergeos/adapters/postgres/"
          + "PostgresExactPicoOverlayReader";
  private static final String EXACT_PICO_OVERLAY_READER_VALIDATION_ROW_CLASS =
      EXACT_PICO_OVERLAY_READER_PREFIX + "$ValidationRow.class";
  private static final String REVIEWED_OPENAI_CLIENT_CLASS =
      "io/emergeos/adapters/openai/ReviewedOpenAiClient.class";
  private static final String DEEPSEEK_RESPONSES_PROBE_CLASS =
      "io/emergeos/adapters/openai/"
          + "DeepSeekV4FlashResponsesProbe.class";
  private static final String DEEPSEEK_PRODUCTION_BASE_URL =
      "https://api.deepseek.com";
  private static final String DEEPSEEK_V4_FLASH_MODEL =
      "deepseek-v4-flash";
  private static final String OPENAI_RESPONSES_SESSION_CLASS =
      "io/emergeos/adapters/openai/"
          + "OpenAiResponsesModel$ResponsesSession.class";
  private static final String OPENAI_VALIDATION_STATEMENTS_CLASS =
      "io/emergeos/adapters/openai/"
          + "OpenAiProviderValidationStatements.class";
  private static final String PRODUCTION_BASE_URL =
      "https://api.openai.com/v1";
  private static final String CREDENTIAL_NAME = "OPENAI_API_KEY";
  private static final Set<String> V13_AUTHORITY_CONSTANTS =
      Set.of(
          "agent_graph_require_provider_validation_v13",
          "agent_graph_stage_provider_validation_v13",
          "agent_graph_commit_provider_validation_v13",
          "agent_graph_provider_validation_keys",
          "agent_graph_provider_validations",
          "emergeos_provider_attestor");
  private static final Set<String> V14_PROFILE_AUTHORITY_CONSTANTS =
      Set.of(
          "agent_graph_assert_provider_statement_v14",
          "agent_graph_provider_profiles_v14",
          "emergeos_provider_attestor_v14");
  private static final Set<String> V15_EXACT_TX_A_AUTHORITY_CONSTANTS =
      Set.of(
          "agent_graph_require_exact_tx_a_v15",
          "agent_graph_assert_exact_tx_a_requirement_v15",
          "agent_graph_exact_tx_a_requirements_v15",
          "emergeos_provider_attestor_v15");
  private static final Set<String> V16_EXACT_PICO_AUTHORITY_CONSTANTS =
      Set.of(
          "agent_graph_stage_exact_tx_a_v16",
          "agent_graph_commit_exact_tx_a_v16",
          "agent_graph_assert_exact_tx_a_overlay_v16",
          "agent_graph_exact_provider_validations_v16",
          "agent_graph_exact_provider_attributions_v16",
          "agent_graph_exact_attempt_events_v16",
          "agent_graph_exact_attempt_heads_v16",
          "emergeos_provider_attestor_v16");
  private static final String V19_EXACT_OVERLAY_READER_ROLE =
      "emergeos_exact_overlay_reader_v19";
  private static final Set<String> V19_EXACT_OVERLAY_RELATIONS =
      Set.of(
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
          "agent_graph_exact_attempt_heads_v16");
  private static final Set<String> V19_READER_ROLE_CONSUMERS =
      Set.of(
          PROVIDER_VALIDATION_ATTESTOR_CLASS,
          EXACT_PICO_TYPED_ATTESTOR_CLASS,
          EXACT_PICO_OVERLAY_READER_CLASS);
  private static final Set<String> V19_FORBIDDEN_WRITER_TYPES =
      Set.of(
          "io/emergeos/adapters/postgres/"
              + "PostgresProviderValidationAttestor",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestor",
          "io/emergeos/core/port/GraphProviderValidationAttestor",
          "io/emergeos/core/port/GraphProviderValidationSigner",
          "io/emergeos/core/port/"
              + "GraphExactPicoProviderValidationAttestor",
          "io/emergeos/core/port/"
              + "GraphExactPicoProviderValidationSigner",
          "io/emergeos/core/domain/"
              + "GraphExactPicoProviderValidationCommand");
  private static final Pattern QUALIFIED_GRAPH_SQL_SURFACE =
      Pattern.compile("(?i)public\\.(agent_graph_[a-z0-9_]+)");
  private static final Pattern MUTATING_GRAPH_SQL =
      Pattern.compile(
          "(?is)\\b(?:insert\\s+into|update|delete\\s+from|"
              + "merge\\s+into|truncate(?:\\s+table)?|lock\\s+table|call)"
              + "\\s+(?:public\\.)?agent_graph_[a-z0-9_]+");
  private static final Set<String> PRIVATE_SIGNING_TYPES =
      Set.of(
          "java/security/PrivateKey",
          "java/security/KeyPair",
          "java/security/KeyPairGenerator",
          "java/security/spec/PKCS8EncodedKeySpec");
  private static final Set<MemberReference> ALLOWED_BOOTSTRAPS =
      Set.of(
          new MemberReference(
              MemberKind.METHOD,
              "java/lang/invoke/LambdaMetafactory",
              "metafactory",
              "(Ljava/lang/invoke/MethodHandles$Lookup;"
                  + "Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                  + "Ljava/lang/invoke/MethodType;"
                  + "Ljava/lang/invoke/MethodHandle;"
                  + "Ljava/lang/invoke/MethodType;)"
                  + "Ljava/lang/invoke/CallSite;"),
          new MemberReference(
              MemberKind.METHOD,
              "java/lang/invoke/StringConcatFactory",
              "makeConcatWithConstants",
              "(Ljava/lang/invoke/MethodHandles$Lookup;"
                  + "Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                  + "Ljava/lang/String;[Ljava/lang/Object;)"
                  + "Ljava/lang/invoke/CallSite;"),
          new MemberReference(
              MemberKind.METHOD,
              "java/lang/runtime/ObjectMethods",
              "bootstrap",
              "(Ljava/lang/invoke/MethodHandles$Lookup;"
                  + "Ljava/lang/String;"
                  + "Ljava/lang/invoke/TypeDescriptor;"
                  + "Ljava/lang/Class;Ljava/lang/String;"
                  + "[Ljava/lang/invoke/MethodHandle;)"
                  + "Ljava/lang/Object;"));
  private static final List<CapabilityMemberRule>
      CAPABILITY_MEMBER_RULES =
          List.of(
              rule(
                  MemberKind.METHOD,
                  "java/lang/System",
                  "getenv",
                  "(Ljava/lang/String;)Ljava/lang/String;",
                  CREDENTIAL_LEASE_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "requireProviderSessionFresh",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ProviderSessionIntent;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$Pack010Revision;)"
                      + "Ljava/time/Instant;",
                  BROKER_CLASS,
                  CREDENTIAL_LEASE_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "consumeEgress",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ApprovedHandoff;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$Pack010Revision;",
                  BROKER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "bindTerminal",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$TerminalCapability;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$Pack010Revision;)"
                      + "Lio/emergeos/core/domain/GraphAttemptManifest;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "claimChildTerminal",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$TerminalCapability;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ChildTerminalClaim;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "claimParentTerminal",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$TerminalCapability;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ParentTerminalClaim;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "open",
                  "(Ljavax/sql/DataSource;Ljavax/sql/DataSource;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "completeChild",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ChildTerminalClaim;"
                      + "Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters$"
                      + "ChildTerminalTransition;)Ljava/lang/String;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "completeParentAndSeal",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ParentTerminalClaim;"
                      + "Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters$"
                      + "ParentTerminalTransition;)Ljava/lang/String;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "claimProviderSessionIntent",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ApprovedHandoff;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$Pack010Revision;"
                      + "Lio/emergeos/core/domain/GraphProviderIntent;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ProviderSessionIntent;",
                  BROKER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority",
                  "consumeProviderSessionIntent",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ProviderSessionIntent;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ApprovedHandoff;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$Pack010Revision;",
                  BROKER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "credentialReadStarted",
                  egressInstantDescriptor(),
                  BROKER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "requireEgressManifest",
                  "(Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/core/domain/"
                      + "GraphAttemptManifest;)V",
                  BROKER_CLASS,
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderCredentialBroker$CredentialLease",
                  "claim",
                  "(Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Ljava/time/Clock;)"
                      + "Ljava/lang/String;",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderCredentialBroker$CredentialLease",
                  "requireFresh",
                  "(Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Ljava/time/Clock;)V",
                  COMPOSER_CLASS,
                  COMPOSER_SESSION_CLASS,
                  CREDENTIAL_LEASE_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/openai/ReviewedOpenAiClient",
                  "defaultCodecNoRetry",
                  "(Ljava/lang/String;Ljava/lang/String;"
                      + "Ljava/net/Proxy;Ljava/time/Duration;"
                      + "Lcom/openai/core/LogLevel;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "ReviewedOpenAiClient;",
                  COMPOSER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/openai/ReviewedOpenAiClient",
                  "defaultCodecNoRetryNoRedirect",
                  "(Ljava/lang/String;Ljava/lang/String;"
                      + "Ljava/net/Proxy;Ljava/time/Duration;"
                      + "Lcom/openai/core/LogLevel;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "ReviewedOpenAiClient;",
                  DEEPSEEK_RESPONSES_PROBE_CLASS),
              noConsumerRule(
                  "io/emergeos/adapters/openai/OpenAiResponsesModel",
                  "withExactResponseAttribution",
                  "(Lio/emergeos/core/application/ModelExecutionProfile;"
                      + "Lio/emergeos/adapters/openai/ReviewedOpenAiClient;"
                      + "Lio/emergeos/adapters/openai/"
                      + "OpenAiResponsesModel$ProviderInvocationObserver;"
                      + "Lio/emergeos/adapters/openai/"
                      + "OpenAiResponsesModel$ExactProviderAttributionObserver;)"
                      + "Lio/emergeos/adapters/openai/OpenAiResponsesModel;"),
              rule(
                  MemberKind.METHOD,
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
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "clientCreated",
                  egressInstantDescriptor(),
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "modelCreated",
                  egressInstantDescriptor(),
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "providerIntent",
                  "(Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/core/domain/GraphProviderIntent;"
                      + "Ljava/time/Instant;)V",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "providerFailureAttributed",
                  "(Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/core/domain/GraphProviderAttribution;"
                      + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
                      + "Ljava/time/Instant;)V",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.INTERFACE_METHOD,
                  "io/emergeos/core/port/GraphAttemptStore",
                  "providerFailureAttributed",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/core/domain/GraphAttemptCursor;"
                      + "Lio/emergeos/core/domain/GraphProviderAttribution;"
                      + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
                      + "Ljava/time/Instant;)"
                      + "Lio/emergeos/core/domain/GraphAttemptCursor;",
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator.class"),
              noConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphAttemptStore",
                  "providerFailureAttributed",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/core/domain/GraphAttemptCursor;"
                      + "Lio/emergeos/core/domain/GraphProviderAttribution;"
                      + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
                      + "Ljava/time/Instant;)"
                      + "Lio/emergeos/core/domain/GraphAttemptCursor;"),
              noConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore",
                  "<init>",
                  "(Ljavax/sql/DataSource;)V"),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore",
                  "load",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;",
                  "io/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore.class"),
              noConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore",
                  "claim",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;"
                      + "Ljava/lang/String;Ljava/lang/String;"
                      + "Ljava/time/Duration;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;"),
              noConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore",
                  "completeClaimedFailureChild",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;"
                      + "Ljavax/sql/DataSource;"
                      + "Lio/emergeos/core/domain/AgentRun;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;"),
              noConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore",
                  "completeClaimedFailureParentAndSeal",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;"
                      + "Ljavax/sql/DataSource;"
                      + "Lio/emergeos/core/domain/AgentRun;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresAttributedFailureResumeStore$"
                      + "DurableFailureCursor;"),
              rule(
                  MemberKind.FIELD,
                  "java/net/Proxy",
                  "NO_PROXY",
                  "Ljava/net/Proxy;",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "ModelBoundReadOnlyWorkerExecutionProfile",
                  "deadlineMs",
                  "()J",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "java/time/Duration",
                  "ofMillis",
                  "(J)Ljava/time/Duration;",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.FIELD,
                  "com/openai/core/LogLevel",
                  "OFF",
                  "Lcom/openai/core/LogLevel;",
                  COMPOSER_CLASS),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderCredentialBroker",
                  "readAfterDurableEgress",
                  "(Lio/emergeos/grapheval/Pack010GraphPreflight$Result;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ApprovedHandoff;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$Pack010Revision;"
                      + "Ljava/time/Clock;)"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010ProviderCredentialBroker$CredentialLease;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "compose",
                  "(Lio/emergeos/grapheval/Pack010GraphPreflight$Result;"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010ProviderCredentialBroker$CredentialLease;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Ljava/time/Clock;)"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer$ProviderSession;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer$ProviderSession",
                  "next",
                  "(Lio/emergeos/adapters/agentloop/AgentModel$Turn;"
                      + "Lio/emergeos/adapters/agentloop/"
                      + "AgentModel$ModelCallContext;)"
                      + ATTRIBUTED_OUTCOME_DESCRIPTOR),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "reviewStructuredFinal",
                  structuredFinalDescriptor(),
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "claimStructuredFinal",
                  structuredFinalDescriptor(),
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "reviewAttributedPreCandidateFailure",
                  attributedFailureDescriptor(),
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "claimAttributedPreCandidateFailure",
                  attributedFailureDescriptor(),
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "preparePreCandidateFailureChild",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/core/domain/AgentRun;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters$"
                      + "ChildFailureTransition;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "preparePreCandidateFailureParentAndSeal",
                  "(Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/core/domain/AgentRun;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters$"
                      + "ParentFailureTransition;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "completePreCandidateFailureChild",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ChildTerminalClaim;"
                      + "Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters$"
                      + "ChildFailureTransition;)Ljava/lang/String;",
                  RUNTIME_COMPOSITION_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters",
                  "completePreCandidateFailureParentAndSeal",
                  "(Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "OwnerTtyGraphAuthority$ParentTerminalClaim;"
                      + "Lio/emergeos/core/domain/GraphAttemptManifest;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresGraphRuntimeWriters$"
                      + "ParentFailureTransition;)Ljava/lang/String;",
                  RUNTIME_COMPOSITION_CLASS),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition",
                  "prepareChild",
                  "("
                      + ATTRIBUTED_OUTCOME_DESCRIPTOR
                      + "Lio/emergeos/contracts/HarnessCandidateEnvelope;"
                      + "Lio/emergeos/core/domain/AgentRun;"
                      + "Lio/emergeos/contracts/WorkerResultEnvelope;)"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition$"
                      + "Pack010ChildTerminalCommand;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition",
                  "completeChild",
                  "(Lio/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition$"
                      + "Pack010ChildTerminalCommand;)Ljava/lang/String;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition",
                  "preparePreCandidateFailureChild",
                  "(" + ATTRIBUTED_OUTCOME_DESCRIPTOR
                      + "Lio/emergeos/core/domain/AgentRun;)"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition$"
                      + "Pack010FailedChildTerminalCommand;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition",
                  "completePreCandidateFailureChild",
                  "(Lio/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition$"
                      + "Pack010FailedChildTerminalCommand;)"
                      + "Ljava/lang/String;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition",
                  "preparePreCandidateFailureParentAndSeal",
                  "(Lio/emergeos/core/domain/AgentRun;)"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition$"
                      + "Pack010FailedParentTerminalCommand;"),
              noConsumerRule(
                  "io/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition",
                  "completePreCandidateFailureParentAndSeal",
                  "(Lio/emergeos/grapheval/"
                      + "Pack010PostgresRuntimeComposition$"
                      + "Pack010FailedParentTerminalCommand;)"
                      + "Ljava/lang/String;"),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "durableIntentObserver",
                  "(Lio/emergeos/core/application/"
                      + "ModelBoundReadOnlyWorkerExecutionProfile;I"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010ProviderCredentialBroker$CredentialLease;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Ljava/time/Clock;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "OpenAiResponsesModel$ProviderInvocationObserver;",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer",
                  "durableAttributionObserver",
                  "(Lio/emergeos/core/application/"
                      + "ModelBoundReadOnlyWorkerExecutionProfile;I"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator;"
                      + "Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Ljava/time/Clock;"
                      + "Lio/emergeos/grapheval/"
                      + "Pack010ProviderSessionComposer$OutcomeLedger;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "OpenAiResponsesModel$ExactProviderAttributionObserver;",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/GraphProviderAttribution",
                  "create",
                  "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                      + "Ljava/lang/String;Ljava/lang/String;"
                      + "Lio/emergeos/core/domain/GraphPricingSnapshot;"
                      + "JJJJJLjava/math/BigDecimal;)"
                      + "Lio/emergeos/core/domain/"
                      + "GraphProviderAttribution;",
                  COMPOSER_CLASS),
              rule(
                  MemberKind.METHOD,
                  "io/emergeos/core/application/"
                      + "GraphAttemptCoordinator",
                  "providerAttributed",
                  "(Lio/emergeos/core/application/"
                      + "GraphAttemptCoordinator$EgressAuthority;"
                      + "Lio/emergeos/core/domain/"
                      + "GraphProviderAttribution;"
                      + "Ljava/time/Instant;)V",
                  COMPOSER_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresProviderValidationAttestor",
                  "open",
                  "(Ljavax/sql/DataSource;)"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresProviderValidationAttestor;"),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresProviderValidationAttestor",
                  "<init>",
                  "(Ljavax/sql/DataSource;"
                      + "Lio/emergeos/adapters/postgres/"
                      + "PostgresProviderValidationAttestor$Probe;)V",
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresProviderValidationAttestor",
                  "requireValidation",
                  providerValidationRequirementDescriptor()),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresProviderValidationAttestor",
                  "completeValidation",
                  providerValidationCompletionDescriptor()),
              firstPartyWideRule(
                  MemberKind.INTERFACE_METHOD,
                  "io/emergeos/core/port/"
                      + "GraphProviderValidationAttestor",
                  "requireValidation",
                  providerValidationRequirementDescriptor()),
              firstPartyWideRule(
                  MemberKind.INTERFACE_METHOD,
                  "io/emergeos/core/port/"
                      + "GraphProviderValidationAttestor",
                  "completeValidation",
                  providerValidationCompletionDescriptor()),
              firstPartyWideRule(
                  MemberKind.INTERFACE_METHOD,
                  "io/emergeos/core/port/GraphProviderValidationSigner",
                  "attest",
                  "(Lio/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript;)"
                      + "Lio/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation;",
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript",
                  "create",
                  providerValidationTranscriptCreateDescriptor(),
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation",
                  "verifiesWith",
                  "([B)Z",
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation",
                  "transcript",
                  "()Lio/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript;",
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation",
                  "signatureHex",
                  "()Ljava/lang/String;",
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.FIELD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation",
                  "transcript",
                  "Lio/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript;",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS),
              firstPartyWideRule(
                  MemberKind.FIELD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation",
                  "signatureHex",
                  "Ljava/lang/String;",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationAttestation",
                  "<init>",
                  "(Lio/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript;"
                      + "Ljava/lang/String;)V"),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationChallenge",
                  "<init>",
                  providerValidationChallengeConstructorDescriptor(),
                  PROVIDER_VALIDATION_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationStatement",
                  "<init>",
                  providerValidationStatementConstructorDescriptor(),
                  OPENAI_VALIDATION_STATEMENTS_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/openai/"
                      + "OpenAiResponsesModel$ProviderOutcomeReceipt",
                  "<init>",
                  providerOutcomeReceiptConstructorDescriptor(),
                  OPENAI_RESPONSES_SESSION_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/openai/"
                      + "OpenAiProviderValidationStatements",
                  "policy",
                  "()Lio/emergeos/adapters/openai/"
                      + "OpenAiProviderValidationStatements$Policy;"),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/openai/"
                      + "OpenAiProviderValidationStatements",
                  "fromExactOutcome",
                  providerValidationStatementMapperDescriptor()),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript",
                  "<init>",
                  providerValidationTranscriptConstructorDescriptor(),
                  PROVIDER_VALIDATION_TRANSCRIPT_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphProviderValidationTranscript",
                  "signatureMaterial",
                  "()[B",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderSignatureVerifier",
                  "verifyOrThrow",
                  exactPicoSignatureVerifierDescriptor(),
                  EXACT_PICO_TYPED_ATTESTOR_CLASS,
                  EXACT_PICO_OVERLAY_READER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderValidationChallenge",
                  "<init>",
                  exactPicoValidationChallengeConstructorDescriptor(),
                  EXACT_PICO_TYPED_ATTESTOR_CLASS,
                  EXACT_PICO_OVERLAY_READER_VALIDATION_ROW_CLASS),
              firstPartyWideRule(
                  MemberKind.INTERFACE_METHOD,
                  "io/emergeos/core/port/"
                      + "GraphExactPicoProviderValidationSigner",
                  "sign",
                  exactPicoSignerDescriptor(),
                  EXACT_PICO_TYPED_ATTESTOR_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderValidationReceipt",
                  "<init>",
                  exactPicoReceiptConstructorDescriptor(),
                  EXACT_PICO_TYPED_ATTESTOR_CLASS,
                  EXACT_PICO_OVERLAY_READER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderValidationCommand",
                  "<init>",
                  exactPicoCommandConstructorDescriptor()),
              firstPartyWideRule(
                  MemberKind.INTERFACE_METHOD,
                  "io/emergeos/core/port/"
                      + "GraphExactPicoProviderValidationAttestor",
                  "complete",
                  exactPicoAttestorCompleteDescriptor()),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresExactPicoProviderValidationAttestor",
                  "open",
                  exactPicoAttestorOpenDescriptor()),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresExactPicoProviderValidationAttestor",
                  "complete",
                  exactPicoAttestorCompleteDescriptor()),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresExactPicoProviderValidationAttestor",
                  "<init>",
                  "(Ljavax/sql/DataSource;Lio/emergeos/adapters/postgres/"
                      + "PostgresExactPicoProviderValidationAttestor$Probe;)V",
                  EXACT_PICO_TYPED_ATTESTOR_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresExactPicoOverlayReader",
                  "open",
                  "(Ljavax/sql/DataSource;)Lio/emergeos/core/port/"
                      + "GraphExactPicoOverlayReader;"),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/postgres/"
                      + "PostgresExactPicoOverlayReader",
                  "<init>",
                  "(Ljavax/sql/DataSource;)V",
                  EXACT_PICO_OVERLAY_READER_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/postgres/"
                      + "PostgresExactPicoOverlayReader",
                  "findVerified",
                  exactPicoOverlayReaderDescriptor()),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/core/port/GraphExactPicoOverlayReader",
                  "findVerified",
                  exactPicoOverlayReaderDescriptor()),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderValidationChallenge",
                  "signatureMaterial",
                  "()[B",
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderValidationChallenge",
                  "keyFingerprint",
                  "()Ljava/lang/String;",
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.FIELD,
                  "io/emergeos/core/domain/"
                      + "GraphExactPicoProviderValidationChallenge",
                  "keyFingerprint",
                  "Ljava/lang/String;",
                  EXACT_PICO_VALIDATION_CHALLENGE_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/Signature",
                  "getInstance",
                  "(Ljava/lang/String;)Ljava/security/Signature;",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/KeyFactory",
                  "getInstance",
                  "(Ljava/lang/String;)Ljava/security/KeyFactory;",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/spec/X509EncodedKeySpec",
                  "<init>",
                  "([B)V",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/KeyFactory",
                  "generatePublic",
                  "(Ljava/security/spec/KeySpec;)"
                      + "Ljava/security/PublicKey;",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/Signature",
                  "initVerify",
                  "(Ljava/security/PublicKey;)V",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/Signature",
                  "update",
                  "([B)V",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "java/security/Signature",
                  "verify",
                  "([B)Z",
                  PROVIDER_VALIDATION_ATTESTATION_CLASS,
                  EXACT_PICO_SIGNATURE_VERIFIER_CLASS),
              firstPartyWideRule(
                  MemberKind.INTERFACE_METHOD,
                  "com/openai/core/http/HttpResponseFor",
                  "body",
                  "()Ljava/io/InputStream;",
                  REVIEWED_OPENAI_CLIENT_CLASS),
              firstPartyWideRule(
                  MemberKind.METHOD,
                  "io/emergeos/adapters/openai/ReviewedOpenAiClient",
                  "createReviewedResponse",
                  "(Lcom/openai/models/responses/ResponseCreateParams;"
                      + "Lcom/openai/core/RequestOptions;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "ReviewedOpenAiClient$ReviewedResponse;",
                  OPENAI_RESPONSES_SESSION_CLASS,
                  DEEPSEEK_RESPONSES_PROBE_CLASS),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/openai/"
                      + "DeepSeekV4FlashResponsesProbe",
                  "execute",
                  "(Ljava/lang/String;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "DeepSeekV4FlashResponsesProbe$Receipt;"),
              firstPartyWideNoConsumerRule(
                  "io/emergeos/adapters/openai/"
                      + "DeepSeekV4FlashResponsesProbe",
                  "executeForTest",
                  "(Ljava/lang/String;Ljava/lang/String;)"
                      + "Lio/emergeos/adapters/openai/"
                      + "DeepSeekV4FlashResponsesProbe$Receipt;"));
  private static final Set<String> ALLOWED_APP_CLASSES =
      Set.of(
          "io/emergeos/grapheval/GraphEvalCli$Mode.class",
          "io/emergeos/grapheval/GraphEvalCli.class",
          "io/emergeos/grapheval/GraphEvalMain.class",
          "io/emergeos/grapheval/"
              + "HarnessEvaluationReportJson$Rejected.class",
          "io/emergeos/grapheval/"
              + "HarnessEvaluationReportJson.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphEvalCatalog.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "EnvironmentManifest.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "InputTokenUpperBound.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Model.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "ModelWorkerEval.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "OperatorGate.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Pricing.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "PromptCachePolicy.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "Provenance.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Rejected.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$"
              + "RequestPolicy.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Result.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$Seed.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight$TaskPack.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphPreflight.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphVerifier$Rejected.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphVerifier$Result.class",
          "io/emergeos/grapheval/"
              + "Pack009GraphVerifier.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphEvalCatalog$RepetitionSpec.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphEvalCatalog.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$EnvironmentManifest.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$EvaluatorPolicy.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$HarnessPilot.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$InputTokenUpperBound.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Model.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$OperatorGate.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Pricing.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$PromptCachePolicy.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Provenance.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Rejected.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Repetition.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$RequestPolicy.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Result.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$Seed.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight$TaskPack.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphPreflight.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderCredentialBroker$CredentialLease.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderCredentialBroker$CredentialRejected.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderCredentialBroker.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$OutcomeLedger.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$"
              + "AttributedPreCandidateFailure.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$"
              + "Pack010AttributedModelOutcome.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$"
              + "PreCandidateFailureCode.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$ProviderSession.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$ProviderSessionRejected.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer$StructuredFinalBinding.class",
          "io/emergeos/grapheval/"
              + "Pack010ProviderSessionComposer.class",
          "io/emergeos/grapheval/"
              + "Pack010PostgresRuntimeComposition$"
              + "Pack010ChildTerminalCommand.class",
          "io/emergeos/grapheval/"
              + "Pack010PostgresRuntimeComposition$"
              + "Pack010FailedChildTerminalCommand.class",
          "io/emergeos/grapheval/"
              + "Pack010PostgresRuntimeComposition$"
              + "Pack010FailedParentTerminalCommand.class",
          "io/emergeos/grapheval/"
              + "Pack010PostgresRuntimeComposition$"
              + "Pack010ParentTerminalCommand.class",
          "io/emergeos/grapheval/"
              + "Pack010PostgresRuntimeComposition.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphTerminalVerifier$1.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphTerminalVerifier$Checkpoint.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphTerminalVerifier$Rejected.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphTerminalVerifier$Result.class",
          "io/emergeos/grapheval/"
              + "Pack010GraphTerminalVerifier.class");
  private static final List<String> ALLOWED_FIRST_PARTY =
      List.of(
          "io/emergeos/grapheval/",
          "io/emergeos/contracts/",
          "io/emergeos/core/",
          "io/emergeos/adapters/agentloop/",
          "io/emergeos/adapters/openai/",
          "io/emergeos/adapters/postgres/");
  private static final List<String> FORBIDDEN_EXTERNAL =
      List.of(
          "org/springframework/boot/",
          "org/springframework/web/",
          "org/springframework/ai/",
          "io/temporal/",
          "org/hibernate/",
          "jakarta/persistence/",
          "org/langchain4j/",
          "dev/langchain4j/",
          "com/microsoft/semantickernel/");
  private static final List<String> FORBIDDEN_ARCHIVE_ENTRIES =
      List.of(
          "io/emergeos/api/",
          "io/emergeos/evalrunner/",
          "io/emergeos/offlineharness/",
          "io/emergeos/adapters/inmemory/",
          "io/emergeos/adapters/postgres/"
              + "PostgresProviderValidationAttestorTestAccess",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoProviderValidationAttestorTestAccess",
          "io/emergeos/adapters/postgres/"
              + "PostgresExactPicoOverlayReaderSurfaceTest",
          "io/emergeos/adapters/openai/"
              + "DeepSeekV4FlashResponsesProbeMain",
          "io/emergeos/adapters/openai/"
              + "DeepSeekV4FlashResponsesProbeTest",
          "io/emergeos/grapheval/"
              + "Pack010ProviderValidationAttestationHarnessMain",
          "io/emergeos/grapheval/"
              + "Pack010ProviderValidationAttestationProcessIT",
          "io/emergeos/grapheval/"
              + "Pack010ProviderValidationAttestationAcceptanceTest",
          "io/emergeos/grapheval/"
              + "Pack010ExactPicoOverlayTxAAcceptanceTest",
          "io/emergeos/grapheval/"
              + "Pack010ExactPicoOverlayReaderAcceptanceIT",
          "io/emergeos/grapheval/"
              + "Pack010ExactPicoOverlayReaderMain",
          "io/emergeos/grapheval/"
              + "Pack010CommitBeforeDelegateHardKillDataSource",
          "io/emergeos/core/port/"
              + "GraphExactPicoOverlayReaderSurfaceTest",
          "io/emergeos/core/domain/"
              + "GraphExactPicoProviderSignatureVerifierTest",
          "org/springframework/boot/",
          "org/springframework/web/",
          "org/springframework/ai/",
          "io/temporal/",
          "org/hibernate/",
          "jakarta/persistence/",
          "org/langchain4j/",
          "dev/langchain4j/",
          "com/microsoft/semantickernel/");

  private GraphEvalBytecodeGate() {}

  static List<String> directoryViolations(Path classes)
      throws IOException {
    if (!Files.isDirectory(classes)) {
      throw new IllegalArgumentException(
          "classes directory is required");
    }
    Set<String> violations = new TreeSet<>();
    try (Stream<Path> paths = Files.walk(classes)) {
      for (Path path :
          paths
              .filter(Files::isRegularFile)
              .filter(
                  candidate ->
                      candidate
                          .toString()
                          .endsWith(".class"))
              .toList()) {
        String name =
            classes
                .relativize(path)
                .toString()
                .replace('\\', '/');
        violations.addAll(
            appOwnedClassViolations(name));
        violations.addAll(
            archiveEntryViolations(name, name));
        violations.addAll(
            classViolations(name, name, Files.readAllBytes(path)));
      }
    }
    return List.copyOf(violations);
  }

  static List<String> shippingJarViolations(Path jarPath)
      throws IOException {
    Set<String> violations = new TreeSet<>();
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      for (JarEntry entry :
          jar.stream()
              .filter(candidate -> !candidate.isDirectory())
              .toList()) {
        String name = entry.getName();
        String logicalName = logicalEntryName(name);
        if (logicalName == null) {
          violations.add(
              name + " -> archive:malformed-multi-release-entry");
          continue;
        }
        violations.addAll(
            archiveEntryViolations(name, logicalName));
        if (logicalName.startsWith(FIRST_PARTY_ROOT)
            && logicalName.endsWith(".class")) {
          try (InputStream input = jar.getInputStream(entry)) {
            violations.addAll(
                classViolations(
                    name, logicalName, input.readAllBytes()));
          }
        }
      }
    }
    return List.copyOf(violations);
  }

  private static List<String> archiveEntryViolations(
      String physicalName, String logicalName) {
    List<String> violations = new ArrayList<>();
    for (String forbidden : FORBIDDEN_ARCHIVE_ENTRIES) {
      if (logicalName.startsWith(forbidden)) {
        violations.add(
            physicalName + " -> archive:" + forbidden);
      }
    }
    if (logicalName.startsWith(APP_CLASS_ROOT)
        && logicalName.endsWith(".class")) {
      violations.addAll(
          appOwnedClassViolations(physicalName));
    }
    return List.copyOf(violations);
  }

  private static List<String> appOwnedClassViolations(
      String physicalName) {
    if (!physicalName.endsWith(".class")
        || ALLOWED_APP_CLASSES.contains(physicalName)) {
      return List.of();
    }
    return List.of(
        physicalName
            + " -> archive:unreviewed-graph-eval-class");
  }

  private static String logicalEntryName(String name) {
    if (!name.startsWith(MULTI_RELEASE_ROOT)) {
      return name;
    }
    int versionStart = MULTI_RELEASE_ROOT.length();
    int versionEnd = name.indexOf('/', versionStart);
    if (versionEnd <= versionStart
        || versionEnd == name.length() - 1) {
      return null;
    }
    for (int index = versionStart;
        index < versionEnd;
        index++) {
      if (!Character.isDigit(name.charAt(index))) {
        return null;
      }
    }
    return name.substring(versionEnd + 1);
  }

  static List<String> classViolations(
      String className, byte[] classBytes) throws IOException {
    return classViolations(className, className, classBytes);
  }

  private static List<String> classViolations(
      String physicalName,
      String logicalName,
      byte[] classBytes) throws IOException {
    Set<String> violations = new TreeSet<>();
    for (String constant : utf8Constants(classBytes)) {
      String normalized = constant.replace('.', '/');
      int firstPartyIndex =
          normalized.indexOf(FIRST_PARTY_ROOT);
      while (firstPartyIndex >= 0) {
        String reference =
            normalized.substring(firstPartyIndex);
        if (ALLOWED_FIRST_PARTY.stream()
            .noneMatch(reference::startsWith)) {
          violations.add(
              physicalName + " -> " + boundedReference(reference));
        }
        firstPartyIndex =
            normalized.indexOf(
                FIRST_PARTY_ROOT,
                firstPartyIndex + FIRST_PARTY_ROOT.length());
      }
      for (String forbidden : FORBIDDEN_EXTERNAL) {
        if (normalized.contains(forbidden)) {
          violations.add(physicalName + " -> " + forbidden);
        }
      }
    }
    violations.addAll(
        capabilityViolations(
            physicalName, logicalName, classBytes));
    return List.copyOf(violations);
  }

  private static List<String> capabilityViolations(
      String physicalName,
      String logicalName,
      byte[] classBytes) throws IOException {
    if (!logicalName.startsWith(FIRST_PARTY_ROOT)) {
      return List.of();
    }
    boolean appOwned = logicalName.startsWith(APP_CLASS_ROOT);
    Set<String> violations = new TreeSet<>();
    for (MemberReference reference : memberReferences(classBytes)) {
      if (isIndirectCapabilityReference(reference)
          && !isReviewedIndirectCapabilityReference(
              physicalName, logicalName, reference)) {
        violations.add(
            physicalName
                + " -> capability:unreviewed-indirect-access:"
                + reference.owner()
                + "."
                + reference.name()
                + reference.descriptor());
      }
      if (isPrivateSigningReference(reference)) {
        violations.add(
            physicalName
                + " -> capability:private-signing-forbidden:"
                + reference.owner()
                + "."
                + reference.name()
                + reference.descriptor());
      }
      List<CapabilityMemberRule> namedRules =
          CAPABILITY_MEMBER_RULES.stream()
              .filter(
                  rule ->
                      appOwned
                          || rule.firstPartyWide()
                          || isFirstPartyWideDurableFailureRule(rule))
              .filter(rule -> reference.owner().equals(rule.owner()))
              .filter(rule -> reference.name().equals(rule.name()))
              .toList();
      if (!namedRules.isEmpty()) {
        List<CapabilityMemberRule> exactRules =
            namedRules.stream()
                .filter(rule -> reference.kind() == rule.kind())
                .filter(
                    rule ->
                        reference.descriptor().equals(rule.descriptor()))
                .toList();
        if (exactRules.isEmpty()) {
          violations.add(
              physicalName
                  + " -> capability:wrong-signature:"
                  + reference.owner()
                  + "."
                  + reference.name()
                  + reference.descriptor());
        } else if (exactRules.stream()
            .noneMatch(
                rule ->
                    rule.allowedConsumers().contains(logicalName)
                        && (!rule.firstPartyWide()
                            || physicalName.equals(logicalName)))) {
          violations.add(
              physicalName
                  + " -> capability:unreviewed-consumer:"
                  + reference.owner()
                  + "."
                  + reference.name()
                  + reference.descriptor());
        }
      }
    }
    if (hasConstantDynamic(classBytes)) {
      violations.add(
          physicalName + " -> capability:constant-dynamic-forbidden");
    }
    for (BootstrapReference bootstrap :
        bootstrapReferences(classBytes)) {
      if (!ALLOWED_BOOTSTRAPS.contains(bootstrap.bootstrap())) {
        violations.add(
            physicalName
                + " -> capability:unreviewed-invokedynamic-bootstrap:"
                + bootstrap.bootstrap().owner()
                + "."
                + bootstrap.bootstrap().name()
                + bootstrap.bootstrap().descriptor());
      }
    }
    List<String> constants = utf8Constants(classBytes);
    if (logicalName.equals(DEEPSEEK_RESPONSES_PROBE_CLASS)) {
      if (!constants.contains(DEEPSEEK_PRODUCTION_BASE_URL)) {
        violations.add(
            physicalName
                + " -> capability:deepseek-production-url-drift");
      }
      if (!constants.contains(DEEPSEEK_V4_FLASH_MODEL)) {
        violations.add(
            physicalName + " -> capability:deepseek-model-drift");
      }
      for (String constant : constants) {
        if ((constant.startsWith("http://")
                || constant.startsWith("https://"))
            && !constant.equals(DEEPSEEK_PRODUCTION_BASE_URL)) {
          violations.add(
              physicalName
                  + " -> capability:unreviewed-deepseek-url:"
                  + constant);
        }
      }
    }
    for (String constant : constants) {
      String normalized = constant.replace('.', '/');
      for (String signingType : PRIVATE_SIGNING_TYPES) {
        if (normalized.equals(signingType)
            || normalized.contains("L" + signingType + ";")) {
          violations.add(
              physicalName
                  + " -> capability:private-signing-type-forbidden:"
                  + signingType);
        }
      }
      for (String authorityConstant : V13_AUTHORITY_CONSTANTS) {
        boolean reviewedV19ReaderSurface =
            physicalName.equals(logicalName)
                && logicalName.equals(EXACT_PICO_OVERLAY_READER_CLASS)
                && (V19_EXACT_OVERLAY_RELATIONS.contains(authorityConstant)
                    || authorityConstant.equals(
                        "emergeos_provider_attestor"));
        boolean reviewedV13Authority =
            physicalName.equals(logicalName)
                && (logicalName.equals(
                        PROVIDER_VALIDATION_ATTESTOR_CLASS)
                    || (logicalName.equals(
                            EXACT_PICO_TYPED_ATTESTOR_CLASS)
                        && Set.of(
                                "agent_graph_provider_validation_keys",
                                "agent_graph_provider_validations")
                            .contains(authorityConstant))
                    || (logicalName.equals(
                            EXACT_PICO_TYPED_ATTESTOR_CLASS)
                        && authorityConstant.equals(
                            "emergeos_provider_attestor")
                        && constant.contains(
                            "emergeos_provider_attestor_v16")))
                || reviewedV19ReaderSurface;
        if (constant.contains(authorityConstant)
            && !reviewedV13Authority) {
            violations.add(
                physicalName
                    + " -> capability:unreviewed-v13-semantic-function:"
                    + authorityConstant);
        }
      }
      for (String authorityConstant :
          V14_PROFILE_AUTHORITY_CONSTANTS) {
        boolean reviewedPrerequisiteRelation =
            physicalName.equals(logicalName)
                && (logicalName.equals(EXACT_PICO_TYPED_ATTESTOR_CLASS)
                    || logicalName.equals(
                        EXACT_PICO_OVERLAY_READER_CLASS))
                && authorityConstant.equals(
                    "agent_graph_provider_profiles_v14");
        if (constant.contains(authorityConstant)
            && !reviewedPrerequisiteRelation) {
          violations.add(
              physicalName
                  + " -> capability:unreviewed-v14-profile-authority:"
                  + authorityConstant);
        }
      }
      for (String authorityConstant :
          V15_EXACT_TX_A_AUTHORITY_CONSTANTS) {
        boolean reviewedV18PrerequisiteRelation =
            physicalName.equals(logicalName)
                && logicalName.equals(EXACT_PICO_TYPED_ATTESTOR_CLASS)
                && authorityConstant.equals(
                    "agent_graph_exact_tx_a_requirements_v15");
        boolean reviewedV19ReaderSurface =
            physicalName.equals(logicalName)
                && logicalName.equals(EXACT_PICO_OVERLAY_READER_CLASS)
                && (authorityConstant.equals(
                        "agent_graph_exact_tx_a_requirements_v15")
                    || authorityConstant.equals(
                        "emergeos_provider_attestor_v15"));
        if (constant.contains(authorityConstant)
            && !reviewedV18PrerequisiteRelation
            && !reviewedV19ReaderSurface) {
          violations.add(
              physicalName
                  + " -> capability:unreviewed-v15-exact-tx-a-authority:"
                  + authorityConstant);
        }
      }
      for (String authorityConstant :
          V16_EXACT_PICO_AUTHORITY_CONSTANTS) {
        boolean reviewedV19ReaderSurface =
            physicalName.equals(logicalName)
                && logicalName.equals(EXACT_PICO_OVERLAY_READER_CLASS)
                && (V19_EXACT_OVERLAY_RELATIONS.contains(authorityConstant)
                    || authorityConstant.equals(
                        "emergeos_provider_attestor_v16"));
        if (constant.contains(authorityConstant)
            && !(physicalName.equals(logicalName)
                && logicalName.equals(EXACT_PICO_TYPED_ATTESTOR_CLASS))
            && !reviewedV19ReaderSurface) {
          violations.add(
              physicalName
                  + " -> capability:unreviewed-v16-exact-pico-authority:"
                  + authorityConstant);
        }
      }
      if (constant.contains(V19_EXACT_OVERLAY_READER_ROLE)
          && !(physicalName.equals(logicalName)
              && V19_READER_ROLE_CONSUMERS.contains(logicalName))) {
        violations.add(
            physicalName
                + " -> capability:unreviewed-v19-reader-role:"
                + V19_EXACT_OVERLAY_READER_ROLE);
      }
      if (isV19ReaderClass(logicalName)) {
        for (String writerType : V19_FORBIDDEN_WRITER_TYPES) {
          if (normalized.contains(writerType)) {
            violations.add(
                physicalName
                    + " -> capability:v19-reader-writer-type-forbidden:"
                    + writerType);
          }
        }
        if (MUTATING_GRAPH_SQL.matcher(constant).find()) {
          violations.add(
              physicalName
                  + " -> capability:v19-reader-mutating-sql-forbidden");
        }
        var relationMatcher =
            QUALIFIED_GRAPH_SQL_SURFACE.matcher(constant);
        while (relationMatcher.find()) {
          String relation = relationMatcher.group(1).toLowerCase();
          if (!V19_EXACT_OVERLAY_RELATIONS.contains(relation)) {
            violations.add(
                physicalName
                    + " -> capability:v19-reader-unreviewed-sql-surface:"
                    + relation);
          }
        }
      }
    }
    if (physicalName.equals(logicalName)
        && logicalName.equals(EXACT_PICO_OVERLAY_READER_CLASS)) {
      Set<String> qualifiedRelations = new TreeSet<>();
      for (String constant : constants) {
        var matcher = QUALIFIED_GRAPH_SQL_SURFACE.matcher(constant);
        while (matcher.find()) {
          qualifiedRelations.add(matcher.group(1).toLowerCase());
        }
      }
      if (!qualifiedRelations.equals(V19_EXACT_OVERLAY_RELATIONS)) {
        violations.add(
            physicalName
                + " -> capability:v19-reader-relation-surface-drift");
      }
      for (String relation : V19_EXACT_OVERLAY_RELATIONS) {
        if (constants.stream()
            .noneMatch(
                constant ->
                    constant.contains("FROM public." + relation))) {
          violations.add(
              physicalName
                  + " -> capability:v19-reader-direct-select-missing:"
                  + relation);
        }
      }
      if (!constants.contains(V19_EXACT_OVERLAY_READER_ROLE)) {
        violations.add(
            physicalName
                + " -> capability:v19-reader-role-drift");
      }
    }
    if (!appOwned) {
      return List.copyOf(violations);
    }
    if (logicalName.equals(CREDENTIAL_LEASE_CLASS)
        && !constants.contains(CREDENTIAL_NAME)) {
      violations.add(
          physicalName + " -> capability:credential-name-drift");
    }
    if (!logicalName.equals(CREDENTIAL_LEASE_CLASS)
        && constants.contains(CREDENTIAL_NAME)) {
      violations.add(
          physicalName + " -> capability:unreviewed-credential-reader");
    }
    if (logicalName.equals(COMPOSER_CLASS)) {
      if (!constants.contains(PRODUCTION_BASE_URL)) {
        violations.add(
            physicalName + " -> capability:production-base-url-drift");
      }
      for (String constant : constants) {
        if ((constant.startsWith("http://")
                || constant.startsWith("https://"))
            && !constant.equals(PRODUCTION_BASE_URL)) {
          violations.add(
              physicalName
                  + " -> capability:unreviewed-provider-url:"
                  + constant);
        }
      }
    }
    if (logicalName.equals(BROKER_CLASS)
        || logicalName.equals(COMPOSER_CLASS)) {
      if (memberReferences(classBytes).stream()
              .noneMatch(
                  reference ->
                      reference.owner().equals("java/time/Instant")
                          && reference.name().equals("truncatedTo")
                          && reference.descriptor().equals(
                              "(Ljava/time/temporal/TemporalUnit;)"
                                  + "Ljava/time/Instant;"))
          || !constants.contains("MICROS")) {
        violations.add(
            physicalName
                + " -> capability:postgres-time-canonicalization-missing");
      }
    }
    return List.copyOf(violations);
  }

  private static boolean isV19ReaderClass(String logicalName) {
    return logicalName.equals(EXACT_PICO_OVERLAY_READER_CLASS)
        || (logicalName.startsWith(
                EXACT_PICO_OVERLAY_READER_PREFIX + "$")
            && logicalName.endsWith(".class"));
  }

  private static boolean isFirstPartyWideDurableFailureRule(
      CapabilityMemberRule rule) {
    if (rule.owner().equals(
        "io/emergeos/adapters/postgres/"
            + "PostgresGraphRuntimeWriters")) {
      return switch (rule.name()) {
        case "preparePreCandidateFailureChild",
            "preparePreCandidateFailureParentAndSeal",
            "completePreCandidateFailureChild",
            "completePreCandidateFailureParentAndSeal" -> true;
        default -> false;
      };
    }
    return (rule.owner().equals(
                "io/emergeos/adapters/openai/OpenAiResponsesModel")
            && (rule.name().equals("withExactResponseAttribution")
                || rule.name().equals("withExactResponseOutcome")))
        || (rule.owner().equals(
                "io/emergeos/core/application/GraphAttemptCoordinator")
            && rule.name().equals("providerFailureAttributed"))
        || (rule.owner().equals("io/emergeos/core/port/GraphAttemptStore")
            && rule.name().equals("providerFailureAttributed"))
        || (rule.owner().equals(
                "io/emergeos/adapters/postgres/"
                    + "PostgresGraphAttemptStore")
            && rule.name().equals("providerFailureAttributed"))
        || rule.owner().equals(
            "io/emergeos/adapters/postgres/"
                + "PostgresAttributedFailureResumeStore");
  }

  private static boolean isPrivateSigningReference(
      MemberReference reference) {
    return (reference.owner().equals("java/security/Signature")
            && (reference.name().equals("initSign")
                || reference.name().equals("sign")))
        || (reference.owner().equals("java/security/KeyFactory")
            && reference.name().equals("generatePrivate"))
        || reference.owner().equals("java/security/KeyPairGenerator")
        || (reference.owner().equals("java/security/KeyPair")
            && reference.name().equals("getPrivate"));
  }

  private static boolean isIndirectCapabilityReference(
      MemberReference reference) {
    if (reference.owner().equals("java/lang/Class")) {
      return reference.name().equals("forName")
          || reference.name().startsWith("getDeclared")
          || reference.name().equals("getMethod")
          || reference.name().equals("getMethods")
          || reference.name().equals("getField")
          || reference.name().equals("getFields");
    }
    if (reference.owner().equals("java/lang/ClassLoader")) {
      return reference.name().equals("loadClass");
    }
    if (reference.owner().equals("java/lang/reflect/Method")) {
      return reference.name().equals("invoke")
          || reference.name().equals("setAccessible")
          || reference.name().equals("trySetAccessible");
    }
    if (reference.owner().equals("java/lang/reflect/Constructor")) {
      return reference.name().equals("newInstance")
          || reference.name().equals("setAccessible")
          || reference.name().equals("trySetAccessible");
    }
    if (reference.owner().equals("java/lang/reflect/Field")) {
      return reference.name().startsWith("get")
          || reference.name().startsWith("set");
    }
    return reference.owner().equals("java/lang/invoke/MethodHandles")
        || reference.owner().startsWith(
            "java/lang/invoke/MethodHandles$")
        || reference.owner().equals("java/lang/invoke/MethodHandle")
        || reference.owner().equals(
            "java/lang/invoke/ConstantBootstraps");
  }

  private static boolean isReviewedIndirectCapabilityReference(
      String physicalName,
      String logicalName,
      MemberReference reference) {
    if (!physicalName.equals(logicalName)
        || reference.kind() != MemberKind.METHOD) {
      return false;
    }
    if ((logicalName.equals(REPORT_JSON_CLASS)
            || logicalName.equals(CANONICAL_ENCODING_CLASS))
        && reference.owner().equals("java/lang/reflect/Method")
        && reference.name().equals("invoke")
        && reference.descriptor().equals(
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
      return true;
    }
    return logicalName.equals(OWNER_AUTHORITY_TYPES_CLASS)
        && reference.owner().equals("java/lang/Class")
        && reference.name().equals("forName")
        && reference.descriptor().equals(
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
  }

  private static boolean hasConstantDynamic(byte[] classBytes)
      throws IOException {
    try (DataInputStream input =
        new DataInputStream(new ByteArrayInputStream(classBytes))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("invalid classfile magic");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int constantPoolCount = input.readUnsignedShort();
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = input.readUnsignedByte();
        if (tag == 17) {
          return true;
        }
        switch (tag) {
          case 1 -> input.readUTF();
          case 3, 4 -> input.readInt();
          case 5, 6 -> {
            input.readLong();
            index++;
          }
          case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
          case 9, 10, 11, 12, 18 -> {
            input.readUnsignedShort();
            input.readUnsignedShort();
          }
          case 15 -> {
            input.readUnsignedByte();
            input.readUnsignedShort();
          }
          default ->
              throw new IOException(
                  "unsupported classfile constant tag " + tag);
        }
      }
      return false;
    }
  }

  /** Resolves every CONSTANT_InvokeDynamic bootstrap method handle. */
  static List<BootstrapReference> bootstrapReferences(byte[] classBytes)
      throws IOException {
    try (DataInputStream input =
        new DataInputStream(new ByteArrayInputStream(classBytes))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("invalid classfile magic");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int constantPoolCount = input.readUnsignedShort();
      Object[] pool = new Object[constantPoolCount];
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> pool[index] = input.readUTF();
          case 3, 4 -> input.readInt();
          case 5, 6 -> {
            input.readLong();
            index++;
          }
          case 7 -> pool[index] =
              new ClassInfo(input.readUnsignedShort());
          case 8, 16, 19, 20 -> input.readUnsignedShort();
          case 9, 10, 11 ->
              pool[index] =
                  new ReferenceInfo(
                      tag,
                      input.readUnsignedShort(),
                      input.readUnsignedShort());
          case 12 ->
              pool[index] =
                  new NameAndTypeInfo(
                      input.readUnsignedShort(),
                      input.readUnsignedShort());
          case 15 ->
              pool[index] =
                  new MethodHandleInfo(
                      input.readUnsignedByte(),
                      input.readUnsignedShort());
          case 17, 18 ->
              pool[index] =
                  new DynamicInfo(
                      tag,
                      input.readUnsignedShort(),
                      input.readUnsignedShort());
          default ->
              throw new IOException(
                  "unsupported classfile constant tag " + tag);
        }
      }

      input.readUnsignedShort();
      input.readUnsignedShort();
      input.readUnsignedShort();
      int interfaceCount = input.readUnsignedShort();
      input.skipNBytes((long) interfaceCount * 2);
      skipMembers(input);
      skipMembers(input);

      List<BootstrapMethodInfo> bootstrapMethods = List.of();
      int attributeCount = input.readUnsignedShort();
      for (int index = 0; index < attributeCount; index++) {
        String name = requireUtf8(pool, input.readUnsignedShort());
        int length = input.readInt();
        if (length < 0) {
          throw new IOException("invalid classfile attribute length");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
          throw new IOException("truncated classfile attribute");
        }
        if (name.equals("BootstrapMethods")) {
          bootstrapMethods = parseBootstrapMethods(value);
        }
      }

      List<BootstrapReference> result = new ArrayList<>();
      for (Object entry : pool) {
        if (!(entry instanceof DynamicInfo dynamic)
            || dynamic.tag() != 18) {
          continue;
        }
        if (dynamic.bootstrapMethodIndex() < 0
            || dynamic.bootstrapMethodIndex()
                >= bootstrapMethods.size()) {
          throw new IOException("invalid invokedynamic bootstrap index");
        }
        BootstrapMethodInfo bootstrap =
            bootstrapMethods.get(dynamic.bootstrapMethodIndex());
        List<MemberReference> arguments = new ArrayList<>();
        for (int argumentIndex : bootstrap.argumentIndexes()) {
          if (argumentIndex > 0
              && argumentIndex < pool.length
              && pool[argumentIndex] instanceof MethodHandleInfo handle) {
            arguments.add(resolveMethodHandle(pool, handle));
          }
        }
        result.add(
            new BootstrapReference(
                resolveMethodHandle(
                    pool,
                    requirePoolEntry(
                        pool,
                        bootstrap.methodHandleIndex(),
                        MethodHandleInfo.class)),
                List.copyOf(arguments)));
      }
      return List.copyOf(result);
    }
  }

  private static void skipMembers(DataInputStream input)
      throws IOException {
    int memberCount = input.readUnsignedShort();
    for (int member = 0; member < memberCount; member++) {
      input.readUnsignedShort();
      input.readUnsignedShort();
      input.readUnsignedShort();
      int attributeCount = input.readUnsignedShort();
      for (int attribute = 0;
          attribute < attributeCount;
          attribute++) {
        input.readUnsignedShort();
        long length = Integer.toUnsignedLong(input.readInt());
        input.skipNBytes(length);
      }
    }
  }

  private static List<BootstrapMethodInfo> parseBootstrapMethods(
      byte[] value) throws IOException {
    try (DataInputStream input =
        new DataInputStream(new ByteArrayInputStream(value))) {
      int count = input.readUnsignedShort();
      List<BootstrapMethodInfo> methods = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int methodHandleIndex = input.readUnsignedShort();
        int argumentCount = input.readUnsignedShort();
        List<Integer> arguments = new ArrayList<>();
        for (int argument = 0;
            argument < argumentCount;
            argument++) {
          arguments.add(input.readUnsignedShort());
        }
        methods.add(
            new BootstrapMethodInfo(
                methodHandleIndex, List.copyOf(arguments)));
      }
      if (input.available() != 0) {
        throw new IOException("invalid BootstrapMethods attribute");
      }
      return List.copyOf(methods);
    }
  }

  private static MemberReference resolveMethodHandle(
      Object[] pool, MethodHandleInfo handle) throws IOException {
    ReferenceInfo reference =
        requirePoolEntry(
            pool, handle.referenceIndex(), ReferenceInfo.class);
    ClassInfo owner =
        requirePoolEntry(pool, reference.classIndex(), ClassInfo.class);
    NameAndTypeInfo nameAndType =
        requirePoolEntry(
            pool,
            reference.nameAndTypeIndex(),
            NameAndTypeInfo.class);
    return new MemberReference(
        switch (reference.tag()) {
          case 9 -> MemberKind.FIELD;
          case 10 -> MemberKind.METHOD;
          case 11 -> MemberKind.INTERFACE_METHOD;
          default ->
              throw new IOException("invalid method handle reference");
        },
        requireUtf8(pool, owner.nameIndex()),
        requireUtf8(pool, nameAndType.nameIndex()),
        requireUtf8(pool, nameAndType.descriptorIndex()));
  }

  private static CapabilityMemberRule rule(
      MemberKind kind,
      String owner,
      String name,
      String descriptor,
      String... allowedConsumers) {
    return new CapabilityMemberRule(
        kind,
        owner,
        name,
        descriptor,
        Set.of(allowedConsumers),
        false);
  }

  private static CapabilityMemberRule noConsumerRule(
      String owner, String name, String descriptor) {
    return new CapabilityMemberRule(
        MemberKind.METHOD,
        owner,
        name,
        descriptor,
        Set.of(),
        false);
  }

  private static CapabilityMemberRule firstPartyWideRule(
      MemberKind kind,
      String owner,
      String name,
      String descriptor,
      String... allowedConsumers) {
    return new CapabilityMemberRule(
        kind,
        owner,
        name,
        descriptor,
        Set.of(allowedConsumers),
        true);
  }

  private static CapabilityMemberRule firstPartyWideNoConsumerRule(
      String owner, String name, String descriptor) {
    return new CapabilityMemberRule(
        MemberKind.METHOD,
        owner,
        name,
        descriptor,
        Set.of(),
        true);
  }

  private static CapabilityMemberRule firstPartyWideNoConsumerRule(
      String owner,
      String name,
      String descriptor,
      String allowedConsumer) {
    return firstPartyWideRule(
        MemberKind.METHOD,
        owner,
        name,
        descriptor,
        allowedConsumer);
  }

  private static String providerValidationRequirementDescriptor() {
    return "(Lio/emergeos/core/domain/GraphAttemptManifest;"
        + "Lio/emergeos/core/domain/GraphAttemptCursor;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/time/Duration;)Ljava/lang/String;";
  }

  private static String providerValidationCompletionDescriptor() {
    return "(Lio/emergeos/core/domain/GraphAttemptManifest;"
        + "Lio/emergeos/core/domain/GraphAttemptCursor;"
        + "Lio/emergeos/core/domain/GraphProviderValidationStatement;"
        + "Lio/emergeos/core/port/GraphProviderValidationSigner;)"
        + "Lio/emergeos/core/domain/GraphAttemptCursor;";
  }

  private static String providerValidationTranscriptCreateDescriptor() {
    return "(Lio/emergeos/core/domain/GraphProviderValidationChallenge;"
        + "Lio/emergeos/core/domain/GraphAttemptCursor;"
        + "Lio/emergeos/core/domain/GraphProviderAttribution;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphProviderValidationDecision;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphAttributedFailureCode;)"
        + "Lio/emergeos/core/domain/GraphProviderValidationTranscript;";
  }

  private static String providerValidationChallengeConstructorDescriptor() {
    return "(Ljava/lang/String;Ljava/lang/String;JJJ"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/time/Instant;I"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/util/UUID;Ljava/time/Instant;Ljava/time/Instant;"
        + "Ljava/lang/String;Ljava/lang/String;)V";
  }

  private static String providerValidationStatementConstructorDescriptor() {
    return "(Lio/emergeos/core/domain/GraphProviderAttribution;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphProviderValidationDecision;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphAttributedFailureCode;)V";
  }

  private static String providerOutcomeReceiptConstructorDescriptor() {
    return "(Lio/emergeos/adapters/openai/"
        + "OpenAiResponsesModel$ProviderAttributionReceipt;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/adapters/openai/"
        + "OpenAiResponsesModel$ProviderOutcomeKind;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
  }

  private static String providerValidationStatementMapperDescriptor() {
    return "(Lio/emergeos/adapters/openai/"
        + "OpenAiResponsesModel$ProviderOutcomeReceipt;"
        + "Lio/emergeos/core/domain/GraphProviderAttribution;"
        + "Ljava/lang/String;)"
        + "Lio/emergeos/core/domain/GraphProviderValidationStatement;";
  }

  private static String providerValidationTranscriptConstructorDescriptor() {
    return "(Lio/emergeos/core/domain/GraphProviderValidationChallenge;"
        + "Lio/emergeos/core/domain/GraphAttemptCursor;"
        + "Lio/emergeos/core/domain/GraphProviderAttribution;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphProviderValidationDecision;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
        + "Ljava/lang/String;)V";
  }

  private static String exactPicoSignatureVerifierDescriptor() {
    return "(Lio/emergeos/core/domain/"
        + "GraphExactPicoProviderValidationChallenge;[B"
        + "Ljava/lang/String;)V";
  }

  private static String exactPicoValidationChallengeConstructorDescriptor() {
    return "(Ljava/lang/String;Ljava/lang/String;JJJ"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/util/UUID;Ljava/time/Instant;"
        + "Ljava/time/Instant;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphProviderValidationDecision;"
        + "Ljava/lang/String;"
        + "Lio/emergeos/core/domain/GraphAttributedFailureCode;"
        + "Ljava/lang/String;Ljava/lang/String;)V";
  }

  private static String exactPicoSignerDescriptor() {
    return "(Lio/emergeos/core/domain/"
        + "GraphExactPicoProviderValidationChallenge;)Ljava/lang/String;";
  }

  private static String exactPicoCommandConstructorDescriptor() {
    return "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;JJJLjava/lang/String;"
        + "Ljava/lang/String;Ljava/time/Duration;)V";
  }

  private static String exactPicoReceiptConstructorDescriptor() {
    return "(Ljava/lang/String;IJLjava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        + "Ljava/lang/String;Lio/emergeos/core/domain/"
        + "GraphExactPicoProviderValidationReceipt$ValidationState;)V";
  }

  private static String exactPicoAttestorOpenDescriptor() {
    return "(Ljavax/sql/DataSource;)Lio/emergeos/adapters/postgres/"
        + "PostgresExactPicoProviderValidationAttestor;";
  }

  private static String exactPicoAttestorCompleteDescriptor() {
    return "(Lio/emergeos/core/domain/"
        + "GraphExactPicoProviderValidationCommand;"
        + "Lio/emergeos/core/port/"
        + "GraphExactPicoProviderValidationSigner;)"
        + "Lio/emergeos/core/domain/"
        + "GraphExactPicoProviderValidationReceipt;";
  }

  private static String exactPicoOverlayReaderDescriptor() {
    return "(Lio/emergeos/core/domain/GraphAttemptManifest;)"
        + "Lio/emergeos/core/domain/GraphExactPicoOverlayVerification;";
  }

  private static String structuredFinalDescriptor() {
    return "("
        + ATTRIBUTED_OUTCOME_DESCRIPTOR
        + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
        + "Lio/emergeos/core/application/"
        + "GraphAttemptCoordinator$EgressAuthority;"
        + "Lio/emergeos/adapters/postgres/"
        + "OwnerTtyGraphAuthority$Pack010Revision;"
        + "Lio/emergeos/core/domain/GraphAttemptManifest;"
        + "Lio/emergeos/contracts/HarnessCandidateEnvelope;)"
        + STRUCTURED_FINAL_BINDING_DESCRIPTOR;
  }

  private static String attributedFailureDescriptor() {
    return "("
        + ATTRIBUTED_OUTCOME_DESCRIPTOR
        + "Lio/emergeos/core/application/GraphAttemptCoordinator;"
        + "Lio/emergeos/core/application/"
        + "GraphAttemptCoordinator$EgressAuthority;"
        + "Lio/emergeos/adapters/postgres/"
        + "OwnerTtyGraphAuthority$Pack010Revision;"
        + "Lio/emergeos/core/domain/GraphAttemptManifest;)"
        + ATTRIBUTED_FAILURE_DESCRIPTOR;
  }

  private static String egressInstantDescriptor() {
    return "(Lio/emergeos/core/application/"
        + "GraphAttemptCoordinator$EgressAuthority;"
        + "Ljava/time/Instant;)V";
  }

  /** Returns exact field/method/interface references from one classfile. */
  static List<MemberReference> memberReferences(byte[] classBytes)
      throws IOException {
    try (DataInputStream input =
        new DataInputStream(new ByteArrayInputStream(classBytes))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("invalid classfile magic");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int constantPoolCount = input.readUnsignedShort();
      Object[] pool = new Object[constantPoolCount];
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> pool[index] = input.readUTF();
          case 3, 4 -> input.readInt();
          case 5, 6 -> {
            input.readLong();
            index++;
          }
          case 7 -> pool[index] =
              new ClassInfo(input.readUnsignedShort());
          case 8, 16, 19, 20 -> input.readUnsignedShort();
          case 9, 10, 11 ->
              pool[index] =
                  new ReferenceInfo(
                      tag,
                      input.readUnsignedShort(),
                      input.readUnsignedShort());
          case 12 ->
              pool[index] =
                  new NameAndTypeInfo(
                      input.readUnsignedShort(),
                      input.readUnsignedShort());
          case 15 -> {
            input.readUnsignedByte();
            input.readUnsignedShort();
          }
          case 17, 18 -> {
            input.readUnsignedShort();
            input.readUnsignedShort();
          }
          default ->
              throw new IOException(
                  "unsupported classfile constant tag " + tag);
        }
      }
      List<MemberReference> references = new ArrayList<>();
      for (Object entry : pool) {
        if (!(entry instanceof ReferenceInfo reference)) {
          continue;
        }
        ClassInfo owner =
            requirePoolEntry(
                pool, reference.classIndex(), ClassInfo.class);
        NameAndTypeInfo nameAndType =
            requirePoolEntry(
                pool,
                reference.nameAndTypeIndex(),
                NameAndTypeInfo.class);
        references.add(
            new MemberReference(
                switch (reference.tag()) {
                  case 9 -> MemberKind.FIELD;
                  case 10 -> MemberKind.METHOD;
                  case 11 -> MemberKind.INTERFACE_METHOD;
                  default ->
                      throw new IOException(
                          "invalid member reference tag");
                },
                requireUtf8(pool, owner.nameIndex()),
                requireUtf8(pool, nameAndType.nameIndex()),
                requireUtf8(
                    pool, nameAndType.descriptorIndex())));
      }
      return List.copyOf(references);
    }
  }

  private static <T> T requirePoolEntry(
      Object[] pool, int index, Class<T> type) throws IOException {
    if (index < 1
        || index >= pool.length
        || !type.isInstance(pool[index])) {
      throw new IOException("invalid classfile constant reference");
    }
    return type.cast(pool[index]);
  }

  private static String requireUtf8(Object[] pool, int index)
      throws IOException {
    return requirePoolEntry(pool, index, String.class);
  }

  static List<String> utf8Constants(byte[] classBytes)
      throws IOException {
    try (DataInputStream input =
        new DataInputStream(
            new ByteArrayInputStream(classBytes))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("invalid classfile magic");
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      int constantPoolCount = input.readUnsignedShort();
      List<String> values = new ArrayList<>();
      for (int index = 1; index < constantPoolCount; index++) {
        int tag = input.readUnsignedByte();
        switch (tag) {
          case 1 -> values.add(input.readUTF());
          case 3, 4 -> input.readInt();
          case 5, 6 -> {
            input.readLong();
            index++;
          }
          case 7, 8, 16, 19, 20 ->
              input.readUnsignedShort();
          case 9, 10, 11, 12, 17, 18 -> {
            input.readUnsignedShort();
            input.readUnsignedShort();
          }
          case 15 -> {
            input.readUnsignedByte();
            input.readUnsignedShort();
          }
          default ->
              throw new IOException(
                  "unsupported classfile constant tag " + tag);
        }
      }
      return List.copyOf(values);
    }
  }

  private static String boundedReference(String reference) {
    int end = 0;
    while (end < reference.length()
        && isReferenceCharacter(reference.charAt(end))) {
      end++;
    }
    String bounded = reference.substring(0, end);
    return bounded.length() <= 240
        ? bounded
        : bounded.substring(0, 240);
  }

  private static boolean isReferenceCharacter(char value) {
    return Character.isJavaIdentifierPart(value)
        || value == '/'
        || value == '$';
  }

  enum MemberKind {
    FIELD,
    METHOD,
    INTERFACE_METHOD
  }

  record MemberReference(
      MemberKind kind,
      String owner,
      String name,
      String descriptor) {}

  record BootstrapReference(
      MemberReference bootstrap,
      List<MemberReference> methodHandleArguments) {}

  private record CapabilityMemberRule(
      MemberKind kind,
      String owner,
      String name,
      String descriptor,
      Set<String> allowedConsumers,
      boolean firstPartyWide) {}

  private record ClassInfo(int nameIndex) {}

  private record NameAndTypeInfo(
      int nameIndex, int descriptorIndex) {}

  private record ReferenceInfo(
      int tag, int classIndex, int nameAndTypeIndex) {}

  private record MethodHandleInfo(
      int referenceKind, int referenceIndex) {}

  private record DynamicInfo(
      int tag,
      int bootstrapMethodIndex,
      int nameAndTypeIndex) {}

  private record BootstrapMethodInfo(
      int methodHandleIndex, List<Integer> argumentIndexes) {}
}
