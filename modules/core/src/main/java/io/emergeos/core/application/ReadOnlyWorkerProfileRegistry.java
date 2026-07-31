package io.emergeos.core.application;

import io.emergeos.contracts.HarnessExperiment;
import io.emergeos.contracts.TaskEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable compiled registry for durable read-only Worker generations.
 *
 * <p>A PostgreSQL store must be able to verify historical Pack007 truth while
 * accepting a separately frozen Pack008 graph. Running truth has no persisted
 * Worker profile key yet, so it is resolved only when exactly one registered
 * profile accepts the complete Task authority. Terminal truth must name the
 * exact registry and profile fingerprint in its Bundle; no fallback to a
 * "current" profile is allowed.
 */
public final class ReadOnlyWorkerProfileRegistry {

  private static final String REGISTRY_COMPONENT = "worker-registry";
  private static final String PROFILE_COMPONENT =
      "worker-profile-fingerprint";

  private final List<ReadOnlyWorkerProfile> profiles;
  private final Map<ProfileKey, ReadOnlyWorkerProfile> byIdentity;

  private ReadOnlyWorkerProfileRegistry(
      List<ReadOnlyWorkerProfile> profiles) {
    if (profiles == null || profiles.isEmpty()) {
      throw new IllegalArgumentException(
          "at least one Worker profile must be registered");
    }
    List<ReadOnlyWorkerProfile> frozen = List.copyOf(profiles);
    Map<ProfileKey, ReadOnlyWorkerProfile> identities =
        new LinkedHashMap<>();
    for (ReadOnlyWorkerProfile profile : frozen) {
      Objects.requireNonNull(profile, "Worker profile");
      ProfileKey key =
          new ProfileKey(
              profile.registryVersion(), profile.fingerprint());
      if (identities.putIfAbsent(key, profile) != null) {
        throw new IllegalArgumentException(
            "duplicate Worker profile identity");
      }
    }
    this.profiles = frozen;
    this.byIdentity = Map.copyOf(identities);
  }

  public static ReadOnlyWorkerProfileRegistry pack007Only() {
    return of(ReadOnlyWorkerExecutionProfile.pack007FakeV1());
  }

  public static ReadOnlyWorkerProfileRegistry of(
      ReadOnlyWorkerProfile... profiles) {
    Objects.requireNonNull(profiles, "profiles");
    return new ReadOnlyWorkerProfileRegistry(List.of(profiles));
  }

  public ReadOnlyWorkerProfile requireForRunningParent(
      TaskEnvelope parent) {
    Objects.requireNonNull(parent, "parent");
    return requireUnique(
        profiles.stream()
            .filter(profile -> acceptsParent(profile, parent))
            .toList(),
        "running parent Task");
  }

  public ReadOnlyWorkerProfile requireForChild(
      TaskEnvelope parent, TaskEnvelope child) {
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(child, "child");
    return requireUnique(
        profiles.stream()
            .filter(profile -> acceptsChild(profile, parent, child))
            .toList(),
        "parent/child Task graph");
  }

  public ReadOnlyWorkerProfile requireForTerminalParent(
      TaskEnvelope parent,
      HarnessExperiment experiment,
      String harnessVersion,
      Map<String, String> componentVersions) {
    ReadOnlyWorkerProfile profile =
        requireExactIdentity(componentVersions);
    profile.requireParentBinding(
        Objects.requireNonNull(parent, "parent"));
    requireExperiment(profile, experiment);
    if (!profile
            .parentExecutionProfile()
            .harnessVersion()
            .equals(harnessVersion)
        || !profile
            .expectedParentComponentVersions()
            .equals(componentVersions)) {
      throw new IllegalArgumentException(
          "terminal parent Bundle does not match its Worker profile");
    }
    return profile;
  }

  public ReadOnlyWorkerProfile requireForTerminalChild(
      TaskEnvelope parent,
      TaskEnvelope child,
      HarnessExperiment experiment,
      String harnessVersion,
      Map<String, String> componentVersions) {
    ReadOnlyWorkerProfile profile =
        requireExactIdentity(componentVersions);
    profile.requireChildBinding(
        Objects.requireNonNull(parent, "parent"),
        Objects.requireNonNull(child, "child"));
    requireExperiment(profile, experiment);
    if (!profile.harnessVersion().equals(harnessVersion)
        || !profile.componentVersions().equals(componentVersions)) {
      throw new IllegalArgumentException(
          "terminal child Bundle does not match its Worker profile");
    }
    return profile;
  }

  private ReadOnlyWorkerProfile requireExactIdentity(
      Map<String, String> componentVersions) {
    Objects.requireNonNull(componentVersions, "componentVersions");
    String registry = componentVersions.get(REGISTRY_COMPONENT);
    String fingerprint = componentVersions.get(PROFILE_COMPONENT);
    if (registry == null || fingerprint == null) {
      throw new IllegalArgumentException(
          "terminal Worker truth lacks its exact profile identity");
    }
    ReadOnlyWorkerProfile profile =
        byIdentity.get(new ProfileKey(registry, fingerprint));
    if (profile == null) {
      throw new IllegalArgumentException(
          "terminal Worker truth names an unregistered profile");
    }
    return profile;
  }

  private static void requireExperiment(
      ReadOnlyWorkerProfile profile, HarnessExperiment experiment) {
    if (!Objects.equals(profile.experiment(), experiment)) {
      throw new IllegalArgumentException(
          "terminal Worker experiment does not match its profile");
    }
  }

  private static boolean acceptsParent(
      ReadOnlyWorkerProfile profile, TaskEnvelope parent) {
    try {
      profile.requireParentBinding(parent);
      return true;
    } catch (IllegalArgumentException rejected) {
      return false;
    }
  }

  private static boolean acceptsChild(
      ReadOnlyWorkerProfile profile,
      TaskEnvelope parent,
      TaskEnvelope child) {
    try {
      profile.requireChildBinding(parent, child);
      return true;
    } catch (IllegalArgumentException rejected) {
      return false;
    }
  }

  private static ReadOnlyWorkerProfile requireUnique(
      List<ReadOnlyWorkerProfile> candidates, String graphPart) {
    if (candidates.size() != 1) {
      throw new IllegalArgumentException(
          graphPart
              + " must match exactly one compiled Worker profile");
    }
    return candidates.getFirst();
  }

  private record ProfileKey(String registry, String fingerprint) {

    private ProfileKey {
      Objects.requireNonNull(registry, "registry");
      Objects.requireNonNull(fingerprint, "fingerprint");
    }
  }
}
