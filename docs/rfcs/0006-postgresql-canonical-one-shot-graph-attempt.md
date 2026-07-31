# RFC-0006：PostgreSQL-canonical one-shot graph attempt 与 UNKNOWN replay boundary

- Status: Accepted
- Author: project owner + main Codex agent
- Created: 2026-07-31
- Accepted: 2026-07-31
- Scope: Stage 2 Pack009
- Extends:
  [RFC-0002](0002-real-model-synthetic-egress-and-metering.md)、
  [RFC-0004](0004-typed-read-only-worker-handoff.md)、
  [RFC-0005](0005-model-bound-read-only-worker-eval-baseline.md)
- Evidence:
  [Pack009 Build Note](../operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)

## Problem

Pack008 已证明 exact Fake parent + model-bound child 可以通过 production graph classes、
OpenAI adapter 与 PostgreSQL V6 truth 组合，但它故意停在：

- shipping `--worker-preflight` 没有 execute route；
- graph permit 只存在于单进程内存；
- Pack008 的 fresh Store 仍在同一 JVM；
- 没有 graph-level operator approval、durable marker、journal、terminal seal；
- 没有证明“provider 可能已经接收请求，但本地尚未形成 attribution”时如何解释、
  阻断 replay 与避免第二次费用。

如果直接把 Pack003 的 POSIX single-Run protocol 泛化，或者把 OpenAI adapter 接进
普通 API，会产生错误的 authority 与 durability 叙事：

- Pack003 marker/journal 只绑定一个 Task/Run，不认识 parent、child、WorkerResult、
  HANDOFF、parent-only Artifact 或 exact Worker profile；
- 文件 marker 与 PostgreSQL graph truth 会形成两个互相竞争的 authority；
- 普通 API 还没有 production authentication、credential broker 与 per-user egress
  consent；
- `RUNNING` AgentRun 不是 checkpoint，不能把进程重启解释成安全 resume。

Pack009 先解决最危险的一个 crash window：

> provider 已 durable 接收一次 loopback synthetic request，writer 在 attribution
> commit 前被强杀；两个 fresh JVM 必须一致返回 `billingStatus=UNKNOWN`，而同一
> attempt 的重新执行必须在 provider credential/client/request 前拒绝，request count
> 保持 1。完整 writer replay仍先读取连接 PostgreSQL所需的 DB password。

## Proposal

### 1. 新建独立 graph composition root

新增真实、非空、one-shot CLI App：

```text
apps/graph-eval-runner
```

它可以组合：

```text
contracts + core + agent-loop + openai + postgres
```

但不能依赖：

```text
apps/api
apps/eval-runner
apps/offline-harness-runner
adapters/inmemory
Temporal / Spring Web / Actuator / Hibernate / upper-layer Agent framework
```

旧 `apps/eval-runner` 的 PostgreSQL/product Store 禁令保持不变；普通 API 的 OpenAI
禁令也保持不变。新 App 是退出即结束的 synthetic verification composition root，
不是 Web service、长期 Worker service 或产品 route。

### 2. Core 持有 graph attempt 语义，PostgreSQL 持有 durable truth

Core 增加 framework-neutral：

```text
GraphAttemptManifest
GraphAttemptEvent / GraphAttemptEventType
GraphAttemptSnapshot
GraphAttemptVerification
GraphAttemptStore port
```

V7 由 PostgreSQL adapter 增加：

```text
agent_graph_attempts          immutable create-only manifest / marker
agent_graph_attempt_run_bindings
                              exact parent/child Run + Task/profile binding
agent_graph_attempt_heads     CAS phase/version/journal-head projection
agent_graph_attempt_events    append-only hash-chain journal
agent_graph_attempt_seals     disabled create-only terminal-seal skeleton
```

`agent_graph_attempts` 的 unique row 同时是 marker 与 immutable manifest。Pack009
不再创建 POSIX marker，避免“文件说可以、数据库说不可以”的双 truth。Event append
必须锁定 exact manifest row、verified read 完整 prefix、重算 root，再插入唯一
`sequence`；manifest、binding、event 均拒绝 update/delete。seal table在 V7以
`CHECK(FALSE)` 明确禁用，先保留 schema skeleton；Pack009没有可写 terminal seal。

