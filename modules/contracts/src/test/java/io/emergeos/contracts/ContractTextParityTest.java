package io.emergeos.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractTextParityTest {

  @Test
  void lengthIsCountedInUnicodeCodePoints() {
    assertDoesNotThrow(
        () -> ContractText.require("😀".repeat(2_048), "emoji boundary"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContractText.require("😀".repeat(2_049), "emoji overflow"));
  }

  @Test
  void frozenWhitespaceNulAndLoneSurrogatesAreRejected() {
    for (String invalid : List.of(" \t\n", "\u3000", "\u00A0", "before\0after", "\uD800")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ContractText.require(invalid, "invalid SafeText"));
    }
    assertDoesNotThrow(() -> ContractText.require("\n正文", "multiline text"));
  }

  @Test
  void taskListAndOptionalElementsUseTheSameSafeTextDomain() {
    TaskEnvelope valid = task(List.of("uses evidence"), null, "valid intent");
    assertDoesNotThrow(() -> valid);
    assertThrows(
        IllegalArgumentException.class,
        () -> task(List.of("\u3000"), null, "valid intent"));
    assertThrows(
        IllegalArgumentException.class,
        () -> task(List.of("uses evidence"), "   ", "valid intent"));
    assertThrows(
        IllegalArgumentException.class,
        () -> task(List.of("uses evidence"), null, "x".repeat(2_049)));
  }

  @Test
  void claimAndResultListsRejectUnsafeElements() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Claim("claim", List.of("capture://ok", "\0"), 1.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResultEnvelope(
                "1.0",
                "run-safe-text",
                "task-safe-text",
                RunStatus.FAILED,
                List.of(),
                List.of(),
                List.of(),
                List.of("\u3000"),
                List.of(),
                null,
                "agent-v1",
                "verifier-v1",
                BigDecimal.ZERO,
                0,
                0,
                "/api/v1/agent-runs/run-safe-text/trace",
                "SAFE_TEXT_REJECTED"));
  }

  private static TaskEnvelope task(
      List<String> acceptanceChecks, String idempotencyKey, String intent) {
    return new TaskEnvelope(
        "1.0",
        "task-safe-text",
        null,
        "owner-safe-text",
        List.of(),
        "CREATE_ARTICLE_DRAFT",
        intent,
        List.of("capture://capture-safe-text"),
        List.of(),
        List.of("text"),
        DataClass.PERSONAL,
        RiskLevel.REVERSIBLE,
        "INTERACTIVE",
        List.of("capture.read"),
        "urn:test:artifact",
        acceptanceChecks,
        false,
        2,
        1,
        5_000,
        BigDecimal.ZERO,
        idempotencyKey,
        "policy-v1",
        "state-v1",
        "context-v1",
        "tools-v1",
        null,
        List.of(),
        List.of(),
        "terminal");
  }
}
