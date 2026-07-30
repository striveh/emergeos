package io.emergeos.evalrunner;

import io.emergeos.contracts.RunStatus;
import java.nio.file.Path;
import java.time.Clock;

public final class SyntheticEvalMain {

  private SyntheticEvalMain() {}

  public static void main(String[] args) {
    try {
      SyntheticEvalCli.Mode mode = SyntheticEvalCli.parse(args);
      if (mode == SyntheticEvalCli.Mode.HELP) {
        System.out.println(SyntheticEvalCli.help());
        return;
      }
      Path repoRoot = Path.of("").toAbsolutePath();
      String preflightReceipt =
          new SyntheticEvalRunner(repoRoot).preflightReceipt();
      System.out.println(preflightReceipt);
      System.out.flush();
      if (mode == SyntheticEvalCli.Mode.EXECUTE) {
        SyntheticEvalExecutor.ExecutionResult result =
            new SyntheticEvalExecutor(repoRoot)
                .execute(
                    new SyntheticEvalExecutor.Dependencies(
                        new SystemInteractiveConsole(),
                        ownerHome(),
                        () -> System.getenv("OPENAI_API_KEY"),
                        new ProductionOpenAiClientFactory(),
                        Clock.systemUTC(),
                        System::nanoTime));
        System.out.println(result.receipt());
        System.out.flush();
        if (result.outcome().result().status() != RunStatus.SUCCEEDED) {
          System.exit(3);
        }
      }
    } catch (SyntheticEvalPreflight.Rejected rejected) {
      System.err.println("EVAL_REJECTED reason=" + rejected.code());
      System.exit(2);
    } catch (SyntheticEvalExecutor.Rejected rejected) {
      if (rejected.safeReceipt() != null) {
        System.err.println(rejected.safeReceipt());
      }
      System.err.println("EVAL_REJECTED reason=" + rejected.code());
      System.exit(2);
    } catch (RuntimeException bootstrapFailure) {
      System.err.println("EVAL_REJECTED reason=BOOTSTRAP_FAILED");
      System.exit(2);
    }
  }

  private static Path ownerHome() {
    String value = System.getProperty("user.home");
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("user.home is unavailable");
    }
    return Path.of(value);
  }
}
