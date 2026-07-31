# RFC-0004：typed read-only Worker handoff 与 durable Worker Result

- Status: Accepted
- Author: project owner + main Codex agent
- Created: 2026-07-31
- Discussion: Stage 2 Pack 007

阅读说明：下方 `Problem` 与 `Validation` 中的 Acceptance Red 描述保留 RFC 起草时的
历史状态，不代表最终能力上限；最终落地与证据见文末 `Implementation Resolution`。

## Problem

EmergeOS 已经能够持久化一个 Agent 的 Task、Result、safe Trace、
HarnessRunBundle 与 Artifact，但 `One Self, Many Workers` 目前仍只是 schema
中的占位形状：

- `TaskEnvelope.parentId/delegationChain` 只有语法，没有 parent/child 语义；
- `HarnessRunBundle.handoffRefs` 与 `HANDOFF` binding 只做列表同形校验；
- Agent Model 只能返回 ToolCall、FinalDraft 或 Failed；
- Agent Trace 无法表达 Worker request/result/rejection；
- PostgreSQL 不验证 Handoff 指向同 owner、terminal、exact Bundle hash 的 child；
- `AgentDraftProposal.content` 只存在于 process memory。即使 parent 绑定 child
  Bundle hash，也无法证明 parent 最终消费的是哪份 Worker proposal。

把 Worker 注册成普通 Tool 会隐藏 child Task、Run、budget、policy、model usage 和
独立失败状态；只把 proposal hash 放进 Trace 又无法在 process restart 后取回并验证
output。两种方案都不能形成生产级、可审计的 typed handoff。

## Proposal

### 1. Pack007 的唯一 Worker 形状

本 Pack 只实现一个 server-owned、串行、depth=1 的 read-only Worker：

```text
root Task:
  kind = CREATE_ARTICLE_DRAFT
  parentId = null
  delegationChain = []
  requiredTools = []
  capabilityRefs includes
    capability://worker-handoff/article-draft-read-v1

child Task:
  kind = PROPOSE_ARTICLE_DRAFT
  parentId = root Task.id
  delegationChain = [root Task.id]
  risk = READ_ONLY
  allowParallel = false
  requiredTools = [capture.read]
  capabilityRefs excludes Worker handoff
```

`parentId` 在本契约中始终是 parent **Task ID**，不是 Run ID。child 与 parent
必须：

- `principalRef` 相同；
- exact inherit `policyVersion/stateVersion/contextPolicyVersion/
  toolRegistryVersion/environmentSnapshotRef`；
- child input 是 parent input 的子集；Pack007 固定为同一个 Capture ref；
- child risk、budget、deadline、model/tool limits 与 capability 只能相等或收缩；
- child 不得创建 Artifact、Receipt、Action、Checkpoint 或第二次 Handoff。

parent 只消费一个 verified terminal child，且仍是唯一 Artifact writer。

### 2. Worker authority 由 capability + exact registry 双重约束

本 Pack 不把通用 Worker graph 塞进 Task schema，也不新增可由 Model 填写的
principal、policy、budget 或 model route。root Task 使用 server-owned capability：

```text
capability://worker-handoff/article-draft-read-v1
```

runtime 另绑定 exact manifest：

```text
agent-workers-v1
  └── article-draft.read-v1
      ├── task kind: PROPOSE_ARTICLE_DRAFT
      ├── effect: READ_ONLY
      ├── input: one Capture ref
      ├── tool: capture.read
      └── max depth: 1
```

registry version 与 Worker profile fingerprint 进入 execution profile fingerprint
和 Bundle `componentVersions`。unknown、extra、duplicate entry 在 registry
构造时失败；parent execution profile 与 Worker runtime identity 不一致时在
`AgentDraftService` composition/startup 时抛错，既不创建 parent Run，也不伪造一个
terminal Agent 状态。请求到达后的 context/authority drift 则在 child Run、Model
与 Tool dispatch 前形成 typed `BLOCKED` truth。未来需要多 Worker、并行或动态
routing 时，再通过新 schema version 增加 `requiredWorkers/maxChildRuns`；
Pack007 不预造一个未经验证的通用图协议。

