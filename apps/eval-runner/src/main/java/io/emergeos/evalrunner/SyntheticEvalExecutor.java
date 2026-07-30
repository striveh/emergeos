package io.emergeos.evalrunner;

import com.openai.client.OpenAIClient;
import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.IntegrityHashes;
import io.emergeos.contracts.RunStatus;
import io.emergeos.core.application.AgentDraftCommand;
import io.emergeos.core.application.AgentDraftOutcome;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.application.AgentExecutionProfile;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureSourceType;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

final class SyntheticEvalExecutor {

  private final Path repoRoot;

  SyntheticEvalExecutor(Path repoRoot) {
    this.repoRoot =
        Objects.requireNonNull(repoRoot, "repoRoot")
            .toAbsolutePath()
            .normalize();
  }

  ExecutionResult execute(Dependencies dependencies) {
    Objects.requireNonNull(dependencies, "dependencies");
    EffectsCounter effects = new EffectsCounter();
    new SyntheticEvalPreflight(repoRoot).run();
    dependencies
        .observer()
        .observed(EvalExecutionObserver.Phase.PREFLIGHT_VERIFIED);

    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(dependencies.nanoTime());
    Path marker;
    try {
      marker =
          new OneShotOperatorGate(
                  dependencies.console(),
                  new PosixAttemptMarkerStore(
                      dependencies.ownerHome()),
                  dependencies.observer())
              .authorize(permit);
    } catch (OneShotOperatorGate.Rejected rejected) {
      if ("CHALLENGE_MISMATCH".equals(rejected.code())) {
        throw rejected(
            rejected.code(),
            gateFailureReceipt("markerBurned=true"));
      }
      throw rejected(rejected.code());
    } catch (PosixAttemptMarkerStore.Rejected rejected) {
      throw rejected(rejected.code());
    } catch (OneShotExecutionPermit.Rejected rejected) {
      throw rejected(rejected.code());
    }
    PosixAttemptJournal journal;
    try {
      journal = PosixAttemptJournal.open(marker);
    } catch (PosixAttemptJournal.Rejected failure) {
      throw rejected(
          failure.code(),
          gateFailureReceipt("markerBurned=true"));
    }
    dependencies
        .observer()
        .observed(
            EvalExecutionObserver.Phase.GATE_APPROVED_DURABLE);

    String apiKey;
    try {
      journal.credentialReadStarted();
      effects.keyReads.incrementAndGet();
      dependencies
          .observer()
          .observed(
              EvalExecutionObserver.Phase.CREDENTIAL_READ_STARTED);
      apiKey = dependencies.credentialSource().readOnce();
    } catch (PosixAttemptJournal.Rejected failure) {
      throw rejected(
          failure.code(),
          failureReceipt(effects.snapshot(), "NOT_INVOKED"));
    } catch (RuntimeException failure) {
      throw rejected(
          "CREDENTIAL_READ_FAILED",
          failureReceipt(effects.snapshot(), "NOT_INVOKED"));
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw rejected(
          "CREDENTIAL_MISSING",
          failureReceipt(effects.snapshot(), "NOT_INVOKED"));
    }

    OpenAIClient client = null;
    try {
      effects.clientFactories.incrementAndGet();
      client =
          Objects.requireNonNull(
              dependencies.clientFactory().create(apiKey),
              "OpenAI client");
      journal.clientCreated();
      dependencies
              .observer()
              .observed(EvalExecutionObserver.Phase.CLIENT_CREATED);
    } catch (RuntimeException failure) {
      closeQuietly(client);
      throw rejected(
          "CLIENT_CREATION_FAILED",
          failureReceipt(effects.snapshot(), "NOT_INVOKED"));
    }

    try {
      return executeWithClient(
          dependencies,
          permit,
          marker,
          journal,
          client,
          effects);
    } catch (Rejected failure) {
      if (failure.safeReceipt() != null) {
        throw failure;
      }
      Effects observed = effects.snapshot();
      throw rejected(
          failure.code(),
          failureReceipt(
              observed,
              observed.providerSdkCreateInvocations() == 0
                  ? "NOT_INVOKED"
                  : "UNKNOWN"));
    } catch (PosixEvalRunRecordStore.Rejected failure) {
      Effects observed = effects.snapshot();
      throw rejected(
          failure.code(),
          failureReceipt(
              observed,
              observed.providerSdkCreateInvocations() == 0
                  ? "NOT_INVOKED"
                  : "UNKNOWN"));
    } catch (PosixAttemptJournal.Rejected failure) {
      Effects observed = effects.snapshot();
      throw rejected(
          failure.code(),
          failureReceipt(
              observed,
              observed.providerSdkCreateInvocations() == 0
                  ? "NOT_INVOKED"
                  : "UNKNOWN"));
    } catch (RuntimeException failure) {
      Effects observed = effects.snapshot();
      throw rejected(
          "EXECUTION_FAILED",
          failureReceipt(
              observed,
              observed.providerSdkCreateInvocations() == 0
                  ? "NOT_INVOKED"
                  : "UNKNOWN"));
    } finally {
      closeQuietly(client);
    }
  }