one-shot claim 不能只以 manifest-derived `attemptId` 唯一。rolling binary 若改变
profile、codec 或 verifier version，会得到另一个 attempt hash，并让同一商业/费用槽位
再次执行。manifest 因此还必须包含 checked-in、server-owned
`executionSlotId`；数据库对 `(principalId, executionSlotId)` 建唯一约束。intentional
新 repetition只能通过受审查的新 Pack/slot进入，调用方不能提交 nonce、时间或任意 slot
制造无限 attempt。

`agent_graph_attempt_heads` 是唯一可变 projection，必须以 expected
`phase + version + headHash` 做 compare-and-swap；`version == lastSequence`，且
last sequence/head外键绑定 exact journal event。SEALED 后不再更新。append transaction
必须同时插入 event并 CAS head；任何一个失败都整体回滚。Store 不暴露“调用方自选 event”
的通用 append，而只暴露 approve、startParent、startChild、consumeEgress、
providerIntent、providerAttributed、completeChild、completeParentAndSeal 等语义命令。

V7 不把历史 Pack007/Pack008 `RUNNING` rows 猜成某个新 profile。只有 Pack009 Run
通过 `agent_graph_attempt_run_bindings` 持久绑定 exact：

```text
attempt / role / run / task / task hash
worker registry / profile fingerprint
```

terminal 或 incomplete graph reader 都必须交叉核验 manifest、binding、AgentRun 与
journal；不能按 registry order、“current profile”或 Task ID 近似匹配。

`agent_runs` 还要增加 nullable、all-or-none 的 graph selector：attempt、manifest hash、
role、Task hash、selected parent/Worker profile identity。Pack009 Run 初次 INSERT 时
必须完整填写并以 composite FK指向 reserved run binding；V1–V6 historical rows保持
全 NULL，不猜、不回填。数据库必须阻止旧 V6-style writer占用 reserved run ID却写入
NULL selector，也必须阻止 graph-bound Run绕开 matching event/seal直接 terminalize。

terminal transaction 也不能由现有 `PostgresAgentRunStore.complete*` 与独立 journal
append顺序拼接。V7 graph-aware Store 必须让：

- child terminal + WorkerResult + matching graph event；
- parent terminal + HANDOFF + Artifact + terminal seal；

分别处于各自一个 PostgreSQL transaction。否则真实 kill 会留下 terminal Run、却没有
对应 graph evidence，reader无法区分“未记录”与“伪 terminal”。

verified read 必须在一个 `READ ONLY REPEATABLE READ` transaction中读取 manifest、
selection、head、events、Runs、WorkerResult、Artifact与 seal，重建 typed domain、
重算全部 hash/state，再与 compiled expected manifest exact比较。数据库内部自洽但不
匹配当前 frozen catalog也必须 fail closed。

### 3. 三个结论轴不可折叠

Verifier 分别返回：

```text
evidenceVerdict = VALID | INVALID
graphOutcome    = INCOMPLETE | SUCCEEDED | FAILED
billingStatus  = NOT_INVOKED | ATTRIBUTED | UNKNOWN
```

- hash/state/DB relation 自相矛盾是 `evidenceVerdict=INVALID`；
- journal 合法但缺 terminal seal 是 `graphOutcome=INCOMPLETE`；
- 没有 `PROVIDER_INTENT` 的合法 prefix 可为 `billingStatus=NOT_INVOKED`；
- 已有 durable intent、却没有完整 provider attribution，必须为
  `billingStatus=UNKNOWN`，即使 observed cost/token 都为 0；
- 只有完整、exact attribution 才能使用 `ATTRIBUTED`；
- `UNKNOWN` 不是 success、failure、free call 或可自动 retry。

Graph outcome 与 billing 互不推导：一次 graph 可以业务失败但 usage 已归因，也可以
保持 incomplete 且 billing unknown。

### 4. 第一代 journal 只允许 frozen linear state machine

Pack009 仍是 single、serial、`depth=1` graph。第一代安全前缀固定：

```text
create immutable manifest / ATTEMPT_CLAIMED
→ OPERATOR_APPROVED
→ PARENT_AUTHORIZED
→ PARENT_STARTED
→ CHILD_AUTHORIZED
→ CHILD_STARTED
→ CHILD_EGRESS_CONSUMED
→ CREDENTIAL_READ_STARTED
→ CLIENT_CREATED
→ MODEL_CREATED
→ PROVIDER_INTENT(requestOrdinal=1)
```

