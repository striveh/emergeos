# ADR-0007：保留 observed latency，并区分 post-dispatch Tool deadline truth

- Status: Accepted
- Date: 2026-07-31
- Supersedes: ADR-0006 Decision 11 中“所有 Result latency 不得超过 Task deadline”的部分
- RFC: [RFC-0003](../../rfcs/0003-post-dispatch-read-only-tool-deadline-truth.md)

## Context

当前 `AgentLoopKernel` 对 Tool 使用 synchronous/cooperative execution。deadline
只能在一次调用返回或抛错后再次观察，因而失败回执可能晚于 Task deadline。旧 contract
却要求所有状态的 `latencyMs <= deadlineMs`，导致 product sanitizer、`AgentRun` 和
`HarnessRunBundle` 清零或改写真实 failure。

这既丢失 actual elapsed time，也会把“Tool 已 dispatch、late result 未被接受”误报成
`UNSAFE_AGENT_OUTCOME`。当前 exact `agent-tools-v2` registry 只有 trusted、
read-only `capture.read`，适合先冻结这条最小 truth；write-capable Tool 不适用。

## Decision

1. `latencyMs` 是单调时钟观测到的实际 elapsed time，并继续受共享 24 小时 domain
   上限约束。
2. `SUCCEEDED` 必须满足 `latencyMs <= task.deadlineMs()`；所有 non-success 可以保留
   `latencyMs > deadlineMs()`，不得 clamp、清零或仅因 latency 改写 failure。
3. read-only Tool 已进入 `execute`，返回或抛错后观察到
   `elapsed > deadline` 时，固定形成：

   ```text
   FAILED / TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH
   MODEL_STEP(COMPLETED)
   → TOOL_REQUEST(REQUESTED)
   → TOOL_REJECTED(DEADLINE_EXCEEDED)
   ```

   late result、exception、null 或 wrong-reference 都不成为 Tool Result、Evidence、
   binding 或 Artifact。
4. `elapsed == deadline` 不是 exceeded。合法结果先成为
   `TOOL_RESULT / Evidence`，随后在下一 cooperative boundary 以普通
   `DEADLINE_EXHAUSTED` 结束。
5. post-execute canonical precedence 为：

   ```text
   strict deadline exceeded
   > Tool result / exception attribution
   > cancellation observed at the next cooperative boundary
   ```

   late 与 cancellation 同时可见时采用 typed deadline attribution，但不声称
   cancellation 未发生。只有 cancellation 且结果仍 within deadline 时，保留结果，
   再于下一 Model 前以 `CANCELLED` 结束。post-result boundary 即使位于最后一个允许的
   Model step 后也必须执行，不能被 loop exhaustion 覆盖。
6. Java contracts/Core/Kernel 与 Node semantic verifier 必须使用同一 strict `>`
   predicate，并把 typed failure、Run status、latency 与
   `TOOL_REJECTED / DEADLINE_EXCEEDED` 双向绑定。
7. write-capable Tool 在 dispatch 后结果不确定时必须走独立的 durable
   `UNKNOWN + reconciliation` contract；不能套用本 ADR 的 read-only `FAILED`。

## Consequences

- terminal Result、AgentRun、Trace 与 HarnessRunBundle 能保存同一真实 failure，
  provider/model attribution、usage 和 latency 不再被 sanitizer 抹掉。
- strict late path 不产生后续 Model、Tool 或 Artifact，但 Tool 的 read counter 可以为
  1，因为 dispatch 已经发生。
- exact-boundary 与 cancellation-only control 可以保留已验证 Evidence；它们不能与
  over-deadline fault 混为一谈。
- 当前实现仍不能在 deadline 瞬间抢占线程，也不证明 Tool 没有完成部分工作。
- 数据库无需 migration：现有 `latency_ms` domain 已容纳共享 24 小时范围，failure
  与 Trace status 不是数据库 enum。

## Evidence and validation

- Pack 006 用相同 Task、Capture、Fake Model、Tool、预算与 registry，只改变 Tool
  推进 frozen monotonic clock 的时长。
- control 在 4ms 内成功；fault 在 7ms 才返回，Task deadline 为 5ms。
- late valid/throw/null/wrong-reference matrix 都归因到同一 typed deadline truth。
- exact `5ms / 5ms`、simultaneous late+cancellation 与 cancellation-only 都有独立
  regression。
- Java、Node correct-hash fixtures、PostgreSQL round-trip/fresh store read 与 full
  reactor 共同验证 contract、persistence 和 regression。
- 完整计数器、hash、commands、审查结论与非声明见
  [Pack 006 Build Note](../../operations/build-notes/2026-07-31-s2-s4-post-dispatch-deadline.md)。

## Rollback or migration

本次无 schema migration。若回滚应用代码，新的合法 over-deadline terminal rows 会被
旧 verifier 拒绝，因此必须先发布兼容 reader 或迁移这些 rows；不能直接部署旧 binary
并假定向后兼容。

未来若引入 async preemption、interrupt-aware Tool 或 write-capable execution，必须先
以新 contract 定义 ownership、cancellation、`UNKNOWN`、idempotency、fencing、
reconciliation 与 Receipt，再显式 supersede 本 ADR 的适用部分。
