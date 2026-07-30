package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OneShotOperatorGateTest {

  @TempDir Path tempDir;

  @Test
  void missingTtyDoesNotCreateMarkerOrArmPermit() throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("no-tty-home"));
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(() -> 0);
    OneShotOperatorGate gate =
        new OneShotOperatorGate(
            console(false, null),
            new PosixAttemptMarkerStore(home));

    assertRejected("REAL_TTY_REQUIRED", () -> gate.authorize(permit));

    assertEquals(OneShotExecutionPermit.State.PREPARED, permit.state());
    assertFalse(Files.exists(home.resolve(".emergeos")));
  }

  @Test
  void wrongChallengeBurnsMarkerWithoutArmingPermit()
      throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("wrong-home"));
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(() -> 0);
    OneShotOperatorGate gate =
        new OneShotOperatorGate(
            console(true, "yes"),
            new PosixAttemptMarkerStore(home));

    assertRejected("CHALLENGE_MISMATCH", () -> gate.authorize(permit));

    assertEquals(OneShotExecutionPermit.State.PREPARED, permit.state());
    assertTrue(
        Files.exists(
            home.resolve(
                ".emergeos/eval-attempts/"
                    + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID
                    + ".attempt")));
    assertThrows(
        PosixAttemptMarkerStore.Rejected.class,
        () ->
            new OneShotOperatorGate(
                    console(
                        true,
                        OneShotOperatorGate.CHALLENGE_PREFIX
                            + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID),
                    new PosixAttemptMarkerStore(home))
                .authorize(permit));
  }

  @Test
  void exactChallengeArmsTaskBoundPermit() throws Exception {
    Path home = Files.createDirectory(tempDir.resolve("valid-home"));
    AtomicBoolean prompted = new AtomicBoolean();
    OneShotOperatorGate.InteractiveConsole console =
        new OneShotOperatorGate.InteractiveConsole() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public String readLine(String prompt) {
            prompted.set(
                prompt.contains(
                    SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID));
            return OneShotOperatorGate.CHALLENGE_PREFIX
                + SyntheticEvalCatalog.EXPECTED_ATTEMPT_ID;
          }
        };
    OneShotExecutionPermit permit =
        new OneShotExecutionPermit(() -> 0);

    Path marker =
        new OneShotOperatorGate(
                console, new PosixAttemptMarkerStore(home))
            .authorize(permit);

    assertTrue(prompted.get());
    assertTrue(Files.isRegularFile(marker));
    assertEquals(OneShotExecutionPermit.State.ARMED, permit.state());
    permit.authorize(SyntheticEvalCatalog.task());
  }

  @Test
  void cliExposesOnlyTheFixedExecutionRoute() {
    assertEquals(
        SyntheticEvalCli.Mode.PREFLIGHT,
        SyntheticEvalCli.parse(new String[] {}));
    assertEquals(
        SyntheticEvalCli.Mode.EXECUTE,
        SyntheticEvalCli.parse(new String[] {"--execute"}));
    assertEquals(
        SyntheticEvalCli.Mode.HELP,
        SyntheticEvalCli.parse(new String[] {"--help"}));
    assertThrows(
        SyntheticEvalPreflight.Rejected.class,
        () ->
            SyntheticEvalCli.parse(
                new String[] {"--base-url", "http://127.0.0.1"}));
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

  private static void assertRejected(
      String expectedCode, Runnable action) {
    OneShotOperatorGate.Rejected rejected =
        assertThrows(OneShotOperatorGate.Rejected.class, action::run);
    assertEquals(expectedCode, rejected.code());
  }
}
