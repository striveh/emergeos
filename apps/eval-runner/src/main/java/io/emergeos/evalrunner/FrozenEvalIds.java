package io.emergeos.evalrunner;

import io.emergeos.core.port.IdGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class FrozenEvalIds implements IdGenerator {

  private final Map<String, String> ids =
      Map.copyOf(
          new LinkedHashMap<>(
              Map.of(
                  "run", SyntheticEvalCatalog.RUN_ID,
                  "task", SyntheticEvalCatalog.TASK_ID,
                  "art", SyntheticEvalCatalog.ARTIFACT_ID)));
  private final Set<String> consumed = ConcurrentHashMap.newKeySet();

  @Override
  public String next(String prefix) {
    String id = ids.get(prefix);
    if (id == null || !consumed.add(prefix)) {
      throw new IllegalStateException(
          "unexpected frozen ID request");
    }
    return id;
  }

  void requireSuccessPathConsumed() {
    if (!consumed.equals(ids.keySet())) {
      throw new IllegalStateException(
          "successful eval did not consume the frozen ID set");
    }
  }
}
