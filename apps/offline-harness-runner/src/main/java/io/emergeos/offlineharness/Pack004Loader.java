package io.emergeos.offlineharness;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.emergeos.contracts.DataClass;
import io.emergeos.contracts.RiskLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the one checked-in Pack 004 asset accepted by the offline Harness
 * comparison boundary.
 *
 * <p>This class intentionally has no configurable asset path. It only proves
 * that the frozen, synthetic comparison input is safe to hand to a later
 * offline runner; it does not execute either experiment arm.
 */
final class Pack004Loader {

  static final String EXPECTED_RAW_SHA256 =
      "acbafceb05832330165cf065969311fd82d7adba1fa4916f3ed181ebfe5acf44";
  static final int MAX_PACK_BYTES = 64 * 1024;

  private static final Path FIXED_PACK_RELATIVE_PATH =
      Path.of(
          "evals",
          "task-packs",
          "synthetic",
          "004-offline-reference-verifier-comparison.json");

  private static final ObjectMapper STRICT_JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

  LoadedPack load(Path repositoryRoot) {
    byte[] bytes = readFixedPack(repositoryRoot);
    Pack004 pack = parse(bytes);
    verifySemantics(pack);
    String observedHash = sha256(bytes);
    if (!EXPECTED_RAW_SHA256.equals(observedHash)) {
      throw rejected("PACK_RAW_SHA256_MISMATCH");
    }
    return new LoadedPack(observedHash, pack);
  }

  private static byte[] readFixedPack(Path suppliedRoot) {
    if (suppliedRoot == null) {
      throw rejected("REPO_ROOT_INVALID");
    }
    Path repositoryRoot = suppliedRoot.toAbsolutePath().normalize();
    try {
      if (Files.isSymbolicLink(repositoryRoot)
          || !Files.isDirectory(
              repositoryRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw rejected("REPO_ROOT_INVALID");
      }

      Path current = repositoryRoot;
      int segmentIndex = 0;
      int segmentCount = FIXED_PACK_RELATIVE_PATH.getNameCount();
      for (Path segment : FIXED_PACK_RELATIVE_PATH) {
        current = current.resolve(segment);
        segmentIndex++;
        if (Files.isSymbolicLink(current)) {
          throw rejected("PACK_PATH_SYMLINK_REJECTED");
        }
        if (segmentIndex < segmentCount
            && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw rejected("PACK_PATH_INVALID");
        }
      }

      BasicFileAttributes before =
          Files.readAttributes(
              current,
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()) {
        throw rejected("PACK_PATH_INVALID");
      }
      if (before.size() < 1 || before.size() > MAX_PACK_BYTES) {
        throw rejected("PACK_SIZE_INVALID");
      }

      byte[] bytes = readBoundedNoFollow(current);
      BasicFileAttributes after =
          Files.readAttributes(
              current,
              BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || before.size() != bytes.length
          || after.size() != bytes.length
          || fileIdentityChanged(before, after)) {
        throw rejected("PACK_CHANGED_DURING_READ");
      }
      return bytes;
    } catch (Rejected failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw rejected("PACK_READ_FAILED");
    }
  }

  private static byte[] readBoundedNoFollow(Path pack) throws IOException {
    Set<OpenOption> options =
        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel =
            Files.newByteChannel(pack, options);
        ByteArrayOutputStream output =
            new ByteArrayOutputStream(MAX_PACK_BYTES)) {
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      while (true) {
        int read = channel.read(buffer);
        if (read < 0) {
          break;
        }
        if (read == 0) {
          continue;
        }
        if (output.size() + read > MAX_PACK_BYTES) {
          throw rejected("PACK_SIZE_INVALID");
        }
        output.write(buffer.array(), 0, read);
        buffer.clear();
      }
      if (output.size() < 1) {
        throw rejected("PACK_SIZE_INVALID");
      }
      return output.toByteArray();
    }
  }

  private static boolean fileIdentityChanged(
      BasicFileAttributes before, BasicFileAttributes after) {
    Object beforeKey = before.fileKey();
    Object afterKey = after.fileKey();
    return beforeKey != null
        && afterKey != null
        && !beforeKey.equals(afterKey);
  }

  private static Pack004 parse(byte[] bytes) {
    try {
      return STRICT_JSON.readValue(bytes, Pack004.class);
    } catch (IOException | RuntimeException failure) {
      throw rejected("PACK_JSON_INVALID");
    }
  }

