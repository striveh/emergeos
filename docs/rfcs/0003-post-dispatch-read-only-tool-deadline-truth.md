# RFC-0003：post-dispatch read-only Tool deadline truth

- Status: Accepted
- Author: project owner + main Codex agent
- Created: 2026-07-31
- Discussion: Stage 2 S4/F2 Pack 006

## Problem

当前 `AgentLoopKernel` 在同步 `capture.read` 返回后，先把结果记录成
`TOOL_RESULT / SUCCEEDED`，到下一轮 Model 前才重新检查 Task deadline。若 Tool
已经完成读取，但此时单调时钟已越过 deadline，系统会产生两组互相冲突的事实：

1. Kernel Trace 声称拿到了成功 Tool Result，并把 reference 加入 obtained Evidence；
2. `AgentDraftService`、`AgentRun` 与 `HarnessRunBundle` 又都拒绝
   `latencyMs > task.deadlineMs()`，因此把真实 outcome 改写成
   `UNSAFE_AGENT_OUTCOME`，或直接拒绝构造 terminal aggregate。

这会同时丢失 Tool 已 dispatch、实际 elapsed time、原 failure attribution 与 safe
Trace。它也可能让排障者把“late result 不可信”误读成“Tool 从未执行”。

当前 exact `agent-tools-v2` registry 只允许 trusted、read-only `capture.read`。本 RFC
只解决该边界，不把相同终态套到 write-capable Tool。写型 Tool 在 dispatch 后失去确定
结果时必须使用独立的 `UNKNOWN + reconciliation` action contract。

## Proposal

### 1. deadline 是成功接受边界，不是失败观测值的截断器

`latencyMs` 继续表示 Kernel 用单调时钟观测到的实际 elapsed time，并继续受共享
24 小时 domain 上限约束：

- `SUCCEEDED` 必须满足 `latencyMs <= task.deadlineMs()`；
- 任意 non-success 可以诚实保留 `latencyMs > task.deadlineMs()`；
- 不允许 clamp、清零或把真实 failure 改写为 `UNSAFE_AGENT_OUTCOME`；
- Java contract、Core aggregate、Bundle 与 Node semantic validator 使用同一规则。

这不是放宽成功 SLO。它只是承认 cooperative boundary 只能在调用返回或抛错后观察
deadline；失败回执可能天然晚于 deadline。

### 2. post-dispatch late completion 使用独立 typed truth

当 registered、Task-declared、authority-checked 的 read-only Tool 已进入同步
`execute`，而 Kernel 在其返回或抛错后首次发现 elapsed time **严格大于**
deadline 时，终态固定为：

```text
RunStatus.FAILED
failureReason = TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH

MODEL_STEP(COMPLETED)
→ TOOL_REQUEST(REQUESTED, authorized-ref)
→ TOOL_REJECTED(DEADLINE_EXCEEDED, authorized-ref)
```

`TOOL_REJECTED / DEADLINE_EXCEEDED` 表示 Tool 已 dispatch，但 late completion 不再
被接受为可信 Tool Result。它不是 pre-dispatch schema rejection，也不声称执行被强制
中断。

`elapsed == deadline` 不属于 exceeded。此时合法 Tool Result 可以被接受并形成
Evidence；Kernel 到达下一次 cooperative boundary 时，因为 remaining time 为 0，
以普通 `FAILED / DEADLINE_EXHAUSTED` 结束，不再调用 Model，也不提交 Artifact。
typed `TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH` 只有在
`status == FAILED && latencyMs > deadlineMs` 时才合法。

该路径必须：

- 在 `execute` 返回或抛错后、校验/接受 Tool Result 前检查 deadline；
- 不写 `TOOL_RESULT / SUCCEEDED`；
- 不把 reference 加入 obtained Evidence 或 resource bindings；
- 不再调用 Model；
- 不提交 Artifact；
- 保留 Model attribution、usage 与实际 latency；
- 将 Trace status 与 failure reason 双向绑定，防止任意 Run 伪造
  post-dispatch deadline attribution。

### 3. post-execute canonical precedence

在一次同步 read-only Tool 返回或抛错后的同一个 observation boundary，采用以下
canonical attribution：

```text
strict deadline exceeded
> Tool result / exception attribution
> cancellation observed at the next cooperative boundary
```

- late completion 与 cancellation 同时可见时，以 post-dispatch deadline failure
  作为 canonical terminal attribution；这不声称 cancellation 没有发生；
- `elapsed <= deadline` 且 Tool 返回合法结果时，先保留
  `TOOL_RESULT / Evidence`；若只观察到 cancellation，则在下一 Model 前以
  `CANCELLED` 结束；
- deadline、cancellation 任一路径一旦决定 terminal，都不能产生后续 Model、Tool
  或 Artifact；
- 该 precedence 只冻结当前 synchronous、trusted、read-only registry。未来异步
  cancellation 或 write-capable Tool contract 可以通过新 RFC/ADR 取代它。

