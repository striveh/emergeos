# Build Note：Pack010 V9 terminal authority / packaged successor

- Change class：`R`（PostgreSQL role/ACL authority、semantic transaction、process fault）
- Status：两个 focused Engineering slice Green；Authority/Live Gate仍为 Red
- Task：Stage 2 S4/F6 · Pack010
- Date：2026-08-02
- Commit：未提交；当前工作区含 owner既有 Pack010增量，以 Git history为准
- RFC：
  [RFC-0007](../../rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)
- 前序回执：
  [Pack010 owner TTY / predecessor authority](./2026-08-02-s2-s4-pack010-owner-tty-predecessor-authority.md)

## Outcome

本轮以 forward-only `V9__split_graph_terminal_authority.sql`关闭了 V8 custom GUC
不能作为 live authority 的已知 P1。V9 没有修改任何历史 migration bytes/checksum；
fresh V9与 populated V8→V9都由 Flyway执行。V9增加两个 exact、schema-qualified
`SECURITY DEFINER` semantic function，分别承载原 TX-B child terminal与 TX-C parent
terminal + seal transaction；它们固定 trusted `search_path`，创建后立即从 `PUBLIC`
撤销 `EXECUTE`。

provisioned Testcontainers topology把 table owner、terminal function owner、
`graph_executor`、generic writer与 restricted reader分开。executor为
`LOGIN NOINHERIT`，只有两个 exact non-grantable `EXECUTE`；没有 relation/table/column
authority、membership、ownership、trigger/TRUNCATE或 grant option。table owner与
function owner均为不同的 `NOLOGIN` role，migrator不进入 runtime。

V9 trigger不读取 caller-set custom GUC。database-native self-check以原始
`session_user`核对 exact executable surface、ACL、双向 membership、role capability、
relation/table/column authority、function owner、relation owner与 trigger topology；
Java `PostgresGraphTerminalExecutor`还在每次 semantic call前后冻结并复核 role/database/
schema/function/trigger OID、定义 fingerprint、ACL与 ownership drift。generic writer与
executor即使尝试 raw SQL、forged GUC、`SET ROLE`、membership、trigger、TRUNCATE、
PUBLIC/other role execute、grant option或 owner/config/body drift，也不能取得 terminal
transaction authority。

为了保持现有 offline Store/fault contracts可运行，V8 GUC guard作为 legacy compatibility
layer仍存在；但 V9 guard在同一 terminal UPDATE上独立执行上述 `session_user`与 topology
检查，伪造 GUC不能绕过 V9。这个双 guard是明确的长期收敛项，不是 live-ready声明。

V9 meaningful slice之后又完成 packaged successor fault slice：test-only process shell
以 `shipping fat JAR + test-classes`顺序启动，逐类确认 Catalog、Store、Core与 adapter
production bytecode来自 shipping JAR，而 harness/bridge只来自 test-classes且不会进入
shipping JAR。R1 sequence-17之后，R2 claim在同一 transaction已写入 manifest、两个
bindings、event与head、但尚未 commit的 `AFTER_HEAD_UPDATE`边界被强杀；外部 reader在
marker时与强杀后都只见 exact旧 row-image/`xmin`，R2 uncommitted claim完整回滚。随后
两个清空 ambient environment的新 JVM在同一 barrier后竞争 R2，恰好一个 sequence-1
`MARKED` winner、一个 conflict。

这仍不是 shipping live route。CLI继续拒绝 execution argument；provider credential、
client/model session与 loopback effect-ordering尚未接入；operator role provisioning当前也
只有 Testcontainers helper，没有 production operator bootstrap artifact。

## Acceptance Red → focused implementation

V9 Acceptance首先把上一轮 same-writer raw GUC P1保留为 Red；最小实现不是弱化该测试，
而是新增 forward migration、role split与 exact semantic function。实施期间 full adapter
回归曾暴露六个 legacy Store测试因移除 V8 trigger而失败；最终保留 legacy trigger并让 V9
independent guard负责 live authority，避免用新 topology破坏冻结的 offline fault证据。

packaged successor Acceptance Red为：

```text
ClassNotFoundException:
io.emergeos.grapheval.Pack010SuccessorClaimHarnessMain
```

minimum implementation只增加 test-only process shell/bridge与 packaging/code-source Gate；
没有增加 shipping execute route。首次 Green的 kill点位于 predecessor verified、R2写入前，
独立复核将 `rollback=R2_MISSING`列为措辞 P2；post-fix把 probe移动到
`AFTER_HEAD_UPDATE`，再实际复核确认 uncommitted mutation rollback，P2关闭。

## V9 executable evidence

覆盖内容：

1. fresh V9 exact function、fixed search path、SECDEF owner、PUBLIC revoke、无 overload；
2. populated V8 sequence-14 attributed prefix的14 events、2 attributions与14张 graph/product
   table row-images/`xmin`，以及 Flyway V1–V8 history，在升级后 exact保真；
3. restricted generic writer/executor raw GUC、raw DML、`SET ROLE`、membership、relation/
   column privilege、trigger、TRUNCATE、PUBLIC/other execute与 grant option negative；
4. TX-B/TX-C payload shape/scope/Artifact binding negative、同 transaction一次成功、
   stale/replay拒绝；
