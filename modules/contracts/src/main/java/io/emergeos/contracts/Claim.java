package io.emergeos.contracts;

import java.util.List;
import java.util.Objects;

public record Claim(String text, List<String> evidenceRefs, double confidence) {

  public Claim {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
    }
  }
}
