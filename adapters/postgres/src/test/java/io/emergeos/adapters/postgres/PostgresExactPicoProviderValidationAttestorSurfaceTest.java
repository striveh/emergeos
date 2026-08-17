package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.domain.GraphExactPicoProviderValidationChallenge;
import io.emergeos.core.domain.GraphExactPicoProviderValidationCommand;
import io.emergeos.core.domain.GraphExactPicoProviderValidationReceipt;
import io.emergeos.core.port.GraphExactPicoProviderValidationAttestor;
import io.emergeos.core.port.GraphExactPicoProviderValidationSigner;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresExactPicoProviderValidationAttestorSurfaceTest {

  @Test
  void exposesOneTypedCompletionAndNoRawStageOrCommit() {
    Class<?> adapter = PostgresExactPicoProviderValidationAttestor.class;
    assertTrue(Modifier.isPublic(adapter.getModifiers()));
    assertTrue(Modifier.isFinal(adapter.getModifiers()));
    assertEquals(
        List.of(GraphExactPicoProviderValidationAttestor.class),
        List.of(adapter.getInterfaces()));

    List<Method> publicMethods =
        Arrays.stream(adapter.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .sorted(java.util.Comparator.comparing(Method::getName))
            .toList();
    assertEquals(List.of("complete", "open"),
        publicMethods.stream().map(Method::getName).toList());
    Method complete = publicMethods.getFirst();
    assertFalse(Modifier.isStatic(complete.getModifiers()));
    assertEquals(
        List.of(
            GraphExactPicoProviderValidationCommand.class,
            GraphExactPicoProviderValidationSigner.class),
        List.of(complete.getParameterTypes()));
    assertEquals(
        GraphExactPicoProviderValidationReceipt.class,
        complete.getReturnType());
    Method open = publicMethods.getLast();
    assertTrue(Modifier.isStatic(open.getModifiers()));
    assertEquals(List.of(DataSource.class), List.of(open.getParameterTypes()));
    assertEquals(adapter, open.getReturnType());

    Constructor<?>[] constructors = adapter.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    assertFalse(Modifier.isPublic(constructors[0].getModifiers()));
    assertFalse(Modifier.isProtected(constructors[0].getModifiers()));
    assertEquals(
        List.of(DataSource.class, PostgresExactPicoProviderValidationAttestor.Probe.class),
        List.of(constructors[0].getParameterTypes()));
    assertFalse(Modifier.isPublic(
        PostgresExactPicoProviderValidationAttestor.Probe.class.getModifiers()));
    assertFalse(Modifier.isPublic(
        PostgresExactPicoProviderValidationAttestor.ProbePoint.class.getModifiers()));
  }

  @Test
  void freezesTheTwoSingleMethodCapabilityPorts() {
    Method[] attestorMethods =
        GraphExactPicoProviderValidationAttestor.class.getDeclaredMethods();
    assertEquals(1, attestorMethods.length);
    assertEquals("complete", attestorMethods[0].getName());
    assertTrue(Modifier.isAbstract(attestorMethods[0].getModifiers()));
    assertFalse(attestorMethods[0].isDefault());

    Method[] signerMethods =
        GraphExactPicoProviderValidationSigner.class.getDeclaredMethods();
    assertEquals(1, signerMethods.length);
    assertEquals("sign", signerMethods[0].getName());
    assertEquals(
        List.of(GraphExactPicoProviderValidationChallenge.class),
        List.of(signerMethods[0].getParameterTypes()));
    assertEquals(String.class, signerMethods[0].getReturnType());
    assertTrue(Modifier.isAbstract(signerMethods[0].getModifiers()));
    assertFalse(signerMethods[0].isDefault());
    assertTrue(GraphExactPicoProviderValidationSigner.class
        .isAnnotationPresent(FunctionalInterface.class));
  }
}
