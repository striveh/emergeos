package io.emergeos.offlineharness;

import io.emergeos.contracts.HarnessRunBundle;
import io.emergeos.core.application.AgentDraftReferenceGrounding;
import io.emergeos.core.application.AgentDraftService;
import io.emergeos.core.domain.AgentDraftProposal;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.port.AgentKernel;

final class ForbiddenProductRuntimeTypeFixture {

  static final String REFLECTIVE_SERVICE_NAME =
      "io.emergeos.core.application.AgentDraftService";

  private ForbiddenProductRuntimeTypeFixture() {}

  static void consumeForbiddenDescriptors(
      AgentDraftService service,
      AgentKernel kernel,
      AgentRun run,
      HarnessRunBundle bundle) {}

  static void consumeExplicitlyAllowedDescriptors(
      AgentDraftReferenceGrounding.Candidate candidate,
      AgentDraftProposal proposal,
      ArtifactLineageEntry lineageEntry) {}
}
