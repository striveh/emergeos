package io.emergeos.adapters.inmemory;

import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.Receipt;
import io.emergeos.core.domain.ReflectionCandidate;
import io.emergeos.core.port.ReflectionProposer;
import java.time.Instant;
import java.util.List;

public final class DefaultReflectionProposer implements ReflectionProposer {

  @Override
  public ReflectionCandidate propose(
      String candidateId,
      String principalId,
      ArtifactVersion generated,
      ArtifactVersion approved,
      Receipt receipt,
      String proposedByRun,
      Instant now) {
    boolean edited = !generated.contentHash().equals(approved.contentHash());
    var status = edited ? "PENDING" : "NO_CHANGE";
    var claim =
        edited
            ? "一次编辑表明用户更偏好批准版本的表达；证据不足，需更多样本或用户确认。"
            : "本次未观察到用户编辑信号，不提出稳定表达偏好。";
    return new ReflectionCandidate(
        candidateId,
        principalId,
        "STYLE_RULE",
        claim,
        List.of(generated.artifactId() + ":v" + generated.version(),
            approved.artifactId() + ":v" + approved.version(),
            receipt.receiptId()),
        edited ? 0.25 : 0.0,
        "writing",
        status,
        proposedByRun,
        now);
  }
}

