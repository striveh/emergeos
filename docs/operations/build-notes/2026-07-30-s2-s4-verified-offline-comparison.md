# Build Note · 2026-07-30 · Stage 2 S4 可重放 Offline Comparison

- Change class：`R`
- Canonical encoding commit：`693a3c7`
- Product-runtime isolation commits：`c1cb73c`、`da54365`
- Runner / report / verifier commit：`8fa96cd`
- 状态：in-memory deterministic comparison 与独立 replay verification 完成；
  atomic report store、跨新 JVM 持久核验尚未完成

## 这次真正完成了什么

固定的 Task Pack 004 现在不再只是 expected matrix。测试进程真实调用
package-private offline Runner，得到以下受控执行：

```text
4 个 frozen cases × 3 repetitions
  → 12 次 immutable shared candidate generation
  → 每个 candidate 由 H0、H1 各消费一次
  → 24 次 VerifierEvaluation
  → 完整 Pair / Evaluation / Summary / OwnedEffects report
  → 独立 replay verifier
  → VERIFIED_PASSED
```

Observed result：

| 指标 | 结果 |
|---|---:|
| shared candidate generations | 12 |
| verifier evaluations | 24 |
| H0 accepted | 12 |
| H0 accepted reference faults | 9 |
| H1 accepted grounded candidates | 3 |
| H1 rejected reference faults | 9 |
| H1 `MISSING_REQUIRED_EVIDENCE` | 3 |
| H1 `INVALID_EVIDENCE_CLAIM` | 6 |
| typed literal fixture reads | 9 |
| outcome mismatches | 0 |

这里的 24 条记录是 **Verifier evaluations**，不是 24 个 product `AgentRun`，也没有
生成 `HarnessRunBundle`。Pack 中遗留的 `expectedTotals.runs=24` 只作为冻结输入保留，
输出层不沿用这个容易误解的名称。

## Authoritative Runner 如何避免“看起来一样”

Runner 不接受 caller 注入 generator、observer、H0 或 H1：

- loader 仍只接受固定路径和固定 raw SHA 的 Pack 004；
- generator 固定为 `literal-reference-candidate-fixture-v1`；
- H0 固定为 isolated `schema-only-eval-v1`；
- H1 直接调用 production
  `AgentDraftReferenceGrounding.verify`，没有复制算法或 runtime selector；
- `GeneratedCandidate` 只保存一个 production Candidate，report snapshot 从该对象派生，
  不存在“hash 绑定 A、Verifier 实际消费 B”的双事实源；
- identity audit 要求 12 个生成对象都由 H0、H1 各消费且只消费一次，否则不形成 report。

三种“已读取”case 必须先成功调用 typed in-memory
`LiteralSyntheticCaptureReader`。Reader 先校验唯一 canonical synthetic Capture ref，
成功返回 Pack literal content 后才递增 owned counter；forged ref 在计数前以固定错误码
拒绝。`CLAIM_WITHOUT_TOOL` 不调用 reader，仍构造“声称引用但没有 obtained evidence”
的 adversarial candidate。

这不是 product `capture.read` Tool、真实 Store 或 Agent loop；它只是这个 isolated
comparison 内可执行、可计数的 literal fixture read seam。

## Report 与完整性边界

Report 保存：

- Pack、suite、variable、repetition 与 component version binding；
- canonical ordered 12 个 Pair records；
- canonical ordered 24 个 VerifierEvaluation records；
- candidate snapshot 与 fingerprint；
- expected/observed outcome、fixed failure code 和 match flag；
- independently reducible Summary、typed Issues 与 `PASSED / FAILED`；
- `OWNED_OFFLINE_RUNNER_SEAMS_V1` 范围内的 effects counters。

ID / hash graph 是单向 DAG：

```text
candidate inputs
  → candidateFingerprint

trusted report config
  → reportId

reportId + pair identity + candidateFingerprint
  → pairId → pairHash

pairId + arm + verifier version
  → evaluationId → evaluationHash

full ordered report
  → reportHash
```

