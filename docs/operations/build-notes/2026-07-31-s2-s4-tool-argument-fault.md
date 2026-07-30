# Build Note：S4/F1 Tool arguments pre-dispatch fault

- Change class：`R`（Agent Runtime / safety boundary）
- Status：Engineering Green
- Code commit：`33d1b9f`
- Date：2026-07-31

## Outcome

Agent Loop 现在不会让 Model 或 provider adapter 直接决定 `capture.read` 的可执行参数。
Model 只能交付有界、不可变、redacted 的 raw arguments；Tool 在独立、无副作用的
validation phase 中解析 schema，registry 与 Task authority 通过后，才生成一次性
typed dispatch。

Pack 005 已证明：当合法 `reference` 旁多出一个 `unexpected` 字段时，Run 保留一次
可归因的 Fake Model step，并如实完成 `RUNNING → FAILED`，但不会执行 Tool、不会发生
Tool-backed Capture read，也不会创建 Artifact。

## Why this matters

“Model 发出 Tool call”不等于“系统已经授权并执行 Tool”。如果 arguments schema
散落在 OpenAI 等 provider adapter 中，不同 adapter 会用不同规则归一化输入，
duplicate key、unknown field 或 malformed shape 也可能在进入统一安全边界前消失。

这次改动把职责重新分开：

```text
provider response
  → bounded opaque ToolArguments
  → Model attribution / usage / budget
  → Tool-owned pure validation
  → registry manifest + Task allowlist + Task input refs
  → one-shot typed execute
  → verified ToolResult
```

## Architecture delta

- `AgentModel.ToolArguments`
  - 最多 65,536 UTF-8 bytes；
  - defensive copy、value equality；
  - 拒绝 NUL 与 lone surrogate；
  - `toString()` 固定 redacted；persistent projection 不保存或打印 raw JSON。
- `AgentTool<A>`
  - 明确拆成 `validate(TaskEnvelope, ToolCall)` 与
    `execute(TaskEnvelope, A)`；
  - execute 只接受 typed `ValidatedArguments`。
- `CaptureReadTool`
  - 使用仓库实际依赖 Jackson 3.1.4；
  - Tool-specific 上限收紧到 1,024 bytes；
  - 拒绝 duplicate keys、trailing tokens、unknown/missing/wrong-type 字段；
  - reference 与 durable contract 统一为
    `capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}`。
- `AgentToolRegistry`
  - `agent-tools-v2` 是 exact manifest，不是一个可随意填写的 label；
  - 当前精确绑定
    `capture.read → urn:emergeos:tool:capture-read-arguments:v1`；
  - 缺失、额外 Tool、schema drift 或 Task registry mismatch 都 fail fast；
  - validated reference 必须属于 server-owned `Task.inputRefs`；
  - prepared execution 只能调用一次。
- `AgentLoopKernel`
  - 未注册或未被 Task 声明的 Tool 在 validation 前直接 `BLOCKED`；
  - 对 registered + declared call，registry 完成 validation 与 input-ref authority
    检查后，Kernel 在接受 rejection 或写入 `TOOL_REQUEST` 前再次检查
    cancellation/deadline；
  - 该 cooperative check 不是与同步 `execute` 原子化的强制中断；
  - schema-invalid 结束为
    `FAILED / TOOL_ARGUMENTS_INVALID`；
  - safe Trace 为 `MODEL_STEP → TOOL_REJECTED`，拒绝事件不携带 model-controlled
    reference 或 raw arguments。
- OpenAI adapter
  - 只核验 provider function-call envelope/call ID；
  - 不再提前解释具体 Tool schema；
  - raw function arguments 原样进入统一 Tool validation。

实际顺序是：

```text
exact manifest/version（打开 Model session 前）
→ Model accounting
→ registered + requiredTools pre-gate
→ Tool validation
→ input-ref authority
→ cancellation/deadline recheck
→ rejection 或 TOOL_REQUEST
→ Tool limit
→ execute
```

## TDD and adversarial evidence

第一轮 vertical Red 要求 production
`AgentDraftService → AgentLoopKernel → AgentToolRegistry → CaptureReadTool`
在 extra-property fault 下保持零 Tool dispatch。随后独立审查又形成两组可执行 Red：

- 空 registry 仍能自称 `agent-tools-v2`；
- `capture.read` regex 接受前导 `._:-` 和 colon，却拒绝 durable contract 合法的 `~`。

修复后：

