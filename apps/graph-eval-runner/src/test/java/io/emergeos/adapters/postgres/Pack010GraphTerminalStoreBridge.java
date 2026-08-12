package io.emergeos.adapters.postgres;

import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptEvent;
import io.emergeos.core.domain.GraphAttemptEventType;
import io.emergeos.core.domain.GraphAttemptCursor;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptOutcome;
import io.emergeos.core.domain.GraphAttemptPhase;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphAttributedFailureCode;
import io.emergeos.core.domain.GraphProviderAttribution;
import io.emergeos.core.domain.GraphBillingStatus;
import io.emergeos.core.domain.GraphProviderIntent;
import io.emergeos.core.domain.GraphRunSelection;
import io.emergeos.core.domain.GraphTerminalBinding;
import io.emergeos.core.domain.GraphTerminalSeal;
import io.emergeos.core.port.GraphAttemptReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Test-only bridge to the package-private Pack010 fault probe. */
public final class Pack010GraphTerminalStoreBridge {

  private static final List<String> TX_A_PROBES =
      List.of(
          "AFTER_PROVIDER_ATTRIBUTION_INSERT",
          "AFTER_EVENT_INSERT",
          "AFTER_HEAD_UPDATE",
          "AFTER_ATTRIBUTED_FAILURE_OUTCOME_INSERT",
          "AFTER_PROVIDER_ATTRIBUTION_VERIFIED_READ");

  private static final List<String> TX_B_PROBES =
      List.of(
          "AFTER_CANDIDATE_INSERT",
          "AFTER_WORKER_RESULT_INSERT",
          "AFTER_TERMINAL_TRACE_ROW_INSERT@1",
          "AFTER_TERMINAL_TRACE_ROW_INSERT@2",
          "AFTER_TERMINAL_TRACE_ROW_INSERT@3",
          "AFTER_TERMINAL_TRACE_ROW_INSERT@4",
          "AFTER_TERMINAL_TRACE_ROW_INSERT@5",
          "AFTER_TERMINAL_RESOURCE_BINDING_ROW_INSERT@1",
          "AFTER_TERMINAL_RESOURCE_BINDING_ROW_INSERT@2",
          "AFTER_TERMINAL_RUN_UPDATE",
          "AFTER_TERMINAL_BINDING_INSERT",
          "AFTER_EVENT_INSERT",
          "AFTER_HEAD_UPDATE",
          "AFTER_CHILD_TERMINAL_VERIFIED_READ");

  private static final List<String> TX_C_PROBES =
      List.of(
          "AFTER_PARENT_ARTIFACT_ROW_INSERT",
          "AFTER_PARENT_ARTIFACT_VERSION_INSERT",
          "AFTER_PARENT_TRACE_ROW_INSERT@1",
          "AFTER_PARENT_TRACE_ROW_INSERT@2",
          "AFTER_PARENT_TRACE_ROW_INSERT@3",
          "AFTER_PARENT_TRACE_ROW_INSERT@4",
          "AFTER_PARENT_TRACE_ROW_INSERT@5",
          "AFTER_PARENT_TRACE_ROW_INSERT@6",
          "AFTER_PARENT_RESOURCE_BINDING_ROW_INSERT@1",
          "AFTER_PARENT_RESOURCE_BINDING_ROW_INSERT@2",
          "AFTER_PARENT_RESOURCE_BINDING_ROW_INSERT@3",
          "AFTER_PARENT_RUN_UPDATE",
          "AFTER_PARENT_TERMINAL_BINDING_INSERT",
          "AFTER_PARENT_EVENT_INSERT",
          "AFTER_PARENT_HEAD_UPDATE",
          "AFTER_TERMINAL_SEAL_INSERT",
          "AFTER_SEAL_EVENT_INSERT",
          "AFTER_SEALED_HEAD_UPDATE",
          "AFTER_SEALED_VERIFIED_READ");

  private Pack010GraphTerminalStoreBridge() {}

  public static PostgresGraphAttemptStore openWriter(
      DataSource dataSource) {
    return new PostgresGraphAttemptStore(
        Objects.requireNonNull(dataSource, "dataSource"));
  }

