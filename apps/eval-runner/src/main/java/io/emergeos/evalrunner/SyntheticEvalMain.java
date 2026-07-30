package io.emergeos.evalrunner;

import java.nio.file.Path;

public final class SyntheticEvalMain {

  private SyntheticEvalMain() {}

  public static void main(String[] args) {
    try {
      System.out.println(
          new SyntheticEvalRunner(Path.of("").toAbsolutePath())
              .preflightReceipt());
    } catch (SyntheticEvalPreflight.Rejected rejected) {
      System.err.println("EVAL_REJECTED reason=" + rejected.code());
      System.exit(2);
    }
  }
}
