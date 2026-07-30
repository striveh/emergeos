package io.emergeos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record Claim(String text, List<String> evidenceRefs, double confidence) {

  public Claim {
    ContractText.require(text, "text");
    evidenceRefs = ContractText.copyStrings(evidenceRefs, "evidenceRefs");
    if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be finite and between 0 and 1");
    }
    ContractValueDomains.requireUsd(BigDecimal.valueOf(confidence), "confidence");
  }
}