  private static ExecutionResult executeWithClient(
      Dependencies dependencies,
      OneShotExecutionPermit permit,
      Path marker,
      PosixAttemptJournal journal,
      OpenAIClient client,
      EffectsCounter effects) {
    AgentExecutionProfile profile = SyntheticEvalCatalog.profile();
    effects.modelFactories.incrementAndGet();
    AgentModel model =
        new OpenAiResponsesModel(
            profile,
            client,
            () -> {
              int ordinal =
                  effects.providerSdkCreateInvocations.get() + 1;
              journal.providerInvocationIntent(ordinal);
              dependencies
                  .observer()
                  .observed(
                      EvalExecutionObserver.Phase
                          .PROVIDER_SDK_CREATE_INTENT_DURABLE);
              effects.providerSdkCreateInvocations.incrementAndGet();
              dependencies
                  .observer()
                  .observed(
                      EvalExecutionObserver.Phase
                          .PROVIDER_SDK_CREATE);
            },
            (resolvedModel, usage) -> {
              int ordinal = effects.nextAttributionOrdinal();
              journal.providerAttributed(
                  ordinal,
                  resolvedModel,
                  usage.costUsd(),
                  usage.tokenCount());
              dependencies
                  .observer()
                  .observed(
                      EvalExecutionObserver.Phase
                          .PROVIDER_ATTRIBUTED_DURABLE);
              effects.recordAttribution(usage);
            });
    dependencies
        .observer()
        .observed(EvalExecutionObserver.Phase.MODEL_CREATED);
    Capture capture =
        new Capture(
            SyntheticEvalCatalog.CAPTURE_ID,
            SyntheticEvalCatalog.PRINCIPAL_ID,
            SyntheticEvalCatalog.CLIENT_NONCE,
            SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
            SyntheticEvalCatalog.CONTENT,
            CaptureSourceType.TEXT,
            SyntheticEvalCatalog.SOURCE_REF,
            DataClass.PUBLIC,
            dependencies.clock().instant());
    SingleRunStores stores =
        new SingleRunStores(
            capture,
            () -> {
              effects.runStarts.incrementAndGet();
              dependencies
                  .observer()
                  .observed(
                      EvalExecutionObserver.Phase.RUN_STARTED);
            });
    FrozenEvalIds ids = new FrozenEvalIds();
    AgentToolRegistry tools =
        new AgentToolRegistry(
            List.of(new CaptureReadTool(stores.captures())));
    var loop =
        new AgentLoopKernel(
            model,
            tools,
            profile.maxModelSteps(),
            profile.maxToolCalls(),
            dependencies.nanoTime());
    var observedKernel =
        new PermitBoundAgentKernel(
            permit,
            loop,
            () -> {
              effects.permitConsumes.incrementAndGet();
              dependencies
                  .observer()
                  .observed(
                      EvalExecutionObserver.Phase.PERMIT_CONSUMED);
            });
    var service =
        new AgentDraftService(
            observedKernel,
            stores.runs(),
            stores.captures(),
            ids,
            dependencies.clock(),
            profile,
            task -> {
              permit.authorize(task);
              dependencies
                  .observer()
                  .observed(
                      EvalExecutionObserver.Phase.TASK_AUTHORIZED);
            });

    AgentDraftOutcome outcome =
        service.draft(
            new AgentDraftCommand(
                SyntheticEvalCatalog.PRINCIPAL_ID,
                SyntheticEvalCatalog.CAPTURE_ID,
                SyntheticEvalCatalog.INTENT));
    Effects observed = effects.snapshot();
    verifyOutcome(outcome, ids, observed);
    PosixEvalRunRecordStore.Stored stored =
        new PosixEvalRunRecordStore()
            .save(
                marker,
                outcome,
                observed,
                dependencies.observer());
    journal.terminalRecordPersisted(
        outcome.result().status(),
        outcome.run().bundle().integrityHash(),
        billingStatus(observed),
        observed.providerObservedCostUsd(),
        observed.providerObservedTokenCount(),
        meteringMatchesRun(outcome, observed),
        observed.providerSdkCreateInvocations(),
        observed.providerAttributedInvocations(),
        stored);
    dependencies
        .observer()
        .observed(
            EvalExecutionObserver.Phase.TERMINAL_JOURNAL_DURABLE);
    return new ExecutionResult(
        outcome,
        receipt(outcome, observed, stored, journal),
        observed);
  }

