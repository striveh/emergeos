# RFC-0001：持久 AgentRun、Safe Trace 与 HarnessRunBundle 完整性绑定

- Status: Accepted
- Author: project owner + main Codex agent
- Created: 2026-07-30
- Discussion: Stage 2 S2 的三次独立只读审查已完成；本 RFC 冻结 S2 的实现边界

## Problem

Stage 2 S1 已经能够执行一条受约束的 Fake Agent Loop：

```text
Capture
→ Fake Model
→ capture.read
→ structured proposal
→ Core validation
→ PostgreSQL Artifact
```

但是，一次执行目前没有持久的身份和聚合：

- `ResultEnvelope` 只有 `taskId`，同一个 Task 的多次运行无法区分；
- Trace 只存在于 `POST /api/v1/agent-drafts` 的 inline response，
  `traceRef` 为 `null`；
- JVM 重启后只能找回 Artifact，不能找回产生它的 Run、Result 和 Trace；
- `HarnessRunBundle` 没有绑定完整 Result，重复字段之间也没有一致性校验；
- `integrityHash` 只检查 64 位十六进制格式，没有规范化输入或重新计算；
- Artifact ref 指向可变化的 lineage head，无法证明本次 Run 产生的是哪一个
  immutable version；
- 当前 synthetic Task Pack 不能离线重新执行 Agent Draft。

如果直接在 Artifact 提交后追加一次 Run 保存，进程可能在两次提交之间死亡，
形成“Artifact 已存在，但 Run 仍缺失或显示 `RUNNING`”的 ghost success。这个状态
无法可靠审计，也会污染后续真实模型、Harness 对照、多 Agent 和自我进化评测。

## Proposal

### 1. 分离产品执行真相与 Eval 投影

`AgentRun` 是产品运行真相，负责回答：

> 谁在什么 Task、模型、工具、策略和版本边界下运行，产生了什么安全 Trace、
> Result 和 immutable resource binding？

`HarnessRunBundle` 是从一个 terminal AgentRun 生成的、自包含的 Eval evidence
projection。普通产品调用不伪造 `experimentArm`、`WorkingSelf`、
`environmentSnapshot` 或 `verification`。只有实际存在并参与本次运行的资源才可
进入 Bundle。

S2 可以在 terminal commit 时生成 Bundle projection，方便立刻做完整性验证；
它不等同于产品生命周期本身。

### 2. AgentRun 生命周期

内部 lifecycle：

```text
RUNNING
→ SUCCEEDED | FAILED | NEEDS_INPUT | BLOCKED | CANCELLED
```

执行流程：

1. server 生成 `runId` 和 `taskId`；
2. 在一次短事务中保存 owner-scoped `RUNNING` AgentRun 和 Task snapshot；
3. 在数据库事务外运行 AgentKernel；
4. 只保留经过 allowlist 和 redaction 的 Trace event；
5. 成功时，在同一个 PostgreSQL transaction 中提交：
   - Artifact v1；
   - immutable Artifact version/hash binding；
   - `ARTIFACT_COMMITTED` Trace event；
   - terminal Result；
   - terminal AgentRun；
   - HarnessRunBundle projection；
6. 非成功时，在同一个 transaction 中提交 terminal Result、Trace、AgentRun 和
   Bundle，且 Artifact binding 必须为空；已经由成功 `TOOL_RESULT` 读到的 Evidence
   仍以 exact Capture/request hash 进入 Result 和 Bundle，不能因后续失败而丢失。

S2 不自动重跑 stale `RUNNING`，也不声称 mid-run checkpoint resume。意外崩溃后
留下的 `RUNNING` 是可见但未完成的事实；durable resume、lease 和 fencing 属于
后续 Workflow slice。

### 3. Public contract 的 unpublished-v1 协同修正

现有 v1 尚未发布。S2 在同一个 commit 中协调更新 Java records、JSON Schemas、
fixtures、semantic validator 和 compatibility tests；不能声称 wire-compatible。

`ResultEnvelope`：

- 增加 required `runId`；
- 增加 required `tokenCount`；
- terminal Result 的 `traceRef` 必须指向同一个 Run 的可解析 Trace。

`TaskEnvelope`：

- 增加 required `maxModelSteps` 与 `maxToolCalls`，使执行上限进入 Run/Bundle hash；
- `parentId`、`idempotencyKey` 与 `environmentSnapshotRef` 即使为 `null` 也必须显式
  出现，避免 Node 的“缺 key”与 Java 的“key + null”形成不同 hash preimage；
- budget、deadline 与执行上限采用 Java、Node、PostgreSQL 共同可表达的有界数值域。
- 所有 contract 文本采用统一 SafeText：有效 Unicode scalar、no NUL、至少一个非
  frozen Unicode whitespace code point，并按 code point 而不是 UTF-16 unit 计长；
  Task Pack 的长 Seed content 使用独立上限。