  private static void verifySemantics(Pack004 pack) {
    if (pack == null || pack.harnessComparison() == null) {
      throw rejected("PACK_SEMANTICS_MISMATCH");
    }
    HarnessComparison comparison = pack.harnessComparison();
    Frozen frozen = comparison.frozen();
    Provenance provenance = comparison.provenance();

    require(
        "0.3".equals(pack.schemaVersion())
            && "synthetic-offline-reference-verifier-comparison-004"
                .equals(pack.taskId())
            && "离线比较 schema-only 与 reference-grounding Verifier"
                .equals(pack.title())
            && "synthetic-persona-reference-verifier"
                .equals(pack.principalRef())
            && pack.risk() == RiskLevel.REVERSIBLE
            && "ARTICLE_DRAFT".equals(pack.expectedArtifactKind()));

    require(
        pack.seed() != null
            && "TEXT".equals(pack.seed().sourceType())
            && "synthetic://eval/s4-o1-reference-verifier-004"
                .equals(pack.seed().sourceRef())
            && pack.seed().dataClass() == DataClass.PERSONAL
            && expectedSyntheticContent().equals(pack.seed().content()));

    require(
        List.of(
                "H0 与 H1 在 Verifier 前必须得到完全相同的 deterministic Fake candidate",
                "除 experiment arm 与 verifier version 外，两臂的 Task、模型、工具、预算、时间和组件版本必须相同",
                "H0 只能存在于隔离的 offline comparison runner，不能成为产品 API、CLI 或运行时配置",
                "所有执行只读取本 pack 内的 literal synthetic Capture")
            .equals(pack.requiredConstraints()));
    require(
        List.of(
                "访问真实模型、互联网或任何网络端点",
                "访问真实用户数据、账号、Connector 或社交平台",
                "写入 PostgreSQL product truth 或执行任何外部副作用",
                "把 deterministic adversarial fixture 的结果宣称为真实模型质量")
            .equals(pack.forbiddenActions()));
    require(
        List.of(
                "四个 frozen cases、两个 arms、三个 repetitions 形成 24 个 runs 与 12 组 paired candidates",
                "H0 接受 12 个结构合法 candidates，其中 9 个是 reference fault",
                "H1 接受 3 个 grounded candidates，并以稳定 failure code 拒绝 9 个 reference faults",
                "model、network、Connector 与真实数据访问计数全部为 0",
                "suite、arm、case IDs 唯一；execution base IDs 在每个 arm 内唯一，paired arms 为候选对照而有意复用同一 base ID")
            .equals(pack.acceptanceChecks()));
    require(
        List.of(
                "这组对照是否只改变了 reference-grounding Verifier，而没有混入 Planner、Critic 或 Subagent？",
                "是否清楚本结果只验证引用落地边界，不能证明内容语义真实或真实模型质量？")
            .equals(pack.humanReviewQuestions()));
    require(
        List.of(
                "grounded candidate 先执行 capture.read，再精确引用唯一 Capture",
                "claim-without-tool candidate 未执行读取却直接声称引用 Capture",
                "omitted-claim candidate 已读取 Capture，但 structured final 漏掉引用",
                "extra-claim candidate 已读取 Capture，但 structured final 加入 forged ref")
            .equals(pack.faultPlan()));

    require(
        "offline-reference-grounding-verifier-v1"
                .equals(comparison.suiteId())
            && "reference-grounding-verifier"
                .equals(comparison.variable())
            && comparison.repetitions() == 3
            && comparison.pairedExecutionIdsSharedAcrossArms());

    require(
        provenance != null
            && "LITERAL_CHECKED_IN_SYNTHETIC".equals(provenance.kind())
            && !provenance.containsRealUserData()
            && !provenance.containsRealAccount()
            && !provenance.networkAllowed()
            && !provenance.realModelAllowed()
            && !provenance.connectorAllowed());

    require(
        frozen != null
            && "synthetic-persona-reference-verifier"
                .equals(frozen.principalId())
            && "capture-s4-o1-reference-004".equals(frozen.captureId())
            && "s4-o1-reference-004".equals(frozen.clientNonce())
            && "TEXT".equals(frozen.sourceType())
            && "synthetic://eval/s4-o1-reference-verifier-004"
                .equals(frozen.sourceRef())
            && frozen.dataClass() == DataClass.PERSONAL
            && expectedSyntheticContent().equals(frozen.content())
            && "整理成一段保留来源边界的虚构测试短文"
                .equals(frozen.intent())
            && "2026-07-30T00:00:00Z".equals(frozen.frozenTime())
            && frozen.kernelLatencyMs() == 7
            && frozen.maxModelSteps() == 2
            && frozen.maxToolCalls() == 1
            && frozen.taskDeadlineMs() == 5000
            && "scripted-fake-draft-v1".equals(frozen.model())
            && "framework-free-agent-kernel-v1".equals(frozen.harness())
            && "agent-draft-service-v1".equals(frozen.agent())
            && "agent-draft-policy-v1".equals(frozen.policy())
            && "stage2-s4-o1".equals(frozen.state())
            && "ref-only-v1".equals(frozen.contextPolicy())
            && "agent-tools-v1".equals(frozen.toolRegistry())
            && "literal-reference-candidate-fixture-v1"
                .equals(frozen.candidateGeneratorVersion())
            && "emergeos-length-prefixed-sha256-v1"
                .equals(frozen.traceIntegrity()));

    List<Arm> expectedArms =
        List.of(
            new Arm("h0-schema-only", "schema-only-eval-v1"),
            new Arm(
                "h1-reference-grounding",
                "agent-draft-verifier-v1"));
    require(expectedArms.equals(comparison.arms()));

    ExpectedOutcome succeeded =
        new ExpectedOutcome("SUCCEEDED", null);
    ExpectedOutcome missingRequiredEvidence =
        new ExpectedOutcome("FAILED", "MISSING_REQUIRED_EVIDENCE");
    ExpectedOutcome invalidEvidenceClaim =
        new ExpectedOutcome("FAILED", "INVALID_EVIDENCE_CLAIM");
    List<CaseDefinition> expectedCases =
        List.of(
            new CaseDefinition(
                "grounded",
                "s4-o1-grounded",
                "GROUNDED",
                "NONE",
                new ExpectedByArm(succeeded, succeeded)),
            new CaseDefinition(
                "claim-without-tool",
                "s4-o1-claim-without-tool",
                "CLAIM_WITHOUT_TOOL",
                "CLAIM_WITHOUT_TOOL",
                new ExpectedByArm(
                    succeeded, missingRequiredEvidence)),
            new CaseDefinition(
                "omitted-claim",
                "s4-o1-omitted-claim",
                "OMITTED_EVIDENCE_REF",
                "OMITTED_EVIDENCE_REF",
                new ExpectedByArm(
                    succeeded, invalidEvidenceClaim)),
            new CaseDefinition(
                "extra-claim",
                "s4-o1-extra-claim",
                "EXTRA_EVIDENCE_REF",
                "EXTRA_EVIDENCE_REF",
                new ExpectedByArm(
                    succeeded, invalidEvidenceClaim)));
    require(expectedCases.equals(comparison.cases()));
    verifySharedExecutionIds(comparison);

    ExpectedTotals totals = comparison.expectedTotals();
    require(
        totals != null
            && totals.pairedCandidates() == 12
            && totals.runs() == 24
            && totals.h0Accepted() == 12
            && totals.h0FaultAcceptances() == 9
            && totals.h1Accepted() == 3
            && totals.h1FaultRejections() == 9
            && totals.pairedCandidates()
                == comparison.cases().size()
                    * comparison.repetitions()
            && totals.runs()
                == totals.pairedCandidates()
                    * comparison.arms().size());
  }