### 4. 当前能力边界

这个实现仍是 synchronous/cooperative detection：

- 它不能在 deadline 瞬间抢占线程；
- 它不能证明 Tool 在 deadline 前没有开始或完成部分工作；
- Pack 006 的 read counter 预期为 1，正是“已 dispatch”的证据之一；
- 当前 exact registry 只有 read-only `capture.read`，因此 late read 可以安全失败；
- 在引入 write-capable Tool 前，必须先设计 durable `UNKNOWN`、idempotency、
  reconciliation、lease/fencing 与 Receipt 语义；当前 Agent Loop 不承载该能力。

## Alternatives

### 保持 `latencyMs <= deadlineMs` 对所有终态成立

会迫使失败路径清零、截断或改写 outcome，丢失真实 elapsed time 与 failure
attribution。拒绝。

### 把 late result 继续记为 `TOOL_RESULT / SUCCEEDED`

这会让不应再进入 Model context 的 late value 成为 Evidence，并把 deadline failure
推迟到下一轮。拒绝。

### 只记录 `TOOL_REQUEST` 后直接失败

当前 `TOOL_REQUEST` 在 Tool limit check 前产生，单独出现不能证明真正 dispatch；
还会形成未配对 request。拒绝。

### 立即加入异步执行和强制 cancellation

会同时引入 executor ownership、线程泄漏、interrupt safety、Tool cooperation 与
write-side uncertainty 等新变量，掩盖当前 truth bug。先冻结 cooperative semantics，
后续再以独立 fault slice 决定。延期。

## Safety, privacy and autonomy

- Trace 只记录已声明 Tool name、已授权 ref 与固定 status/reason，不记录 arguments、
  raw/late result、Capture content、exception 或 hidden reasoning。
- late `capture.read` 不产生 Evidence binding 或 Artifact。
- exact-boundary 或 cancellation-only control 可以保留已验证的 read result；这与
  strict over-deadline fault 的“零 Evidence”是不同事实。
- 不读取 credential、不访问 live provider、不增加外部动作权限。
- write-capable Tool 明确排除，不能用本 RFC 的 `FAILED` 终态掩盖未知外部副作用。

## Validation

可证伪假设：

> 在相同 synthetic Task、Capture、Fake Model、Tool、预算和组件版本下，只让
> read-only Tool 在 execute 中推进 frozen monotonic clock 越过 deadline，系统会保留
> 一次真实 Tool dispatch/read，却不接受 late Tool Result、不再调用 Model、不提交
> Artifact，并形成 Result、terminal AgentRun、Trace 与 HarnessRunBundle 一致的
> post-dispatch deadline truth。

Pack 006 Acceptance Red 必须先观察当前错误：

- Model 1、validation 1、Tool execute 1、Tool-backed read 1；
- 旧 Trace 出现 `TOOL_RESULT / SUCCEEDED`；
- product path 将真实 failure mask 为 `UNSAFE_AGENT_OUTCOME`，或无法构造 aggregate。

Green 必须固定：

- Model 1、validation 1、Tool execute 1、Tool-backed read 1、Artifact 0；
- `FAILED / TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH`；
- Trace 为
  `MODEL_STEP → TOOL_REQUEST → TOOL_REJECTED(DEADLINE_EXCEEDED)`；
- Evidence/resource bindings 为空；
- `latencyMs > deadlineMs` 在 Result、AgentRun 与 Bundle 中 exact-equal；
- 两个 fresh fixture 得到完全相同 observation；
- 成功 over-deadline、status/reason/Trace mismatch、late malformed/null/throwing
  Tool 与 privacy sentinel 都有 adversarial evidence；
- exact boundary 固定为 `TOOL_RESULT` 后 `DEADLINE_EXHAUSTED`，而不是 typed
  exceeded；simultaneous late+cancellation 与 cancellation-only control 固定上述
  precedence；即使 Tool call 占用最后一个允许的 Model step，也必须先执行这次
  post-result boundary，不能落入 `MODEL_STEP_LIMIT_EXHAUSTED`；
- focused、contracts、full Maven、doc links 与独立 review 全部 Green。

## Migration and rollback

当前仓库版本尚未对外承诺 contract compatibility，数据库 schema 不变；
`latency_ms` 已允许共享 24 小时 domain 内数值，变更集中在 Java/Node cross-field
verifier 与 Trace status allowlist。

实验已形成一致 truth，因此本 RFC 转为 `Accepted`；最终决定见
[ADR-0007](../architecture/decisions/0007-observed-latency-and-post-dispatch-tool-deadline.md)，
它只 supersede ADR-0006 中“所有 Result latency 不得超过 Task deadline”的部分，
不改写历史决策。

若后续实现不能继续维持该 truth，应通过新 RFC/ADR 迁移或回滚相关实现；不得通过放宽
Trace pairing、删除 latency 或把失败清零来获得 Green。