所有 domain 都独立、带版本；evaluation domain 不使用 `run` 词汇。Canonical profile
明确区分 `null`、empty、boolean、safe integer、UTF-8 byte length、list order 与
Unicode code-point object-key order。Map insertion order不影响结果。

四种 Pack candidate 的 fingerprint 由独立 Node encoder 计算并作为 fixed golden：

```text
GROUNDED              afab18c45f481fe1d48a2a03980b3e5ab2f74f0f7e9831c00626a5ae87f0817a
CLAIM_WITHOUT_TOOL    39d9e63b22482d54115c234876a7758473013e5fc19f8467e9f44f63f80fabc6
OMITTED_EVIDENCE_REF  688d5c4206db5f086693491b536a01f20f17d8c68e56299b751b37274f554ea6
EXTRA_EVIDENCE_REF    ce161910c06dc2201d3b9cb125a42236a410e3c21ad8bfc8f080a83d364cc02c
```

## 独立 Report Verifier

`Pack004ComparisonReportVerifier` 不调用、也不在 bytecode 中引用 Runner 或 generator。
它自己：

1. 从固定路径重新加载并验证 Pack；
2. 按 Pack case order × repetitions 重建 12 个 logical Pair slots；
3. 按独立的 generator-v1 oracle 重建 candidate；
4. 本地重跑 H0，并直接调用 production H1 facade；
5. 重算 Pair/Evaluation matrix、canonical order、IDs 与 nested hashes；
6. 从 replay outcomes 独立归约 Summary、OwnedEffects、Issues 与 status；
7. 最后才验证整体 report hash。

Verifier 的唯一非 private entrypoint 是：

```text
verify(Path, OfflineComparisonReport) → Verification
```

`Verification` constructor 是 private，同 package caller 不能跳过 `verify()` 伪造
`VERIFIED_PASSED`。合法实验结果与报告完整性分栏：

```text
VERIFIED_PASSED
VERIFIED_FAILED
INVALID + fixed failureCode
```

`FAILED` experiment 不会因为结果“不好看”被当作 invalid；缺行、换序、伪造或不一致才是
`INVALID`。

## TDD 与 adversarial evidence

### Acceptance Red

第一条 Runner acceptance test 先因缺少 `OfflineComparisonReport` 与
`Pack004ComparisonRunner` 在 test compilation 失败。最小实现后，它只证明 12/24
计数 Green；随后才补充 exact order、failure-code、identity、determinism 与 immutable
records。

独立 verifier 的第一条 test 同样先以 3 个 missing-symbol compiler errors Red，再实现
replay verifier。

### 审查发现并关闭的高风险问题

独立 review 在提交前找到并关闭：

1. snapshot 与 production Candidate 双事实源，可能让 hash 不覆盖实际 Verifier input；
2. caller 可注入 generator/observer，却仍使用固定 component version 与 report ID；
3. 同一个 Runner 自算 outcome、summary、status、hash，形成自证；
4. fixture read counter 曾经只是手动递增，没有真实 typed reader call；
5. verifier result 可被同 package 直接构造；
6. verifier independence gate 曾只扫描 outer class，nested class 可以藏 common-mode
   reference。

最终 adversarial tests 覆盖：

- schema、Pack、component 与 report ID mutation；
- Pair/Evaluation missing、extra、duplicate、cross-binding 与 reordering；
- expected/observed outcome、failure code 与 match flag mutation；
- Summary、OwnedEffects、Issues 与 status mutation；
- Pair、Evaluation 与 report nested hash mutation；
- 攻击者修改 candidate 后，完整重算
  `fingerprint → pair ID/hash → evaluation ID/hash → report hash`。

最后一种攻击的所有 hash 都自洽，独立 verifier 仍因自己的 candidate oracle 返回
`REPORT_CANDIDATE_MISMATCH`。这证明 verifier 不是只看最外层 hash。

## Isolation evidence