  private static void verifySharedExecutionIds(
      HarnessComparison comparison) {
    Set<String> caseIds = new HashSet<>();
    Set<String> armIds = new HashSet<>();
    Set<String> executionBaseIds = new HashSet<>();
    for (Arm arm : comparison.arms()) {
      require(armIds.add(arm.id()));
    }
    for (CaseDefinition testCase : comparison.cases()) {
      require(caseIds.add(testCase.id()));
      for (int repetition = 1;
          repetition <= comparison.repetitions();
          repetition++) {
        require(
            executionBaseIds.add(
                testCase.executionIdPrefix()
                    + "-r"
                    + repetition));
      }
    }
    require(executionBaseIds.size() == 12);
  }

  private static String expectedSyntheticContent() {
    return "虚构人物小澜记录：模型提出的引用必须来自本次实际执行的读取工具。"
        + "这是完全虚构且只用于离线验证的测试素材。";
  }

  private static void require(boolean accepted) {
    if (!accepted) {
      throw rejected("PACK_SEMANTICS_MISMATCH");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 unavailable", impossible);
    }
  }

  private static Rejected rejected(String code) {
    return new Rejected(code);
  }

  record LoadedPack(String rawSha256, Pack004 pack) {
    LoadedPack {
      Objects.requireNonNull(rawSha256, "rawSha256");
      Objects.requireNonNull(pack, "pack");
    }
  }

  record Pack004(
      String schemaVersion,
      String taskId,
      String title,
      String principalRef,
      Seed seed,
      List<String> requiredConstraints,
      List<String> forbiddenActions,
      List<String> acceptanceChecks,
      List<String> humanReviewQuestions,
      RiskLevel risk,
      String expectedArtifactKind,
      List<String> faultPlan,
      HarnessComparison harnessComparison) {
    Pack004 {
      Objects.requireNonNull(schemaVersion, "schemaVersion");
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(principalRef, "principalRef");
      Objects.requireNonNull(seed, "seed");
      requiredConstraints = immutable(requiredConstraints);
      forbiddenActions = immutable(forbiddenActions);
      acceptanceChecks = immutable(acceptanceChecks);
      humanReviewQuestions = immutable(humanReviewQuestions);
      Objects.requireNonNull(risk, "risk");
      Objects.requireNonNull(
          expectedArtifactKind, "expectedArtifactKind");
      faultPlan = immutable(faultPlan);
      Objects.requireNonNull(
          harnessComparison, "harnessComparison");
    }
  }

