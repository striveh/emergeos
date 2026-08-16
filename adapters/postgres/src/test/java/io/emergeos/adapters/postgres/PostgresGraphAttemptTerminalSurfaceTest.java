package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.port.AgentRunContext;
import io.emergeos.core.port.GraphAttemptStore;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PostgresGraphAttemptTerminalSurfaceTest {

  @Test
  void terminalMutationsAreRequiredAndImplementedByPostgresStore()
      throws ReflectiveOperationException {
    requirePostgresImplementation(
        "providerAttributed",
        GraphAttemptManifest.class,
        GraphAttemptCursor.class,
        GraphProviderAttribution.class,
        Instant.class);
    requirePostgresImplementation(
        "completeChild",
        GraphAttemptManifest.class,
        GraphAttemptCursor.class,
        AgentRunContext.class,
        AgentRun.class,
        HarnessCandidateEnvelope.class,
        WorkerResultEnvelope.class,
        Instant.class);
    requirePostgresImplementation(
        "completeParentAndSeal",
        GraphAttemptManifest.class,
        GraphAttemptCursor.class,
        AgentRun.class,
        ArtifactLineage.class,
        Instant.class);
  }

  private static void requirePostgresImplementation(
      String name, Class<?>... parameterTypes)
      throws ReflectiveOperationException {
    Method port =
        GraphAttemptStore.class.getMethod(name, parameterTypes);
    Method implementation =
        PostgresGraphAttemptStore.class.getMethod(
            name, parameterTypes);

    assertFalse(port.isDefault());
    assertEquals(
        PostgresGraphAttemptStore.class,
        implementation.getDeclaringClass());
  }
}
