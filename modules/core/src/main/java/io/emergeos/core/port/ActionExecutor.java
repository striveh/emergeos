package io.emergeos.core.port;

import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.CapabilityGrant;
import io.emergeos.core.domain.Receipt;
import java.time.Instant;

@FunctionalInterface
public interface ActionExecutor {

  Receipt execute(
      ActionPlan plan, ArtifactVersion artifact, CapabilityGrant capability, Instant now);
}