  public static int claimSuccessor(
      DataSource dataSource,
      GraphAttemptManifest current,
      GraphAttemptManifest predecessor,
      String probeName,
      Consumer<String> predecessorVerified) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(predecessor, "predecessor");
    Objects.requireNonNull(probeName, "probeName");
    Objects.requireNonNull(
        predecessorVerified, "predecessorVerified");
    if (!Set.of(
            "NONE",
            "AFTER_PREDECESSOR_VERIFIED",
            "AFTER_HEAD_UPDATE")
        .contains(probeName)) {
      throw new IllegalArgumentException(
          "successor probe is outside the reviewed transaction");
    }
    AtomicBoolean delivered = new AtomicBoolean();
    PostgresGraphAttemptStore claimant =
        new PostgresGraphAttemptStore(
            dataSource,
            point -> {
              if (point.name().equals(probeName)
                  && delivered.compareAndSet(false, true)) {
                predecessorVerified.accept(point.name());
              }
            });
    return claimant
        .claimForOwner(
            current, predecessor, Duration.ofSeconds(30))
        .cursor()
        .lastSequence();
  }

  public static GraphAttemptReader openLegacyWriterBackedReader(
      DataSource dataSource) {
    return openWriter(dataSource);
  }

  /**
   * Test-only read-back of the production durable provider-session claim.
   * The shipping store method remains package-private; this bridge exposes no
   * credential, client, model, session, or network construction effect.
   */
  public static ProviderSessionIntentReceipt claimProviderSessionIntent(
      DataSource dataSource,
      GraphAttemptManifest manifest,
      OwnerTtyGraphAuthority.Pack010Revision revision,
      GraphProviderIntent firstRequest,
      Instant expiresAt) {
    Instant exactExpiresAt =
        requireDatabaseInstant(expiresAt, "expiresAt");
    PostgresGraphAttemptStore store = openWriter(dataSource);
    PostgresGraphAttemptStore.ProviderSessionClaim claim =
        store.claimProviderSessionIntent(
            manifest,
            revision.name().toLowerCase(java.util.Locale.ROOT),
            firstRequest,
            exactExpiresAt,
            store.freezeAuthorityIdentity());
    return new ProviderSessionIntentReceipt(
        claim.cursor().lastSequence(),
        claim.cursor().headHash(),
        claim.intentHash(),
        claim.expiresAt());
  }

  public record ProviderSessionIntentReceipt(
      int cursorSequence,
      String cursorHeadHash,
      String intentHash,
      Instant expiresAt) {}

  private static Instant requireDatabaseInstant(
      Instant instant, String name) {
    Objects.requireNonNull(instant, name);
    if (!instant.equals(instant.truncatedTo(ChronoUnit.MICROS))) {
      throw new IllegalArgumentException(
          name + " exceeds PostgreSQL microsecond precision");
    }
    return instant;
  }

  public static GraphAttemptCursor recordAttributedFailure(
      DataSource dataSource,
      GraphAttemptManifest manifest,
      GraphAttemptCursor expected,
      GraphProviderAttribution attribution,
      GraphAttributedFailureCode failureCode,
      Instant occurredAt) {
    return openWriter(dataSource)
        .providerFailureAttributed(
            manifest,
            expected,
            attribution,
            failureCode,
            occurredAt);
  }

  /**
   * Test-only synthetic binding for composing an already-durable sequence-14
   * PostgreSQL fixture with the production opaque terminal surface.
   *
   * <p>Real owner TTY issuance is covered by the separate packaged-process
   * matrix. Reflection is deliberately confined to this test bridge and is
   * never present in production bytecode.
   */
  public static SyntheticTerminalBinding syntheticTerminalBinding(
      DataSource dataSource,
      List<GraphAttemptManifest> exactCatalog,
      GraphAttemptManifest manifest,
      GraphAttemptCursor cursor,
      AgentRun parent,
      AgentRun child,
      OwnerTtyGraphAuthority.Pack010Revision revision,
      String egressStep) {
    return syntheticTerminalBinding(
        dataSource,
        exactCatalog,
        manifest,
        cursor,
        parent,
        child,
        revision,
        egressStep,
        Instant.now()
            .truncatedTo(ChronoUnit.MICROS)
            .plus(Duration.ofMinutes(5)));
  }

  public static SyntheticTerminalBinding syntheticTerminalBinding(
      DataSource dataSource,
      List<GraphAttemptManifest> exactCatalog,
      GraphAttemptManifest manifest,
      GraphAttemptCursor cursor,
      AgentRun parent,
      AgentRun child,
      OwnerTtyGraphAuthority.Pack010Revision revision,
      String egressStep,
      Instant expiresAt) {
    try {
      OwnerTtyGraphAuthority ownerAuthority =
          new OwnerTtyGraphAuthority(
              openWriter(dataSource),
              exactCatalog,
              Duration.ofMinutes(5));
      Field coordinatorField =
          OwnerTtyGraphAuthority.class.getDeclaredField("coordinator");
      coordinatorField.setAccessible(true);
      GraphAttemptCoordinator coordinator =
          (GraphAttemptCoordinator) coordinatorField.get(ownerAuthority);

      Field ownerTokenField =
          GraphAttemptCoordinator.class.getDeclaredField("ownerToken");
      ownerTokenField.setAccessible(true);
      Object coordinatorOwner = ownerTokenField.get(coordinator);
      Constructor<GraphAttemptCoordinator.EgressAuthority>
          egressConstructor =
              GraphAttemptCoordinator.EgressAuthority.class
                  .getDeclaredConstructor(
                      Object.class,
                      GraphAttemptManifest.class,
                      GraphAttemptCursor.class,
                      AgentRun.class,
                      AgentRun.class);
      egressConstructor.setAccessible(true);
      GraphAttemptCoordinator.EgressAuthority egress =
          egressConstructor.newInstance(
              coordinatorOwner, manifest, cursor, parent, child);
      Field stepField =
          GraphAttemptCoordinator.EgressAuthority.class
              .getDeclaredField("step");
      stepField.setAccessible(true);
      @SuppressWarnings({"rawtypes", "unchecked"})
      Object step =
          Enum.valueOf(
              (Class<? extends Enum>) stepField.getType(),
              Objects.requireNonNull(egressStep, "egressStep"));
      stepField.set(egress, step);

      Field ownerField =
          OwnerTtyGraphAuthority.class.getDeclaredField("owner");
      ownerField.setAccessible(true);
      Constructor<OwnerTtyGraphAuthority.TerminalCapability>
          capabilityConstructor =
              OwnerTtyGraphAuthority.TerminalCapability.class
                  .getDeclaredConstructor(
                      Object.class,
                      OwnerTtyGraphAuthority.Pack010Revision.class,
                      GraphAttemptManifest.class,
                      Instant.class,
                      GraphAttemptCoordinator.class,
                      GraphAttemptCoordinator.EgressAuthority.class);
      capabilityConstructor.setAccessible(true);
      OwnerTtyGraphAuthority.TerminalCapability capability =
          capabilityConstructor.newInstance(
              ownerField.get(ownerAuthority),
              revision,
              manifest,
              Objects.requireNonNull(expiresAt, "expiresAt"),
              coordinator,
              egress);
      return new SyntheticTerminalBinding(
          ownerAuthority, capability, coordinator, egress);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "synthetic terminal test binding failed", failure);
    }
  }

  public record SyntheticTerminalBinding(
      OwnerTtyGraphAuthority ownerAuthority,
      OwnerTtyGraphAuthority.TerminalCapability capability,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egress) {}

  /**
   * Test-only canonical payload projection for direct restricted-role
   * authority negatives. The production projector remains package-private
   * and shipping code still cannot accept caller-authored JSON.
   */
  public static String preCandidateFailureChildPayload(
      GraphAttemptSnapshot before, AgentRun terminalChild) {
    return PostgresGraphTerminalPayloads.preCandidateFailureChild(
        Objects.requireNonNull(before, "before"),
        Objects.requireNonNull(terminalChild, "terminalChild"));
  }

  /** Test-only source of one valid Candidate row for a forged mix negative. */
  public static String successfulChildPayload(
      GraphAttemptSnapshot before,
      AgentRun terminalChild,
      io.emergeos.contracts.HarnessCandidateEnvelope candidate,
      io.emergeos.contracts.WorkerResultEnvelope workerResult) {
    return PostgresGraphTerminalPayloads.child(
        Objects.requireNonNull(before, "before"),
        Objects.requireNonNull(terminalChild, "terminalChild"),
        Objects.requireNonNull(candidate, "candidate"),
        Objects.requireNonNull(workerResult, "workerResult"));
  }

  /** Test-only canonical failed parent-and-seal payload projection. */
  public static String preCandidateFailureParentPayload(
      GraphAttemptSnapshot before, AgentRun terminalParent) {
    return PostgresGraphTerminalPayloads
        .preCandidateFailureParentAndSeal(
            Objects.requireNonNull(before, "before"),
            Objects.requireNonNull(terminalParent, "terminalParent"));
  }

  /**
   * Test-only raw projector for a self-consistent parent terminal payload that
   * deliberately skips only the aggregate parent/child truth constructor.
   * This lets the restricted-role Acceptance prove that V10 itself rejects a
   * SQL-valid parent whose terminal mapping disagrees with the durable child.
   */
  public static String rawNonSuccessParentPayloadWithoutAggregateValidation(
      GraphAttemptSnapshot before, AgentRun terminalParent) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(terminalParent, "terminalParent");
    try {
      GraphRunSelection selection = before.manifest().parentSelection();
      GraphTerminalBinding parentBinding =
          GraphTerminalBinding.parent(terminalParent, null);
      GraphAttemptEvent parentEvent =
          GraphAttemptEvent.terminal(
              before.cursor(),
              GraphAttemptEventType.PARENT_TERMINAL,
              GraphAttemptPhase.PARENT_TERMINAL,
              selection,
              parentBinding,
              terminalParent.completedAt());
      List<String> attributionHashes =
          before.providerAttributions().stream()
              .map(attribution -> attribution.attributionHash())
              .toList();
      GraphTerminalBinding childBinding =
          before.terminalBindings().getFirst();
      String sealHash =
          GraphTerminalSeal.computeHash(
              before.manifest().attemptId(),
              before.manifest().manifestHash(),
              17,
              parentEvent.currentHeadHash(),
              GraphAttemptOutcome.FAILED,
              GraphBillingStatus.ATTRIBUTED,
              attributionHashes,
              null,
              null,
              childBinding.terminalHash(),
              parentBinding.terminalHash(),
              terminalParent.completedAt());
      GraphAttemptEvent sealEvent =
          GraphAttemptEvent.sealed(
              parentEvent.cursor(before.manifest()),
              sealHash,
              terminalParent.completedAt());
      GraphTerminalSeal seal =
          new GraphTerminalSeal(
              before.manifest().attemptId(),
              before.manifest().manifestHash(),
              17,
              parentEvent.currentHeadHash(),
              sealEvent.currentHeadHash(),
              GraphAttemptOutcome.FAILED,
              GraphBillingStatus.ATTRIBUTED,
              attributionHashes,
              null,
              null,
              childBinding.terminalHash(),
              parentBinding.terminalHash(),
              sealHash,
              terminalParent.completedAt());

      JsonMapper json = JsonMapper.shared();
      ObjectNode root = json.createObjectNode();
      root.put("protocol", "emergeos.graph-terminal.v10");
      root.put("principal_id", before.manifest().principalId());
      root.put("attempt_id", before.manifest().attemptId());
      root.putNull("artifact");
      root.putNull("artifact_version");
      root.set(
          "trace_events",
          payloadNode(
              "traceRows",
              new Class<?>[] {AgentRun.class},
              terminalParent));
      root.set(
          "resource_bindings",
          payloadNode(
              "resourceRows",
              new Class<?>[] {AgentRun.class},
              terminalParent));
      root.set(
          "terminal_run",
          payloadNode(
              "terminalRunRow",
              new Class<?>[] {
                GraphAttemptSnapshot.class,
                GraphRunSelection.class,
                AgentRun.class
              },
              before,
              selection,
              terminalParent));
      root.set(
          "terminal_binding",
          payloadNode(
              "terminalBindingRow",
              new Class<?>[] {
                GraphAttemptSnapshot.class,
                GraphAttemptEvent.class,
                GraphTerminalBinding.class
              },
              before,
              parentEvent,
              parentBinding));
      root.set(
          "parent_event",
          payloadNode(
              "graphEventRow",
              new Class<?>[] {
                GraphAttemptSnapshot.class, GraphAttemptEvent.class
              },
              before,
              parentEvent));
      root.set(
          "seal",
          payloadNode(
              "sealRow",
              new Class<?>[] {
                GraphAttemptSnapshot.class,
                GraphTerminalSeal.class,
                GraphTerminalBinding.class,
                GraphTerminalBinding.class
              },
              before,
              seal,
              childBinding,
              parentBinding));
      root.set(
          "seal_event",
          payloadNode(
              "graphEventRow",
              new Class<?>[] {
                GraphAttemptSnapshot.class, GraphAttemptEvent.class
              },
              before,
              sealEvent));
      return json.writeValueAsString(root);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "raw parent semantic Acceptance projection failed", failure);
    }
  }

  private static JsonNode payloadNode(
      String methodName, Class<?>[] parameterTypes, Object... arguments)
      throws ReflectiveOperationException {
    Method method =
        PostgresGraphTerminalPayloads.class.getDeclaredMethod(
            methodName, parameterTypes);
    method.setAccessible(true);
    return (JsonNode) method.invoke(null, arguments);
  }

  /**
   * Test-only claim-to-semantic expiry probe. The terminal claim is minted
   * before the supplied DB-time deadline. The restricted transaction then
   * freezes authority and is deliberately held at the last Java probe before
   * the single expiry-plus-semantic SQL statement until the deadline passes.
   */
  public static String completeChildAfterExpiry(
      DataSource prefixDataSource,
      DataSource terminalDataSource,
      SyntheticTerminalBinding binding,
      OwnerTtyGraphAuthority.Pack010Revision revision,
      GraphAttemptManifest manifest,
      String payload,
      Instant expiresAt) {
    PostgresGraphRuntimeWriters.open(
        prefixDataSource, terminalDataSource);
    GraphAttemptManifest bound =
        binding.ownerAuthority().bindTerminal(
            binding.capability(),
            binding.coordinator(),
            binding.egress(),
            revision);
    if (!bound.equals(manifest)) {
      throw new IllegalStateException("synthetic manifest mismatch");
    }
    OwnerTtyGraphAuthority.ChildTerminalClaim claim =
        binding.ownerAuthority().claimChildTerminal(
            binding.capability(),
            binding.coordinator(),
            binding.egress());
    Instant claimExpiry =
        binding.ownerAuthority().consumeChildTerminalClaim(
            claim, manifest);
    PostgresGraphTerminalExecutor terminal =
        new PostgresGraphTerminalExecutor(
            terminalDataSource,
            point -> {
              if (point
                  != PostgresGraphTerminalExecutor.ProbePoint
                      .AFTER_AUTHORITY_RECHECK) {
                return;
              }
              long remaining =
                  Math.max(
                      0L,
                      Duration.between(
                              Instant.now(), expiresAt)
                          .toMillis());
              try {
                Thread.sleep(remaining + 250L);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "synthetic expiry wait interrupted", interrupted);
              }
            });
    return completeChildRaw(
        terminal, manifest, claimExpiry, payload);
  }

  public static List<String> probes(String tx) {
    return switch (Objects.requireNonNull(tx, "tx")) {
      case "TX_A" -> TX_A_PROBES;
      case "TX_B" -> TX_B_PROBES;
      case "TX_C" -> TX_C_PROBES;
      default -> throw new IllegalArgumentException("unknown Pack010 transaction");
    };
  }

  private static String completeChildRaw(
      PostgresGraphTerminalExecutor executor,
      GraphAttemptManifest manifest,
      Instant expiresAt,
      String payload) {
    try {
      var method =
          PostgresGraphTerminalExecutor.class.getDeclaredMethod(
              "completeChild",
              GraphAttemptManifest.class,
              Instant.class,
              String.class);
      method.setAccessible(true);
      return (String)
          method.invoke(executor, manifest, expiresAt, payload);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }

  public static PostgresGraphAttemptStore store(
      DataSource dataSource,
      String tx,
      String probeName,
      Consumer<String> reached) {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(probeName, "probeName");
    Objects.requireNonNull(reached, "reached");
    if ("NONE".equals(probeName)) {
      return openWriter(dataSource);
    }
    if (!probes(tx).contains(probeName)) {
      throw new IllegalArgumentException("probe is outside the reviewed transaction");
    }
    Target target = Target.parse(probeName);
    Map<PostgresGraphAttemptStore.ProbePoint, Integer> occurrences =
        new EnumMap<>(PostgresGraphAttemptStore.ProbePoint.class);
    AtomicBoolean delivered = new AtomicBoolean();
    return new PostgresGraphAttemptStore(
        dataSource,
        point -> {
          int occurrence =
              occurrences.merge(point, 1, Integer::sum);
          if (point.name().equals(target.point())
              && occurrence == target.occurrence()
              && delivered.compareAndSet(false, true)) {
            reached.accept(probeName);
          }
        });
  }

  private record Target(String point, int occurrence) {

    private static Target parse(String value) {
      int separator = value.indexOf('@');
      if (separator < 0) {
        return new Target(value, 1);
      }
      if (separator == 0
          || separator == value.length() - 1
          || value.indexOf('@', separator + 1) >= 0) {
        throw new IllegalArgumentException("invalid Pack010 probe target");
      }
      int occurrence =
          Integer.parseInt(value.substring(separator + 1));
      if (occurrence < 1) {
        throw new IllegalArgumentException("invalid Pack010 probe occurrence");
      }
      return new Target(value.substring(0, separator), occurrence);
    }
  }
}