原有 default-deny Maven allowlist 与 JDK network/process bytecode gate 保留。新增的
offline-only product-runtime classifier 只扫描本模块 production classes，拒绝：

- `AgentDraftService`、`AgentKernel`、product `AgentRun`；
- `HarnessRunBundle`、Task/Result/Trace product contracts；
- API、Adapter、Eval Runner 与 Core product Store/runtime types。

只显式放行当前切片需要的 production H1 facade、`AgentDraftProposal` 与
`ArtifactLineageEntry`。Test fixture 真实编译 forbidden descriptors 和 reflective
class name，先 Red 后 Green。Verifier outer class 及全部 nested classes 另有
common-mode reference scan。

这些是 dependency / bytecode architecture evidence，不是 OS sandbox、firewall 或
packet capture。

## 最终验证

latest tree 已重新执行：

```bash
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/offline-harness-runner -am clean verify
./mvnw --batch-mode --no-transfer-progress verify
git diff --check
```

当前已确认的 focused clean reactor：

```text
Contracts                         26 tests
Core                              79 tests
Offline Harness Runner            54 tests
──────────────────────────────────────────
合计                             159 tests
failures / errors / skipped       0 / 0 / 0
```

Full Maven reactor：

```text
Contracts                         26 tests
Core                              79 tests
Agent Loop                         8 tests
OpenAI Adapter                    19 tests
In-memory Adapters                21 tests
PostgreSQL Adapter                45 tests
API unit + process IT             32 tests
Synthetic Eval unit + process IT 49 tests
Offline Harness Runner            54 tests
──────────────────────────────────────────
合计                             333 tests
failures / errors / skipped       0 / 0 / 0
```

同时：

- contract verifier：5 个 JSON Schema、33 个 fixtures、2 个 cross-language Task
  hash golden vectors、4 个 synthetic Task Packs、1 个 environment manifest；
- doc links：73 个 Markdown files；
- Maven Enforcer、offline dependency allowlist 与 bytecode gates Green；
- `git diff --check` Green；
- full Maven verify 总耗时 `01:10 min`。

两名独立 reviewer 的最终结论均为 `P0=0、P1=0`。

## 可信声明边界

本回执可以声明：

> 在固定 Pack 004 与 `OWNED_OFFLINE_RUNNER_SEAMS_V1` 范围内，本次 deterministic
> offline execution 生成 12 个 immutable shared candidates；其中 9 个 candidate
> 先成功调用 typed in-memory literal reader。每个 candidate 随后由 H0 与 production
> H1 分别验证，共 24 次 VerifierEvaluation。独立 verifier 重建并重放后得到
> `VERIFIED_PASSED`。

本回执不能声明：

- report 证明某个历史进程、producer identity 或 trusted execution environment；
- 9 次 read 是 product `capture.read` Tool、真实数据库或真实用户 Evidence；
- Effects 中的零值是系统级零网络、零凭据、零副作用 attestation；
- unkeyed SHA-256 是 signature、authentication 或不可抵赖证明；
- report 已原子持久化、跨进程存活或可由新 JVM 从 durable bytes 核验；
- 已读取 real key、调用真实模型、产生费用或完成 real-model baseline；
- 已访问 Connector、真实账号、真实用户数据或外部平台；
- 这四个 deterministic cases 证明模型质量、用户价值或 production autonomy。

## 下一项可证伪假设

下一切片不扩大到 live provider，而是在同一隔离模块完成 durable report evidence：

1. 固定 deterministic JSON serialization 与 bounded schema；
2. 私有目录、regular-file / symlink / owner / mode checks；
3. `.pending` write、read-back、file `fsync`、`ATOMIC_MOVE`、directory `fsync`；
4. 在 write / move / fsync crash windows 强制终止真实 JVM；
5. 新 JVM 只读加载 bytes、重算 hash、独立 replay，得到相同 verdict；
6. incomplete evidence 保持 `UNKNOWN / INVALID`，绝不改写成 success。

完成前，当前结果仍是 verified in-memory comparison，不是 durable report receipt。