5. function/trigger definition、owner/config/OID/enabled state与 reader identity drift；
6. forked JVM在 semantic function后、commit前强杀，数据库保持旧 prefix；两个 JVM
   同抢 child/parent transaction各恰好一个 winner；
7. custom schema与动态 UUID credential，Flyway schema migration与 role provisioning
   分离，migration不包含 role/password明文。

聚焦命令：

```bash
./mvnw -pl adapters/postgres -am \
  -Dtest=V9GraphTerminalAuthorityMigrationTest,PostgresGraphAttemptStoreTest#v9RestrictedExecutorOwnsOnlyExactAtomicTerminalFunctions \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`4 tests`，`0 failures / 0 errors / 0 skipped`。

PostgreSQL adapter全回归：

```bash
./mvnw -pl adapters/postgres -am test
```

结果：Contracts `59`、Core `191`、PostgreSQL adapter `152`，全部 Green。

## Packaged successor evidence

聚焦 post-fix命令：

```bash
./mvnw -pl apps/graph-eval-runner -am \
  -Dtest=Pack010ProcessLifecycleSurfaceTest \
  -Dit.test=Pack010DurableGraphTerminalProcessIT#packagedSuccessorClaimRollsBackAfterKillAndTwoJvmsHaveOneWinner+shippingJarContainsVerifierButNoHarnessOrCliRoute \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

独立复核结果：lifecycle `1/1`、focused IT `2/2` Green。关键 receipt：

```text
PACK010_PACKAGED_SUCCESSOR_RECEIPT
predecessor=R1_SEQUENCE_17
killedBoundary=AFTER_HEAD_UPDATE
rollback=R2_UNCOMMITTED_CLAIM_MISSING
freshJvms=2 winner=1 conflict=1
ambientEnvironment=EMPTY
modelCalls=0 providerNetworkCalls=0 synthetic=true
```

完整 graph-eval Gate：

```bash
./mvnw -pl apps/graph-eval-runner -am verify
```

结果：7-module reactor Green；graph-eval unit `35`、IT `7`全部 Green；既有 terminal
`37`个 kill boundary、TX-A/B/C two-JVM race、Pack009 loopback crash与三份 Pack010
Report integration同时通过。

V9使 API packaged restart readiness的 latest version从8变为9；root `verify`首次据此
真实失败，修正 stale expectation后独立重跑
`RecoverableLocalActionHttpIT`为 `1/1` Green。最终 root Gate：

```bash
./mvnw verify
```

结果：`11/11` modules `BUILD SUCCESS`，总耗时 `04:36 min`。其中 PostgreSQL adapter
`152` tests、API packaged IT `10` tests、graph-eval unit `35` tests / IT `7` tests、
offline-harness unit `78` tests / IT `5` tests全部通过；Pack010 `37`个 process kill
boundary、packaged successor与 TX-A/B/C two-JVM race receipt均在同一次 root Gate中重现。

## Independent review

V9三路 actual post-fix read-only review均基于稳定快照实际复跑 focused tests，结论一致：
scoped provisioned runtime threat model内 `P0=0、P1=0`。剩余均为P2交付边界：

- production role/ACL provisioning尚无独立 operator bootstrap artifact；
- V8 legacy GUC双 guard未来需收敛；
- full-row `jsonb_populate_record`协议随未来 nullable column演进必须同步；
- live前必须持续审计 NOLOGIN、零 membership、owner split等 provisioning invariant。

packaged successor首次 review为 `P0=0、P1=0、P2=1`；移动 probe后，Acceptance/Gate
post-fix review实际复跑并给出 `P0=0、P1=0、P2=0`。threat-model post-fix review给出
`P0=0、P1=0、P2=1`：唯一 P2是证据 scope——当前证明的是“test-only shell以
fat-JAR-first顺序加载 shipping production bytecode”，不是 `java -jar` App Main、runtime
V9 executor/roles、TTY permit、credential broker/session composer或 live Gate。其此前指出的
post-race R1 predecessor只读不变量已增加 fresh restricted-reader exact row/`xmin`复核并关闭。
因此不能把 focused review结果外推为 Authority/Live Gate Green。

## Evidence boundary

Engineering：只声明本机 PostgreSQL 18.4 Testcontainers、PUBLIC synthetic Pack010、
dynamic ephemeral test credentials、fat-JAR-first process、loopback/no-egress条件下的 V9
authority与 successor fault evidence。shipping execute仍 disabled；真实 API key、provider、
model、provider network、真实 r1/r2/r3与 billing均为 `0`。

Human-learning：暂停；没有 teach-back、资料讲解、owner真实批准或学习回执。

Commercial：没有访谈、报价、付款、留存或真实用户验证。

## Remaining risk / next falsifiable slice

Authority/Live Gate保持 Red。下一条安全 Acceptance是独立、可审计且不含明文 secret的
production role provisioning bootstrap。exact bytecode credential broker/provider session
composer与本机 loopback effect-ordering sentinels已由后续
[Build Note](./2026-08-02-s2-s4-pack010-dormant-provider-capabilities.md)收口；但 real owner permit
与 durable coordinator egress仍缺少 production handoff，V9 prefix/terminal双 role尚未进入同一
runtime writer composition，完整 response attribution与 shipping execute也仍为 Red。这些
本地 Green不自动授权真实 provider调用。