Tool authority 不是“registry 里存在这个 Tool，Model 就能调用”，而是 exact
registry 与 server-owned Task `requiredTools` 的交集。Pack007 的 parent Task 固定
`requiredTools=[]`，因此 Conductor Model 在 handoff 前后直接请求 `capture.read`
都会得到 `BLOCKED / TOOL_NOT_ALLOWED`，不会进入 Tool validation 或 execute；只有
child Task 获得 `requiredTools=[capture.read]`。`AgentDraftService` 仍会以应用层
owner authority 预读 Capture 并建立 Evidence，故这里证明的是 **Model authority**
隔离，不是 parent process 完全不持有 Capture store 的进程级隔离。

### 3. Provider-neutral two-phase runtime

`AgentModel.Decision` 增加 typed `WorkerCall`，`Turn` 增加 ephemeral、bounded 的
`WorkerResult`。Core 增加 provider-neutral `AgentWorkerRuntime`：

```text
prepare(parent Task, WorkerHandoffRequest, available execution window)
  → Rejected(status, stable failure code)
  | Prepared(childRunRef, one-shot execute)
```

`prepare` 只做 pure validation 和 server-owned child identity 规划，不创建 child
Run、不打开 Model、不读 Tool。Kernel 在 `prepare` 与 `execute` 之间再次检查
cancellation/deadline。`Prepared.execute()` 只能调用一次。

Agent Loop 不依赖 PostgreSQL、Spring 或 provider SDK；durable Worker service 通过
Core port 持久化 child Run 和 Worker Result。

### 4. Durable WorkerResultEnvelope

child proposal 不是 Artifact，也不能只存在于内存。新增
`WorkerResultEnvelope 1.0`：

```text
schemaVersion
workerResultRef = worker-result://{childRunId}
childRunId
childTaskId
outputSchema
content
contentHash = SHA-256(UTF-8 content)
evidenceRefs
integrityProfile
integrityHash
```

`integrityHash` 使用独立 domain
`emergeos.worker-result-envelope.v1` 和现有
`emergeos-length-prefixed-sha256-v1` canonical encoding。content 必须是有界
Unicode scalar safe text；不保存 prompt、hidden reasoning 或 provider raw response。

child terminal Run、safe Trace、Bundle 与 WorkerResult 必须在同一个 transaction
提交。专用 `ReadOnlyWorkerRunStore` 不接受 `ArtifactLineage`，让 child no-write
成为结构性 capability boundary，而不只是调用者约定。

child Bundle 使用：

```text
WORKER_RESULT binding:
  ref = worker-result://{childRunId}
  contentHash = WorkerResultEnvelope.integrityHash
```

child `handoffRefs` 保持空；`WORKER_RESULT` 表示本 Run 的 durable output，不是假装
child 又做了一次 Handoff。

### 5. Safe Trace 与 proposal lineage

新增 allowlisted Trace event：

| event | toolName | status | reference |
|---|---|---|---|
| `HANDOFF_REQUEST` | `null` | `REQUESTED` | `agent-run://{childRunId}` |
| `HANDOFF_RESULT` | `null` | `SUCCEEDED` | 同一 child ref |
| `HANDOFF_REJECTED` | `null` | closed status/failure mapping | pre-request 为 `null`；post-request 为同一 child ref |

pre-request 只允许 `BLOCKED / FAILED / MALFORMED_RESULT` 与对应 terminal
status/failure；post-request 只允许
`CANCELLED_UNOBSERVED / DEADLINE_EXHAUSTED / DEADLINE_EXCEEDED_UNOBSERVED /
DEADLINE_EXCEEDED_AFTER_CHILD / FAILED / MALFORMED_RESULT / LIMIT_EXHAUSTED /
CHILD_FAILED / CHILD_BLOCKED / CHILD_NEEDS_INPUT / CHILD_CANCELLED`，并与 terminal
status/failure exact match。不能用自由字符串把一个 Handoff 失败改写成另一种终局。

success parent Trace：

```text
MODEL_STEP
→ HANDOFF_REQUEST
→ HANDOFF_RESULT
→ MODEL_STEP
→ STRUCTURED_FINAL
→ ARTIFACT_COMMITTED
```

non-success Trace 原则上必须以显式 terminal event 结束。没有 event 的 preflight/
session-open failure 继续允许；已完成 Model step、Tool result 或 Handoff result 后，
只有可证明的 cooperative cancellation、`latencyMs >= deadlineMs` 的 deadline
exhaustion，以及 `modelSteps == maxModelSteps` 的 step-limit exhaustion 可以作为
implicit control-boundary terminal。`UNSUPPORTED_MODEL_DECISION` 仅允许紧跟已完成的
Model step。其他 non-success 若缺 terminal event，必须在 Core boundary fail-closed。

