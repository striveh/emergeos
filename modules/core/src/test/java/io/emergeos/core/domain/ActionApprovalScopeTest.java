package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.RiskLevel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ActionApprovalScopeTest {

  @Test
  void canonicalHashUsesSchemaNulAndUnsignedLengthPrefixedUtf8Values() {
    ActionApprovalScope scope = scope(ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
        ActionApprovalScope.LOCAL_DRAFTBOX_V1);
    byte[] canonical = scope.canonicalBytes();
    byte[] prefix = (ActionApprovalScope.SCHEMA + "\0").getBytes(StandardCharsets.UTF_8);
    assertEquals(prefix.length + 4 + "CONFIGURED_LOCAL_PRINCIPAL".getBytes(StandardCharsets.UTF_8).length,
        prefix.length + 4 + ByteBuffer.wrap(canonical, prefix.length, 4).getInt());
    assertEquals("8d02f322dd1796f70a86c3404de09bedb74d7d8f6a6ff5372769a2441871fe60",
        scope.scopeHash());
  }

  @Test
  void provenanceAndRouteAreInsideTheScopeHash() {
    ActionApprovalScope explicit = scope(ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
        ActionApprovalScope.LOCAL_DRAFTBOX_V1);
    ActionApprovalScope legacy = scope(ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
        ActionApprovalScope.SIMULATED_PROVIDER_V1);
    assertNotEquals(explicit.scopeHash(), legacy.scopeHash());
    assertThrows(
        IllegalArgumentException.class,
        () -> scope(ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
            ActionApprovalScope.SIMULATED_PROVIDER_V1));
    assertThrows(
        IllegalArgumentException.class,
        () -> scope(ActionApprovalScope.PRE_V17_UNPROVEN,
            ActionApprovalScope.LOCAL_DRAFTBOX_V1));
  }

  @Test
  void zeroTtlIsCanonicalOnlyForUnprovenPreV17History() {
    ActionApprovalScope historical =
        scope(
            ActionApprovalScope.PRE_V17_UNPROVEN,
            ActionApprovalScope.SIMULATED_PROVIDER_V1,
            0);

    assertEquals(0, historical.capabilityTtlMicros());
    assertEquals(
        "5ac8219b6659906c89a7d8326724113cfb0582aa9b8de1e857479a5e7fb54d9d",
        historical.scopeHash());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scope(
                ActionApprovalScope.LEGACY_SERVER_IMPLICIT,
                ActionApprovalScope.SIMULATED_PROVIDER_V1,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scope(
                ActionApprovalScope.EXPLICIT_LOCAL_OWNER_INPUT,
                ActionApprovalScope.LOCAL_DRAFTBOX_V1,
                0));
  }

  private static ActionApprovalScope scope(String origin, String route) {
    return scope(origin, route, 300_000_000L);
  }

  private static ActionApprovalScope scope(String origin, String route, long ttlMicros) {
    return new ActionApprovalScope(
        ActionApprovalScope.CONFIGURED_LOCAL_PRINCIPAL,
        "本地主人",
        origin,
        route,
        "CREATE_LOCAL_DRAFT",
        "local://drafts",
        "artifact-1",
        2,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        RiskLevel.REVERSIBLE,
        "local-action-v1",
        "simulated.local-draft",
        "adapter:simulated-provider",
        "simulated-account:owner",
        ttlMicros,
        2);
  }
}
