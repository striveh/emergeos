# Build Note：S4/F2 read-only Tool post-dispatch deadline truth

- Change class：`R`（Agent Runtime / failure attribution / durable truth）
- Status：Engineering Green
- Code commits：`d7ff1cd`、`b4f8a9c`
- Date：2026-07-31

## Outcome

EmergeOS 现在能诚实区分三件事：

1. 下一步还没启动，deadline 已经 exhausted；
2. read-only Tool 已 dispatch，但返回时已经严格越过 deadline；
3. Tool 恰好在 deadline boundary 返回，结果合法，但下一步已没有时间。

Pack 006 证明第二种情况不会被写成成功 Tool Result，也不会被
`UNSAFE_AGENT_OUTCOME` 掩盖。系统保留一次真实 dispatch/read、Model attribution、
usage 与 actual latency，同时拒绝 late value 进入 Evidence、resource binding 或
Artifact。

## Why this matters

synchronous/cooperative Agent Runtime 不能在任意 Tool 内部瞬间停住时间。旧 contract
要求所有 Result 的 `latencyMs <= deadlineMs`，使真正晚于 deadline 才能观察到的失败
无处表达：

```text
Tool 已 dispatch/read
→ late result 被 Kernel 暂时接受
→ 下一 boundary 才发现 deadline
→ product sanitizer/aggregate 拒绝 latency
→ failure 被改写成 UNSAFE_AGENT_OUTCOME 或无法持久化
```

这会同时损坏 execution truth、费用归因与排障能力。deadline 应是 success acceptance
boundary，而不是 non-success observation 的截断器。

## Adopted contract

[RFC-0003](../../rfcs/0003-post-dispatch-read-only-tool-deadline-truth.md) 与
[ADR-0007](../../architecture/decisions/0007-observed-latency-and-post-dispatch-tool-deadline.md)
已接受以下规则：

- `SUCCEEDED` 必须满足 `latencyMs <= deadlineMs`；
- non-success 可以保留共享 24 小时 domain 内的真实 over-deadline latency；
- typed `TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH` 只有在
  `FAILED && latencyMs > deadlineMs` 时合法；
- Trace 必须与 typed failure 双向绑定为
  `TOOL_REJECTED / DEADLINE_EXCEEDED`；
- Java contracts、Core aggregate、Kernel 与 Node semantic validator 使用相同
  strict `>` policy。

shared `ObservedExecutionLimits` 防止 `AgentDraftService`、`AgentRun`、
`HarnessRunBundle`、Kernel 与 `AgentTraceProtocol` 各自复制并漂移判断。

## Runtime order and precedence

read-only Tool 已形成 typed、authorized one-shot execution 后，顺序固定为：

```text
TOOL_REQUEST
→ synchronous execute
→ observe elapsed time
→ strict deadline attribution
→ validate/accept Tool result
→ observe next cooperative cancellation/deadline boundary
```

post-execute canonical precedence：

```text
strict deadline exceeded
> Tool result / exception attribution
> cancellation observed at the next cooperative boundary
```

- `elapsed > deadline`：late valid/throw/null/wrong-reference 都形成 typed deadline
  failure；无 Tool Result、Evidence、后续 Model 或 Artifact。
- `elapsed == deadline`：合法 Tool Result/Evidence 保留；下一 boundary 以普通
  `DEADLINE_EXHAUSTED` 结束。
- late 与 cancellation 同时可见：deadline 是 canonical attribution，但这不声称
  cancellation 没发生。
- within-deadline valid result 与 cancellation：结果/Evidence 保留，下一 Model 前
  `CANCELLED`。

## TDD and adversarial evidence

Acceptance Red 首先复现了旧错误：

- Model 1、validation 1、Tool execute/read 1、Artifact 0；
- Trace 却出现 `TOOL_RESULT / SUCCEEDED`；
- product path 把真实 deadline failure 改写为 `UNSAFE_AGENT_OUTCOME`。

minimum Green 在 `execute` 返回或抛错后、接受 Tool Result 前加入 strict
deadline observation，并放宽 non-success actual latency 的 aggregate contract。
独立审查随后产生并关闭：

