# Evaluations

Evaluation contributions are first-class product work.

```text
task-packs/synthetic/   Safe, reproducible tasks with no real user data
regression/             Failures promoted into permanent checks
reports/                Versioned aggregate results and limitations
```

Every Task Pack should state:

- seed and frozen evidence;
- required constraints and forbidden actions;
- expected Artifact class;
- deterministic acceptance checks;
- human review questions;
- risk and fault plan;
- model, Harness, state and tool versions required for replay.

Do not commit real conversations, voice, Self Model records, complete production traces or platform receipts. A realistic synthetic persona is preferable to weak anonymization.

## Gate types

- **Deterministic safety/correctness** checks—Schema, authority, tool allowlist, evidence linkage,
  duplicated side effect, invalid receipt—may block every PR.
- **Stochastic quality** checks—usefulness, style, planning quality—run with fixed versions and
  repeated trials. Judge distributions and failure categories, not one answer.
- **Live-provider smoke** may be scheduled or pre-release because of cost and availability. It must
  save a HarnessRunBundle、one-shot attempt marker、attempt journal、create-only terminal record
  或明确的 billing-`UNKNOWN` failure receipt，且永远不能替代 offline deterministic
  coverage。

Before changing a model, Prompt, Skill or Harness, record the baseline task set, repetitions, grader,
budget and decision threshold. A new version is not better because it is newer, and a Critic model is
not ground truth.

## 当前 Offline baseline

`task-packs/synthetic/002-fake-agent-draft-replay.json` 由
`OfflineFakeAgentRunnerTest` 在两个 fresh in-memory runner 中执行，并比较 exact
Artifact、Result、Safe Trace 和 HarnessRunBundle hashes：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/inmemory -am \
  -Dtest=OfflineFakeAgentRunnerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该 runner 是 test fixture，不是 CLI 或 product replay API；它不能读取 shared
store、真实用户数据，也不能产生外部副作用。当前结果只证明 frozen synthetic Fake
success 的确定性，不证明真实模型质量或 H0/H1 指标。

## Stage 2 S3 · Isolated synthetic provider runner

S3 只允许独立 `apps/eval-runner` 处理：

- `task-packs/synthetic/003-openai-public-draft-smoke.json`；
- 当前 active environment
  `environments/openai-responses-synthetic-v2.json`；
- compiled catalog 中冻结的 pack、environment、Capture request、Task、execution
  profile、pricing profile 与 attempt hashes。

`openai-responses-synthetic-v1.json` 保留原始 bytes 与 raw SHA，作为
`agent-tools-v1` 的历史冻结 identity；它不再是当前 runner 的 executable
environment。v2 与 v1 只允许 `toolRegistryVersion` 从 `agent-tools-v1` 变为
`agent-tools-v2`，repository validator 同时冻结两份文件的 path、raw SHA、registry
version 和单变量关系。

普通 `apps/api` 不依赖 OpenAI adapter，也没有 live provider route。默认 packaged
command 只执行 zero-egress preflight：

```bash
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar
```

默认、`--help`、invalid asset、missing TTY、错误 challenge、expired permit 与 replay
必须在读取 credential、创建 client/model、启动 Run 或建立 socket 前失败。只有项目
所有者另行明确批准一次 bounded smoke 后，才允许在 real TTY 手工执行 `--execute`；
它不是 CI、自动化或 Agent 可自行触发的命令。

### 本地 one-shot 状态

Runner 在当前 owner home 下使用：

```text
~/.emergeos/eval-attempts/{attemptId}.attempt
~/.emergeos/eval-attempts/{attemptId}.journal
~/.emergeos/eval-attempts/{attemptId}.run.json
```

- directory 必须是 `0700`，file 必须是 `0600`；
- symlink、foreign owner 与 filesystem provider 可见的 foreign allow ACL 会
  fail closed；provider 不暴露 ACL view 时不宣称已验证 ACL，也不构成
  hostile-local-user authorization；
- marker 在 challenge 前使用 `CREATE_NEW`，错误 challenge 也会烧掉当前 host 上的
  attempt；
- 30 秒 permit 绑定 exact Task hash 与 execution profile，并在 provider egress 前
  以 CAS 消费；
- journal 在 credential read、client creation、provider SDK create intent、observed
  attribution 与 terminal publish 周围 append、hash-chain、`fsync`；
- terminal record 使用私有 `.pending` 的 `CREATE_NEW` write、file `fsync`、
  read-back、directory `fsync`，再以 `createLink(target, pending)` 做 create-only
  logical commit；commit 后再次 `fsync` directory、清理 pending 并重验 target；
- target 在 precheck 后出现时固定 `RUN_RECORD_ALREADY_EXISTS`，原 target 不被覆盖；
  hard link 不支持时 fail closed，不降级为可覆盖 move；
- pending-only 始终 non-authoritative；target+pending 只有在二者是同一 non-null
  file identity 时才是 committed cleanup residue，不同 inode 即使 bytes 相同也
  `INVALID`。

