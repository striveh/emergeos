# RFC-0005：child-only model-bound read-only Worker Eval baseline

- Status: Accepted
- Author: project owner + main Codex agent
- Created: 2026-07-31
- Scope: Stage 2 Pack008
- Related:
  [RFC-0002](0002-real-model-synthetic-egress-and-metering.md)、
  [RFC-0004](0004-typed-read-only-worker-handoff.md)

## Problem

Pack007 已证明一个 Fake Conductor 可以委派 single、synchronous、`depth=1` 的
read-only Worker，并把 child Run、WorkerResult、parent HANDOFF 与 Artifact 形成
durable hash chain；Pack003 则证明一个独立 Eval Runner 可以安全装配
OpenAI Responses adapter。两条证据仍是分开的：

- Pack007 的 parent 与 child 都是 deterministic Fake，无法证明真实 model adapter
  只绑定 child；
- Pack003 是 single-Agent Task，没有 parent/child graph、Worker profile 或
  child-only authority；
- OpenAI adapter 过去只接受 root `AgentExecutionProfile`，会把“model route”与
  “root product profile”错误耦合；
- PostgreSQL reader 只知道一个 Pack007 profile，无法在同一 schema 下同时验证
  historical Pack007 与新一代 model-bound Worker truth；
- 如果直接把 Pack003 的 `--execute` 复用给 Worker graph，可能在 durable
  operator gate、graph marker/journal 与 crash evidence 尚未完成前形成未经审查的
  live route。

Pack008 的目标不是立刻调用真实 provider，而是先把“Fake parent + exact model child”
做成可冻结、可拒绝漂移、可在 loopback 集成验证的最小 baseline。

## Decision

### 1. model execution profile 与 root profile 解耦

Core 增加 provider-neutral `ModelExecutionProfile`。OpenAI adapter 只依赖这组真实
执行所需的 immutable identity 与 limits，不要求它必须是 root
`AgentExecutionProfile`：

```text
ModelExecutionProfile
  ├── AgentExecutionProfile（Pack003 root model Task）
  └── ModelBoundReadOnlyWorkerExecutionProfile（Pack008 child）
```

SDK 类型仍停在 `adapters/openai`；Core 不依赖 OpenAI、Spring、PostgreSQL、
Temporal、Pi、AgentScope 或其他 Agent Runtime。

### 2. parent 永远不是 model-bound route

Pack008 graph 固定为：

```text
PUBLIC synthetic Capture
→ Task 1.0 parent
   actor = deterministic Fake Conductor
   requiredTools = []
   modelBound = false
→ typed WorkerCall
→ Task 1.1 child
   actor = OpenAI Responses adapter
   requiredTools = [capture.read]
   risk = EXTERNAL
   model/pricing/environment/experiment = exact frozen identity
→ WorkerResult
→ verified parent HANDOFF
→ parent-only Artifact
```

parent 只能创建 exact child；不能读取 provider credential、创建 provider client、
消费 child egress permit，或把 child 的 `capture.read` authority 反向继承给自己。
child 继续服从 RFC-0004 的 same-owner、depth-one、read-only、single-consume 和
parent-only Artifact 规则。

### 3. non-model parent budget 是 subtree reservation

Pack008 首次让 non-model-bound Task 1.0 parent 携带非零 budget。它不是 parent
自己的 model budget，而是整个 fixed `depth=1` subtree 的 authorization ceiling：

- parent `budgetUsd` exact 等于 child full-run reservation（当前 `$0.417000`）；
- parent 没有 model provider/requested model、pricing profile 或逐 step token bounds；
- 只有 child 可以产生 provider usage/cost；
- parent Result 只能聚合 verified child 的 token/cost，不能自己新增或抹掉计量；
- parent Bundle 不出现 `model-adapter`，只绑定 Worker profile、Conductor surface 与
  verified HANDOFF；child Bundle 才保存 model component identity。

这是 RFC-0002 single-model route 在 typed Worker graph 中的组合扩展，不修改历史
Task 1.0 canonical truth，也不让 parent 冒充一次 provider execution。