  private static void verifyOutcome(
      AgentDraftOutcome outcome,
      FrozenEvalIds ids,
      Effects effects) {
    Objects.requireNonNull(outcome, "outcome");
    if (!SyntheticEvalCatalog.EXPECTED_TASK_HASH.equals(
            IntegrityHashes.taskHash(outcome.run().task()))
        || !SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT.equals(
            outcome
                .run()
                .bundle()
                .componentVersions()
                .get("execution-profile-fingerprint"))
        || !SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT.equals(
            outcome
                .run()
                .bundle()
                .componentVersions()
                .get("pricing-profile-fingerprint"))
        || !SyntheticEvalCatalog.profile()
            .environmentSnapshotRef()
            .equals(
                outcome
                    .run()
                    .bundle()
                    .environmentSnapshotRef())
        || !outcome
            .run()
            .bundle()
            .integrityHash()
            .equals(
                IntegrityHashes.bundleHash(
                    outcome.run().bundle()))
        || effects.providerSdkCreateInvocations()
            > SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
        || effects.providerAttributedInvocations()
            > effects.providerSdkCreateInvocations()
        || effects.runStarts() != 1
        || effects.permitConsumes() != 1) {
      throw rejected("EXECUTION_INTEGRITY_MISMATCH");
    }
    if (outcome.result().status() == RunStatus.SUCCEEDED) {
      if (outcome.artifact() == null
          || !SyntheticEvalCatalog.ARTIFACT_ID.equals(
              outcome.artifact().artifactId())
          || !SyntheticEvalCatalog.profile()
              .modelRequested()
              .equals(outcome.result().resolvedModel())
          || effects.providerSdkCreateInvocations()
              != SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS
          || effects.providerAttributedInvocations()
              != SyntheticEvalCatalog.MAXIMUM_PROVIDER_REQUESTS) {
        throw rejected("SUCCESS_INTEGRITY_MISMATCH");
      }
      if (!meteringMatchesRun(outcome, effects)) {
        throw rejected("SUCCESS_METERING_MISMATCH");
      }
      ids.requireSuccessPathConsumed();
    } else if (outcome.artifact() != null) {
      throw rejected("FAILED_RUN_HAS_ARTIFACT");
    }
  }