proposal 使用 content-addressed safe reference：

```text
proposal://sha256:{contentHash}
```

Pack007 必须证明：

```text
child STRUCTURED_FINAL proposal hash
= WorkerResultEnvelope.contentHash
= parent STRUCTURED_FINAL proposal hash
= parent Artifact binding.contentHash
```

legacy Task 继续使用既有 `task://{taskId}` STRUCTURED_FINAL ref，历史 Trace 与
Bundle hash 不重算。

### 6. Parent→child durable binding

parent Bundle 使用 exactly one：

```text
handoffRefs = [agent-run://{childRunId}]

HANDOFF binding:
  ref = agent-run://{childRunId}
  contentHash = child HarnessRunBundle.integrityHash
```

完整 hash chain 是：

```text
parent Bundle
→ HANDOFF binding
→ child Bundle hash
→ child Trace root
→ WORKER_RESULT binding
→ WorkerResultEnvelope integrity hash
→ proposal content hash
→ parent STRUCTURED_FINAL
→ parent immutable Artifact hash
```

单个 Bundle constructor 只能校验本地 shape；shared Core pair verifier 与
PostgreSQL verified read 负责跨 Run 关系，不能用 JSON Schema 假装已经验证 child。

### 7. V6 PostgreSQL truth

V6 复用 `agent_run_resource_bindings`，为 `HANDOFF` 和 `WORKER_RESULT` 增加 typed
run identity，并新增 immutable `agent_worker_results`。declarative constraints
至少证明：

- relation 和 Worker Result 与 parent/child 使用同一 `principal_id`；
- child/Worker Result 存在且 child 是 terminal；
- HANDOFF hash exact 等于 child Bundle hash；
- WORKER_RESULT hash exact 等于 WorkerResult integrity hash；
- typed ref grammar；
- immediate self-reference 被拒绝；
- 每个 parent 最多一个 child；
- 一个 child 不能被两个 parent 重复消费；
- Handoff relation 不能单独 commit 到仍为 RUNNING 的 parent。

带 read-only Worker capability 的 root parent 在 `agent_runs` INSERT 前必须通过 exact
parent-profile 校验，包括 `requiredTools=[]`；root verified read（含 `RUNNING`）会再次
校验。非法首次 admission 不得留下 row，durable JSON 被篡改后必须在读取时 fail-closed。

parent completion transaction 的顺序是：

```text
lock RUNNING parent
→ verified read/lock terminal child + WorkerResult
→ shared pair verifier
→ Artifact
→ Trace
→ typed bindings
→ terminal parent update
→ verified read-back
→ commit
```

child terminal commit 后、parent terminal commit 前的 process death 必须保留：

```text
child = terminal + verified WorkerResult
parent = RUNNING
parent Trace/Handoff/Artifact = 0
```

本 Pack 不自动 resume 或 retry 这个 parent。

### 8. failure、deadline、budget 与 cancellation

首批 stable mapping：

| boundary | parent terminal truth |
|---|---|
| execution profile ↔ Worker runtime identity mismatch | configuration/startup fail-fast；无 Run |
| unknown/undeclared Worker | `BLOCKED / HANDOFF_NOT_ALLOWED` |
| second Worker | `BLOCKED / HANDOFF_LIMIT_EXHAUSTED` |
| context-policy drift before child start | `BLOCKED / HANDOFF_CONTEXT_POLICY_DRIFT` |
| authority expansion | `BLOCKED / HANDOFF_AUTHORITY_ESCALATION` |
| untrusted child/hash/output | `FAILED / UNSAFE_HANDOFF_OUTCOME` |
| child definite non-success | matching status + stable `HANDOFF_CHILD_*` code |
| strict late child completion | `FAILED / HANDOFF_DEADLINE_EXCEEDED_AFTER_DISPATCH` |
| subtree budget exhausted | `BLOCKED / HANDOFF_BUDGET_EXHAUSTED` |
| cooperative cancellation | `CANCELLED / CANCELLED` |

child dispatch 返回后，先验证 child identity 与 usage domain。若这些字段不可信，
直接 `FAILED / UNSAFE_HANDOFF_OUTCOME`，不能拿未验证 child 决定业务状态。对可信
observation 的 canonical precedence 是：

