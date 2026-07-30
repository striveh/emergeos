package io.emergeos.evalrunner;

import java.nio.file.Path;

final class SyntheticEvalRunner {

  private final SyntheticEvalPreflight preflight;

  SyntheticEvalRunner(Path repoRoot) {
    this.preflight = new SyntheticEvalPreflight(repoRoot);
  }

  String preflightReceipt() {
    return preflight.run().receipt();
  }
}