  private static String receipt(
      AgentDraftOutcome outcome,
      Effects effects,
      PosixEvalRunRecordStore.Stored stored,
      PosixAttemptJournal journal) {
    String artifactHash =
        outcome.artifact() == null
            ? "none"
            : outcome.artifact().current().contentHash();
    String resolvedModel =
        outcome.result().resolvedModel() == null
            ? "none"
            : outcome.result().resolvedModel();
    String failureReason =
        outcome.result().failureReason() == null
            ? "none"
            : outcome.result().failureReason();
    String billingStatus = billingStatus(effects);
    return "EVAL_EXECUTION_RECEIPT status="
        + outcome.result().status()
        + " caseId="
        + SyntheticEvalCatalog.CASE_ID
        + " attemptId="
        + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
        + " runId="
        + outcome.run().runId()
        + " taskHash="
        + SyntheticEvalCatalog.EXPECTED_TASK_HASH
        + " modelRequested="
        + SyntheticEvalCatalog.profile().modelRequested()
        + " resolvedModel="
        + resolvedModel
        + " billingStatus="
        + billingStatus
        + " observedCostUsd="
        + effects.providerObservedCostUsd().toPlainString()
        + " observedTokenCount="
        + effects.providerObservedTokenCount()
        + " runCostUsd="
        + outcome.result().costUsd().toPlainString()
        + " runTokenCount="
        + outcome.result().tokenCount()
        + " meteringMatchesRun="
        + meteringMatchesRun(outcome, effects)
        + " failureReason="
        + failureReason
        + " artifactContentHash="
        + artifactHash
        + " bundleHash="
        + outcome.run().bundle().integrityHash()
        + " keyReads="
        + effects.keyReads()
        + " clientFactories="
        + effects.clientFactories()
        + " modelFactories="
        + effects.modelFactories()
        + " runStarts="
        + effects.runStarts()
        + " permitConsumes="
        + effects.permitConsumes()
        + " providerSdkCreateInvocations="
        + effects.providerSdkCreateInvocations()
        + " providerAttributedInvocations="
        + effects.providerAttributedInvocations()
        + " runRecordPersisted=true"
        + " runRecordFile="
        + stored.fileName()
        + " runRecordSha256="
        + stored.sha256()
        + " attemptJournalFile="
        + journal.fileName()
        + " attemptJournalLatestHash="
        + journal.latestHash()
        + " markerCreated=true";
  }

  private static String gateFailureReceipt(String markerState) {
    return "EVAL_GATE_RECEIPT status=REJECTED"
        + " caseId="
        + SyntheticEvalCatalog.CASE_ID
        + " attemptId="
        + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
        + " billingStatus=NOT_INVOKED"
        + " keyReads=0 clientFactories=0 modelFactories=0"
        + " runStarts=0 permitConsumes=0"
        + " providerSdkCreateInvocations=0 "
        + "providerAttributedInvocations=0 "
        + markerState;
  }

  private static String failureReceipt(
      Effects effects, String billingStatus) {
    String status =
        "NOT_INVOKED".equals(billingStatus)
            ? "REJECTED"
            : "UNKNOWN";
    return "EVAL_EXECUTION_FAILURE_RECEIPT status="
        + status
        + " caseId="
        + SyntheticEvalCatalog.CASE_ID
        + " attemptId="
        + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
        + " billingStatus="
        + billingStatus
        + " observedCostUsd="
        + effects.providerObservedCostUsd().toPlainString()
        + " observedTokenCount="
        + effects.providerObservedTokenCount()
        + " keyReads="
        + effects.keyReads()
        + " clientFactories="
        + effects.clientFactories()
        + " modelFactories="
        + effects.modelFactories()
        + " runStarts="
        + effects.runStarts()
        + " permitConsumes="
        + effects.permitConsumes()
        + " providerSdkCreateInvocations="
        + effects.providerSdkCreateInvocations()
        + " providerAttributedInvocations="
        + effects.providerAttributedInvocations()
        + " markerCreated=true";
  }

  static String billingStatus(Effects effects) {
    if (effects.providerSdkCreateInvocations() == 0) {
      return "NOT_INVOKED";
    }
    return effects.providerAttributedInvocations()
            == effects.providerSdkCreateInvocations()
        ? "ATTRIBUTED"
        : "UNKNOWN";
  }

  private static boolean meteringMatchesRun(
      AgentDraftOutcome outcome, Effects effects) {
    return outcome
                .result()
                .costUsd()
                .compareTo(effects.providerObservedCostUsd())
            == 0
        && outcome.result().tokenCount()
            == effects.providerObservedTokenCount();
  }

  private static void closeQuietly(OpenAIClient client) {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (RuntimeException ignoredCloseFailure) {
      // Closing cannot rewrite an already committed one-shot outcome.
    }
  }

