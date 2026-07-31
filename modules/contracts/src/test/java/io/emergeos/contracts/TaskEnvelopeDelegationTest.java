package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEnvelopeDelegationTest {

  @Test
  void acceptsOnlyRootOrExactDepthOneDelegationShapes() {
    assertDoesNotThrow(() -> task(null, List.of()));
    assertDoesNotThrow(
        () -> task("parent-task", List.of("parent-task")));
  }

  @Test
  void rejectsADelegationChainOnARootTask() {
    assertThrows(
        IllegalArgumentException.class,
        () -> task(null, List.of("unbound-parent")));
  }

  @Test
  void rejectsAChildWithoutItsExactParentInTheChain() {
    assertThrows(
        IllegalArgumentException.class,
        () -> task("parent-task", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> task("parent-task", List.of("other-task")));
    assertThrows(
        IllegalArgumentException.class,
        () -> task("parent-task", List.of("parent-task", "ancestor-task")));
  }

  @Test
  void rejectsImmediateSelfParentCycles() {
    assertThrows(
        IllegalArgumentException.class,
        () -> task("delegation-task", List.of("delegation-task")));
  }

  private static TaskEnvelope task(
      String parentId,
      List<String> delegationChain) {
    return new TaskEnvelope(
        "1.0",
        "delegation-task",
        parentId,
        "delegation-owner",
        delegationChain,
        parentId == null ? "CREATE_ARTICLE_DRAFT" : "PROPOSE_ARTICLE_DRAFT",
        "Verify root and depth-one delegation shape",
        List.of("capture://delegation-capture"),
        List.of(),
        List.of("text"),
        DataClass.PUBLIC,
        parentId == null ? RiskLevel.REVERSIBLE : RiskLevel.READ_ONLY,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
        List.of(),
        false,
        2,
        1,
        5_000,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        "agent-draft-policy-v1",
        "stage2-pack007",
        "ref-only-v1",
        "agent-tools-v2",
        null,
        List.of(),
        List.of(),
        "structured final or non-success");
  }
}
