# Build Note · 2026-07-30 · Stage 2 S2 持久 AgentRun、Safe Trace 与 HarnessRunBundle

- Change class：`V`
- 基线：`main@d88ece3c30fb`（Stage 2 S1 Fake Agent Draft Loop）
- 本回执范围：Stage 2 S2；不关闭 Stage 1 的 human-learning、market 或真实 Connector Gate

## 交付结果

S2 把 S1 只存在于 HTTP response 的 Agent 执行摘要，升级为 PostgreSQL 中
owner-scoped、可 verified read、跨 JVM 存活的产品执行真相：

```text
POST /api/v1/agent-drafts
  → server-owned TaskEnvelope
  → 短事务提交 RUNNING AgentRun
  → transaction 外执行 framework-free Fake AgentKernel
  → capture.read 得到 owner-scoped Evidence
  → deterministic Product Control Plane 验证 proposal
  → 同一 PostgreSQL transaction 提交
      Artifact v1
      + terminal Result
      + Safe Trace hash chain
      + immutable resource bindings
      + HarnessRunBundle integrity hash
      + terminal AgentRun
  → /agent-runs/{runId}、/trace、/bundle verified read
```

`AgentRun` 是产品执行真相；`HarnessRunBundle` 是 terminal Run 的 Eval evidence
projection。两者互相绑定，但不会互相冒充。

## 为什么这一步必要

S1 已能生成持久 Artifact，却无法在 JVM 重启后回答“哪个 Task、模型、工具、策略和
Evidence 产生了它”。若 Artifact 和 Run 分两次提交，进程可能在中间死亡，形成
Artifact 已存在但 Run 仍为 `RUNNING` 的 ghost success。若持久化完整 transcript，
又会把 Capture 原文、raw tool/model payload、异常和 hidden reasoning 变成长期泄漏面。

S2 因此只持久化足以验证执行事实的 safe projection，并让成功 Artifact 与 terminal
Run 原子提交。

## Acceptance Red 与最小 Green

- Acceptance baseline 是干净的 `main@d88ece3c30fb`：没有 durable AgentRun、
  Trace/Bundle route、verified read 或 Offline runner。
- 第一条 `AgentRunPersistenceHttpIT` 启动 packaged JVM 与真实 PostgreSQL，创建
  synthetic Capture 后调用 draft endpoint；基线因缺少 `runRef`、resolvable
  `traceRef` 与 Run route 而 Red。
- Minimum Green 先加入 provider-neutral contracts/Core aggregate，再加入 V4
  PostgreSQL store、薄 API route 和 packaged restart acceptance；没有引入 real model、
  Temporal、Agent framework、Connector 或外部 credential。

## Contract 与完整性边界

- Java records、JSON Schema 2020-12、Node semantic verifier 与 PostgreSQL 共同冻结
  Task、Result、AgentTraceEnvelope 和 HarnessRunBundle v1。
- `ContractText` 统一为有效 Unicode scalar、no NUL、frozen Unicode White_Space
  非空白语义，并按 code point 计长度；多行中文仍合法。
- `costUsd/budgetUsd` 使用非负、最多六位小数且不超过 `999999.999999` 的共同域；
  token count 留在 JavaScript safe integer 内，latency/deadline 不超过 24 小时。
- Ajv 的 `multipleOfPrecision=3` 只吸收 IEEE-754 quotient 误差；Node semantic
  verifier 另行执行六位小数检查。合法 `875825.408612`、非法
  `875825.4086121`，以及 Ajv 容差会接受但 semantic 必须拒绝的
  `100.0000000001` 都有 fixture。
- typed `ResourceBinding` 约束 Evidence 必须是 owned
  `capture://... + requestHash`，Artifact 必须是 immutable
  `artifact-version://{id}/{version} + contentHash`；全局 role/ordinal 顺序固定。
- Java/Node 使用 `emergeos-length-prefixed-sha256-v1` canonical encoding；
  Safe Trace 为 hash chain，Bundle integrity hash 覆盖 Task、Result、Trace root、
  resource bindings、版本和 usage。verified read 会重新计算，不只相信存储值。

这些 hash 提供 corruption detection 和 tamper evidence，不是 signature 或
authenticity。拥有完整数据库写权限的人仍能重算它们。

## Trace protocol 与 hostile outcome

同一个 deterministic `AgentTraceProtocol` 同时校验 adapter outcome 和 durable
aggregate：

- MODEL step、Tool request/result、structured final 与 Artifact commit 必须按状态机
  排序；
- Tool request/result 必须一一配对，terminal event 后不能继续；
- model/tool 数量不能超过 Task limits；
- Result cost/latency 不能超过 Task budget/deadline；
- `SUCCEEDED` 必须有 resolved model；
- `CREATE_ARTICLE_DRAFT + SUCCEEDED` 必须有且只有一个 immutable Artifact binding
  和 trailing `ARTIFACT_COMMITTED`。

孤立或错配 Tool Result、超 limit、failure 后继续 success、超 budget/deadline、
null outcome、success-without-model 或 success-without-Artifact 都进入固定、
非泄漏的 terminal failure，不会把 hostile metadata 持久化，也不会遗留永久
`RUNNING`。

## PostgreSQL 与真实进程证据

本次 full verification 的真实 PostgreSQL 输出：

