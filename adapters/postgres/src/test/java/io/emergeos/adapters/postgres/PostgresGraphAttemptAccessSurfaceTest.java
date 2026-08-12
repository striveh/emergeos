package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.port.GraphAttemptReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresGraphAttemptAccessSurfaceTest {

  @Test
  void exposesOnlyAReadCapabilityAndHidesStoreConstruction()
      throws Exception {
    Class<?> access =
        Class.forName(
            "io.emergeos.adapters.postgres.PostgresGraphAttemptAccess");
    assertTrue(Modifier.isPublic(access.getModifiers()));
    assertTrue(Modifier.isFinal(access.getModifiers()));
    for (Constructor<?> constructor : access.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }

    List<Method> publicMethods =
        Arrays.stream(access.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .toList();
    assertEquals(1, publicMethods.size());
    Method openReader = publicMethods.getFirst();
    assertEquals("openReader", openReader.getName());
    assertTrue(Modifier.isStatic(openReader.getModifiers()));
    assertEquals(
        List.of(DataSource.class),
        List.of(openReader.getParameterTypes()));
    assertEquals(GraphAttemptReader.class, openReader.getReturnType());

    for (Constructor<?> constructor :
        PostgresGraphAttemptStore.class.getDeclaredConstructors()) {
      assertFalse(Modifier.isPublic(constructor.getModifiers()));
      assertFalse(Modifier.isProtected(constructor.getModifiers()));
    }
  }
}
