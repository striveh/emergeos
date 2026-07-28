package io.emergeos.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.domain.ActionPlan;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.ProviderActionRequest;
import io.emergeos.core.domain.ProviderResult;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HttpSimulatedActionProviderTest {

  @Test
  void acceptsOnlyAnHttpLoopbackOrigin() {
    assertDoesNotThrow(
        () ->
            new HttpSimulatedActionProvider(
                URI.create("http://127.0.0.1:9876"), Duration.ofMillis(100)));
    assertDoesNotThrow(
        () ->
            new HttpSimulatedActionProvider(
                URI.create("http://[::1]:9876"), Duration.ofMillis(100)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpSimulatedActionProvider(
                URI.create("http://0.0.0.0:9876"), Duration.ofMillis(100)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpSimulatedActionProvider(
                URI.create("http://192.0.2.1:9876"), Duration.ofMillis(100)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpSimulatedActionProvider(
                URI.create("https://127.0.0.1:9876"), Duration.ofMillis(100)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HttpSimulatedActionProvider(
                URI.create("http://127.0.0.1:9876/path"), Duration.ofMillis(100)));
  }

  @Test
  void acceptsOnlyASuccessEchoForTheExactAuthorityAndPlan() {
    ActionPlan plan =
        new ActionPlan(
            "plan-echo",
            "echo-owner",
            "CREATE_LOCAL_DRAFT",
            "local://drafts",
            "artifact-echo",
            2,
            ContentHashes.sha256("synthetic approved echo artifact"),
            RiskLevel.REVERSIBLE,
            "local-action-v1",
            "echo-key",
            Instant.parse("2026-07-28T10:05:00Z"));
    ProviderActionRequest request =
        new ProviderActionRequest(
            ProviderActionRequest.ProviderOperation.RECONCILE_ONLY,
            plan,
            "simulated.local-draft",
            "adapter:simulated-provider",
            "simulated-account:echo-owner");

    assertInstanceOf(
        ProviderResult.Succeeded.class,
        HttpSimulatedActionProvider.parse(
            succeededEcho(request, request.audience()), request));
    assertInstanceOf(
        ProviderResult.Unknown.class,
        HttpSimulatedActionProvider.parse(
            succeededEcho(request, "adapter:wrong-audience"), request));
  }

  private static String succeededEcho(
      ProviderActionRequest request, String audience) {
    return String.join(
        "\t",
        "SUCCEEDED",
        "sim-object-echo",
        "sim-request-echo",
        "simulated://provider/objects/sim-object-echo",
        "2026-07-28T10:00:00Z",
        request.plan().principalId(),
        request.connector(),
        audience,
        request.accountRef(),
        request.plan().planId(),
        request.plan().planHash(),
        request.plan().artifactId(),
        Integer.toString(request.plan().artifactVersion()),
        request.plan().artifactHash(),
        request.plan().idempotencyKey());
  }
}
