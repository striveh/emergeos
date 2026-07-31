package io.emergeos.grapheval;

import java.nio.file.Path;

public final class GraphEvalMain {

  private GraphEvalMain() {}

  public static void main(String[] args) {
    try {
      GraphEvalCli.Mode mode = GraphEvalCli.parse(args);
      if (mode == GraphEvalCli.Mode.HELP) {
        System.out.println(GraphEvalCli.help());
        return;
      }

      Path repoRoot = Path.of("").toAbsolutePath();
      System.out.println(new Pack009GraphPreflight(repoRoot).run().receipt());
      System.out.flush();

      if (mode == GraphEvalCli.Mode.VERIFY) {
        throw new Pack009GraphPreflight.Rejected(
            "SHIPPING_VERIFY_ROUTE_DISABLED");
      }
    } catch (Pack009GraphPreflight.Rejected rejected) {
      System.err.println(
          "GRAPH_EVAL_REJECTED reason=" + rejected.code());
      System.exit(2);
    } catch (RuntimeException bootstrapFailure) {
      System.err.println("GRAPH_EVAL_REJECTED reason=BOOTSTRAP_FAILED");
      System.exit(2);
    }
  }
}
