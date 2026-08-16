package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GraphExactPicoProviderValidationTypesTest {

  @Test
  void commandDerivesBoundedTotalsAndFreezesChallengeTtl() {
    GraphExactPicoProviderValidationCommand command = command(100, 20, 10);
    assertEquals(110L, command.totalTokens());
    assertEquals(Duration.ofSeconds(5), command.challengeTtl());

    assertThrows(
        IllegalArgumentException.class,
        () -> command(10, 11, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> command(9_007_199_254_740_991L, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphExactPicoProviderValidationCommand(
                "principal",
                hash("attempt"),
                hash("manifest"),
                hash("requirement"),
                hash("head"),
                hash("request"),
                hash("response"),
                hash("model"),
                1,
                0,
                1,
                hash("decision"),
                "key",
                Duration.ofMillis(99)));
  }

  @Test
  void receiptRequiresTheExactOverlayTerminalShape() {
    GraphExactPicoProviderValidationReceipt receipt =
        new GraphExactPicoProviderValidationReceipt(
            "PICO_OVERLAY_V1",
            14,
            1,
            hash("head"),
            hash("statement"),
            hash("attribution"),
            hash("event"),
            hash("transcript"),
            hash("receipt"),
            GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED);
    assertEquals(14, receipt.overlaySequence());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphExactPicoProviderValidationReceipt(
                "PICO_OVERLAY_V1",
                15,
                1,
                hash("head"),
                hash("statement"),
                hash("attribution"),
                hash("event"),
                hash("transcript"),
                hash("receipt"),
                GraphExactPicoProviderValidationReceipt.ValidationState.CONSUMED));
    assertThrows(
        NullPointerException.class,
        () ->
            new GraphExactPicoProviderValidationReceipt(
                "PICO_OVERLAY_V1",
                14,
                1,
                hash("head"),
                hash("statement"),
                hash("attribution"),
                hash("event"),
                hash("transcript"),
                hash("receipt"),
                null));
  }

  private static GraphExactPicoProviderValidationCommand command(
      long input, long cached, long output) {
    return new GraphExactPicoProviderValidationCommand(
        "principal",
        hash("attempt"),
        hash("manifest"),
        hash("requirement"),
        hash("head"),
        hash("request"),
        hash("response"),
        hash("model"),
        input,
        cached,
        output,
        hash("decision"),
        "key",
        Duration.ofSeconds(5));
  }

  private static String hash(String value) {
    return io.emergeos.contracts.IntegrityHashes.utf8ContentHash(value);
  }
}
