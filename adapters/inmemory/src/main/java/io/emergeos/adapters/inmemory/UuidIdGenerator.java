package io.emergeos.adapters.inmemory;

import io.emergeos.core.port.IdGenerator;
import java.util.UUID;

public final class UuidIdGenerator implements IdGenerator {

  @Override
  public String next(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("prefix must not be blank");
    }
    return prefix + "_" + UUID.randomUUID();
  }
}

