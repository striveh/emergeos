package io.emergeos.grapheval;

import io.emergeos.adapters.agentloop.AgentModel;
import io.emergeos.adapters.openai.OpenAiResponsesModel;
import io.emergeos.adapters.openai.ReviewedOpenAiClient;
import io.emergeos.adapters.postgres.OwnerTtyGraphAuthority;
import io.emergeos.adapters.postgres.Pack010GraphTerminalStoreBridge;
import io.emergeos.contracts.HarnessCandidateEnvelope;
import io.emergeos.contracts.WorkerResultEnvelope;
import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.application.ModelBoundReadOnlyWorkerExecutionProfile;
import io.emergeos.core.domain.AgentRun;
import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphAttemptSnapshot;
import io.emergeos.core.domain.GraphProviderAttribution;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

/** Test-only reflection bridge; never present in shipping bytecode. */
final class Pack010TerminalOutcomeTestBridge {

  private Pack010TerminalOutcomeTestBridge() {}

  static Pack010PostgresRuntimeComposition.Pack010ChildTerminalCommand
      command(
          Pack010PostgresRuntimeComposition runtime,
          Pack010GraphTerminalStoreBridge.SyntheticTerminalBinding binding,
          GraphAttemptManifest manifest,
          GraphAttemptSnapshot snapshot,
          OwnerTtyGraphAuthority.Pack010Revision revision,
          AgentRun terminalChild,
          HarnessCandidateEnvelope candidate,
          WorkerResultEnvelope workerResult) {
    return runtime.prepareChild(
        syntheticOutcome(
            binding, manifest, snapshot, revision, candidate),
        candidate,
        terminalChild,
        workerResult);
  }

