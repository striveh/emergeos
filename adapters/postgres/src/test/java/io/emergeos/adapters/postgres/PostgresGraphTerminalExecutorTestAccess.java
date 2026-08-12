package io.emergeos.adapters.postgres;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Test-only access to the private raw semantic-function probe boundary. */
final class PostgresGraphTerminalExecutorTestAccess {

  private static final Method CHILD = method("completeChild");
  private static final Method PARENT = method("completeParentAndSeal");

  private PostgresGraphTerminalExecutorTestAccess() {}

  static String completeChild(
      PostgresGraphTerminalExecutor executor, String payload) {
    return invoke(CHILD, executor, payload);
  }

  static String completeParentAndSeal(
      PostgresGraphTerminalExecutor executor, String payload) {
    return invoke(PARENT, executor, payload);
  }

  private static Method method(String name) {
    try {
      Method method =
          PostgresGraphTerminalExecutor.class.getDeclaredMethod(
              name, String.class);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static String invoke(
      Method method,
      PostgresGraphTerminalExecutor executor,
      String payload) {
    try {
      return (String) method.invoke(executor, payload);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
