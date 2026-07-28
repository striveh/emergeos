package io.emergeos.adapters.inmemory;

import io.emergeos.core.domain.ArtifactVersion;
import io.emergeos.core.domain.ContentHashes;
import io.emergeos.core.domain.EvidenceEvent;
import io.emergeos.core.domain.WorkingSelf;
import io.emergeos.core.port.ArtifactGenerator;
import java.time.Instant;
import java.util.List;

public final class TemplateArtifactGenerator implements ArtifactGenerator {

  @Override
  public ArtifactVersion generate(
      String artifactId, EvidenceEvent evidence, WorkingSelf workingSelf, Instant createdAt) {
    var content =
        """
        # 显现草稿

        ## 思想种子

        %s

        ## 可继续展开

        - 核心判断是什么？
        - 哪些事实、经历或反例能够支撑或修正它？
        - 它最适合显现成什么作品或行动？

        > 当前由确定性 Template Generator 生成，尚未调用真实模型。
        """
            .formatted(evidence.content())
            .strip();

    return new ArtifactVersion(
        artifactId,
        1,
        content,
        ContentHashes.sha256(content),
        List.of(evidence.id()),
        workingSelf.snapshotId(),
        "template-generator-v1",
        null,
        createdAt);
  }
}

