package io.emergeos.grapheval;

import com.openai.core.LogLevel;
import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.port.CancellationSignal;
import java.io.DataInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Packaged-process hard-kill harness for exact provider attribution. */
public final class Pack010ExactAttributionCrashHarnessMain {

  private static final String SYNTHETIC_CREDENTIAL =
      "synthetic-loopback-only-not-a-real-key";

  private Pack010ExactAttributionCrashHarnessMain() {}

  public static void main(String[] args) {
    try {
      int exitCode = run(args);
      if (exitCode != 0) {
        System.exit(exitCode);
      }
    } catch (Exception failure) {
      System.out.println(
          "PACK010_EXACT_ATTRIBUTION_PROCESS_REJECTED"
              + " reason=HARNESS_FAILED");
      System.out.flush();
      System.exit(3);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null || args.length != 8) {
      throw new IllegalArgumentException();
    }
    Mode mode = Mode.parse(args[0]);
    int repetition = Integer.parseInt(args[1]);
    if (repetition < 1
        || repetition
            > Pack010GraphEvalCatalog.GENERATION_REPETITIONS) {
      throw new IllegalArgumentException();
    }
    Path repo = Pack009ProcessSupport.absoluteDirectory(args[2]);
    String jdbcUrl =
        Pack009ProcessSupport.requireLoopbackPostgres(args[3]);
    String username =
        Pack009ProcessSupport.requireBounded(args[4], "username");
    String providerBaseUrl = args[5];
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[6]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[7]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack010ExactAttributionCrashHarnessMain.class,
        Pack010ProviderSessionEffectOrderingTest.class,
        Pack010GraphTerminalFixture.class,
        Pack010GraphTerminalStoreBridge.class);
    if (!repo.equals(
        Pack009ProcessSupport.absoluteDirectory(repo.toString()))) {
      throw new IllegalArgumentException();
    }