```text
aggregate subtree budget
→ strict deadline exceeded
→ child terminal status
→ accept WorkerResult
→ post-success cancellation
→ exact-deadline exhaustion before next Model step
```

因此 observed child usage 即使同时越过 budget 与 deadline，也必须先形成
`HANDOFF_BUDGET_EXHAUSTED`；在 budget 内，strict late completion 胜过
cancellation 与 child failure；在 deadline 内，verified child non-success 胜过
cancellation。只有成功 child 已验证并发出 `HANDOFF_RESULT` 后，cooperative
cancellation 才形成 parent `CANCELLED`，同时保留 child observation。若 runtime
抛错、没有可信 child observation，则优先级是 strict deadline → cancellation →
`HANDOFF_DISPATCH_FAILED`。

parent latency 是整个 wall elapsed；不能再把 child latency 相加。parent usage/cost
表示 depth=1 subtree aggregate，child Bundle 保留独立 breakdown，parent
`resolvedModel` 仍表示 Conductor route。该 Task version 的 verifier 必须避免重复计量。

### 9. Context-policy drift 必须 pre-dispatch fail closed

若 registered Worker profile 的 `contextPolicyVersion` 与 parent 不同：

```text
parent = BLOCKED / HANDOFF_CONTEXT_POLICY_DRIFT
Trace = MODEL_STEP → HANDOFF_REJECTED
child Run = 0
child Model = 0
Tool validation/execute/read = 0
HANDOFF/WORKER_RESULT binding = 0
Artifact = 0
```

不允许先创建 child RUNNING 再“发现” drift，因为那会制造无执行事实的 stale Run。

## Alternatives

### 把 Worker 注册成 Tool

会抹去 child Task/Run/profile/status/usage 和独立持久化边界。拒绝。

### 只绑定 child Bundle hash

当前 child Bundle 不含 proposal content 或 output hash，无法证明 parent 消费了什么。
拒绝。

### 只把 proposal hash 放进 Trace

可以证明 hash，不能在 restart 后取回 output，也不能支持后续 verified replay/resume。
拒绝。

### 把 proposal 放进 Claim、uncertainty 或 hidden reasoning

语义错误，且可能扩大 privacy surface。拒绝。

### 允许 child 写一个“临时 Artifact”

会产生 shared writer 和 lineage ownership 冲突。child output 是 WorkerResult，不是
用户 Artifact。拒绝。

### 本 Pack 同时引入 Pi、AgentScope 或 Temporal

会把 typed contract、durability 与 framework behavior 混成多个变量。先建立固定
framework-free baseline；后续再用同一 Pack 做 runtime/harness 比较。延期。

## Safety, privacy and autonomy

- runtime Worker 只能读取 parent 已授权的同一个 owner-scoped、非
  `SENSITIVE/SECRET` Capture；公开验收只使用 PUBLIC synthetic fixture；
- Model 不提供 principal、policy、capability、budget、tool registry 或 child ID；
- Trace 只保存 allowlisted identity、status、usage、refs 与 hashes；Bundle 会内嵌
  bounded Task fields，包括 server-owned Task intent。两者都不保存 provider raw prompt/response、
  Capture/WorkerResult 正文、Tool raw result、exception 或 hidden reasoning；
- 真实产品 WorkerResult content 只在 owner-scoped truth store 中保存，不进入公开
  eval evidence；仓库 fixture 与 Pack007 只能包含明确标记的 PUBLIC synthetic literal；
- 无 external action、Connector、credential、live model 或真实用户数据；
- parent Artifact 仍经 deterministic verifier，Worker 不能直接 commit；
- context drift、hash drift、cross-owner、nested handoff 与 child write 均 fail closed。

## Validation

第一条 runnable Acceptance Red 先让现有 Model 把 Worker 冒充
`worker.delegate` Tool，并保持目标断言：

```text
parent SUCCEEDED
two terminal Runs
one child capture.read
child Artifact = 0
parent Artifact = 1
one exact HANDOFF binding
durable WorkerResult = 1
```

当前系统应实际得到：

```text
parent BLOCKED / TOOL_NOT_ALLOWED
child Run/Model/read = 0
HANDOFF/WorkerResult/Artifact = 0
```

Green 后测试改用 typed `WorkerCall`，而旧 Worker-as-Tool characterization 必须继续
被拒绝。

