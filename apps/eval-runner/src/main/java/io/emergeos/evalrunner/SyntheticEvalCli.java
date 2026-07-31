package io.emergeos.evalrunner;

import java.util.Arrays;

final class SyntheticEvalCli {

  enum Mode {
    PREFLIGHT,
    WORKER_PREFLIGHT,
    EXECUTE,
    HELP
  }

  private SyntheticEvalCli() {}

  static Mode parse(String[] args) {
    if (args == null || args.length == 0) {
      return Mode.PREFLIGHT;
    }
    if (Arrays.equals(args, new String[] {"--help"})) {
      return Mode.HELP;
    }
    if (Arrays.equals(args, new String[] {"--worker-preflight"})) {
      return Mode.WORKER_PREFLIGHT;
    }
    if (Arrays.equals(args, new String[] {"--execute"})) {
      return Mode.EXECUTE;
    }
    throw new SyntheticEvalPreflight.Rejected("ARGUMENTS_INVALID");
  }

  static String help() {
    return """
        Usage: java -jar emerge-eval-runner.jar [mode]
          (no args)           Pack003 preflight only
          --worker-preflight  Pack008 preflight only
          --execute           Pack003 one-shot execution only
          --help              Show this help
        """
        .strip();
  }
}
