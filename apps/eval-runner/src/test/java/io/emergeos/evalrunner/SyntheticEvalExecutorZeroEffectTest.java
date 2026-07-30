package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntheticEvalExecutorZeroEffectTest {

  @TempDir Path tempDir;

  @Test
  void missingTtyStopsBeforeMarkerCredentialAndClient()
      throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("no-tty-home"));
    AtomicInteger credentialReads = new AtomicInteger();
    AtomicInteger clientFactories = new AtomicInteger();

    SyntheticEvalExecutor.Rejected rejected =
        assertThrows(
            SyntheticEvalExecutor.Rejected.class,
            () ->
                new SyntheticEvalExecutor(repoRoot())
                    .execute(
                        dependencies(
                            home,
                            console(false, null),
                            credentialReads,
                            clientFactories)));

    assertEquals("REAL_TTY_REQUIRED", rejected.code());
    assertEquals(0, credentialReads.get());
    assertEquals(0, clientFactories.get());
    assertFalse(Files.exists(home.resolve(".emergeos")));
  }

  @Test
  void wrongChallengeBurnsAttemptAndReplayStillReadsNoCredential()
      throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("wrong-home"));
    AtomicInteger credentialReads = new AtomicInteger();
    AtomicInteger clientFactories = new AtomicInteger();
    SyntheticEvalExecutor executor =
        new SyntheticEvalExecutor(repoRoot());

    SyntheticEvalExecutor.Rejected wrong =
        assertThrows(
            SyntheticEvalExecutor.Rejected.class,
            () ->
                executor.execute(
                    dependencies(
                        home,
                        console(true, "not-the-challenge"),
                        credentialReads,
                        clientFactories)));
    assertEquals("CHALLENGE_MISMATCH", wrong.code());
    assertTrue(wrong.safeReceipt().contains("markerBurned=true"));
    assertTrue(
        wrong.safeReceipt().contains("billingStatus=NOT_INVOKED"));
    assertTrue(
        Files.isRegularFile(
            home.resolve(
                ".emergeos/eval-attempts/"
                    + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                    + ".attempt")));

    SyntheticEvalExecutor.Rejected replay =
        assertThrows(
            SyntheticEvalExecutor.Rejected.class,
            () ->
                executor.execute(
                    dependencies(
                        home,
                        console(
                            true,
                            OneShotOperatorGate.CHALLENGE_PREFIX
                                + SyntheticEvalCatalog
                                    .EXPECTED_ATTEMPT_ID),
                        credentialReads,
                        clientFactories)));
    assertEquals("MARKER_ALREADY_EXISTS", replay.code());
    assertEquals(0, credentialReads.get());
    assertEquals(0, clientFactories.get());
  }

  @Test
  void invalidPackStopsBeforeTtyAndAllEffects() throws Exception {
    Path repo = tempDir.resolve("invalid-repo");
    Path pack = repo.resolve(SyntheticEvalCatalog.PACK_PATH);
    Path environment =
        repo.resolve(SyntheticEvalCatalog.ENVIRONMENT_PATH);
    Files.createDirectories(pack.getParent());
    Files.createDirectories(environment.getParent());
    Files.copy(
        repoRoot().resolve(SyntheticEvalCatalog.PACK_PATH), pack);
    Files.copy(
        repoRoot().resolve(SyntheticEvalCatalog.ENVIRONMENT_PATH),
        environment);
    Files.writeString(pack, "\n", java.nio.file.StandardOpenOption.APPEND);
    Path home = Files.createDirectory(tempDir.resolve("invalid-home"));
    AtomicInteger ttyChecks = new AtomicInteger();
    AtomicInteger credentialReads = new AtomicInteger();
    AtomicInteger clientFactories = new AtomicInteger();
    OneShotOperatorGate.InteractiveConsole console =
        new OneShotOperatorGate.InteractiveConsole() {
          @Override
          public boolean available() {
            ttyChecks.incrementAndGet();
            return true;
          }

          @Override
          public String readLine(String prompt) {
            throw new AssertionError("challenge must not be reached");
          }
        };

    SyntheticEvalPreflight.Rejected rejected =
        assertThrows(
            SyntheticEvalPreflight.Rejected.class,
            () ->
                new SyntheticEvalExecutor(repo)
                    .execute(
                        dependencies(
                            home,
                            console,
                            credentialReads,
                            clientFactories)));

    assertEquals("ASSET_HASH_MISMATCH", rejected.code());
    assertEquals(0, ttyChecks.get());
    assertEquals(0, credentialReads.get());
    assertEquals(0, clientFactories.get());
    assertFalse(Files.exists(home.resolve(".emergeos")));
  }

  @Test
  void postGateSetupFailuresRemainOneShotAndExplicitlyUnbilled()
      throws Exception {
    assertSetupFailure(
        "CREDENTIAL_MISSING",
        Files.createDirectory(tempDir.resolve("missing-key-home")),
        () -> null,
        ignored -> {
          throw new AssertionError("client must not be created");
        });
    assertSetupFailure(
        "CREDENTIAL_READ_FAILED",
        Files.createDirectory(tempDir.resolve("key-read-home")),
        () -> {
          throw new IllegalStateException("sensitive source detail");
        },
        ignored -> {
          throw new AssertionError("client must not be created");
        });
    assertSetupFailure(
        "CLIENT_CREATION_FAILED",
        Files.createDirectory(tempDir.resolve("client-home")),
        () -> "sentinel-setup-key",
        ignored -> {
          throw new IllegalStateException("sensitive client detail");
        });
  }

  private static void assertSetupFailure(
      String expectedCode,
      Path home,
      SyntheticEvalExecutor.CredentialSource credentialSource,
      SyntheticEvalExecutor.OpenAiClientFactory clientFactory) {
    SyntheticEvalExecutor.Rejected rejected =
        assertThrows(
            SyntheticEvalExecutor.Rejected.class,
            () ->
                new SyntheticEvalExecutor(repoRoot())
                    .execute(
                        new SyntheticEvalExecutor.Dependencies(
                            console(
                                true,
                                OneShotOperatorGate.CHALLENGE_PREFIX
                                    + SyntheticEvalCatalog
                                        .EXPECTED_ATTEMPT_ID),
                            home,
                            credentialSource,
                            clientFactory,
                            Clock.systemUTC(),
                            System::nanoTime)));
    assertEquals(expectedCode, rejected.code());
    assertTrue(
        rejected.safeReceipt().contains("billingStatus=NOT_INVOKED"));
    assertTrue(
        rejected
            .safeReceipt()
            .contains("providerSdkCreateInvocations=0"));
    assertTrue(rejected.safeReceipt().contains("markerCreated=true"));
    assertFalse(rejected.safeReceipt().contains("sentinel-setup-key"));
    assertFalse(rejected.safeReceipt().contains("sensitive"));
  }

  private static SyntheticEvalExecutor.Dependencies dependencies(
      Path home,
      OneShotOperatorGate.InteractiveConsole console,
      AtomicInteger credentialReads,
      AtomicInteger clientFactories) {
    return new SyntheticEvalExecutor.Dependencies(
        console,
        home,
        () -> {
          credentialReads.incrementAndGet();
          return "must-not-be-read";
        },
        ignored -> {
          clientFactories.incrementAndGet();
          throw new AssertionError("client factory must not be reached");
        },
        Clock.systemUTC(),
        System::nanoTime);
  }

  private static OneShotOperatorGate.InteractiveConsole console(
      boolean available, String answer) {
    return new OneShotOperatorGate.InteractiveConsole() {
      @Override
      public boolean available() {
        return available;
      }

      @Override
      public String readLine(String prompt) {
        return answer;
      }
    };
  }

  private static Path repoRoot() {
    Path current =
        Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("evals/task-packs"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