这些是本机防误触与审计边界，不是跨主机 exactly-once、provider-side idempotency、
signature、WORM 或 invoice reconciliation。同 UID 恶意进程、owner/root 删除或改写
状态、换主机重放不在保护范围内。Provider SDK create intent 只表示调用可能发生，
不证明 provider 已接收、执行或计费。

### Billing receipt 语义

| 字段 / 状态 | 含义 |
|---|---|
| `NOT_INVOKED` | provider SDK create count 为 0 |
| `ATTRIBUTED` | 每次 create 都取得可信 model/usage，可按 frozen PricingProfile 估算 |
| `UNKNOWN` | 至少一次 create 可能发生，但 observed attribution 不完整 |
| `observedCostUsd/observedTokenCount` | provider response 中实际观察并归因的顶层计量；billing/对账读取这里 |
| `runCostUsd/runTokenCount` | 经过 product sanitizer 的 Run/Bundle projection；失败路径可合法为 0 |
| `meteringMatchesRun` | 两层计量是否一致；`false` 时 Run usage 不能充当 invoice truth |

`UNKNOWN + observedCostUsd=0` 只表示费用未观测，不能解释成免费。Reservation 是 egress
前的 authorization ceiling，不是记账截断上限；provider 已返回的 observed usage 即使
超过 reservation 或 requested budget，也必须完整保存并形成 paid failure。

### 当前证据边界

Runner engineering Gate 已通过：独立安全审查为 `P0=0、P1=0`，focused/package/full
verification、contracts、doc links 与最终 Diff 检查全部为 Green。Production
read-only journal verifier 与 8-point fat-JAR process-kill/restart matrix 进一步证明：
incomplete snapshot 保持 `UNKNOWN`，只有 terminal journal 与 immutable record 完整绑定
才是 `VERIFIED`，而 marker 仍会阻断 replay。

当前执行证据仍来自 zero-egress packaged preflight 与 loopback provider；没有读取 real
key、执行 live-provider smoke、产生 real model result、real token receipt 或 billing
receipt，也没有物理断电、NFS 或 packet capture 证据。完整 durability 边界见
[Stage 2 S3 Durable Attempt Build Note](../docs/operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)。
create-only 修复、状态矩阵与最新 process evidence 见
[Stage 2 S3 Eval Run Record Create-only Build Note](../docs/operations/build-notes/2026-07-31-s2-s3-eval-run-record-create-only.md)。
Preflight receipt、loopback receipt 或 engineering-complete 状态都不能冒充
live-provider receipt。

## Stage 2 S4 O1 · Verifier comparison 与 durable report

`task-packs/synthetic/004-offline-reference-verifier-comparison.json` 已冻结 H0
`schema-only-eval-v1` 与 H1 `agent-draft-verifier-v1` 的单变量
reference-grounding 对照。4 cases × 2 arms × 3 repetitions 定义了 24 runs / 12 paired
candidates，以及执行前 expected matrix。

独立 `apps/offline-harness-runner` 已实际生成 12 个 shared candidates，分别执行 H0/H1，
形成 24 次 `VerifierEvaluation`，再由不引用 Runner/generator 的独立 verifier 重建
candidate、重跑两臂并重算 matrix、IDs、hash 与 counters，得到
`VERIFIED_PASSED`。相同结果已编码为 28,343-byte canonical report，并通过
owner-local hard-link create-only commit、双 packaged writer、7-point
process-kill 与两个 fresh read-only JVM 验证。

这是 fixed PUBLIC synthetic Pack 004 上的 deterministic comparison，不是 24 个
product `AgentRun`，也不是 stochastic 模型质量、真实 `capture.read`、历史执行
attestation、用户价值或 live-provider 证据。完整回执见
[Verified Offline Comparison](../docs/operations/build-notes/2026-07-30-s2-s4-verified-offline-comparison.md)
与
[Durable Offline Comparison Report](../docs/operations/build-notes/2026-07-30-s2-s4-durable-offline-comparison-report.md)。

## Stage 2 S4 F1 · Tool arguments pre-dispatch fault

`task-packs/synthetic/005-offline-tool-arguments-schema-fault.json` 冻结一组单变量
control/fault：

- control 只传合法 `reference`；
- fault 保持相同 Task、Capture、Fake Model、预算、时钟和组件版本，仅增加一个
  `unexpected` PUBLIC synthetic argument。

两组 case 都经过 production
`AgentDraftService → AgentLoopKernel → AgentToolRegistry → CaptureReadTool`。control
发生 2 次 Model step、1 次 Tool validation、1 次 Tool execute、1 次 Tool-backed
Capture read，并提交 1 个 Artifact；fault 发生 1 次 Model step、1 次 validation，
但 Tool execute、Tool-backed read 与 Artifact 均为 0，以
`FAILED / TOOL_ARGUMENTS_INVALID` 和
`MODEL_STEP → TOOL_REJECTED` 结束。