- exact equality 被错误归因为 exceeded；
- typed reason 可以在 `latency == deadline` 伪造；
- late valid/throw/null/wrong-reference precedence 不一致；
- deadline 与 cancellation 同时出现时没有 canonical attribution；
- Tool Result 位于最后一个允许的 Model step 时，`continue` 会跳过 post-result
  cooperative boundary，并错误落入 `MODEL_STEP_LIMIT_EXHAUSTED`；
- shared Java policy 在 Kernel/Trace 又复制，存在 drift；
- Eval Runner 的旧 regression 仍期待 sanitizer 清零已归因 usage。

最后一项现在固定为：provider response 已完成 attribution 后才到达 pre-dispatch
deadline boundary，Run 以 `FAILED / DEADLINE_EXHAUSTED` 结束，并保留 31,000ms、
resolved Model、`$0.000165 / 120 tokens`、单个
`MODEL_STEP / COMPLETED`；run/observed metering 一致，Evidence 与 Artifact 为空。

## Pack 006 receipt

control 与 fault 的 Task、Capture、合法 Tool arguments、Fake Model、预算、
registry、组件版本和全部输入完全相同，只改变 `capture.read` completion latency：

| Receipt | 4ms control | 7ms fault |
|---|---:|---:|
| Task deadline | 5ms | 5ms |
| Model calls | 2 | 1 |
| Tool validations | 1 | 1 |
| Tool executes | 1 | 1 |
| Tool-backed reads | 1 | 1 |
| Run starts / completes | 1 / 1 | 1 / 1 |
| Artifact | 1 | 0 |
| Evidence refs | 1 | 0 |
| Resource bindings | 2 | 0 |
| Terminal status | `SUCCEEDED` | `FAILED` |
| Failure reason | null | `TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH` |

fault safe Trace 固定为：

```text
MODEL_STEP(COMPLETED)
→ TOOL_REQUEST(REQUESTED)
→ TOOL_REJECTED(DEADLINE_EXCEEDED)
```

两个 case 都在两个 fresh fixture 中执行，并比较完整 terminal observation。

### Frozen identity

- Pack bytes：`6,451`
- Pack raw SHA-256：
  `ada80a0f05bffb907408cb9d877b039dbb46253ab09904404570f4c721b53f22`
- shared Task hash：
  `da267ac640654eeae9e83e485a90f4cfd9496ea2ab95a4e3358f29b002696489`
- control Trace root：
  `e3e72b87f00047a6a4c455b5ffe3698d75619d0174550359e88f62a7db8f18a9`
- control Bundle：
  `ebb982e652777f176b3ae1786ca4a28d066eb58e82e21aaacfebbc08bdc45451`
- fault Trace root：
  `d081324187b38e962ca14876a1b4fa9da762cdbb0823e90d44dc061763edadd0`
- fault Bundle：
  `71ce0d367459bbe5a55f1da713d9311c71ba06f33815f05b8f5017b3b9438f3a`

Node validator 固定 exact path、byte count、raw hash、top-level shape、provenance、
control/fault counters、Trace statuses 与 hashes。Java
`OfflineToolDeadlineFaultTest` 的 privacy regression 同时拒绝完整 Capture content
和子串 sentinel 出现在 persistent projection。

## PostgreSQL receipt

真实 Testcontainers PostgreSQL round-trip 保存并由 fresh
`PostgresAgentRunStore` 重新读取：

- lifecycle `FAILED`；
- failure `TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH`；
- Task deadline 5ms、actual latency 7ms；
- 三条 Trace：
  `MODEL_STEP → TOOL_REQUEST → TOOL_REJECTED`；
- statuses：
  `COMPLETED → REQUESTED → DEADLINE_EXCEEDED`；
- Artifact、Evidence 与 resource bindings 全部为 0；
- Result、Trace root 与 Bundle hash 经 verified read 后保持一致。

V4 `latency_ms` 已允许共享 24 小时 domain，failure 与 Trace status 使用有界文本而非
database enum，因此本切片无需 Flyway migration。测试使用 generic product fixture
验证 persistence semantics，不冒充 Pack 006 本身的 exact DB replay identity。

