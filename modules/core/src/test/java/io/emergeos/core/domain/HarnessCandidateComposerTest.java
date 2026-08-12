package io.emergeos.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.emergeos.core.application.HarnessCandidateComposer;
import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessCandidateComposerTest {

  @Test
  void composesTheExactCandidateFromSuccessfulTerminalChildTruth() {
    GraphAttemptSnapshot snapshot =
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(
            1, true);

    assertEquals(
        snapshot.candidate(),
        HarnessCandidateComposer.compose(
            snapshot.manifest(),
            snapshot.childRun(),
            new AgentDraftProposal(
                snapshot.candidate().content(),
                snapshot.candidate().evidenceRefs()),
            snapshot.providerAttributions()));
  }

  @Test
  void composesTheCandidateEvenWhenH1RejectsTheStructuredFinal() {
    GraphAttemptSnapshot snapshot =
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(
            2, false);

    assertEquals(
        snapshot.candidate(),
        HarnessCandidateComposer.compose(
            snapshot.manifest(),
            snapshot.childRun(),
            new AgentDraftProposal(
                snapshot.candidate().content(),
                snapshot.candidate().evidenceRefs()),
            snapshot.providerAttributions()));
  }

  @Test
  void rejectsPreCandidateAndNonTerminalOrIncompleteAttributionTruth() {
    GraphAttemptSnapshot snapshot =
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(
            1, true);
    AgentDraftProposal proposal =
        new AgentDraftProposal(
            snapshot.candidate().content(),
            snapshot.candidate().evidenceRefs());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCandidateComposer.compose(
                snapshot.manifest(),
                AgentRun.running(
                    snapshot.childRun().runId(),
                    snapshot.childRun().principalId(),
                    snapshot.childRun().task(),
                    snapshot.childRun().startedAt()),
                proposal,
                snapshot.providerAttributions()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCandidateComposer.compose(
                snapshot.manifest(),
                snapshot.childRun(),
                proposal,
                List.of(snapshot.providerAttributions().getFirst())));
  }

  @Test
  void rejectsContentOrProviderIdentityDrift() {
    GraphAttemptSnapshot snapshot =
        GraphAttemptTerminalProtocolTest.reportEligibleSnapshot(
            1, true);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCandidateComposer.compose(
                snapshot.manifest(),
                snapshot.childRun(),
                new AgentDraftProposal(
                    snapshot.candidate().content() + " drift",
                    snapshot.candidate().evidenceRefs()),
                snapshot.providerAttributions()));

    GraphProviderAttribution source =
        snapshot.providerAttributions().get(1);
    GraphProviderAttribution drifted =
        GraphProviderAttribution.create(
            source.requestOrdinal(),
            source.requestHash(),
            source.responseHash(),
            "OTHER_PROVIDER",
            source.modelRequested(),
            source.modelResolved(),
            source.pricing(),
            source.inputTokens(),
            source.cachedInputTokens(),
            source.outputTokens(),
            source.reasoningOutputTokens(),
            source.totalTokens(),
            source.observedCostUsd());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessCandidateComposer.compose(
                snapshot.manifest(),
                snapshot.childRun(),
                new AgentDraftProposal(
                    snapshot.candidate().content(),
                    snapshot.candidate().evidenceRefs()),
                List.of(
                    snapshot.providerAttributions().getFirst(),
                    drifted)));
  }
}
