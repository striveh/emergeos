package io.emergeos.core.port;

import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptVerification;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import java.time.Instant;

/**
 * Semantic durable port for a one-shot graph attempt.
 *
 * <p>There is intentionally no generic append method and no resume method.
 */
public interface GraphAttemptStore extends GraphAttemptReader {

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

  GraphAttemptCursor providerAttributed(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      Instant occurredAt);

  default GraphAttemptCursor providerFailureAttributed(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      GraphAttributedFailureCode failureCode,
      Instant occurredAt) {
    throw new UnsupportedOperationException(
        "durable attributed failure is not supported by this store");
  }

  GraphAttemptCursor completeChild(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRunContext parent,
      AgentRun terminalChild,
      HarnessCandidateEnvelope candidate,
      WorkerResultEnvelope workerResult,
      Instant occurredAt);

  GraphAttemptCursor completeParentAndSeal(
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      AgentRun terminalParent,
      ArtifactLineage artifact,
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
