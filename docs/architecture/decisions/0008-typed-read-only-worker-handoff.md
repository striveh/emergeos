# ADR-0008：采用 bounded typed read-only Worker handoff

- Status: Accepted
- Date: 2026-07-31
- RFC: [RFC-0004](../../rfcs/0004-typed-read-only-worker-handoff.md)

## Context

EmergeOS 已有一个 provider-neutral `AgentKernel`、持久 AgentRun/Safe Trace 与
HarnessRunBundle，但一个 Model/Tool loop 不能表达可审计的 child Agent：

- 把 Worker 当 Tool 会隐藏 child Task、Run、profile、budget、usage 与失败状态；
- 只把 proposal hash 放进 Trace，进程重启后无法取回并验证 output；
- generic `HANDOFF` ref 不能证明 same-owner、terminal child、exact Bundle hash 或
  parent 实际消费的 proposal；
- 让 child 直接写 Artifact 会产生 shared writer 与 lineage ownership 冲突。

第一步需要验证 typed delegation 与 durable truth，不应同时引入通用 workflow graph、
parallel scheduling、Temporal、第三方 Agent runtime 或 live model。

## Decision

1. Pack007 只采用一个 server-owned、serial、synchronous、`depth=1`、
   read-only Worker。每个 parent 最多一个 child；child 不能再委派，也不能写
   Artifact、Action、Receipt、Checkpoint 或 Handoff。
2. Model 只能提出 typed `WorkerCall`。principal、child identity、policy/state/context、
   tool registry、environment、budget、deadline 与 profile 均由 server-owned
   composition/runtime 继承、收缩和验证，不接受 Model 自报。
   Model 的有效 Tool authority 是 exact registry 与 Task `requiredTools` 的交集：
   parent Task 固定为空，只有 child Task 获得 `capture.read`。合法 handoff 不会把
   child Tool authority 反向授予 Conductor。
3. Core 以 provider-neutral `AgentWorkerRuntime` 暴露 two-phase seam：
   `prepare(...)` 只规划与验证，`Prepared.execute()` one-shot 执行。Kernel 在两阶段
   之间重新观察 cancellation/deadline；product composition 对 Worker
   registry/profile identity mismatch fail-fast，并且不创建伪 terminal Run。
4. child output 使用 `WorkerResultEnvelope` 独立持久化，不是用户 Artifact。
   child Bundle 通过 `WORKER_RESULT` binding 绑定 exact result hash；parent Bundle
   再以 `HANDOFF` binding 绑定 exact child Run/Bundle。parent verified consume 后仍是
   唯一 Artifact writer。
5. V6 在 PostgreSQL 中增加 `agent_worker_results` 与 parent/child typed relation。
   composite foreign keys、unique constraints、deferred graph guards 与 immutable
   triggers共同约束 same-owner、terminal child/parent、exact hashes、single-consume、
   exact one child 和 no cycle/reuse。不能证明 lineage 的 legacy generic Handoff 或
   orphan child migration 必须 fail-fast，不能猜测。带 Worker capability 的 root
   parent 在 INSERT 前必须验证 `requiredTools=[]` 等 exact profile；root verified
   read（包括 `RUNNING`）再次校验，非法 admission 不留 row，durable tamper
   fail-closed。
6. child terminal + WorkerResult commit 和 parent consume/Artifact commit 是两个明确
   transaction boundary。两者之间进程死亡时，允许持久观察到 child terminal、
   WorkerResult 已存在而 parent 仍为 `RUNNING`；当前不会自动 resume，也不把它改写成
   success。
7. runtime 对 child observation 使用固定 precedence：

   ```text
   child identity / usage validity
   → aggregate subtree budget
   → strict post-child deadline
   → verified child terminal status
   → accept WorkerResult
   → post-success cancellation / 下一步 deadline exhaustion
   ```

8. Trace 只保存 allowlisted identity、status、usage、refs 与 hashes；Bundle 会内嵌
   bounded Task fields，包括 server-owned Task intent。两者都不保存 provider raw
   prompt/response、Capture/WorkerResult 正文、Tool raw result、exception 或 hidden
   reasoning。公开 Pack/fixtures 只允许 literal、PUBLIC、synthetic content。
   non-success Trace 原则上必须显式进入 terminal；只有可证明的 cancellation、达到
   deadline 的 exhaustion、耗尽全部 model steps，以及紧跟 completed Model step 的
   unsupported decision 可以 implicit 结束。