```text
S2_V4_MIGRATION_RECEIPT fromV1=V4-stable fromV2=V4-stable
fromV3=V4-stable fresh=V4 tables=9 synthetic=true
```

V4 是 additive migration：新增 `agent_runs`、`agent_trace_events` 和
`agent_run_resource_bindings`，并为 Capture identity/request hash 增加 composite
unique constraint。它不改写 V1–V3 历史行。

`PostgresAgentRunStoreTest` 的 12 个案例证明：

- Artifact insert 后注入 terminal-finalization fault，整个 transaction rollback；
- FAILED Run 可以保留已经真实读取的 Evidence，但不能绑定 Artifact；
- 双线程并发完成同一 Run，恰好一个成功，另一个得到 fixed conflict；
- Artifact head 推进到 v2 后，历史 Run 仍绑定 exact v1 ref/hash；
- forged Evidence request hash 被 composite foreign key 拒绝；
- owned Trace event 或 checkpoint root 被修改后，verified read 拒绝 partial truth；
- 最大合法 cost/token/latency 可 round-trip；
- raw SQL 的七位小数、超 JavaScript-safe token、超 24 小时 latency 被数据库拒绝；
- Store 拒绝没有同 transaction proposed Artifact 的 draft success。

`AgentRunPersistenceHttpIT` 用 packaged executable jar 完成一次真实强制重启：

1. JVM A 先达到健康状态，写入 terminal Run/Trace/Bundle，并确认进程仍存活；
2. 测试强制终止 A，等待退出并确认它已死亡；
3. JVM B 使用不同 PID、同一 PostgreSQL container 启动；
4. B 取回 Run、Trace 与 Bundle，三份 JSON 与重启前 semantic-stable；
5. Trace root 与 Bundle integrity hash 保持一致；
6. persistent representation 不包含 raw Capture sentinel 或 chain-of-thought sentinel。

## API、主体与缓存边界

- `/api/v1/agent-runs/{runId}`、`/trace`、`/bundle` 全部 owner-scoped；
- foreign 与 missing 返回同形 `404`，owner lookup 先于 integrity verification，
  因而 foreign tamper 不形成 existence oracle；
- owner 的损坏数据返回固定、非泄漏 `409`；
- Agent API 的成功和 framework/application error 都带
  `Cache-Control: private, no-store`；
- 不返回 ETag，领域 integrity hash 不充当 HTTP cache validator；
- principal、runId、model、tools、limits、budget 与版本仍由 server 控制。

## Offline deterministic evidence

`OfflineFakeAgentRunnerTest` 在两个 fresh、隔离的 in-memory runner 中执行 frozen
`002-fake-agent-draft-replay.json`，得到 exact 相同的 Artifact、Result、六步 Safe
Trace 与 HarnessRunBundle hashes。改变 frozen Evidence 后，控制流保持相同，但
Evidence binding、Artifact/Result 与 Bundle hash 随之改变。

它是 test fixture，不是 CLI 或 product replay API；不能读取 shared store、真实用户
Evidence，也不能执行外部副作用。

## 最终验证

稳定快照按顺序执行：

```bash
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：

- Maven：147 tests，0 failure、0 error、0 skipped；
  - Contracts 16；
  - Core 43；
  - In-memory adapters 21；
  - PostgreSQL adapter 35；
  - API unit + packaged integration 32；
- Contracts：5 个 Schema、25 个 fixture、2 个 synthetic Task Pack；
- 文档：66 个 Markdown file 的 local links 通过；
- `git diff --check`：通过；
- transaction/replay 独立复审：`P0=0`、`P1=0`；
- 最终 Merge Audit：`P0=0`、`P1=0`、`P2=1`；P2 是下面已记录的非阻塞
  API/adapter exception layering debt。

## 已知限制与非声明

- 没有 real model；scripted Fake Model 的确定性不能证明写作质量或个性化。
- 没有 H0/H1 的 60 次正式实验、真实任务、人工盲评或 stochastic quality Eval。
- `RUNNING` 只表示“已经开始但没有 terminal truth”，不是 durable checkpoint，
  也不支持 mid-run resume、lease 或 fencing。
- 没有 authenticity/signature、HMAC、WORM 或 transparency log。
- Node verifier 当前只处理 repository fixture/golden；若未来直接校验 hostile raw
  JSON，应采用 integer micros、decimal string 或 lossless parser，避免
  `JSON.parse` 丢失 decimal lexeme。
- 没有真实用户 replay、retention/purge/export、真实外部副作用、Temporal、
  authentication、Secret Broker 或真实 Connector。
- API exception handler 目前直接依赖 PostgreSQL adapter 的 conflict/integrity
  exception；这是一个非阻塞 P2 layering debt，后续应把稳定异常语义上移到 Core/port。
- Stage 1 的 Teach-back、真实 Seed、访谈、报价、付款与 stale-`DISPATCHING`
  Connector Gate 仍然开放。

## 下一条可证伪假设

S3 接入一个 real model adapter 后，在不让 vendor SDK 类型、credential 或原始响应进入
Core/contract/Trace 的前提下，同一 Task Pack 能形成有真实 model attribution、token、
cost、latency 与 failure code 的 durable AgentRun；Fake baseline 继续作为确定性对照。

## Public derivatives

- 社交平台短文：未在本切片自动发布。
- 可视化 Demo：待 S3 真实模型路径后制作。
- Weekly long-form：待形成可比较的 real/Fake evidence 后再写。
