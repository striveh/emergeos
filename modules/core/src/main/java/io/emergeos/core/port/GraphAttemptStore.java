package io.emergeos.core.port;

import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderIntent;
import java.time.Instant;

/**
 * Semantic durable port for a one-shot graph attempt.
 *
 * <p>There is intentionally no generic append method and no resume method.
 */
public interface GraphAttemptStore {

  CreateResult create(
      GraphAttemptManifest manifest, Instant occurredAt);

  GraphAttemptCursor approve(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphOperatorApproval approval,
      Instant occurredAt);

  GraphAttemptCursor startParent(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt);

  GraphAttemptCursor authorizeParent(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt);

  GraphAttemptCursor authorizeChild(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt);

  GraphAttemptCursor startChild(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun running,
      Instant occurredAt);

  GraphAttemptCursor consumeChildEgress(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt);

  GraphAttemptCursor credentialReadStarted(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt);

  GraphAttemptCursor clientCreated(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt);

  GraphAttemptCursor modelCreated(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      Instant occurredAt);

  GraphAttemptCursor providerIntent(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderIntent intent,
      Instant occurredAt);

  GraphAttemptVerification findVerified(
      GraphAttemptManifest expected);

  sealed interface CreateResult
      permits CreateResult.Created, CreateResult.AlreadyExists {

    record Created(GraphAttemptCursor cursor)
        implements CreateResult {}

    record AlreadyExists()
        implements CreateResult {}
  }
}
