# ADR-0010：采用 PostgreSQL-canonical one-shot graph attempt

- Status: Accepted
- Date: 2026-07-31
- RFC:
  [RFC-0006](../../rfcs/0006-postgresql-canonical-one-shot-graph-attempt.md)
- Extends:
  [ADR-0009](0009-child-only-model-worker-eval-boundary.md)
- Evidence:
  [Pack009 Build Note](../../operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)

## Context

Pack008 只有 process-local graph permit、loopback integration 与 same-JVM fresh Store。
它不能证明 provider request 已被接收、writer 在 attribution 前死亡时的 durable
billing truth，也不能阻止 fresh process 再次执行同一 graph。

现有三个 composition root 都不适合直接承载该能力：

- `apps/eval-runner` 被隔离为 no-PostgreSQL single-Run Eval；
- `apps/offline-harness-runner` 是 no-network deterministic report boundary；
- `apps/api` 没有 production auth/consent且明确禁止 OpenAI adapter。

## Decision

1. 新建 `apps/graph-eval-runner`，仅作为 contracts/core/agent-loop/openai/postgres 的
   synthetic graph composition root；不依赖其他 App、inmemory、Web、Temporal 或
   upper-layer Agent framework。
2. Core 持有 `GraphAttemptManifest/Event/Snapshot/Verification` 与
   `GraphAttemptStore` port；PostgreSQL V7 持有 create-only manifest marker、exact
   Run/profile bindings、CAS head projection、append-only hash-chain journal 与
   disabled terminal-seal skeleton。V7以 `CHECK(FALSE)` 阻止 seal写入；Pack009
   没有 terminal seal capability。
3. manifest row 是唯一 durable claim。Pack009 不再增加 POSIX marker；同一数据库上的
   并发/重启 writer只能一个成功。另以 checked-in `executionSlotId` 建稳定唯一键，
   防止 rolling binary 因 manifest/profile hash改变而把同一费用槽位执行两次；caller
   不能自报 slot/nonce。reader不 repair、不 resume、不删除 claim。
4. verifier分别返回 evidence、graph outcome 与 billing：
   `VALID|INVALID`、`INCOMPLETE|SUCCEEDED|FAILED`、
   `NOT_INVOKED|ATTRIBUTED|UNKNOWN`。durable provider intent后缺完整 attribution
   必须保持 `UNKNOWN`。
5. operator gate固定为 strict preflight → real TTY → create claim → exact challenge
   → durable approval。exact parent/child authorization与 child-only durable consume
   之后，才允许 lazy provider credential/client/model construction；provider
   invocation 前先 durable append matching intent。DB password是连接 durable
   authority的基础设施 secret，不属于这里的 provider credential gate。
6. 第一条 process evidence选择“loopback provider 已 durable 接收一次 request并阻塞
   response、数据库已有 intent、writer强杀”的窗口；两个 fresh JVM只读得到一致
   UNKNOWN；无 provider endpoint/key 的 claim probe与 fresh完整 writer replay都在
   第二次 provider request前拒绝，request count保持 1。
7. 第一阶段 shipping CLI只执行 preflight/help，不开放 execute。`--verify` 是保留参数，
   完成 preflight后明确返回 disabled；DB-backed verifier仍为 test-only fresh process。
   synthetic console、loopback base URL、DB password stdin frame、blocking provider、
   crash coordination与 ready/state file逻辑只存在于 test classes。
8. V7 不改写或猜测历史 Pack007/Pack008 rows。Pack009 exact profile selection由
   manifest + run binding持久化，不能依赖 registry order或 current profile。
9. 未来开放 terminal path时，child terminal + WorkerResult + graph event必须同事务；
   parent terminal + HANDOFF + Artifact + terminal seal也必须同事务。V7 seal skeleton
   当前禁用，不能把现有独立 AgentRun transaction与 journal/seal按调用顺序冒充原子
   graph truth。
10. graph-bound `agent_runs` 必须持久化 all-or-none attempt/manifest/role/Task hash与
    selected profile selector，并以 composite FK指向 reserved selection。历史 rows保持
    NULL；old-style writer不得占用 reserved Run或绕开 graph event/seal terminalize。
11. verified read使用单个 read-only repeatable-read snapshot，并与 compiled expected
    manifest比较；只验证数据库自洽不够。event/head在同事务插入/CAS，request ordinal
    另有 database unique/count guard。

## Consequences

- graph-level operator approval、one-shot claim、provider intent与 Run truth首次进入同一
  PostgreSQL authority，可以跨 writer JVM与 host（共享同一数据库时）拒绝 replay；
- provider call与数据库仍不构成 transaction；intent之后的 crash只能解释为 UNKNOWN，
  不能自动 retry；
- PostgreSQL成为该 synthetic graph attempt的 availability dependency；
- unkeyed hash/immutable trigger只提供 cooperative corruption evidence，不防 DBA、
  superuser、签名伪造、数据库 restore到旧快照或跨数据库 replay；
- 新 App增加构建与 process-test成本，但不污染普通 API和历史 Eval boundaries；
- terminal graph、真实 smoke、reconciliation、Temporal、parallel Worker、write-capable
  Tool与产品用户价值仍需后续切片。

上面的跨 JVM/host拒绝是数据库唯一约束与协议属性，不是本次所有部署拓扑的实测结论。
Pack009实测的是两个独立 Store/DataSource transaction的 concurrent one-winner，以及
fresh完整 writer的 sequential replay；没有执行 simultaneous cross-process/cross-host
contention。

## Evidence at acceptance

- RFC-0006 Acceptance全部 Green；
- V7 fresh/populated migration与 integrity/fault matrix Green；
- provider-accepted process-kill、两个 fresh verifier、两种 replay rejection与
  provider count `1` Green；
- shipping JAR/test seam、direct dependency default-deny与 bytecode Gate Green；
- full repository Gate与 least-authority/recovery independent review为
  `P0=0、P1=0`；
- [中文 Build Note](../../operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)
  列出 hashes、PIDs、DB/provider snapshots与 nonclaims。

## Rollback

在没有 Pack009 rows时可移除 App wiring；V7 schema保持 forward-compatible。已有 attempt
truth不得删除或降级解释，旧 binary不得继续或覆盖它。UNKNOWN只能由未来独立
reconciliation evidence推进，不能人工改写。
