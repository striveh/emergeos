package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.port.GraphAttemptReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessEvaluationReportProjectorSurfaceTest {

  @Test
  void ownsOnlyTheReadCapabilityAndReturnsCompleteOrUnavailable()
      throws Exception {
    Class<?> projector =
        Class.forName(
            "io.emergeos.core.application.HarnessEvaluationReportProjector");
    Constructor<?>[] constructors = projector.getConstructors();
    assertEquals(1, constructors.length);
    assertEquals(
        List.of(GraphAttemptReader.class),
        List.of(constructors[0].getParameterTypes()));
    Method project = projector.getMethod("project", List.class);
    assertEquals("Reduction", project.getReturnType().getSimpleName());
    assertEquals(1, projector.getDeclaredFields().length);
    assertEquals(
        GraphAttemptReader.class,
        projector.getDeclaredFields()[0].getType());

    Class<?> reduction =
        Arrays.stream(projector.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("Reduction"))
            .findFirst()
            .orElseThrow();
    assertTrue(reduction.isSealed());
    assertEquals(
        List.of("Complete", "Unavailable"),
        Arrays.stream(reduction.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .sorted()
            .toList());
    for (Class<?> type : reduction.getPermittedSubclasses()) {
      assertTrue(type.isRecord());
      assertTrue(type.getRecordComponents().length > 0);
      Arrays.stream(type.getRecordComponents())
          .map(RecordComponent::getName)
          .forEach(name -> assertTrue(!name.equals("partialReport")));
    }
  }
}