### 4. Conductor 行为也是 execution identity

Pack007 的历史 fixed-intent Conductor 行为保持不变。Pack008 单独使用
`inheritingParentIntent()`，让 child intent exact 继承 parent/request intent。
该 decision surface 有版本与 SHA-256 fingerprint，并同时进入 parent execution
profile、component versions、Pack008 attempt identity 与 preflight 校验。

`parentActor=SCRIPTED_FAKE` 只是可读标签，不能替代 decision-surface binding。

### 5. profile registry 不存在“当前版本”fallback

`ReadOnlyWorkerProfileRegistry` 同时承载 Pack007 与 Pack008：

- `RUNNING` truth 只有在完整 parent Task 或 parent/child Task graph **恰好匹配一个**
  registered profile 时才可接受；
- terminal truth 必须显式命名 exact
  `worker-registry + worker-profile-fingerprint`；
- experiment、Harness version 与完整 component map 必须与该 profile exact 一致；
- zero-match、ambiguous match、unknown identity 与 cross-profile parent/child
  均 fail closed；
- registration order 不参与选择。

V6 已能保存 Task 1.1 model identity、WorkerResult 与 HANDOFF graph，本切片不新增
migration。未来若两个 profile 可以接受同一份 `RUNNING` Task，必须先新增 durable
profile selector/migration，不能恢复“最新 profile”猜测。

### 6. Pack008 是独立 frozen synthetic asset

Task Pack 固定：

```text
evals/task-packs/synthetic/008-openai-read-only-worker-baseline.json
```

它只包含 literal、checked-in、PUBLIC synthetic content；无真实用户、真实账号、
Self Model、conversation 或 Connector。compiled catalog 同时冻结：

- Pack 与 environment raw SHA-256；
- Capture request hash；
- parent/child Task hash；
- parent/Worker/pricing profile fingerprint；
- OpenAI prompt surface fingerprint；
- Conductor decision surface fingerprint；
- Harness experiment；
- reservation、maximum provider requests 与 attempt ID。

`maximumProviderRequests` 必须同时等于 Worker `maxModelSteps`、child Task
`maxModelSteps` 和 environment request cap。任一 bytes、语义或 compiled binding
漂移都在已知 credential/client/model/Run/marker/provider invocation construction
path 之前失败；这不是 system-wide socket instrumentation。

### 7. shipping CLI 只增加 zero-egress preflight

CLI 语义保持互不混淆：

```text
no args              → historical Pack003 preflight
--worker-preflight   → Pack008 preflight only
--execute            → historical Pack003 operator-gated execution
```

Pack008 不接受 `--worker-execute`、pack path、model、base URL、API key 或其他
运行时 override。`--worker-preflight` 只读取两份 bounded、no-symlink、
hash-frozen repository assets，并重建 server-owned Tasks/profiles；它没有
credential reader、client/model factory、Run store、marker、socket 或 execution
dependency。

### 8. graph permit 目前只作为 process-local integration boundary

Pack008 新增专用 one-shot permit，不泛化或改变 Pack003 已冻结的 permit：

```text
PREPARED
→ ARMED
→ exact parent authorized
→ exact child authorized
→ child-only CAS consume
→ CONSUMED
```

child 不能先于 parent authorize；parent 不能 consume；expired、drifted、unarmed、
replayed 或并发第二次 consume 均拒绝。consume observer 在 CAS 后、delegate 前执行；
observer failure 或 consume 后发现 expiry 都永久烧掉 permit，delegate count 保持 0。
`arm` 还必须收到 exact compiled attempt ID；该 v3 identity 显式覆盖 Pack/environment、
Capture request、principal/Capture/Run/Task/Artifact IDs、fixed start time、双 Task、
双 profile、pricing、prompt/Conductor surface、reservation、request cap 与
Harness experiment。test-only graph `Spec` 必须与同一 frozen identity exact 相等。

