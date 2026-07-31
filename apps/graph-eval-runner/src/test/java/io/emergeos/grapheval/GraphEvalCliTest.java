package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphEvalCliTest {

  @Test
  void acceptsOnlyTheThreeShippingModes() {
    assertEquals(
        GraphEvalCli.Mode.PREFLIGHT, GraphEvalCli.parse(null));
    assertEquals(
        GraphEvalCli.Mode.PREFLIGHT,
        GraphEvalCli.parse(new String[0]));
    assertEquals(
        GraphEvalCli.Mode.PREFLIGHT,
        GraphEvalCli.parse(new String[] {"--preflight"}));
    assertEquals(
        GraphEvalCli.Mode.VERIFY,
        GraphEvalCli.parse(new String[] {"--verify"}));
    assertEquals(
        GraphEvalCli.Mode.HELP,
        GraphEvalCli.parse(new String[] {"--help"}));
  }

  @Test
  void rejectsExecutionAndEveryOverrideSurface() {
    String[][] rejected = {
      {"--execute"},
      {"--harness"},
      {"--preflight", "--verify"},
      {"--jdbc-url", "jdbc:postgresql://localhost/test"},
      {"--api-key", "not-a-secret"},
      {"--model", "other"},
      {"--base-url", "http://127.0.0.1"},
      {"--execution-slot", "other"},
      {"--crash-phase", "provider-accepted"}
    };

    for (String[] args : rejected) {
      Pack009GraphPreflight.Rejected failure =
          assertThrows(
              Pack009GraphPreflight.Rejected.class,
              () -> GraphEvalCli.parse(args));
      assertEquals("ARGUMENTS_INVALID", failure.code());
    }
    assertFalse(GraphEvalCli.help().contains("--execute"));
    assertFalse(GraphEvalCli.help().contains("--jdbc"));
    assertFalse(GraphEvalCli.help().contains("--api-key"));
    assertTrue(
        GraphEvalCli.help().contains(
            "shipping verification route is disabled"));
  }
}
