package io.emergeos.grapheval;

import java.util.Arrays;

final class GraphEvalCli {

  enum Mode {
    PREFLIGHT,
    VERIFY,
    HELP
  }

  private GraphEvalCli() {}

  static Mode parse(String[] args) {
    if (args == null || args.length == 0) {
      return Mode.PREFLIGHT;
    }
    if (Arrays.equals(args, new String[] {"--preflight"})) {
      return Mode.PREFLIGHT;
    }
    if (Arrays.equals(args, new String[] {"--verify"})) {
      return Mode.VERIFY;
    }
    if (Arrays.equals(args, new String[] {"--help"})) {
      return Mode.HELP;
    }
    throw new Pack009GraphPreflight.Rejected("ARGUMENTS_INVALID");
  }

  static String help() {
    return """
        Usage: java -jar emerge-graph-eval-runner.jar [mode]
          (no args)     Pack009 strict zero-effect preflight
          --preflight   Pack009 strict zero-effect preflight
          --verify      Reserved; shipping verification route is disabled
          --help        Show this help

        Shipping execution is intentionally unavailable.
        """
        .strip();
  }
}
