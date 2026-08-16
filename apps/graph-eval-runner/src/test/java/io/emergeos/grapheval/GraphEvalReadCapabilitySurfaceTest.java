package io.emergeos.grapheval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.emergeos.core.port.GraphAttemptReader;
import io.emergeos.core.port.GraphAttemptStore;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GraphEvalReadCapabilitySurfaceTest {

  @Test
  void pack009VerifierAcceptsOnlyTheReadCapability() {
    assertReadOnlyConstructor(Pack009GraphVerifier.class);
  }

  @Test
  void pack010VerifierAcceptsOnlyTheReadCapability() {
    assertReadOnlyConstructor(Pack010GraphTerminalVerifier.class);
  }

  private static void assertReadOnlyConstructor(Class<?> type) {
    Constructor<?>[] constructors =
        type.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    assertArrayEquals(
        new Class<?>[] {GraphAttemptReader.class},
        constructors[0].getParameterTypes());
    assertFalse(
        Arrays.stream(constructors[0].getParameterTypes())
            .anyMatch(GraphAttemptStore.class::equals));
  }
}