新增 `AgentTraceEnvelope` 与 `AgentTraceEntry`：

- `schemaVersion`
- `runId`
- ordered events
- `eventCount`
- `rootHash`
- `integrityProfile`

每个 entry 只允许：

- `sequence`
- `type`
- `toolName`
- `status`
- `reference`
- `previousRootHash`
- `eventHash`

不允许 prompt、tool arguments、raw tool result、Capture/Artifact 内容、模型原文、
exception message、stack trace、reasoning 或 chain-of-thought。

`HarnessRunBundle`：

- 内嵌 immutable `TaskEnvelope` 和 `ResultEnvelope`；
- 绑定完整 `AgentTraceEnvelope.rootHash`；
- 使用 typed `ResourceBinding(role, ref, contentHash)`；
- 绑定 exact Artifact version，例如
  `artifact-version://{artifactId}/1` 加 `contentHash`；
- 保留实际参与运行的 model/agent/verifier/Harness/tool/policy/state/context
  version；
- experiment、WorkingSelf、environment、handoff、checkpoint、Receipt 和
  nullable field 在没有真实值时显式写 `null`，collection 显式写空数组，不得缺少
  required key，也不能制造 placeholder ref；
- cross-field verifier 必须检查 Run、Task、Result、Trace、tool registry、
  outcome、model、cost、token、latency 和 resource bindings 一致；
- `componentVersions.agent/verifier/trace-integrity` 必须分别等于 Result 与
  integrity profile；`failureAttribution` 必须等于 `Result.failureReason`；
- ResourceBinding 采用固定的 global role + per-role ordinal 顺序，并同时校验
  Evidence、Artifact、Receipt、Verification、Checkpoint 与 Handoff refs。
- `SUCCEEDED` 必须记录真实 `resolvedModel`；Result usage 不能超过 Task
  budget/deadline；当前 `CREATE_ARTICLE_DRAFT` success 必须 exactly one Artifact，
  不能让空 `artifactRefs` 与空 commit suffix 形成 ghost success。

### 4. Ref grammar

S2 使用稳定、可解析且不含 secret 的 refs：

```text
/api/v1/agent-runs/{runId}
/api/v1/agent-runs/{runId}/trace
/api/v1/agent-runs/{runId}/bundle
capture://{captureId}
artifact-version://{artifactId}/{version}
```

POST 成功后的 Artifact `Location` 继续指向现有 Artifact endpoint；response 另外返回
`runId`、`runRef` 和 `bundleRef`。S1 inline Trace 在 S2 暂时保留用于本地兼容，
但持久 Trace 是 canonical truth，inline projection 必须与它一致。

### 5. Owner scope 与错误边界

- principal 只来自 server-owned authority；
- 数据库主键和查询始终使用 `(principal_id, run_id)`；
- owner lookup 必须发生在 integrity verification 之前；
- foreign 和 missing 返回完全同形的 `404`；
- owner 的损坏 Run 返回 generic `409 RUN_INTEGRITY_VIOLATION`；
- 任何 integrity error 都不得返回部分 Task、Result、Trace、ref 或内部异常；
- POST 与所有 Run/Trace/Bundle 成功或错误 response 都设置
  `Cache-Control: private, no-store`；
- S2 不返回 ETag。Bundle integrity hash 是领域完整性证据，不是 HTTP
  representation validator，不能混用。

### 6. Integrity profile

S2 采用项目自有、版本化、跨语言的 deterministic encoding：

```text
emergeos-length-prefixed-sha256-v1
```

不直接 hash Jackson 默认 JSON 或 PostgreSQL `jsonb::text`。编码规则：

1. 文本为 UTF-8；
2. 每个值以前置 type tag 和 unsigned big-endian byte length 编码；
3. object/record field 与 map key 都按 Unicode code-point key order 排序；
4. list 保持顺序；
5. decimal 限定为 `0..999999.999999` 且最多六位小数；整数不超过 JavaScript safe
   integer；时长不超过 24 小时；
6. timestamp 统一为 UTC、microsecond precision；
7. nullable 值使用独立 null tag；
8. hash 输入使用 domain separator；
9. Bundle preimage 排除 `integrityHash` 本身。
10. string 与 object key 不允许 lone UTF-16 surrogate；所有 hash-covered nullable
    field 必须显式存在。

Trace：

```text
eventHash =
  SHA256("emergeos.agent-trace-event.v1\0" || canonical(event body))

root0 =
  SHA256("emergeos.agent-trace.v1.empty")

rootN =
  SHA256("emergeos.agent-trace.v1\0" || rootN-1 bytes || eventHashN bytes)
```