当前 permit 只存在于 test/loopback graph，不是 shipping Pack008 execute route。
它没有 preflight receipt object、durable graph marker、operator challenge、
append-only journal、terminal graph record 或 cross-process arbitration。因此
process-local attempt binding Green 不能解释成 live-provider approval、crash-safe
one-shot 或 billing evidence。

### 9. verification boundary

minimum integration 使用真实 production graph classes 与 OpenAI adapter，但 provider
端只允许本机 loopback `HttpServer`。PostgreSQL evidence 必须证明：

- 同一 V6 database 可保存并 verified read Pack007 与 Pack008 graph；
- reverse registration order 不改变结果；
- child terminal / parent `RUNNING` gap 可由 fresh Store instance 读取并继续完成；
- missing/ambiguous/cross-profile identity 在 Artifact 或 Run INSERT 前 fail closed；
- verified read 不改变业务表/Flyway history 的 PK 与 `xmin` snapshot。

这里的 `fresh Store` 仍在同一 JVM，不是 fresh JVM 或真实 process-kill。Pack007
已有的 process-kill evidence 不能自动转授给 Pack008。

## Rejected alternatives

### 让 parent 与 child 共用同一个 model profile

这会让 parent 获得 provider route 或要求 Fake Conductor 伪装成 model-bound actor，
破坏 least authority 与计量归属。

### 把 model-bound Worker 注册成普通 Tool

会再次隐藏 child Task、Run、profile、usage、failure 与 WorkerResult，违背
RFC-0004。

### 复用 Pack003 `--execute`

Pack003 的 marker、journal 与 terminal record 只绑定 single-Agent attempt，不能证明
双 Task、双 Run、WorkerResult、HANDOFF 或 parent-only Artifact。复用会制造错误的
durability/approval 叙事。

### 先把 Pack008 接入普通 API

普通 API 可以读取个人 Capture，当前又没有 production authentication、credential
broker 或 per-user egress consent。接入会越过 RFC-0002 的 synthetic-only boundary。

### 依赖 registry registration order

注册顺序不是 durable execution identity；重启、升级或不同进程的装配顺序都可能改变
解释，不能用于 verified read。

## Acceptance

- Pack008 strict loader 拒绝 Pack007、Pack003、unknown/duplicate/trailing JSON、
  symlink、oversize、hash 与 environment drift；
- packaged `--worker-preflight` 在 hostile environment 下证明不存在
  credential/client/model/provider invocation construction path，指定 local HTTP
  sentinel 收到 0 request；
- `--help` 与所有 invalid combinations 在 preflight/effect 前失败；
- compiled product graph 的 parent/child Task hash exact 等于 catalog；
- graph permit 覆盖 ordering、expiry、drift、wrong consumer、observer failure 与
  concurrent one-winner；
- loopback adapter 完整执行 child model path并由 parent verified consume；
- runtime sanitizer、terminal verifier 与 PostgreSQL write/read 都拒绝 parent
  对唯一 model-bound child 的 cost-only 或 token-only coherent inflation；
- Pack007 frozen replay/hash 保持不变；
- PostgreSQL multi-profile、crash-gap fresh-store、cross-profile tamper 与完整
  snapshot regression Green；
- full Maven、contracts、doc links、Diff check 与独立 review Green；
- Build Note 明确列出未执行的 live provider、Pack008 process-kill/fresh JVM、
  durable graph journal 与 product wiring。

## Accepted implementation resolution · 2026-07-31

上述 baseline 已按
[ADR-0009](../architecture/decisions/0009-child-only-model-worker-eval-boundary.md)
收口。exact metering P1 已由 runnable Reds 复现并修复；full Maven、contracts、
doc links、Diff check 与 Build Note 均已 Green，两路 post-fix 独立复审均确认
`P0=0、P1=0`。其中一路记录两个不阻断的 P2：ExecPlan 的 sentinel 措辞已收窄，
无关 `.workbuddy/` 必须通过精确 staging 排除。

下一切片必须先实现 Pack008 graph attempt manifest、operator gate、create-only marker、
hash-chain journal、terminal graph record 与 selected-boundary process-kill/fresh-JVM
replay，再由项目所有者对一次 bounded live smoke 单独授权。