完成证据至少包括：

- Java/JSON/Node contract parity、negative fixtures 与 golden hashes；
- control + context-drift frozen Pack 007，两次独立运行 exact-equal；
- parent/child Task inheritance、safe Trace、Bundle、WorkerResult 与 Artifact hash chain；
- child no Artifact/Receipt/Action/Checkpoint/Handoff；
- V6 fresh install 与 populated V1–V5→V6；
- owner、missing、RUNNING child、wrong hash、self-link、cycle/reuse/tamper；
- 非法 direct-tool Worker root 的 admission `agent_runs=0`，以及篡改后的
  `RUNNING` root verified read fail-closed；
- parent atomic rollback、两个 parent 并发消费一个 child 只有一个 winner；
- real process kill 后两个 fresh JVM verified read；
- focused、contracts、full Maven、doc links 与独立 correctness/security/recovery review。

## Migration and rollback

V6 只能 additive upgrade；历史 V1–V5 Task/Result/Bundle JSON 与 integrity hash 不得
改写。若 legacy database 已存在无法证明的 generic HANDOFF，migration 必须 fail fast，
不得猜测或静默丢弃。

若 Pack007 无法维持 durable output、same-owner relation、child no-write 或 atomic
parent commit，应整体关闭 Worker capability 并回滚 runtime composition；不能降级为
普通 Tool 或只保留 UI 上的“多 Agent”叙事。

## Implementation Resolution · 2026-07-31

本节记录 Acceptance Red 之后的最终实现，不改写上面的历史：

- typed `WorkerCall`、two-phase `AgentWorkerRuntime`、durable
  `WorkerResultEnvelope`、parent-only Artifact 与固定 child-observation precedence
  已进入 production classes；
- Pack007 strict loader、两个 fresh runner exact-equal 与 Node semantic verifier
  固定 `8,443` bytes、raw SHA
  `808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c`、
  shape、receipts 与 cross-run graph；
- parent Task 的 Model Tool allowlist 固定为空，child 才拥有 `capture.read`；
  Java/Node/PostgreSQL 同时拒绝 immediate self-parent，post-request
  `HANDOFF_REJECTED` 只接受 closed status/failure mapping，child inherited fields
  与 `resolvedModel` safe domain 均 fail-closed；Worker-capable root 的 exact
  parent-profile 校验同时位于 INSERT 前和 root verified read；
- accepted Handoff 后的 non-success Trace 不能伪造缺失的 terminal event；只有
  cancellation、真实 deadline boundary 与真实 model-step exhaustion 可 implicit
  结束，伪 outcome 被归一为 `FAILED / UNSAFE_AGENT_TRACE` 且不创建 Artifact；
- V6 已通过 fresh/populated upgrade、legacy fail-fast、same-owner/terminal/hash、
  immutable/single-consume、rollback、concurrency 与 OLD+NEW moved-Handoff guard；
- packaged success 与真实 JVM process-kill crash gap 均由两个 fresh JVM verified
  read；十张 product-truth 表与 Flyway history 的 PK/`xmin` snapshot 在 reader
  lifecycle 后不变；
- `./scripts/verify-contracts.sh`、`./scripts/verify-doc-links.sh`、`git diff --check`
  与 10-module `clean verify` 全部 Green；full Maven 共 523 tests，0 failure/error/
  skipped；
- contract/runtime、PostgreSQL/recovery 与 replay/docs 的 post-fix 独立审查结论为
  `P0=0、P1=0`；parent process composition 的物理 Tool footprint 与 packaged API
  未重复断言 Task authority 作为非阻断 P2/hardening 保留，不上升为进程级隔离声明。

证据边界：PK/`xmin` 证明采样点之间没有遗留 durable row-set/row-version 变化，不是
SQL audit 或 literal zero-write 证明。Pack007 仍只代表
single/synchronous/`depth=1`、one Worker、read-only Fake 路径；不代表通用
multi-agent、parallel Worker、checkpoint/resume、lease/fencing、live model、
write-capable Worker、真实用户数据或用户价值。

最终采用边界见
[ADR-0008](../architecture/decisions/0008-typed-read-only-worker-handoff.md)，
完整 TDD、Pack、PostgreSQL、process 与 gate 回执见
[Pack007 Build Note](../operations/build-notes/2026-07-31-s2-s4-typed-read-only-worker-handoff.md)。