Bundle：

```text
bundleHash =
  SHA256("emergeos.harness-run-bundle.v1\0" || canonical(bundle preimage))
```

Java 与 Node 必须共同验证一组 golden vectors。verified read 必须重新计算 Trace
chain、Bundle hash 和 cross-field binding，不能只相信数据库中的 digest。

该 profile 提供 corruption detection 和 tamper evidence，不提供 authenticity。
拥有完整数据库写权限的人仍可同时修改内容并重算全部 hash。HMAC、signature、
WORM 或外部 transparency log 属于后续 production security Gate。

### 7. PostgreSQL V4

V4 增加：

- `agent_runs`：owner、run/task identity、lifecycle、Task/Result/Bundle snapshots
  和 hash、version metadata、usage、Trace root、时间；
- `agent_trace_events`：owner/run/sequence 与 safe event hash chain；
- `agent_run_resource_bindings`：role、ordinal、immutable ref 和 content hash。
- 为 `captures(principal_id, capture_id, request_hash)` 增加唯一约束，使 Evidence
  binding 可通过 owner-scoped composite FK 绑定真实 Capture request hash。

关键不变量：

- Trace event 的 PK 为 `(principal_id, run_id, sequence)`；
- resource binding 的 FK 必须包含 principal；
- Evidence binding 必须引用 exact Capture ID/request hash；成功 Artifact 的
  `sourceCaptureId` 必须与 Evidence binding、Task input 和 Result evidence 一致；
- terminal Run 的 Result/Bundle/hash/finished time 必须完整；
- `RUNNING` 不得伪装成 terminal Result；
- terminal 后不能追加事件或第二次生成不同结果；
- safe Trace 必须满足 MODEL/Tool/Final state machine、request/result exact pairing
  与 Task model/tool limits；字段 allowlist 不能单独证明执行发生过；
- 成功 Artifact 与 terminal Run 原子提交；
- 非成功 Run 没有 Artifact binding；
- JSON object、ref 长度、event 数量和 snapshot size 均有上限。

V4 必须通过 fresh install 和 populated V3 → V4 upgrade rehearsal，并保持已有
Capture、Artifact 和 Action truth 不变。

### 8. Replay 的准确语义

S2 分成三种语义边界：

1. **verified read**：读取并校验历史 Run/Trace/Bundle；
2. **offline golden re-execution**：使用 Git 中冻结的 synthetic Task Pack、
   Evidence、Fake Model、Clock、ID、版本和 budget，在每次 fresh、隔离的 in-memory
   store 中重放，并比较 exact Result、Trace、Artifact hash 与 Bundle hash；
3. **future product replay**：如果后续把历史输入重新执行为持久产品 Run，必须生成
   fresh runId，再比较 Artifact content hash、Trace rootHash/normalized trace
   fingerprint、outcome 和
   normalized Bundle fingerprint，不能复用历史身份。

真实用户 Run 如果没有显式、安全导出的 frozen Evidence package，只能 verified
read，不能宣称可以完整离线重执行。S2 的 Offline runner 不是 product replay API，
不得连接 shared/persistent store、真实用户 Evidence 或外部副作用。

### 9. Stage 2 S3 isolated Eval compatibility note

S3 的 `apps/eval-runner` 复用本 RFC 的 AgentRun、Safe Trace 与 Bundle 不变量，但它
没有改变 S2 的产品真相边界：

- PostgreSQL 中 owner-scoped `AgentRun` 仍是普通产品执行真相；
- Eval Runner 使用单次内存 Store 执行 frozen PUBLIC synthetic case，并在当前 owner
  home 发布一份本地 terminal JSON record；它不是 product Store 或 replay API；
- 本地 record 通过私有 `.pending`、read-back、`ATOMIC_MOVE` 与目录 `fsync`
  atomic publish。该原子性不延伸到 provider 调用，也不替代成功 Artifact + terminal
  PostgreSQL Run 的 transaction；
- POSIX one-shot marker 只约束当前 host/owner home，不是跨主机 exactly-once、
  provider-side idempotency、签名或 authenticity；
- attempt journal 在 provider SDK create 前先记录 intent。该 intent 只表示调用可能
  发生，不能证明 provider 已接收、已执行、已计费或未计费；
- provider invocation 已可能发生、但 observed usage 不完整时必须使用
  `billingStatus=UNKNOWN`；`observedCostUsd=0` 只表示未观测，不能解释成免费；
- Billing 与 invoice reconciliation 必须读取 terminal record 顶层
  `observedCostUsd/observedTokenCount`。Run/Bundle 的
  `runCostUsd/runTokenCount` 可能因合法 sanitizer 在失败路径为 `0`；
  `meteringMatchesRun=false` 明确说明两者不一致，因此 Run usage 不能充当 invoice
  truth；