  record Dependencies(
      OneShotOperatorGate.InteractiveConsole console,
      Path ownerHome,
      CredentialSource credentialSource,
      OpenAiClientFactory clientFactory,
      Clock clock,
      LongSupplier nanoTime,
      EvalExecutionObserver observer) {

    Dependencies(
        OneShotOperatorGate.InteractiveConsole console,
        Path ownerHome,
        CredentialSource credentialSource,
        OpenAiClientFactory clientFactory,
        Clock clock,
        LongSupplier nanoTime) {
      this(
          console,
          ownerHome,
          credentialSource,
          clientFactory,
          clock,
          nanoTime,
          EvalExecutionObserver.noop());
    }

    Dependencies {
      Objects.requireNonNull(console, "console");
      ownerHome =
          Objects.requireNonNull(ownerHome, "ownerHome")
              .toAbsolutePath()
              .normalize();
      Objects.requireNonNull(credentialSource, "credentialSource");
      Objects.requireNonNull(clientFactory, "clientFactory");
      Objects.requireNonNull(clock, "clock");
      Objects.requireNonNull(nanoTime, "nanoTime");
      Objects.requireNonNull(observer, "observer");
    }
  }

  @FunctionalInterface
  interface CredentialSource {
    String readOnce();
  }

  @FunctionalInterface
  interface OpenAiClientFactory {
    OpenAIClient create(String apiKey);
  }

  record Effects(
      int keyReads,
      int clientFactories,
      int modelFactories,
      int runStarts,
      int permitConsumes,
      int providerSdkCreateInvocations,
      int providerAttributedInvocations,
      BigDecimal providerObservedCostUsd,
      long providerObservedTokenCount) {

    Effects {
      Objects.requireNonNull(
          providerObservedCostUsd, "providerObservedCostUsd");
      if (providerObservedCostUsd.signum() < 0
          || providerObservedTokenCount < 0
          || providerAttributedInvocations < 0
          || providerAttributedInvocations
              > providerSdkCreateInvocations
          || (providerAttributedInvocations == 0
              && (providerObservedCostUsd.signum() != 0
                  || providerObservedTokenCount != 0))) {
        throw new IllegalArgumentException(
            "provider metering effects are inconsistent");
      }
    }
  }

  record ExecutionResult(
      AgentDraftOutcome outcome, String receipt, Effects effects) {

    ExecutionResult {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(receipt, "receipt");
      Objects.requireNonNull(effects, "effects");
    }
  }

  static final class Rejected extends RuntimeException {
    private final String code;
    private final String safeReceipt;

    private Rejected(String code) {
      this(code, null);
    }

    private Rejected(String code, String safeReceipt) {
      super(code, null, false, false);
      this.code = code;
      this.safeReceipt = safeReceipt;
    }

    String code() {
      return code;
    }

    String safeReceipt() {
      return safeReceipt;
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  private static Rejected rejected(
      String code, String safeReceipt) {
    return new Rejected(code, safeReceipt);
  }

  private static final class EffectsCounter {
    private final AtomicInteger keyReads = new AtomicInteger();
    private final AtomicInteger clientFactories = new AtomicInteger();
    private final AtomicInteger modelFactories = new AtomicInteger();
    private final AtomicInteger runStarts = new AtomicInteger();
    private final AtomicInteger permitConsumes = new AtomicInteger();
    private final AtomicInteger providerSdkCreateInvocations =
        new AtomicInteger();
    private final AtomicInteger providerAttributedInvocations =
        new AtomicInteger();
    private BigDecimal providerObservedCostUsd = BigDecimal.ZERO;
    private long providerObservedTokenCount;

    private synchronized int nextAttributionOrdinal() {
      return providerAttributedInvocations.get() + 1;
    }

    private synchronized void recordAttribution(
        AgentModel.ModelUsage usage) {
      Objects.requireNonNull(usage, "usage");
      BigDecimal nextCost =
          providerObservedCostUsd.add(usage.costUsd());
      long nextTokens =
          Math.addExact(
              providerObservedTokenCount, usage.tokenCount());
      // Reservation is an authorization ceiling, not an observation ceiling.
      // If a provider reports usage above the reviewed bound, billing evidence
      // must remain truthful while the product Run fails closed.
      providerObservedCostUsd = nextCost;
      providerObservedTokenCount = nextTokens;
      providerAttributedInvocations.incrementAndGet();
    }

    private synchronized Effects snapshot() {
      return new Effects(
          keyReads.get(),
          clientFactories.get(),
          modelFactories.get(),
          runStarts.get(),
          permitConsumes.get(),
          providerSdkCreateInvocations.get(),
          providerAttributedInvocations.get(),
          providerObservedCostUsd,
          providerObservedTokenCount);
    }
  }
}
