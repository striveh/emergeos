package io.emergeos.core.port;

import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;

/** One-shot typed V16 stage, local verification, and overlay commit authority. */
public interface GraphExactPicoProviderValidationAttestor {

  GraphExactPicoProviderValidationReceipt complete(
      GraphExactPicoProviderValidationCommand command,
      GraphExactPicoProviderValidationSigner signer);
}