- 空 v2 registry 在构造时被拒绝；
- `capture://.bad`、`_bad`、`-bad`、`:bad`、`a:b` 都在 CaptureStore 前失败；
- declared `capture://capture~agent-kernel` 能完成 Tool read 与成功 Run；
- validation 中出现 cancellation/deadline 时，终态分别保持
  `CANCELLED` / `DEADLINE_EXHAUSTED`，不会被参数错误覆盖。

## Pack 005 receipt

control 与 fault 的完整 Task、Capture、Fake Model path、预算、时钟和组件版本相同，
只改变 raw arguments 中是否存在 `unexpected`。

| Receipt | valid control | extra-property fault |
|---|---:|---:|
| Model calls | 2 | 1 |
| Tool validations | 1 | 1 |
| Tool executes | 1 | 0 |
| `findOwned` total | 2 | 1 |
| Tool-backed reads | 1 | 0 |
| Run starts / completes | 1 / 1 | 1 / 1 |
| Artifacts | 1 | 0 |
| Consumed IDs | `run, task, art` | `run, task` |
| Terminal status | `SUCCEEDED` | `FAILED` |
| Failure reason | null | `TOOL_ARGUMENTS_INVALID` |

fault 中唯一一次 `findOwned` 是 `AgentDraftService` 在启动 Run 前确认 owned Capture 的
preflight，不是 Tool dispatch。这里的“零副作用”准确含义是：schema fault 后零
Tool execute、零 Tool-backed read、零 Artifact；它不表示 Fake Model 没运行，也不
抹掉 `RUNNING → FAILED` execution truth。

### Frozen identity

- Pack raw SHA-256：
  `64b7cf77942e444ee871d766c4dbcd38fa45fa1cdf0d2bf6e96d87f7b1211ece`
- 两个 case 的 Task hash：
  `aea6ef82d51c34b69e5ee81924b029bd460942502d2eee75fae99a9fdee3d3cf`
- control Trace root：
  `2c69c3c5e347d0ad510ee45c62a06f3bc800178bccefeea91169c43310be1fde`
- control Bundle：
  `6efc58076a2914f6e2eb3d28c0b66e31ef98f0257d9cea49c897ef4a6692c3c4`
- fault Trace root：
  `d5157c4273548d58596335485437bb472e70459b3203a277912461dd073126c9`
- fault Bundle：
  `1370f686ecd5cb3697b08f8774834d61ffbf327c8ea1dec02ece5210c09e7163`

每个 case 都在两个 fresh fixture 中执行，并比较完整 `Observation`。Java fixture
直接 hash raw bytes；Node validator 固定 path、byte ceiling、raw SHA、全部 counters、
Trace statuses、ID consumption 与 hashes。safe persistent projection 同时拒绝
synthetic sentinel、bare `unexpected`、带引号字段名和 Capture content。

## Registry v2 and historical identity

active product profile、Pack 002 和 isolated Eval Runner 已迁移到
`agent-tools-v2`。这会改变 Task/profile/Bundle/attempt identity，因此相关 hash
全部重新计算并固定，不能只改一个 label。

`openai-responses-synthetic-v1.json` 没有被改写；它继续以 raw SHA
`f2ddb405f81ac4cf51c8f54479ddbb13fd00b62c9995bee2e52b43db85cccc6f`
保存历史 `agent-tools-v1` identity。active v2 raw SHA 是
`440fe5ce81202d5083e33463849b19e310077c9906baab8d253c1f59fd7de968`。
repository validator 要求两份文件集合、path、raw SHA、registry version 精确匹配，
并证明 v2 只能比 v1 改变 `toolRegistryVersion`。

冻结的 Pack 004、v1 contracts/golden/compatibility fixtures 也继续保留历史 identity；
它们不执行当前 `AgentToolRegistry`，因此不为追随 active profile 而迁移。

## Verification

Focused：

```text
AgentDraftServiceTest                 27
AgentExecutionProfileTest             7
AgentLoopKernelModelSessionTest       14
FrameworkFreeAgentKernelTest          16
OfflineToolArgumentsFaultTest          1
                                      --
                                      65 Green
```

Contracts：

```text
5 JSON Schemas
33 fixtures
2 cross-language Task hash vectors
5 synthetic Task Packs
2 frozen environments
```

`assertStrictJson` 的 committed regression 包含 valid control，以及 plain/escaped/
nested/array duplicate、trailing comma/content 和 invalid escape rejection。

Full：

