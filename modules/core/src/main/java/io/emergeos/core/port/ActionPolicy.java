package io.emergeos.core.port;

import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ApprovalDecision;
import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.CapabilityGrant;
import java.time.Instant;

public interface ActionPolicy {

  ActionPlan plan(
      String planId, String manifestationId, String principalId, ArtifactVersion artifact, Instant now);

  CapabilityGrant authorize(
      String capabilityId,
      ActionPlan plan,
      ArtifactVersion artifact,
      ApprovalDecision approval,
      Instant now);
}
