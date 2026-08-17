package io.emergeos.core.port;

import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphProviderValidationStatement;
import java.time.Duration;

/** Durable V13 authority for one signed request-2 validation receipt. */
public interface GraphProviderValidationAttestor {

  String requireValidation(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expectedPolicyCursor,
      String keyId,
      String transportProfileHash,
      String parserProfileHash,
      String schemaProfileHash,
      Duration challengeTtl);

  GraphAttemptCursor completeValidation(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderValidationStatement statement,
      GraphProviderValidationSigner signer);
}