```text
Contracts              26
Core                   81
Agent Loop             14
OpenAI Adapter         19
In-memory              25
PostgreSQL             45
API                    32
Eval Runner            65
Offline Harness        83
                      ---
                      390 tests
```

`./mvnw --batch-mode --no-transfer-progress clean verify` 的 10 个 reactor modules
全部 `SUCCESS`，共 390 tests、73 个 XML report files，0 failure、0 error、
0 skipped，总耗时 `01:19 min`。
`./scripts/verify-contracts.sh`、75 个 Markdown files 的 local-link verification 与
`git diff --check` 均为 Green。

三路独立 final audit 对 code 均为 `P0=0、P1=0`。审查发现的 cancel/deadline
precedence、canonical ref、exact manifest、raw-byte hash、privacy 与 strict JSON
问题都已转成回归。

## Failure and limits

- Tool limit 当前在 validation 后判断；`invalid + over-limit` 的 precedence 还没有
  独立 fault pack。
- `AgentTool` implementation 仍属于 Trusted TCB。manifest 绑定 name/schema，
  尚未绑定实现制品 hash、签名或 trust policy；不能据此加载任意第三方 Tool。
- 当前 Trace 只对本切片两个 argument failure code 做严格 reason/event binding，
  其他 terminal failure 的通用 provenance 会随后续 fault packs 扩展。
- synchronous Tool 一旦 dispatch，当前没有强制中断或 post-dispatch timeout truth；
  write-capable Tool 的不确定结果必须走 `UNKNOWN + reconciliation`，不能声称零副作用。
- Pack 005 vertical 本身没有网络访问；完整验证只使用本机 loopback HTTP 与本地
  PostgreSQL/Testcontainers TCP，没有访问外网、live provider 或 Connector。没有读取
  credential，也没有 real model result、billing receipt、真实 Seed、用户复用或收入
  证据。

## Principle learned

Tool calling 至少有四个不同事实：Model 提议、schema validation、authority decision、
实际 dispatch。把它们压成一个“Tool 已调用”状态，会同时破坏安全、审计和故障归因。

版本号也必须绑定可验证内容。`agent-tools-v2` 只有在 exact manifest、Task/profile 和
environment identity 同时一致时才有意义。

## AI Coding and Agent Engineering receipts

- AI Coding：用 Acceptance Red → focused Red → minimum Green → adversarial review →
  full verification 收口；主线程唯一 writer，三名 subagent 负责 runtime、Pack/hash
  与 registry migration 的只读审查。
- Agent Engineering：实现 opaque model boundary、Tool-owned validation、typed
  dispatch、exact registry manifest、safe Trace 与 deterministic fault pack。
- 本次工程过程已复现并记录：provider adapter 过早归一化、registry label 漂移、
  canonical ref 多处不一致、validator exception 泄漏，以及“零成功结果”被误写成
  “零 dispatch”。
- 仍未学习/证明：项目所有者的脱稿 Teach-back、第三方 Tool trust、异步 Tool timeout、
  stochastic model evaluation。

## Career and business receipts

这次增量可形成一个面试案例：为什么 Tool schema 不应散落在各 provider adapter；
怎样用 exact manifest、typed arguments、safe Trace 和 fault counters 证明
pre-dispatch rejection。

学习计划仍按项目所有者要求暂停，因此没有把文档或测试 Green 冒充个人掌握。
本切片没有新增访谈、真实 Seed、复用、报价、付款或收入证据。

## Next falsifiable hypothesis

Pack 006 将先冻结并修正 post-dispatch deadline truth，而不是直接声称已经有 timeout
语义。当前同步 Tool 在执行中推进 frozen clock 越过 deadline 后，Kernel 会先记录
`TOOL_RESULT / SUCCEEDED`，到下一轮才发现 deadline；同时
`AgentDraftService`、`AgentRun` 与 `HarnessRunBundle` 都拒绝
`latencyMs > task.deadlineMs()`，使真实 over-deadline outcome 无法进入一致的
product/durable aggregate。Acceptance Red 必须先覆盖这三层 masking，再要求
read-only Tool 已 dispatch 后不再调用 Model、不提交 Artifact，且 Trace 明确表达
“已 dispatch、没有可信成功结果”。冻结状态、计数器和 uncertainty 语义后，再决定是否
引入异步执行/中断。write-capable Tool timeout 必须另走
`UNKNOWN + reconciliation`。

## Public derivatives

- Short post：未生成；如需公开，先由项目所有者审阅。
- Visual/demo：可后续把四阶段 Tool call truth 画成状态图。
- Weekly long-form：未生成。
