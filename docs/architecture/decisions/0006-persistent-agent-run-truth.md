# ADR-0006：以 AgentRun 承载持久执行真相

- Status: Accepted
- Date: 2026-07-30
- Supersedes: none
- Superseded in part by:
  [ADR-0007](0007-observed-latency-and-post-dispatch-tool-deadline.md)

> Historical note：Decision 11 中“所有 Result latency 不得超过 Task deadline”的
> 部分已被 ADR-0007 取代。`SUCCEEDED` 仍受 deadline 约束；cooperative non-success
> 必须保留实际 observed latency。

## Context

Stage 2 S1 已经能把 owner-scoped Capture 经过有界 Fake Agent Loop 转换为
Artifact，但一次运行只有 HTTP inline Trace。JVM 重启后无法回答“哪个 Task、模型、
工具与策略产生了这个 Artifact”，也无法把同一 Task 的多次运行区分开。

如果先提交 Artifact、再单独保存 Run，进程可能在两个事务之间死亡，形成 ghost
success。若只保存模型 transcript，又会把 Capture 原文、tool arguments、raw model
response 或 chain-of-thought 变成长久的数据负担和泄漏面。

## Decision

1. `AgentRun` 是产品执行真相；`HarnessRunBundle` 是 terminal AgentRun 的
   Eval evidence projection，两者不能互相冒充。
2. 每次执行先在短事务中提交 `RUNNING`；AgentKernel 在事务外运行；成功终态把
   Artifact v1、safe Trace、resource bindings、Result、Bundle 与 terminal AgentRun
   在同一个 PostgreSQL transaction 中提交。
3. terminal transition 使用 `RUNNING → terminal` compare-and-set。第二次完成、
   并发完成或部分完成都不能产生第二份真相。
4. Trace 只保存 allowlisted 结构事件与 resource ref，不保存原文、Prompt、
   tool arguments、raw result、模型 reasoning、exception message 或 stack trace。
5. Trace 使用 hash chain；Bundle 使用跨 Java/Node 的版本化 canonical encoding
   计算 integrity hash。verified read 会重新计算并校验，不只相信数据库中的 digest。
6. Artifact binding 必须指向 exact immutable version：
   `artifact-version://{artifactId}/{version}` 加 `contentHash`。
   Evidence binding 同样必须指向 exact owned Capture/request hash，且与 Artifact
   `sourceCaptureId`、Task input、Result evidence 一致。
7. API 使用 owner-scoped：
   `/api/v1/agent-runs/{runId}`、`/trace`、`/bundle`。owner lookup 先于完整性校验；
   foreign 与 missing 同形 `404`，owner 的损坏数据返回固定非泄漏 `409`。
8. POST 与三个 GET 的成功/错误 response 都使用
   `Cache-Control: private, no-store`，S2 不返回 ETag。领域 integrity hash 不充当
   HTTP cache validator。
9. V4 新增 `agent_runs`、`agent_trace_events` 和
   `agent_run_resource_bindings`，并为 Capture identity/request hash 增加 composite
   unique constraint；它不改写 V1–V3 历史行。
10. Offline runner 只执行 synthetic success golden baseline。每次创建 fresh
    in-memory stores，因而可复用 frozen IDs 并比较 exact hashes；它不是 product
    replay API，不得接入 shared store、真实用户 Evidence 或外部副作用。
11. Trace 不只校验 event allowlist，还必须通过确定性 state machine：MODEL/Tool
    request/result 必须合法排序和配对，执行不能超过 Task limit，terminal event 后
    不能继续；Result 的 cost/latency 不能超过 Task budget/deadline。
12. `CREATE_ARTICLE_DRAFT + SUCCEEDED` 必须有且只有一个 immutable Artifact
    binding 与 trailing `ARTIFACT_COMMITTED`，并在同一 transaction 收到 proposed
    Artifact；空列表互相相等不能构成 success。