后续 terminal 扩展只能沿同一 RFC 增加：

```text
PROVIDER_ATTRIBUTED
→ optional second PROVIDER_INTENT / PROVIDER_ATTRIBUTED
→ CHILD_TERMINAL
→ PARENT_TERMINAL
→ TERMINAL_SEALED
```

Event 不能跳序、重复、倒序或越过 `maximumProviderRequests`。intent/attribution
ordinal还必须有 database unique/count guard，不能只依赖 Java。append 前必须从
durable prefix重建状态，不信任调用方自报的“当前状态”。同一 attempt 的第二个
writer、进程重启或跨 host 只要指向同一 PostgreSQL truth，都在 create-only manifest
处失败。

### 5. operator approval 与 model construction 遵循 least authority

顺序必须是：

```text
strict zero-egress preflight
→ real TTY available
→ create-only manifest claim
→ render exact challenge
→ exact owner response
→ OPERATOR_APPROVED durable
→ exact parent/child Run + profile authorization
→ child-only egress consume durable
→ lazy provider credential read/client/model construction
→ PROVIDER_INTENT durable
→ provider invocation
```

manifest/slot claim 在 challenge 前创建，因此错误 challenge 也烧掉该 slot。parent 不能消费
child egress；child token 不能构造第二个 client/model。credential、client 与 model
factory 必须在 durable child consume 之后才可达。provider call 必须在 matching
intent transaction commit 之后发生。

`AUTHORIZED → STARTED` 与现有 production service 的真实调用顺序一致；authorization
event 绑定 exact Run/Task/profile selection，随后 Run INSERT 与 `*_STARTED` event在同一
PostgreSQL transaction提交。journal 不得把尚未授权的 Run伪装成已开始。

测试可以注入 exact synthetic console、provider credential 与 loopback client，但这些
入口只能位于 `src/test` composition/harness；shipping CLI 不接受 JDBC password、
provider base URL、crash phase、ready file、model、Pack path 或 API key override。

V7 migration与 frozen PUBLIC Capture由父测试作为 fixture provisioning预先完成。writer
只做 exact read-check；它不会在 operator gate/claim前 migrate、创建或修改 Capture。
本条 fixture setup不构成真实 owner批准，也不属于 operator-authorized attempt。

### 6. Pack009 先证明 crash/replay，不提前开放 shipping execute

shipping App 第一阶段只执行：

```text
no args / --preflight
--help
```

`--verify` 只保留参数与未来兼容位：当前先执行相同 zero-effect preflight，再明确返回
`SHIPPING_VERIFY_ROUTE_DISABLED`；真正读取 PostgreSQL的 fresh verifier只存在于
`src/test`。`--execute`、crash injection 与 provider override 继续拒绝。test-only harness 可以
组合 production Core/PostgreSQL attempt runtime 与 loopback provider，形成真实 JVM
kill/restart evidence；test harness class、blocking provider admin、synthetic credential
与 crash coordination 不能进入 shaded App JAR。

只有 terminal graph path、operator gate、full fault matrix 与独立 review 全部 Green
后，才讨论 shipping execute route。即使 route 落地，真实 provider smoke 仍需 owner
对 exact attempt 单独批准，不由测试或 RFC 自动授权。

### 7. selected crash window

第一条 outside-in Acceptance 必须使用：

1. PostgreSQL 18.4 Testcontainer；
2. 独立、可持久记录 accepted request 的 loopback provider process；
3. 独立 execute JVM；
4. provider 已记录 exactly one request并阻塞 response；
5. PostgreSQL 已提交 matching `PROVIDER_INTENT`；
6. parent 与 child 都是 exact `RUNNING`；
7. 父测试进程真实 `destroyForcibly()` writer；
8. provider 随后可完成 durable synthetic response，但数据库没有 attribution；
9. 两个 fresh verifier JVM 得到 exact-equal、read-only `UNKNOWN`；
10. 无 provider endpoint/key 的 least-authority replay在 claim处拒绝；
11. fresh完整 writer携带 loopback endpoint参数、但只收到 DB password frame，在
    challenge/provider key前以 claim conflict拒绝；
12. 两条 replay期间 provider仍在线，最终 provider count保持 1。