9. 继续保持 framework-free baseline：Core/Agent Loop 不依赖 Spring、PostgreSQL、
   provider SDK、Temporal、Pi、AgentScope 或其他上层 Agent runtime。以后选择 runtime
   必须以同一 Pack 和更强生产约束做可归因比较。

## Consequences

- parent → child → WorkerResult → proposal → Artifact 形成可持久核验的 hash chain，
  且 child 的权限、状态、用量与失败不再隐藏在一次 Tool call 中。
- parent Conductor Model 在 handoff 前后都不能直接调用 `capture.read`；parent
  application service 仍可用 owner authority 做 preload/evidence read，所以当前边界
  不是独立进程或独立 service identity 的 capability isolation。
- context-policy drift 可以在 child Run、Model、Tool 与 delegated read 前
  fail-closed；runtime/profile mismatch 则在 composition/startup 直接失败。
- child crash gap 是可解释的 incomplete truth，但不是 checkpoint、resume、lease、
  fencing 或 durable workflow。
- 当前 API 没有公开 WorkerResult content endpoint；owner-scoped Store 与 typed
  bindings 是内部持久真相。
- schema/contract surface 增大，任何未来 parallel/general multi-agent 设计都必须以新
  contract 明确 graph ownership、aggregate budget、cancellation、recovery 与 concurrency，
  不能把本 ADR 的 one-child 约束静默放宽。

## Evidence and validation

- Pack007 以 exact path、bytes、raw SHA、shape、component versions、receipts 与 hashes
  冻结 control/context-policy-drift 两个 case；Java 两个 fresh runner exact-equal，
  Node semantic validator 独立交叉验证。
- contract graph 固定 parent `requiredTools=[]` 与 child
  `requiredTools=[capture.read]`；恶意 Conductor 在 handoff 前或成功 handoff 后直接
  请求 `capture.read` 都在 validation/execute 前被拒绝。
- PostgreSQL 回归固定非法 direct-tool Worker root 在首次 admission 后
  `agent_runs=0`，并固定 direct SQL 篡改后的 `RUNNING` root 在 verified read
  以 integrity failure 结束。
- Trace Red 固定 accepted Handoff 后伪造
  `MODEL_STEP_FAILED / AGENT_KERNEL_FAILED` 且无 terminal event；Green 后只有
  `latencyMs >= deadlineMs` 与 `modelSteps == maxModelSteps` 等真实 implicit boundary
  可通过。产品 sanitizer 将伪 outcome 归一为 `FAILED / UNSAFE_AGENT_TRACE`，
  清空 Handoff refs 且不创建 Artifact。
- control 形成两个 terminal Runs、一个 child Tool execute、一个 durable
  WorkerResult、一个 parent `HANDOFF` 与一个 parent-only Artifact。
- drift fault 只有 parent preload read；child Run/Model/Tool/delegated read、
  WorkerResult、`HANDOFF` binding 与 Artifact 均为 0。
- V6 覆盖 fresh/populated upgrade、legacy fail-fast、cross-owner、missing/running、
  wrong hash、self/cycle/reuse/tamper、late rollback、concurrent consume 与 moved-Handoff
  OLD+NEW owner guard。
- packaged success 与真实 process-kill crash gap 都由两个 fresh JVM verified read；
  creator/writer 停止后，十张 product-truth 表与 Flyway history 的 PK/`xmin` snapshot
  在每次 read-back 后保持不变。该 snapshot 证明没有遗留的 durable row-set/row-version
  变化，不是 SQL audit、DDL/sequence 证明或 literal zero-write 证明。
- 完整 commands、计数、审查结论与 nonclaims 见
  [Pack007 Build Note](../../operations/build-notes/2026-07-31-s2-s4-typed-read-only-worker-handoff.md)。

## Rollback or migration

V6 是 forward-only additive migration。若关闭 Worker capability，可移除 product
composition 中的 Worker registry/runtime，让普通 API fail-fast 或回到明确的旧
single-Agent Fake 路径；不能把 typed Worker 降级成普通 Tool 并继续复用已有
`HANDOFF` truth。

已写入 V6 的 parent/child graph、WorkerResult 与 bindings 必须继续由兼容 reader 保留和
验证。回滚 binary 前需要先证明旧代码不会误读或覆盖这些 rows；仓库不提供 generic down
migration。

未来引入 parallel Worker、write-capable Worker、live model、checkpoint/resume 或
distributed workflow runtime 时，必须新增 RFC/ADR 与故障证据，并显式 supersede 本
ADR 的适用部分。