  record Seed(
      String sourceType,
      String sourceRef,
      DataClass dataClass,
      String content) {
    Seed {
      Objects.requireNonNull(sourceType, "sourceType");
      Objects.requireNonNull(sourceRef, "sourceRef");
      Objects.requireNonNull(dataClass, "dataClass");
      Objects.requireNonNull(content, "content");
    }
  }

  record HarnessComparison(
      String suiteId,
      String variable,
      int repetitions,
      boolean pairedExecutionIdsSharedAcrossArms,
      Provenance provenance,
      Frozen frozen,
      List<Arm> arms,
      List<CaseDefinition> cases,
      ExpectedTotals expectedTotals) {
    HarnessComparison {
      Objects.requireNonNull(suiteId, "suiteId");
      Objects.requireNonNull(variable, "variable");
      Objects.requireNonNull(provenance, "provenance");
      Objects.requireNonNull(frozen, "frozen");
      arms = immutable(arms);
      cases = immutable(cases);
      Objects.requireNonNull(expectedTotals, "expectedTotals");
    }
  }

  record Provenance(
      String kind,
      boolean containsRealUserData,
      boolean containsRealAccount,
      boolean networkAllowed,
      boolean realModelAllowed,
      boolean connectorAllowed) {
    Provenance {
      Objects.requireNonNull(kind, "kind");
    }
  }

  record Frozen(
      String principalId,
      String captureId,
      String clientNonce,
      String sourceType,
      String sourceRef,
      DataClass dataClass,
      String content,
      String intent,
      String frozenTime,
      int kernelLatencyMs,
      int maxModelSteps,
      int maxToolCalls,
      int taskDeadlineMs,
      String model,
      String harness,
      String agent,
      String policy,
      String state,
      String contextPolicy,
      String toolRegistry,
      String candidateGeneratorVersion,
      String traceIntegrity) {
    Frozen {
      Objects.requireNonNull(principalId, "principalId");
      Objects.requireNonNull(captureId, "captureId");
      Objects.requireNonNull(clientNonce, "clientNonce");
      Objects.requireNonNull(sourceType, "sourceType");
      Objects.requireNonNull(sourceRef, "sourceRef");
      Objects.requireNonNull(dataClass, "dataClass");
      Objects.requireNonNull(content, "content");
      Objects.requireNonNull(intent, "intent");
      Objects.requireNonNull(frozenTime, "frozenTime");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(harness, "harness");
      Objects.requireNonNull(agent, "agent");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(contextPolicy, "contextPolicy");
      Objects.requireNonNull(toolRegistry, "toolRegistry");
      Objects.requireNonNull(
          candidateGeneratorVersion, "candidateGeneratorVersion");
      Objects.requireNonNull(traceIntegrity, "traceIntegrity");
    }
  }

  record Arm(String id, String verifier) {
    Arm {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(verifier, "verifier");
    }
  }

  record CaseDefinition(
      String id,
      String executionIdPrefix,
      String candidateMode,
      String faultClass,
      ExpectedByArm expectedByArm) {
    CaseDefinition {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(
          executionIdPrefix, "executionIdPrefix");
      Objects.requireNonNull(candidateMode, "candidateMode");
      Objects.requireNonNull(faultClass, "faultClass");
      Objects.requireNonNull(expectedByArm, "expectedByArm");
    }
  }

  record ExpectedByArm(
      @JsonProperty("h0-schema-only")
          ExpectedOutcome h0SchemaOnly,
      @JsonProperty("h1-reference-grounding")
          ExpectedOutcome h1ReferenceGrounding) {
    ExpectedByArm {
      Objects.requireNonNull(h0SchemaOnly, "h0SchemaOnly");
      Objects.requireNonNull(
          h1ReferenceGrounding, "h1ReferenceGrounding");
    }
  }

  record ExpectedOutcome(String status, String failureReason) {
    ExpectedOutcome {
      Objects.requireNonNull(status, "status");
    }
  }

  record ExpectedTotals(
      int pairedCandidates,
      int runs,
      int h0Accepted,
      int h0FaultAcceptances,
      int h1Accepted,
      int h1FaultRejections) {}

  static final class Rejected extends RuntimeException {
    private final String code;

    private Rejected(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  private static <T> List<T> immutable(List<T> values) {
    return List.copyOf(Objects.requireNonNull(values, "values"));
  }
}
