package io.emergeos.contracts;

public record HarnessExperiment(String arm, int repetition) {

  public HarnessExperiment {
    ContractText.require(arm, "arm", 128);
    if (repetition < 1 || repetition > ContractValueDomains.MAX_EXPERIMENT_REPETITION) {
      throw new IllegalArgumentException(
          "repetition must be between 1 and "
              + ContractValueDomains.MAX_EXPERIMENT_REPETITION);
    }
  }
}
