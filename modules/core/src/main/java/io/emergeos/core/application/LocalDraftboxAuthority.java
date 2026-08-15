package io.emergeos.core.application;

import io.emergeos.contracts.RiskLevel;
import java.time.Duration;
import java.util.Objects;

/** Exact authority for the durable local Draftbox; it is not provider authority. */
public record LocalDraftboxAuthority(String principalId, Duration capabilityTtl) {

  public static final String ACTION_TYPE = "CREATE_LOCAL_DRAFT";
  public static final String TARGET_REF = "local://drafts";
  public static final String POLICY_VERSION = "local-action-v2";
  public static final String CONNECTOR = "emergeos.local-draftbox";
  public static final String AUDIENCE = "emergeos:local-draftbox";
  public static final String ACCOUNT_PREFIX = "local-draftbox:";
  public static final int MAX_CALLS = 1;

  public LocalDraftboxAuthority {
    CreateArtifactCommand.requireIdentifier(principalId, "principalId");
    Objects.requireNonNull(capabilityTtl, "capabilityTtl");
    if (capabilityTtl.isZero() || capabilityTtl.isNegative()) {
      throw new IllegalArgumentException("capabilityTtl must be positive");
    }
    CreateArtifactCommand.requireIdentifier(accountRefFor(principalId), "accountRef");
  }

  public String actionType() {
    return ACTION_TYPE;
  }

  public String targetRef() {
    return TARGET_REF;
  }

  public RiskLevel risk() {
    return RiskLevel.REVERSIBLE;
  }

  public String policyVersion() {
    return POLICY_VERSION;
  }

  public String connector() {
    return CONNECTOR;
  }

  public String audience() {
    return AUDIENCE;
  }

  public String accountRef() {
    return accountRefFor(principalId);
  }

  public int maxCalls() {
    return MAX_CALLS;
  }

  public static String accountRefFor(String principalId) {
    return ACCOUNT_PREFIX + principalId;
  }
}
