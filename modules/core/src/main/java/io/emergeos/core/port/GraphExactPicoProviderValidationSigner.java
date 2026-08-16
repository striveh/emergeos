package io.emergeos.core.port;

import io.emergeos.core.domain.GraphExactPicoProviderValidationChallenge;

/** Narrow signing capability; shipping code contains no implementation or private key. */
@FunctionalInterface
public interface GraphExactPicoProviderValidationSigner {

  String sign(GraphExactPicoProviderValidationChallenge challenge);
}