13. hash-covered v1 文本使用统一 `ContractText`：有效 Unicode scalar、no NUL、
    frozen Unicode whitespace 的非空白语义，并按 code point 计上限。Java、JSON
    Schema、Node 与 PostgreSQL 的字段界限必须有 shared negative evidence。

## Alternatives

### 只保留 inline Trace

无法跨 JVM 找回或做 verified read，也不能支撑真实模型、Harness 对照和故障归因。

### Artifact 与 AgentRun 分两个事务提交

存在无法可靠解释的 ghost success 窗口。

### 持久化完整 transcript 或 chain-of-thought

扩大隐私、合规和凭据泄漏面；产品排障需要的是安全结构事件、版本和资源绑定，不是
模型私有推理文本。

### 直接把 HarnessRunBundle 当产品 Run

普通产品调用会被迫伪造 experiment、WorkingSelf、checkpoint 等 Eval 字段，并混淆
产品 lifecycle 与评测投影。

### 现在引入 Temporal 或上层 Agent framework

会同时增加 workflow/runtime 变量，削弱对持久真相与完整性边界的可归因验证。

## Consequences

- JVM 重启后仍能验证 Task、Result、Trace、Bundle 与 Artifact binding。
- 数据库写入、读取、迁移和测试成本增加，但这些成本集中在可替换 Adapter。
- hash 提供 corruption detection 与 tamper evidence，不提供 authenticity。拥有完整
  数据库写权限的人仍可重算 hash；HMAC、signature、WORM 或 transparency log 属于
  production security Gate。
- 崩溃留下的 `RUNNING` 只表示“未完成事实”。S2 不自动恢复 mid-run，也不声称具备
  lease、fencing 或 durable checkpoint。
- frozen offline golden baseline 适合确定性回归；真实历史 Run 若没有安全导出的
  frozen Evidence package，只能 verified read。

## Evidence and validation

- Java 与 Node 使用同一 canonical profile 和 golden vectors。
- V4 fresh install 与 populated V1/V2/V3 → V4 保持旧 Stage 1 truth；现有
  backup/restore game day 只逐列比较六张 Stage 1 populated table，不宣称已验证
  populated AgentRun/Trace/Bundle restore。
- Artifact insert 后故障注入证明整个 terminal transaction 回滚。
- 双线程同时完成同一个 Run 时恰好一个提交，另一个得到固定 conflict。
- Artifact head 修订到 v2 后，历史 Run 仍校验 exact v1 ref/hash 和原 Bundle hash。
- FAILED Run 在没有 Artifact 时仍可持久化已经实际读取的 Evidence binding。
- packaged JVM A 被强制终止后，JVM B 复用 PostgreSQL，三个 representation 语义一致，
  Trace root 与 Bundle integrity hash 不变。
- Spring + PostgreSQL 安全矩阵证明 owner 可读、foreign/missing 同形、foreign tamper
  不形成 existence oracle、owned tamper 返回固定 `409`。
- frozen Task Pack 在两个 fresh runner 中产生相同 Result、六步 safe Trace、Artifact
  hash 和 Bundle hash；Evidence 漂移会改变 resource binding 与 Bundle hash。
- hostile Kernel 的孤立 Tool Result、错配、超 limit、failure 后继续 success、超
  budget/deadline 与 success-without-model 都进入固定非泄漏 failure。
- PostgreSQL 接受共同最大合法 usage，并拒绝超精度 cost、超
  JavaScript-safe token 与超时长 raw write；typed Evidence/Artifact ref 同时受
  Java、Schema、Node 和 DB shape 约束。
- 完整命令、测试总数、真实进程与 non-claim 见
  [Stage 2 S2 Build Note](../../operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)。

## Rollback or migration

V4 是 additive migration，不执行 destructive down migration。若应用回滚到旧版本，
三张新表保留且不再写入；修复版通过 forward migration 或兼容代码恢复。已经产生的
AgentRun 不通过删表“回滚”。