  static Pack010ProviderSessionComposer.Pack010AttributedModelOutcome
      syntheticOutcome(
          Pack010GraphTerminalStoreBridge.SyntheticTerminalBinding binding,
          GraphAttemptManifest manifest,
          GraphAttemptSnapshot snapshot,
          OwnerTtyGraphAuthority.Pack010Revision revision,
          HarnessCandidateEnvelope candidate) {
    try {
      List<GraphProviderAttribution> attributions =
          snapshot.providerAttributions();
      GraphProviderAttribution second = attributions.get(1);
      AgentModel.ModelStep step =
          new AgentModel.ModelStep(
              new AgentModel.FinalDraft(
                  candidate.content(), candidate.evidenceRefs()),
              second.modelResolved(),
              new AgentModel.ModelUsage(
                  second.observedCostUsd(), second.totalTokens()));
      Constructor<
              Pack010ProviderSessionComposer
                  .Pack010AttributedModelOutcome>
          constructor =
              Pack010ProviderSessionComposer
                  .Pack010AttributedModelOutcome.class
                  .getDeclaredConstructor(
                      Object.class,
                      OwnerTtyGraphAuthority.Pack010Revision.class,
                      GraphAttemptManifest.class,
                      io.emergeos.core.application
                          .GraphAttemptCoordinator.class,
                      io.emergeos.core.application
                          .GraphAttemptCoordinator.EgressAuthority.class,
                      Pack010ProviderCredentialBroker.CredentialLease.class,
                      Clock.class,
                      AgentModel.ModelStep.class,
                      GraphProviderAttribution.class,
                      List.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          new Object(),
          revision,
          manifest,
              binding.coordinator(),
              binding.egress(),
              Pack010ProviderSessionEffectOrderingTest.syntheticLease(
                  revision, binding.coordinator(), binding.egress()),
              Clock.fixed(
                  manifest.startedAt().plusMillis(9),
                  ZoneOffset.UTC),
              step,
          second,
          attributions);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "synthetic attributed outcome failed", failure);
    }
  }

  static Pack010ProviderSessionComposer.Pack010AttributedModelOutcome
      syntheticFailedOutcome(
          Pack010GraphTerminalStoreBridge.SyntheticTerminalBinding binding,
          GraphAttemptManifest manifest,
          GraphAttemptSnapshot snapshot,
          OwnerTtyGraphAuthority.Pack010Revision revision,
          String failureReason) {
    try {
      List<GraphProviderAttribution> attributions =
          snapshot.providerAttributions();
      GraphProviderAttribution second = attributions.get(1);
      AgentModel.ModelStep step =
          new AgentModel.ModelStep(
              new AgentModel.Failed(failureReason),
              second.modelResolved(),
              new AgentModel.ModelUsage(
                  second.observedCostUsd(), second.totalTokens()));
      Constructor<
              Pack010ProviderSessionComposer
                  .Pack010AttributedModelOutcome>
          constructor =
              Pack010ProviderSessionComposer
                  .Pack010AttributedModelOutcome.class
                  .getDeclaredConstructor(
                      Object.class,
                      OwnerTtyGraphAuthority.Pack010Revision.class,
                      GraphAttemptManifest.class,
                      io.emergeos.core.application
                          .GraphAttemptCoordinator.class,
                      io.emergeos.core.application
                          .GraphAttemptCoordinator.EgressAuthority.class,
                      Pack010ProviderCredentialBroker.CredentialLease.class,
                      Clock.class,
                      AgentModel.ModelStep.class,
                      GraphProviderAttribution.class,
                      List.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          new Object(),
          revision,
          manifest,
          binding.coordinator(),
          binding.egress(),
          Pack010ProviderSessionEffectOrderingTest.syntheticLease(
              revision, binding.coordinator(), binding.egress()),
          Clock.fixed(
              manifest.startedAt().plusMillis(9), ZoneOffset.UTC),
          step,
          second,
          attributions);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "synthetic attributed failed outcome construction failed",
          failure);
    }
  }

  static Pack010ProviderSessionComposer.ProviderSession actualSession(
      ReviewedOpenAiClient client,
      Pack010ProviderCredentialBroker.CredentialLease lease,
      GraphAttemptCoordinator coordinator,
      GraphAttemptCoordinator.EgressAuthority egress,
      Clock clock,
      GraphAttemptManifest manifest,
      OwnerTtyGraphAuthority.Pack010Revision revision) {
    try {
      int repetition = revision.ordinal() + 1;
      ModelBoundReadOnlyWorkerExecutionProfile profile =
          Pack010GraphEvalCatalog.workerProfile(repetition);
      lease.claim(coordinator, egress, clock);
      Class<?> ledgerType =
          Class.forName(
              "io.emergeos.grapheval."
                  + "Pack010ProviderSessionComposer$OutcomeLedger");
      Constructor<?> ledgerConstructor =
          ledgerType.getDeclaredConstructor();
      ledgerConstructor.setAccessible(true);
      Object ledger = ledgerConstructor.newInstance();
      Method observerFactory =
          Pack010ProviderSessionComposer.class.getDeclaredMethod(
              "durableAttributionObserver",
              ModelBoundReadOnlyWorkerExecutionProfile.class,
              int.class,
              GraphAttemptCoordinator.class,
              GraphAttemptCoordinator.EgressAuthority.class,
              Clock.class,
              ledgerType);
      observerFactory.setAccessible(true);
      var attributionObserver =
          (OpenAiResponsesModel.ExactProviderAttributionObserver)
              observerFactory.invoke(
                  null,
                  profile,
                  repetition,
                  coordinator,
                  egress,
                  clock,
                  ledger);
      AgentModel.Session modelSession =
          OpenAiResponsesModel.withExactResponseAttribution(
                  profile,
                  client,
                  Pack010ProviderSessionComposer.durableIntentObserver(
                      profile,
                      repetition,
                      lease,
                      coordinator,
                      egress,
                      clock),
                  attributionObserver)
              .open(Pack010GraphEvalCatalog.childTask(repetition));
      Constructor<Pack010ProviderSessionComposer.ProviderSession>
          sessionConstructor =
              Pack010ProviderSessionComposer.ProviderSession.class
                  .getDeclaredConstructor(
                      AgentModel.Session.class,
                      ReviewedOpenAiClient.class,
                      OwnerTtyGraphAuthority.Pack010Revision.class,
                      Pack010ProviderCredentialBroker.CredentialLease.class,
                      GraphAttemptCoordinator.class,
                      GraphAttemptCoordinator.EgressAuthority.class,
                      Clock.class,
                      GraphAttemptManifest.class,
                      ledgerType);
      sessionConstructor.setAccessible(true);
      return sessionConstructor.newInstance(
          modelSession,
          client,
          revision,
          lease,
          coordinator,
          egress,
          clock,
          manifest,
          ledger);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(
          "actual attributed session construction failed", failure);
    }
  }
}
