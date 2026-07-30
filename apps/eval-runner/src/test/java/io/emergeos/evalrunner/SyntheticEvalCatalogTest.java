package io.emergeos.evalrunner;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyntheticEvalCatalogTest {

  @Test
  void freezesEveryExecutionAndMeteringBinding() {
    assertAll(
        () ->
            assertEquals(
                SyntheticEvalCatalog.EXPECTED_CAPTURE_REQUEST_HASH,
                SyntheticEvalCatalog.computedCaptureRequestHash()),
        () ->
            assertEquals(
                SyntheticEvalCatalog.EXPECTED_TASK_HASH,
                SyntheticEvalCatalog.computedTaskHash()),
        () ->
            assertEquals(
                SyntheticEvalCatalog.EXPECTED_PRICING_FINGERPRINT,
                SyntheticEvalCatalog.pricing().fingerprint()),
        () ->
            assertEquals(
                SyntheticEvalCatalog.EXPECTED_PROFILE_FINGERPRINT,
                SyntheticEvalCatalog.profile().fingerprint()),
        () ->
            assertEquals(
                "0.417000",
                SyntheticEvalCatalog.profile().reservationUsd().toPlainString()));
  }
}