这项证据强于“intent 后、SDK call 前 request count=0”的窗口，但仍不是真实 provider、
invoice 或 provider-side idempotency 证明。

### 8. privacy 与 evidence boundary

manifest/journal/seal 只保存：

- PUBLIC synthetic identity、hash、version、status、ordinal、usage 与安全引用；
- 不保存 API key、Authorization、数据库 password、raw prompt/response、Capture/
  WorkerResult/Artifact content、exception、stack trace 或 hidden reasoning；
- provider request/response identity 只能保存 bounded safe ref 或 hash；
- unkeyed hash用于 corruption/coherence detection，不是 signature、producer
  authentication、WORM 或 hostile DBA protection。

## Rejected alternatives

### 扩展现有 `apps/eval-runner`

拒绝。它的 Enforcer 与历史回执明确禁止 PostgreSQL/product Store。放宽会使 Pack003
的 isolated single-Run证据失真。

### 接入普通 `apps/api`

拒绝。当前没有 production auth、credential broker 或 per-user egress consent，且 API
明确禁止 OpenAI adapter。

### 文件 marker + PostgreSQL journal

拒绝。两个 authority 在 crash、换 host、restore 或人工删除后无法确定谁有资格继续。

### 把 `RUNNING` AgentRun 当 checkpoint 自动 resume

拒绝。V6 `RUNNING` 只证明开始；没有 checkpoint、lease、fencing 或 safe replay
contract。

### provider intent 后自动 retry

拒绝。intent 之后即可能已经产生费用或效果；没有 reconciliation 或 provider
idempotency 证据时只能保持 `UNKNOWN`。

### 先抽象万能 Attempt framework

拒绝。Pack003 POSIX single-Run、Pack004 durable report 与 Pack009 PostgreSQL graph
protocol有不同 truth shape。至少两个稳定 graph use case 后，才抽取真正共同的 primitive。

## Acceptance

- 新 App 是 non-empty module，dependency/bytecode Gate阻止 API、旧 App、inmemory、
  Web/Temporal/Agent framework越界；
- Pack009 strict preflight 先独立 Green，并拒绝 symlink、oversize、duplicate/trailing
  JSON、raw hash/semantics/profile drift；
- 第一条 process IT 的 Red 到达 packaged/test-harness runtime，并因缺 V7 durable
  graph behavior失败，不是 compile、asset、Docker 或 provider 环境错误；
- V7 fresh/populated upgrade、legacy truth不改写、incompatible fail-fast Green；
- create-only manifest、exact run binding、append-only hash chain、state machine、
  两个独立 Store/DataSource transaction concurrent one-winner 与 immutable tamper
  regression Green；
- selected provider-accepted crash window、两个 fresh verifier JVM、
  least-authority replay、fresh完整 writer replay rejection 与 provider count=1 Green；
- verifier前后 graph/product/Flyway PK/`xmin` snapshot exact-equal；
- shipping JAR 不含 test harness/JUnit/Testcontainers，shipping CLI拒绝 execute/crash/
  JDBC/provider overrides且零 DB/provider/home/tmp effect；
- focused、full Maven、contracts、doc links、Diff check与两类 independent review Green；
- Build Note 明确没有 real key/provider result/billing receipt、terminal graph、
  product wiring、Temporal、power-loss/NFS/cross-database guarantee。

2026-07-31 的实现与 Gate已满足以上范围，详见
[Pack009 Build Note](../operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)。
并发 claim证据是同一 JVM内两个独立 Store/DataSource transaction；fresh进程证据是
sequential replay。没有执行两个同时启动的 writer JVM或 cross-host contention，
因此不得把该未测场景写成实验结论。

## Migration and rollback

V7 必须 additive、forward-only：

- fresh V1→V7 与 populated V6→V7 都不改写历史 AgentRun/Bundle/WorkerResult bytes；
- 新表为空时 rollback binary 可忽略它们，但不得删除已有 graph attempt evidence；
- 写入 Pack009 truth 后，旧 binary不能解释或继续该 attempt；
- migration不能为历史 `RUNNING` rows猜 profile/attempt；
- schema checksum/shape不兼容必须在启动时 fail-fast。

关闭功能时可移除新 App 的 composition route，并保留只读 verifier与 V7 truth。不得删除
marker以“重试”，也不得把 `UNKNOWN` 人工改成 `NOT_INVOKED`。
