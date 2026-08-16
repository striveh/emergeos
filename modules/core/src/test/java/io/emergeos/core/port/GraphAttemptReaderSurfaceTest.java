package io.emergeos.core.port;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptVerification;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class GraphAttemptReaderSurfaceTest {

  @Test
  void graphAttemptReaderIsAnExactOneMethodCapability() {
    Class<?> reader =
        assertDoesNotThrow(
            () ->
                Class.forName(
                    "io.emergeos.core.port.GraphAttemptReader"));

    assertTrue(reader.isInterface());
    assertTrue(Modifier.isPublic(reader.getModifiers()));
    assertTrue(reader.isAssignableFrom(GraphAttemptStore.class));

    Method[] methods = reader.getDeclaredMethods();
    assertEquals(1, methods.length);
    Method findVerified = methods[0];
    assertEquals("findVerified", findVerified.getName());
    assertArrayEquals(
        new Class<?>[] {GraphAttemptManifest.class},
        findVerified.getParameterTypes());
    assertEquals(
        GraphAttemptVerification.class,
        findVerified.getReturnType());
    assertTrue(Modifier.isPublic(findVerified.getModifiers()));
    assertTrue(Modifier.isAbstract(findVerified.getModifiers()));
    assertFalse(findVerified.isDefault());
    assertFalse(Modifier.isStatic(findVerified.getModifiers()));
  }
}
