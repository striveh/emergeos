package io.emergeos.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.contracts.HarnessCandidateEnvelope;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SharedCandidateHarnessEvaluatorSurfaceTest {

  @Test
  void consumesOneSharedCandidateAndExposesNoEffectCapability()
      throws Exception {
    Class<?> evaluator =
        Class.forName(
            "io.emergeos.core.application.SharedCandidateHarnessEvaluator");
    Method evaluate =
        evaluator.getDeclaredMethod(
            "evaluate", HarnessCandidateEnvelope.class);

    assertTrue(Modifier.isStatic(evaluate.getModifiers()));
    assertEquals("EvaluationPair", evaluate.getReturnType().getSimpleName());
    assertEquals(0, evaluator.getDeclaredFields().length);
    assertEquals(1, evaluator.getDeclaredMethods().length);
  }
}