## Verification

可复制命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/inmemory,adapters/postgres,apps/eval-runner -am \
  -Dtest=HarnessRunBundleConsistencyTest,AgentRunInvariantTest,AgentTraceProtocolDeadlineTest,AgentLoopKernelModelSessionTest,OfflineToolDeadlineFaultTest,PostgresAgentRunStoreTest,SyntheticEvalLoopbackTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
./mvnw --batch-mode --no-transfer-progress clean verify
git diff --check
```

Focused：

```text
HarnessRunBundleConsistencyTest      11
AgentTraceProtocolDeadlineTest        5
AgentRunInvariantTest                 8
AgentLoopKernelModelSessionTest      24
OfflineToolDeadlineFaultTest          1
PostgresAgentRunStoreTest            22
SyntheticEvalLoopbackTest             7
                                      --
                                      78 Green
```

Contracts：

```text
5 JSON Schemas
37 fixtures
2 cross-language Task hash vectors
6 synthetic Task Packs
2 frozen environments
```

Full：

```text
Contracts              28
Core                   87
Agent Loop             24
OpenAI Adapter         19
In-memory              26
PostgreSQL             46
API                    32
Eval Runner            65
Offline Harness        83
                      ---
                      410 tests
```

`./mvnw --batch-mode --no-transfer-progress clean verify` 的 10 个 reactor modules
全部 `SUCCESS`，共 410 tests、75 个 XML report files，0 failure、0 error、
0 skipped，总耗时 `01:20 min`。

`./scripts/verify-contracts.sh`、78 个 Markdown files 的 local-link verification 与
`git diff --check` 均为 Green。

## Independent review

本切片使用单 writer + 多个 read-only reviewer。reviewer 已分别检查 runtime/Pack、
cross-language policy、PostgreSQL round-trip 与 full-build regression。第一次最终
source review 又发现：最后一个允许的 Model step 接受 Tool Result 后，loop exhaustion
会绕过 post-result cancellation/deadline boundary。两个 `maxModelSteps=1` tests 先
Red，再由 `b4f8a9c` 修复并完成 focused/full Green。最终 post-fix source review
结论为 `P0=0、P1=0、P2=1`。

保留的 P2 已进入上节 limits：read-only 目前由 exact `agent-tools-v2` manifest
membership 保证，而不是 `AgentTool` effect type；它不阻塞当前只有
`capture.read` 的切片，但新增 write-capable Tool 前必须先关闭。

## Failure and limits

- 这是 cooperative detection，不是 hard timeout、async preemption 或 thread
  interruption；hang Tool 仍可能占住执行线程。
- 当前 exact registry 只有 trusted、read-only `capture.read`。Tool implementation
  identity、第三方 sandbox 与 supply-chain trust 尚未落地；read-only 目前由 exact
  manifest membership 保证，不是 `AgentTool` 类型系统中的 effect classification。
  新增 write-capable Tool 前必须先引入显式 effect/deadline policy guard。
- write-capable Tool dispatch 后结果未知时必须进入 durable
  `UNKNOWN + reconciliation`，并需要 idempotency、lease/fencing 和 Receipt；不能
  套用本切片的 `FAILED`。
- PostgreSQL test 的 global zero-count 依赖 `@BeforeEach TRUNCATE`，当前不并行；
  后续并行化前应改为 run-scoped assertions。
- 本切片没有读取 credential、访问外网、live provider、Connector 或真实用户数据；
  不证明模型质量、用户价值、真实 Seed、复用、报价、付款或收入。

## Product and engineering meaning

这次增量让“系统知道自己什么时候没有可靠结果”更接近生产要求。Agent 产品的可信度
不只来自成功率，也来自能否区分：

- 未开始；
- 已 dispatch；
- 有合法结果；
- 结果太晚而不能继续使用；
- 外部副作用未知、必须对账。

Pack 006 只完成其中 read-only late-result 一格。下一条 fault slice 必须继续增加真实
产品能力，而不是把这一格扩大成并不存在的自治声明。
