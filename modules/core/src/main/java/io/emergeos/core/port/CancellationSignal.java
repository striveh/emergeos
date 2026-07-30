package io.emergeos.core.port;

@FunctionalInterface
public interface CancellationSignal {

  boolean isCancelled();

  static CancellationSignal never() {
    return () -> false;
  }
}
