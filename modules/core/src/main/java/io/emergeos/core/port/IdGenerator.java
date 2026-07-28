package io.emergeos.core.port;

@FunctionalInterface
public interface IdGenerator {

  String next(String prefix);
}

