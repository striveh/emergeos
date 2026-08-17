package io.emergeos.grapheval;

import com.openai.core.LogLevel;
import io.emergeos.adapters.agentloop.AgentLoopKernel;
import io.emergeos.adapters.agentloop.AgentToolRegistry;
import io.emergeos.adapters.agentloop.tool.CaptureReadTool;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.adapters.postgres.PostgresGraphAttemptStore;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.GraphAttemptConflictException;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.CancellationSignal;
import java.io.DataInputStream;
import java.net.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test-only packaged writer for the selected provider-accepted crash.
 *
 * <p>Database and provider credentials arrive only as bounded stdin frames.
 * The provider key frame is not read until child egress and
 * CREDENTIAL_READ_STARTED are durable.
 */
public final class Pack009GraphCrashHarnessMain {

  private Pack009GraphCrashHarnessMain() {}

  public static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (RuntimeException failure) {
      System.out.println(
          "GRAPH_HARNESS_REJECTED reason=HARNESS_FAILED"
              + " failureType="
              + failure.getClass().getName());
      System.out.flush();
      exitCode = 3;
    } catch (Exception failure) {
      System.out.println(
          "GRAPH_HARNESS_REJECTED reason=HARNESS_IO_FAILED");
      System.out.flush();
      exitCode = 3;
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run(String[] args) throws Exception {
    if (args == null
        || args.length != 8
        || !"execute".equals(args[0])) {
      return argumentsInvalid();
    }
    Path repoRoot =
        Pack009ProcessSupport.absoluteDirectory(args[1]);
    String jdbcUrl =
        Pack009ProcessSupport.requireLoopbackPostgres(args[2]);
    String username =
        Pack009ProcessSupport.requireBounded(args[3], "username");
    String providerBaseUrl =
        Pack009ProcessSupport.requireLoopbackProvider(args[4]);
    Pack009ProcessSupport.absoluteDirectory(args[5]);
    Path appJar =
        Pack009ProcessSupport.absoluteRegularFile(args[6]);
    Path testClasses =
        Pack009ProcessSupport.absoluteDirectory(args[7]);
    Pack009ProcessSupport.assertCodeSources(
        appJar,
        testClasses,
        Pack009GraphCrashHarnessMain.class);

    System.out.println("GRAPH_WRITER_PHASE arguments=VALID");
    System.out.println(
        new Pack009GraphPreflight(repoRoot).run().receipt());
    System.out.flush();

    DataInputStream secrets = new DataInputStream(System.in);
    String databasePassword =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "databasePassword");
    DataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl, username, databasePassword);
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(dataSource);
    PostgresCaptureStore captures =
        new PostgresCaptureStore(dataSource, transactions);
    if (!Pack009GraphEvalCatalog.capture().equals(
        captures
            .findOwned(
                Pack009GraphEvalCatalog.PRINCIPAL_ID,
                Pack009GraphEvalCatalog.CAPTURE_ID)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "preprovisioned Capture is missing")))) {
      throw new IllegalStateException(
          "preprovisioned Capture drifted");
    }
    System.out.println(
        "GRAPH_WRITER_PHASE database=PREPROVISIONED");
    System.out.flush();

    PostgresGraphAttemptStore graphStore =
        Pack010GraphTerminalStoreBridge.openWriter(dataSource);
    GraphAttemptCoordinator coordinator =
        new GraphAttemptCoordinator(graphStore);
    var manifest = Pack009GraphEvalCatalog.manifest();
    SyntheticConsole console = new SyntheticConsole();
    GraphAttemptCoordinator.Authorized approved;
    try {
      approved =
          coordinator.approve(
              manifest,
              console,
              Clock.fixed(
                  at(1), ZoneOffset.UTC));
    } catch (GraphAttemptConflictException claimed) {
      console.requireReplayBoundary();
      System.out.println(
          "GRAPH_WRITER_REPLAY_REJECTED"
              + " reason=EXECUTION_SLOT_ALREADY_CLAIMED"
              + " ttyAvailabilityChecks=1"
              + " challengeReads=0"
              + " providerKeyReads=0"
              + " console=SYNTHETIC_TEST");
      System.out.flush();
      return 10;
    }
    console.requireApprovedBoundary();
    GraphAttemptCoordinator.ParentAuthorized parentAuthorized =
        coordinator.authorizeParent(
            approved,
            Pack009GraphEvalCatalog.parentRun(),
            at(2));
    GraphAttemptCoordinator.ParentStarted parentStarted =
        coordinator.startParent(parentAuthorized, at(3));
    GraphAttemptCoordinator.ChildAuthorized childAuthorized =
        coordinator.authorizeChild(
            parentStarted,
            Pack009GraphEvalCatalog.childRun(),
            at(4));
    GraphAttemptCoordinator.ChildStarted childStarted =
        coordinator.startChild(childAuthorized, at(5));
    GraphAttemptCoordinator.EgressAuthority egress =
        coordinator.consumeChildEgress(childStarted, at(6));
    coordinator.credentialReadStarted(egress, at(7));
    System.out.println(
        "GRAPH_WRITER_PHASE credentialReadStarted=DURABLE");
    System.out.flush();

    String providerKey =
        Pack009ProcessSupport.readSecretFrame(
            secrets, "providerApiKey");
    Pack009ProcessSupport.requireEndOfInput(
        secrets, "writer secret input");
    if (!Pack009ProcessSupport.SYNTHETIC_PROVIDER_KEY.equals(
        providerKey)) {
      throw new IllegalArgumentException(
          "only the synthetic loopback key is allowed");
    }
    System.out.println("GRAPH_WRITER_PHASE providerKeyReads=1");
    System.out.flush();

    ReviewedOpenAiClient client =
        ReviewedOpenAiClient.defaultCodecNoRetry(
            providerKey,
            providerBaseUrl,
            Proxy.NO_PROXY,
            Duration.ofSeconds(30),
            LogLevel.OFF);
    coordinator.clientCreated(egress, at(8));
    AtomicBoolean invocationObserved = new AtomicBoolean();
    OpenAiResponsesModel model =
        new OpenAiResponsesModel(
            Pack009GraphEvalCatalog.workerProfile(),
            client,
            invocation -> {
              if (!invocationObserved.compareAndSet(false, true)
                  || invocation.requestOrdinal() != 1
                  || !Pack009GraphEvalCatalog
                      .EXPECTED_FIRST_REQUEST_HASH
                      .equals(invocation.requestHash())
                  || !Pack009GraphEvalCatalog.workerProfile()
                      .modelRequested()
                      .equals(invocation.modelRequested())) {
                throw new IllegalStateException(
                    "provider invocation identity drifted");
              }
              coordinator.providerIntent(
                  egress,
                  new GraphProviderIntent(
                      invocation.requestOrdinal(),
                      invocation.requestHash(),
                      invocation.modelRequested()),
                  at(10));
              System.out.println(
                  "GRAPH_WRITER_PHASE providerIntent=DURABLE");
              System.out.flush();
            },
            (resolvedModel, usage) -> {
              throw new IllegalStateException(
                  "writer should be killed before attribution");
            });
    coordinator.modelCreated(egress, at(9));
    AgentToolRegistry tools =
        new AgentToolRegistry(
            Pack009GraphEvalCatalog
                .workerProfile()
                .childToolRegistryVersion(),
            List.of(new CaptureReadTool(captures)));
    AgentLoopKernel kernel =
        new AgentLoopKernel(
            model,
            tools,
            Pack009GraphEvalCatalog
                .workerProfile()
                .maxModelSteps(),
            Pack009GraphEvalCatalog
                .workerProfile()
                .maxToolCalls());
    System.out.println(
        "GRAPH_WRITER_PHASE model=CREATED pid="
            + ProcessHandle.current().pid());
    System.out.flush();

    try {
      kernel.run(
          AgentRunContext.fromRunning(
              Pack009GraphEvalCatalog.childRun()),
          CancellationSignal.never());
      throw new IllegalStateException(
          "writer unexpectedly returned from the provider call");
    } finally {
      client.close();
    }
  }

  private static Instant at(long millis) {
    return Pack009GraphEvalCatalog.STARTED_AT.plusMillis(millis);
  }

  private static int argumentsInvalid() {
    System.out.println(
        "GRAPH_HARNESS_REJECTED reason=ARGUMENTS_INVALID");
    System.out.flush();
    return 2;
  }

  private static final class SyntheticConsole
      implements GraphAttemptCoordinator.InteractiveConsole {

    private final AtomicInteger ttyAvailabilityChecks =
        new AtomicInteger();
    private final AtomicInteger challengeReads =
        new AtomicInteger();

    @Override
    public boolean realTty() {
      ttyAvailabilityChecks.incrementAndGet();
      return true;
    }

    @Override
    public String readLine(String prompt) {
      challengeReads.incrementAndGet();
      String expected =
          GraphOperatorApproval.expectedChallenge(
              Pack009GraphEvalCatalog.manifest());
      if (!expected.equals(prompt)) {
        throw new IllegalArgumentException(
            "operator challenge drifted");
      }
      return expected;
    }

    private void requireApprovedBoundary() {
      requireCounts(1, 1);
    }

    private void requireReplayBoundary() {
      requireCounts(1, 0);
    }

    private void requireCounts(
        int expectedTtyChecks, int expectedChallengeReads) {
      if (ttyAvailabilityChecks.get() != expectedTtyChecks
          || challengeReads.get() != expectedChallengeReads) {
        throw new IllegalStateException(
            "synthetic console boundary drifted");
      }
    }
  }
}
