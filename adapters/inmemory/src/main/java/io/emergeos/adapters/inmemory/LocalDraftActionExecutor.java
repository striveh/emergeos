package io.emergeos.adapters.inmemory;

import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.CapabilityGrant;
import io.emergeos.core.domain.Receipt;
import io.emergeos.core.port.ActionExecutor;
import io.emergeos.core.port.IdGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalDraftActionExecutor implements ActionExecutor {

  public static final String CAPABILITY_AUDIENCE = "adapter:local-draft";

  private final IdGenerator idGenerator;
  private final Map<String, Receipt> externalDrafts = new ConcurrentHashMap<>();

  public LocalDraftActionExecutor(IdGenerator idGenerator) {
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  public Receipt execute(
      ActionPlan plan, ArtifactVersion artifact, CapabilityGrant capability, Instant now) {
    capability.assertAllows(
        plan, artifact, CAPABILITY_AUDIENCE, accountRef(plan.principalId()), now);
    if (!"local.draft.create".equals(plan.actionType())) {
      throw new IllegalStateException("unsupported local action: " + plan.actionType());
    }
    return externalDrafts.computeIfAbsent(
        plan.idempotencyKey(),
        ignored ->
            new Receipt(
                idGenerator.next("receipt"),
                plan.planId(),
                "SUCCEEDED",
                idGenerator.next("local_draft"),
                plan.idempotencyKey(),
                artifact.contentHash(),
                idGenerator.next("request"),
                "memory://local-draftbox/" + plan.idempotencyKey(),
                now));
  }

  public int externalDraftCount() {
    return externalDrafts.size();
  }

  static String accountRef(String principalId) {
    return "local-draft-account:" + principalId;
  }
}