这项证据中的“零副作用”特指 schema fault 后零 Tool dispatch、零 Tool-backed read、
零 Artifact；不表示 Fake Model 没有执行，也不表示 product service 没有写入
`RUNNING → FAILED` truth。Pack raw SHA、Task/Trace/Bundle hashes、计数器、ID 消耗和
两次 fresh-fixture equivalence 都已冻结。它没有调用真实模型、网络或 Connector，
不能解释成模型质量、live-provider 或用户价值证据。完整回执见
[Tool Arguments Fault Build Note](../docs/operations/build-notes/2026-07-31-s2-s4-tool-argument-fault.md)。

## Stage 2 S4 F2 · read-only Tool post-dispatch deadline fault

`task-packs/synthetic/006-offline-read-only-tool-post-dispatch-deadline.json`
冻结相同 production vertical 的下一条单变量 control/fault。Task deadline 为 5ms：

- control 在 4ms 内返回合法 `capture.read` result，继续完成 draft；
- fault 已实际 dispatch/read 一次，但到 7ms 才返回，固定为
  `FAILED / TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH`。

fault 的 safe Trace 是
`MODEL_STEP → TOOL_REQUEST → TOOL_REJECTED(DEADLINE_EXCEEDED)`；late value、exception、
null 或 wrong-reference 都不能形成 Tool Result、Evidence、binding 或 Artifact。
actual latency、Model attribution 与 usage 必须保留，不能被
`UNSAFE_AGENT_OUTCOME`、clamp 或 zeroing 掩盖。

同一切片另以 Java adversarial tests 固定 `elapsed == deadline`、
simultaneous late+cancellation、cancellation-only 及 final-model-step boundary 的
strict `>` 与 canonical precedence；这些 case 不属于 Pack JSON 的 control/fault。
PostgreSQL round-trip/fresh-store read 证明 terminal status、failure、7ms latency、
三步 Trace 和零 Artifact/Evidence 在持久化后保持一致；本次无需 schema migration。

这是 cooperative、trusted、read-only Tool truth，不是 hard timeout、线程抢占或
write-capable action guarantee。它没有调用真实模型、网络或 Connector，也不证明用户
价值。完整回执见
[Post-dispatch Deadline Build Note](../docs/operations/build-notes/2026-07-31-s2-s4-post-dispatch-deadline.md)。

## Stage 2 Pack007 · typed read-only Worker 与 context-policy drift

`task-packs/synthetic/007-offline-read-only-worker-handoff-context-drift.json`
冻结唯一变量 `registered-worker-context-policy-version`：

- exact bytes：`8,443`；
- raw SHA-256：
  `808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c`；
- control registry 使用 `ref-only-v1`，与 parent 相同；
- fault 只把 registered Worker profile 改为 `ref-only-v2`。

control 经过 production class path
`AgentDraftService → AgentLoopKernel → DurableReadOnlyWorkerService`，底层使用
test recording stores，形成两个
terminal Run、一个 child `capture.read` execute、一个 durable WorkerResult、
一个 parent HANDOFF 与一个 parent-only Artifact。parent/child Trace、Bundle、
WorkerResult、proposal 与 Artifact 的 exact hashes 被 Task Pack、Java replay 与
Node validator 交叉冻结；每个 case 都由两个 fresh runner 完整重放并 exact-equal。
parent Task 的 `requiredTools` 固定为空；只有 child Task 获得
`requiredTools=[capture.read]`，因此 Conductor Model 不能绕过 typed handoff 直接读
Capture。

同一 least-authority 不变量也在 PostgreSQL root `AgentRun` INSERT 前和 root verified
read（包括 `RUNNING`）执行：非法 direct-tool Worker parent 不留下 durable row，
持久 JSON 被篡改后读取会 fail-closed。另有 protocol regression 拒绝 accepted
Handoff 后缺 terminal event 的 non-success outcome；仅真实 cancellation、deadline
boundary 与 step exhaustion 可以 implicit 结束。这两项是 runtime/durability
hardening，不是 Pack007 control/fault 的新增变量，因此不改变 frozen Pack bytes 或
hash。

control 的 `captureReadCount=3` 不是三个 Tool call：

1. parent service 预读 owner-scoped Capture，用于建立 parent Evidence truth；
2. child `capture.read` 的 delegated Tool-backed read；
3. Worker durable evidence binding 提交前的 owner-scoped read。

fault 只发生第一次 parent preload，因此 `captureReadCount=1`；child Run、child
Model、Tool execute、delegated read、WorkerResult、HANDOFF binding 与 Artifact 全部为 0，
parent 以
`BLOCKED / HANDOFF_CONTEXT_POLICY_DRIFT` 和
`MODEL_STEP → HANDOFF_REJECTED` 结束。

`OfflineReadOnlyWorkerReplayTest` 是 strict test fixture，不是 filesystem CLI 或
product replay API。Pack007 只证明 PUBLIC synthetic、single、synchronous、
depth=1、read-only Fake Worker 的 deterministic safety/correctness；它没有网络、
真实模型、Connector、parallel Worker、checkpoint/resume、write-capable Worker、
真实用户数据或用户价值证据。