- reservation 是调用前 authorization ceiling，不是 provider observed usage 的截断
  上限。任何已观察 usage，即使超过 reservation 或 requested budget，也必须保存；
- 同 UID 恶意进程、owner/root 删除或重写本地状态、跨主机重放、WORM、全账户 hard
  spend cap 和 invoice-level reconciliation 仍属于后续 production boundary。

Runner 的 engineering-complete 声明以其当前 focused/package/full verification 和独立
审查全部通过为条件；live-provider smoke 尚未执行，没有 real key、real model result 或
billing receipt。

## Alternatives

### 只保存 inline Trace

无法跨 JVM 找回，也不能为真实模型、Harness 实验和故障排查提供 durable evidence。
拒绝。

### 先提交 Artifact，再单独保存 Run

实现简单，但存在 ghost success 的不可恢复窗口。拒绝。

### 把 HarnessRunBundle 当成产品 AgentRun

会迫使普通调用伪造 experiment、WorkingSelf 和 environment，也混淆产品 truth 与
Eval projection。拒绝。

### Bundle 只保存 `resultRef`

会产生 dangling reference，离线验证还必须访问另一个 Store。当前 Result 很小，
内嵌更简单且更强。拒绝；如果未来 Result 过大，再用 `resultRef + resultHash`
版本化演进。

### 采用 RFC 8785 JCS

它是可接受的标准方案，但当前纯 Java contracts module 没有 JSON dependency，
而 S2 只需要封闭 contract 的确定性编码。先采用小型、可 golden-test 的
length-prefixed profile；未来若要与第三方通用 JSON 工具互操作，再通过新
integrity profile 演进。

### 现在加入 Temporal、Agent Runtime 或真实模型

这些变量会掩盖 Run persistence 和 integrity binding 的缺陷。S2 保持
framework-free Fake baseline；S3/S4 再分别引入真实模型和 Harness 对照。拒绝。

## Safety, privacy and autonomy

- persistent Trace 是 safe projection，不是完整 transcript；
- Capture 原文和模型 reasoning 不进入 Trace；
- model 不能生成自己的 runId、principal、hash、resource binding 或 terminal
  lifecycle；
- Bundle 只记录真正参与本次运行的资源；
- 所有读取 owner-scoped，错误响应不形成 existence oracle；
- S2 没有外部动作、Connector、发布、credential 或新增权限；
- Artifact commit 继续由 deterministic Product Control Plane 决定；
- S2 未实现 retention、purge 或 export；真实数据进入该边界前必须另立 RFC，
  明确保留期限、删除语义与可验证导出流程。

## Validation

可证伪假设：

> 在不引入真实模型、Runtime framework 或外部动作的前提下，一次 Fake Agent
> Draft 可以形成 owner-scoped、跨 JVM 存活、无原文泄漏、可校验且可离线重执行的
> AgentRun evidence；任何 partial write、cross-run swap、Trace mutation 或
> cross-principal lookup 都不能被误报为成功。

Merge Gate：

- packaged JVM A → forced kill → JVM B 使用同一 PostgreSQL，Run/Trace/Bundle
  byte-stable 或 semantic-stable；
- Artifact insert 后、terminal Run update 前的故障注入使整个成功事务回滚；
- foreign/missing 同形 `404`，owned corruption generic `409`；
- Trace 和数据库列中不存在 Seed/Capture/CoT sentinel；
- exact Artifact version/hash 在 lineage 后续修订后仍可验证；
- 双线程/双 Store 同时完成同一个 Run 时恰好一个成功、一个固定 conflict；
- hostile Kernel 的 null、越界或非 allowlist outcome 被收敛为固定 terminal failure，
  不泄漏 metadata，也不遗留永久 `RUNNING`；
- Java/Node golden vectors 一致；
- frozen synthetic Task Pack 连续重执行得到相同 normalized fingerprint；
- full Maven、contracts、doc links、migration rehearsal 与 independent review 通过。

## Migration and rollback

这是 unpublished-v1 的 coordinated correction：

1. 先提交 Schema/Java/fixture/semantic Red；
2. Flyway V4 新增三张表，并为 Capture identity/request hash 增加 composite
   unique constraint；它不改写 V1–V3 的历史业务行；
3. populated V3 → V4 rehearsal 证明旧 truth 保持；
4. 应用必须等待 V4 成功后才 ready；
5. 如部署失败，回滚应用版本；V4 新表在没有成功 S2 writes 时可保留，不执行
   destructive down migration；
6. 已写入 S2 Run 后，不通过删除表回滚；恢复旧应用时新表保持只读，随后由修复版
   前滚。
