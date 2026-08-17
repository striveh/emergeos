package io.emergeos.core.port;

import io.emergeos.core.domain.GraphProviderValidationAttestation;
import io.emergeos.core.domain.GraphProviderValidationTranscript;

/** Narrow signing capability. Shipping code contains no implementation or private key. */
@FunctionalInterface
public interface GraphProviderValidationSigner {

  GraphProviderValidationAttestation attest(
      GraphProviderValidationTranscript transcript);
}
