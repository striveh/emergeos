# Build Note：Pack010 owner TTY / predecessor authority

- Change class：`R`（owner authority、local permit、predecessor claim）
- Status：本地 Authority/predecessor子切片 Engineering Green；Authority/Live Gate仍为 Red
- Task：Stage 2 S4/F6 · Pack010
- Date：2026-08-02
- Commit：未提交；当前工作区含 owner既有 Pack010增量，以 Git history为准
- RFC：
  [RFC-0007](../../rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)
- 前序回执：
  [Pack010 terminal graph / Harness Report](./2026-08-01-s2-s4-pack010-terminal-graph-harness-report.md)

## Outcome

本子切片增加 production `OwnerTtyGraphAuthority`，公开面只接受 server-owned
`R1/R2/R3` revision；bounded challenge TTL在 package-private composition时冻结，不由
public approval caller提供。公开面不接受或返回 Store、Coordinator、
Console、Clock、approval、writer `DataSource`、credential、client或model。authority内部
直接调用 `System.console()`；`MutationPermit` constructor为 private，并用
`AtomicBoolean.compareAndSet`保证同一个本地 permit object并发消费只有一个 winner。同一
authority instance还会在取得真实 Console后原子烧毁 revision selection，不能再选择 r2/r3。

authority在 construction时冻结 server-configured PostgreSQL identity，包括 login/current
role、role/database OID、server address/port/version与角色 capability topology；predecessor
verify + claim和 approve transaction都会在同一 transaction-bound connection重新校验该
identity。当前测试仍使用 Testcontainers bootstrap/table-owner role，所以这是 identity drift
防护，不是 least-privilege role split证明。

Pack010 successor claim现在由 `PostgresGraphAttemptStore.claimForOwner`在同一
PostgreSQL transaction中完成：fresh lock + rebuild predecessor terminal snapshot，检查
`sequence=17 / TERMINAL / SUCCEEDED / ATTRIBUTED / exact two attributions / two terminal
bindings / both Run bundles / Candidate / WorkerResult / Artifact / terminal seal`，然后才插入
current manifest、bindings、event与head。r2与r3不能由 caller提供任意 predecessor ID；
facade只从冻结的 server-owned三份 manifest catalog推导。

这不是 live route。shipping CLI仍拒绝 execute；authority bytecode没有 provider/network/env
construction path，测试只对 provider attribution与Candidate durable truth做零计数，并没有把
它外推为 credential/client/model/request sentinel receipt。

## Acceptance Red → focused implementation

第一条 runnable Acceptance Red：

```text
OwnerTtyGraphAuthoritySurfaceTest
ClassNotFoundException:
io.emergeos.adapters.postgres.OwnerTtyGraphAuthority
```

minimum implementation之后，同一测试转 Green，并增加 same-package `JavaCompiler`
compile-negative：synthetic同 package代码不能调用 private `MutationPermit` constructor。
surface reflection还逐项拒绝公开方法出现 Store、Coordinator、Console、Clock、approval或
writer `DataSource`。

## Local TTY / permit evidence

真实本机 PTY由 `/usr/bin/expect`启动独立 JVM，production authority仍直接读取
`System.console()`，没有 injected console/clock。矩阵结果：

| 路径 | durable结果 | 本轮可观测 effect |
|---|---|---|
| no TTY（test JVM） | `REAL_TTY_REQUIRED`，0 attempt / 0 event | attribution/Candidate = 0 |
| exact challenge经 stdin pipe | `REAL_TTY_REQUIRED`，0 attempt / 0 event | attribution/Candidate = 0 |
| real PTY + wrong response | slot保持 `MARKED / sequence 1`，不可重试 | attribution/Candidate = 0 |
| real PTY + DB-time expired response | slot保持 `MARKED / sequence 1`，不可重试 | attribution/Candidate = 0 |
| real PTY + exact response | `OPERATOR_APPROVED / sequence 2` | attribution/Candidate = 0 |
| 同 process再次选择 r2 | `PROCESS_REVISION_ALREADY_SELECTED`，r2不落库 | attribution/Candidate = 0 |
| replay exact approved slot | `execution slot was already claimed`，仍为 sequence 2 | attribution/Candidate = 0 |
| two-thread local permit consume | exactly one winner；后续消费拒绝 | 未连接 provider mutation |

negative challenge会烧毁 slot，不会把 wrong/expired解释成“数据库零变化”。本 Acceptance的
zero-effect仅表示 production authority没有 provider path，且测试观察到 Candidate与
attribution为零；wrong/expired路径保留 one-shot durable claim正是协议要求。local permit的
CAS证据不等于 provider/mutation exactly-once，后者继续留在 Authority/App Gate。

## Predecessor / concurrency evidence

真实 PostgreSQL 18.4 Testcontainers覆盖：

1. r2-before-r1：predecessor missing，r2没有落库；
2. r3-before-terminal-r2：r2只有 `MARKED / sequence 1`，r3没有落库；
3. r1 sequence-17 sealed → r2 atomic claim → r2 sequence-17 sealed → r3 atomic claim；
4. 两个独立 connection/thread并发 claim exact r2：一个 `OwnerClaim`、一个 `CONFLICT`，
   durable r2只有 sequence 1；
5. billing仍为 `UNKNOWN`的 predecessor不能解锁 successor；用错误 expected manifest读取
   已存在 slot会 fresh-verify为 `Invalid`，也不能解锁 successor；
6. claim使用 PostgreSQL `clock_timestamp()`生成 event time与 expiry；approve transaction再次
   读取 DB time并在写 approval前检查 deadline；
7. 独立 test-classpath JVM在 predecessor fresh verify之后、successor insert之前执行
   `Runtime.halt(86)`；PostgreSQL回滚整个 transaction，r2保持 Missing，随后新 connection
   可正常 claim；