    DataInputStream secrets = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "databasePassword");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "exact attribution process secret input");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    PostgresGraphAttemptStore store =
        Pack010GraphTerminalStoreBridge.openWriter(dataSource);
    if (mode == Mode.VERIFY) {
      return verify(store, repetition);
    }
    if (mode == Mode.REPLAY) {
      Pack009ProcessSupport.requireLoopbackProvider(providerBaseUrl);
      return replay(store, repetition);
    }
    return execute(
        mode,
        repetition,
        Pack009ProcessSupport.requireLoopbackProvider(
            providerBaseUrl),
        store);
  }

  private static int execute(
      Mode mode,
      int repetition,
      String providerBaseUrl,
      PostgresGraphAttemptStore store) throws Exception {
    var manifest = Pack010GraphEvalCatalog.manifest(repetition);
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    GraphAttemptCoordinator.EgressAuthority egress =
        Pack010ProviderSessionEffectOrderingTest.consumedEgress(
            coordinator, manifest, repetition);
    coordinator.credentialReadStarted(
        egress, manifest.startedAt().plusMillis(6));
    coordinator.clientCreated(
        egress, manifest.startedAt().plusMillis(7));
    coordinator.modelCreated(
        egress, manifest.startedAt().plusMillis(8));
    var lease =
        Pack010ProviderSessionEffectOrderingTest.syntheticLease(
            OwnerTtyGraphAuthority.Pack010Revision.values()[
                repetition - 1],
            coordinator,
            egress,
            manifest.startedAt().plus(Duration.ofMinutes(5)));
    Clock clock =
        Clock.fixed(
            manifest.startedAt().plusMillis(9), ZoneOffset.UTC);
    var profile = Pack010GraphEvalCatalog.workerProfile(repetition);

    try (ReviewedOpenAiClient client =
        ReviewedOpenAiClient.defaultCodecNoRetry(
            SYNTHETIC_CREDENTIAL,
            providerBaseUrl,
            Proxy.NO_PROXY,
            Duration.ofSeconds(5),
            LogLevel.OFF)) {
      if (mode == Mode.KILL_AFTER_OUTCOME) {
        Pack010ProviderSessionComposer.ProviderSession providerSession =
            providerSessionWithOutcomeLedger(
                repetition,
                manifest,
                profile,
                lease,
                coordinator,
                egress,
                clock,
                client);
        providerSession.next(
            new AgentModel.Turn(
                Pack010GraphEvalCatalog.childTask(repetition), List.of()),
            context(profile));
        var finalOutcome =
            providerSession.next(
                new AgentModel.Turn(
                    Pack010GraphEvalCatalog.childTask(repetition),
                    List.of(
                        new AgentModel.ToolResult(
                            "capture.read",
                            "capture://capture-openai-worker-010",
                            "synthetic PUBLIC capture"))),
                context(profile));
        if (!(finalOutcome.decision()
            instanceof AgentModel.FinalDraft)) {
          throw new IllegalStateException(
              "actual structured final outcome was not minted");
        }
        Runtime.getRuntime().halt(73);
      }
      var durableAttribution =
          Pack010ProviderSessionComposer.durableAttributionObserver(
              profile, repetition, coordinator, egress, clock);
      OpenAiResponsesModel model =
          OpenAiResponsesModel.withExactResponseAttribution(
              profile,
              client,
              Pack010ProviderSessionComposer.durableIntentObserver(
                  profile,
                  repetition,
                  lease,
                  coordinator,
                  egress,
                  clock),
              receipt -> {
                if (mode == Mode.KILL_BEFORE_ATTRIBUTION) {
                  Runtime.getRuntime().halt(71);
                }
                durableAttribution.attributed(receipt);
                Runtime.getRuntime().halt(72);
              });
      AgentModel.Session session =
          model.open(Pack010GraphEvalCatalog.childTask(repetition));
      session.next(
          new AgentModel.Turn(
              Pack010GraphEvalCatalog.childTask(repetition), List.of()),
          new AgentModel.ModelCallContext(
              profile.deadlineMs(),
              profile.budgetUsd(),
              CancellationSignal.never()));
    }
    throw new IllegalStateException("hard-kill probe was not reached");
  }

  private static AgentModel.ModelCallContext context(
      io.emergeos.core.application
              .ModelBoundReadOnlyWorkerExecutionProfile
          profile) {
    return new AgentModel.ModelCallContext(
        profile.deadlineMs(),
        profile.budgetUsd(),
        CancellationSignal.never());
  }

  private static Pack010ProviderSessionComposer.ProviderSession
      providerSessionWithOutcomeLedger(
          int repetition,
          io.emergeos.core.domain.GraphAttemptManifest manifest,
          io.emergeos.core.application
                  .ModelBoundReadOnlyWorkerExecutionProfile
              profile,
          Pack010ProviderCredentialBroker.CredentialLease lease,
          GraphAttemptCoordinator coordinator,
          GraphAttemptCoordinator.EgressAuthority egress,
          Clock clock,
          ReviewedOpenAiClient client) throws Exception {
    Class<?> ledgerType =
        Class.forName(
            "io.emergeos.grapheval."
                + "Pack010ProviderSessionComposer$OutcomeLedger");
    Constructor<?> ledgerConstructor =
        ledgerType.getDeclaredConstructor();
    ledgerConstructor.setAccessible(true);
    Object ledger = ledgerConstructor.newInstance();
    Method observerFactory =
        Pack010ProviderSessionComposer.class.getDeclaredMethod(
            "durableAttributionObserver",
            io.emergeos.core.application
                .ModelBoundReadOnlyWorkerExecutionProfile.class,
            int.class,
            GraphAttemptCoordinator.class,
            GraphAttemptCoordinator.EgressAuthority.class,
            Clock.class,
            ledgerType);
    observerFactory.setAccessible(true);
    var attributionObserver =
        (OpenAiResponsesModel.ExactProviderAttributionObserver)
            observerFactory.invoke(
                null,
                profile,
                repetition,
                coordinator,
                egress,
                clock,
                ledger);
    AgentModel.Session modelSession =
        OpenAiResponsesModel.withExactResponseAttribution(
                profile,
                client,
                Pack010ProviderSessionComposer.durableIntentObserver(
                    profile,
                    repetition,
                    lease,
                    coordinator,
                    egress,
                    clock),
                attributionObserver)
            .open(Pack010GraphEvalCatalog.childTask(repetition));
    Constructor<Pack010ProviderSessionComposer.ProviderSession>
        sessionConstructor =
            Pack010ProviderSessionComposer.ProviderSession.class
                .getDeclaredConstructor(
                    AgentModel.Session.class,
                    ReviewedOpenAiClient.class,
                    OwnerTtyGraphAuthority.Pack010Revision.class,
                    Pack010ProviderCredentialBroker.CredentialLease.class,
                    GraphAttemptCoordinator.class,
                    GraphAttemptCoordinator.EgressAuthority.class,
                    Clock.class,
                    io.emergeos.core.domain.GraphAttemptManifest.class,
                    ledgerType);
    sessionConstructor.setAccessible(true);
    return sessionConstructor.newInstance(
        modelSession,
        client,
        OwnerTtyGraphAuthority.Pack010Revision.values()[repetition - 1],
        lease,
        coordinator,
        egress,
        clock,
        manifest,
        ledger);
  }

  private static int verify(
      PostgresGraphAttemptStore store, int repetition) {
    var snapshot =
        Pack010GraphTerminalFixture.verified(
            store, Pack010GraphEvalCatalog.manifest(repetition));
    String responseHash =
        snapshot.providerAttributions().isEmpty()
            ? "NONE"
            : snapshot
                .providerAttributions()
                .getFirst()
                .responseHash();
    System.out.println(
        "PACK010_EXACT_ATTRIBUTION_PROCESS_VERIFY"
            + " sequence="
            + snapshot.cursor().lastSequence()
            + " attributions="
            + snapshot.providerAttributions().size()
            + " responseHash="
            + responseHash);
    System.out.flush();
    return 0;
  }

  private static int replay(
      PostgresGraphAttemptStore store, int repetition) {
    var manifest = Pack010GraphEvalCatalog.manifest(repetition);
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(store);
    try {
      Pack010ProviderSessionEffectOrderingTest.consumedEgress(
          coordinator, manifest, repetition);
    } catch (GraphAttemptConflictException expected) {
      System.out.println(
          "PACK010_EXACT_ATTRIBUTION_REPLAY_BLOCKED"
              + " reason=EXECUTION_SLOT_ALREADY_CLAIMED"
              + " clientFactories=0 httpRequests=0");
      System.out.flush();
      return 0;
    }
    throw new IllegalStateException(
        "durable execution slot allowed provider replay");
  }

  private enum Mode {
    KILL_BEFORE_ATTRIBUTION,
    KILL_AFTER_COMMIT,
    KILL_AFTER_OUTCOME,
    REPLAY,
    VERIFY;

    private static Mode parse(String value) {
      return switch (value) {
        case "kill-before-attribution" -> KILL_BEFORE_ATTRIBUTION;
        case "kill-after-commit" -> KILL_AFTER_COMMIT;
        case "kill-after-outcome" -> KILL_AFTER_OUTCOME;
        case "replay" -> REPLAY;
        case "verify" -> VERIFY;
        default -> throw new IllegalArgumentException();
      };
    }
  }
}