8. 两个独立 test-classpath JVM back-to-back争抢同一 r2，结果一个 `CLAIMED`、一个
   `CONFLICT`，durable r2仍只有 sequence 1。

本轮没有重复既有37-point terminal process-kill与三组 two-JVM terminal transaction matrix；
它们仍是前序 offline terminal slice证据。新增 successor hard-kill/two-JVM只覆盖 claim边界，
进程仍由 test classpath helper启动，并非 packaged App，因此不外推为 App Gate。

## GUC safety Acceptance：明确保持 Red

新增 executable test证明上一轮 P2不是文档猜测：使用与 Store相同的 raw writer session，
transaction内手工设置可预测的 `emergeos.graph_terminal_permit` 后，graph-bound child Run的
terminal `UPDATE` statement可通过 V8 trigger并在 transaction内可见。第一段测试主动
rollback；第二段让同样的 forged transaction真实进入 commit，V7 deferred whole-graph
constraint以 `TransactionSystemException`拒绝不完整 commit。两条路径的 durable truth都保持
`RUNNING / sequence 14`。

这证明“不完整 forged terminal transaction不能 commit”，但不等于 writer没有 terminal
transaction authority。拥有相同 raw DML的 session仍可仿造完整 Store transaction，因此
当前 topology不能证明 live安全边界。

所以本轮没有：

- 修改已冻结的 V8 bytes/checksum；
- 弱化或删除 GUC Acceptance；
- 把 `OwnerTtyGraphAuthority`接入 shipping execute；
- 声明 Authority/Live Gate Green。

live route前的最小 forward-only迁移路径保持为：

1. 新增 V9 semantic `SECURITY DEFINER` functions，固定 trusted `search_path`、schema-qualified
   SQL，创建后立即 `REVOKE EXECUTE FROM PUBLIC`；
2. migrator/table owner不进入 runtime；单独 provision `graph_executor LOGIN NOINHERIT`、
   restricted reader与 generic API writer；
3. `graph_executor`只获得 exact function `EXECUTE`，不得直接 UPDATE `agent_runs`，不得拥有
   relation、trigger、truncate、grant option、role membership或 `SET ROLE`路径；
4. V9 trigger不再信任 caller-set custom GUC；terminal child/parent update只能在 semantic
   function的 effective authority下发生；
5. role/password provisioning放在无明文 secret的独立运维 bootstrap；Flyway只安装 schema、
   function、trigger与默认 deny ACL；
6. 以 restricted runtime credential重跑 raw GUC negative、TX-B/TX-C、fault/restart、
   reader与authority identity drift matrix后，才允许讨论 live writer route。

## Reproducible evidence

聚焦 Authority/TTY/predecessor/GUC Gate：

```bash
./mvnw -pl adapters/postgres -am \
  -Dtest=OwnerTtyGraphAuthoritySurfaceTest,PostgresGraphAttemptStoreTest#liveGateRemainsClosedWhileSameWriterCanForgeCustomGucPermit+ownerAuthorityRejectsNoTtyBeforeClaim+ownerAuthorityRejectsCatalogIdentityDriftBeforeClaim+ownerAuthorityRealTtyMatrixBurnsRejectedClaimsAndPermit+pack010PredecessorVerificationAndClaimAreOneTransaction+pack010UnknownOrExpectedManifestMismatchCannotUnlockSuccessor+concurrentPack010SuccessorClaimHasExactlyOneWinner+pack010SuccessorClaimRollsBackAcrossProcessCrash+twoJvmPack010SuccessorClaimHasExactlyOneWinner \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`tests=12 failures=0 errors=0 skipped=0`。

PostgreSQL adapter全模块回归：

```bash
./mvnw -pl adapters/postgres -am test
```

结果：Contracts `59`、Core `191`、PostgreSQL adapter `148`，全部
`failures=0 errors=0 skipped=0`。

graph-eval app surface/architecture回归：

```bash
./mvnw -pl apps/graph-eval-runner -am test
```

结果：7-module reactor SUCCESS；graph-eval runner unit tests `35`，全部
`failures=0 errors=0 skipped=0`；CLI execute仍 disabled。

## Independent review

三路 read-only subagent分别进行 threat model、Acceptance/Gate audit与 post-implementation
review。首次 review为 `P0=0`，提出四项 P1：compile-negative命中错误签名、identity未在
mutation transaction内复核、catalog冻结不完整、同 process可跨 revision复用；四项均已
修正并进入上述 focused/full回归。permit未连接真实 mutation/provider、packaged App route与
least-privilege role split仍是明确 Gate blocker；successor新增 test-classpath hard-kill/
two-JVM evidence，但不是 packaged route，不用 offline test Green覆盖。

## Evidence boundary

Engineering：仅声明本机 PUBLIC synthetic TTY facade、local permit one-winner、Java
transaction内 predecessor verification + claim、raw GUC incomplete-commit Red与相应
PostgreSQL/PTY测试。真实 API key、provider/model、provider network、真实 r1/r2/r3、billing
均为 `0`。

Human-learning：暂停；没有 teach-back、无资料讲解或 owner真实批准证据。

Commercial：没有访谈、报价、付款、留存或真实用户验证。

## Next falsifiable slice

Authority/Live Gate的下一条安全 Acceptance是 forward-only V9 role/ACL split：同一 generic
writer与 `graph_executor`都不能通过 raw SQL/custom GUC取得 terminal authority；只有 exact
semantic function可在原 TX-B/TX-C transaction中成功一次。V9 Gate Green之后，仍需
packaged-process successor kill/two-JVM、exact bytecode broker/session composer与 loopback
effect-ordering，且不自动授权真实 provider调用。
