# Pack010 capability handoff、role provisioning 与 runtime writer composition 回执

日期：2026-08-02
Stage：Stage 2 / S4 / F6
结论：A、B、C 已通过 focused Engineering Gate、PostgreSQL adapter 全量 Gate、无旧报告
污染的 11-module root clean Gate与三路 actual post-fix review；最终 `P0=0 / P1=0 / P2=0`，
但 Authority/Live Gate仍为 Red。

## Outcome

本轮把 owner approval 到 durable terminal truth之间的三段 production-shaped 工程边界接通，
但没有接入 shipping execute。

- A：durable sequence-2 owner cursor只能派生一次 exact Coordinator typestate；owner facade与
  Coordinator不能重复 claim/approve同一 slot。capability继续绑定 owner、attempt、revision、
  Coordinator、egress、DB-time expiry和 one-shot状态；两份完整 attribution durable后再派生
  exact terminal capability，并分别一次性 claim TX-B/TX-C。新增 sequence-7 durable
  provider-session intent：它必须在 credential/client/model/session构造前原子落库，并绑定
  exact cursor、first request与 expiry。
- B：新增与 Flyway完全分离、面向 dedicated Pack010 database的 role/ACL bootstrap和 pure
  read-back audit。runtime没有 migrator/table owner credential，所有 LOGIN role初始
  `PASSWORD NULL`。
- C：新增 shipping artifact内 dormant、Main不可达的 two-credential writer composition。
  generic prefix writer在 composition外完成 sequence 1–14；composition不再暴露 generic
  writer，且只有 owner terminal claim可进入 `graph_executor`的 exact两个 V9
  SECURITY DEFINER semantic function完成 TX-B/TX-C。完整 attribution必须先 durable commit。

## Acceptance Red

### A · production capability handoff

初始测试证明 owner-approved cursor仍不能安全交给 exact Coordinator；后续 Red覆盖 wrong、
stale、replayed、concurrent、expiry以及 approval/adoption/egress附近的 process kill。修复后
adoption不新增 `create/approve`，durable CAS仍是 global one-winner真值。

### B · independent provisioning

旧 bootstrap会把危险 ACL drift静默修复，`--check`也不是 pure current-state audit。Acceptance
先注入 membership、grant option、PUBLIC EXECUTE、column ACL、relation owner、disabled trigger、
V9 body tamper、legacy authorization/assertion body tamper和 ambient schema owner drift，要求
read-back直接失败。真实 fixed-role连接再证明 raw GUC、`SET ROLE`、raw terminal DML、internal
authorization helper与 reader write均被拒绝。

### C · runtime composition

首次 real PostgreSQL flow得到三类 Red：prefix writer在 deferred trigger提交时缺少只读
integrity assertion closure；terminal owner缺少 V9 semantic transaction内部所需的 exact
legacy validation helper；container-level restart在当前 Testcontainers endpoint不会恢复
host-port proxy。最终 harness用 supervisor保持容器映射，真实 `postgres` process通过
`pg_ctl -m immediate`重启，再由新 composition复核 durable truth。

另外，sequence 13进入 TX-B、runtime role拥有同名 ambient schema、Main或其他 production
class引用 dormant composition、manifest A搭配 payload B、wrong revision/coordinator/egress、
terminal capability replay/concurrent claim与 alternating prefix/terminal DataSource都必须失败。
首次 post-fix review又实际给出四个 Acceptance Red：DB-A owner capability可 splice到同 manifest
的 DB-B runtime；terminal claim可在过期前派生、过期后进入 semantic TX；pure audit未锁死
V9 helper owner/topology；runtime首次构造会把预先篡改的 semantic/trigger body当作 trusted
baseline。四项均未豁免。

第二次三路 review继续把候选打回 Red：production provider session可在 durable intent之前
构造；V9只固定 top-level semantic function而没有冻结其 transitive helper closure；trigger
identity遗漏 `tgattr`，`UPDATE OF`列级 drift可穿过 audit；expiry仍有 Java check与 SQL调用间
TOCTOU；prefix writer启动后的 TEMP/ACL drift未纳入每连接复核。上述问题均先变成可执行 Red，
再进入本轮修复；在最终 post-fix review完成前不外推为 `P1=0`。

第三次三路 review仍实际发现四个 P1，而不是把前两次 review外推为最终结论：credential
lease丢失 durable intent/owner/expiry，可能在 expiry后才创建 client/model/session；admin/
migrator-backed OwnerTty只凭同库 identity即可冒充 runtime prefix authority；owner facade虽冻结
了 authority-bound Store，却把原始 Store交给 Coordinator；provider-session intent SELECT又忽略
数据库返回的 cursor sequence/head，额外 trigger可把内存 cursor与 durable truth分离。另有
`verify`沿用 focused XML导致 `136 reports / 771 tests`含重复 testcase的 P2 evidence歧义。
这些 finding均先形成可执行 Acceptance Red；修复后仍必须重新冻结快照并完成实际三路 review。

在 `65e9307b…`冻结快照上完成的下一轮三路 actual post-fix review结论仍是
`P0=0 / P1=2 / P2=1`：session可在 expiry前 compose、expiry后才 `next()`进入 provider
invocation；pure audit与 terminal runtime又只锁一个具名 V9 trigger，其他 TX-B/TX-C relation
上的额外 trigger可在首次启动前成为隐含 side effect。P2是 provisioning只固定部分 helper，
而每 TX runtime固定16-function closure。三项都已先复现 Red；本轮实现把 freshness带到
`ProviderSession.next`与 exact pre-HTTP observer，把35个 public non-internal trigger聚合成 shipping
SHA-256 topology，并让 preflight/pure audit与 runtime使用同一16-helper SHA-256 closure。

该冻结快照的实际最终 review中，B/C与 Gate审查均为 `P0=0 / P1=0 / P2=0`；A审查为
`P0=0 / P1=0 / P2=1`，唯一P2是缺少恰好让 session guard在 expiry前通过、pre-HTTP
observer在 expiry时拒绝的运行时 loopback sentinel。随后补入 sequenced Clock Acceptance：
两次 freshness read分别位于 expiry前与 expiry时，结果为 `clockReads=2 / requestCount=0 /
providerIntents=0`；A focused suite由17增至18 tests。该项不改变 shipping execute状态。

## Minimum implementation

### A · handoff

- public adoption同时验证 trusted classloader下的 exact caller `Class` identity和 private
  permit type；generic/fake-console approval在任何 Store访问前拒绝；
- owner facade内部使用 exact same-store、owner-adoption-only Coordinator；permit、Authorized
  领取和 egress消费均 one-shot；
- egress消费前验证 owner、revision、attempt、exact Coordinator、exact egress manifest和
  PostgreSQL DB-time expiry，再做 CAS；
- 每个 connection冻结并复核 database/schema/search_path、role/database OID与 server identity，
  拒绝 same-database double-schema splice；
- fresh process不能重建 permit或 resume。这是 fail-closed，不是 restart liveness。
- exact owner handoff消费 egress后，Coordinator只有在两份 attribution均 durable时才允许
  owner facade派生 terminal capability；bind、TX-B claim、TX-C claim分别 one-shot。真实 PTY
  process证明 wrong/replay/concurrent与 terminal binding后 hard-kill + fresh-process restart均
  fail closed，durable truth停在 sequence 14。
- OwnerTty冻结的 database OID/server identity必须与 prefix/executor runtime同库；runtime writers
  先完成两类 role/ACL/identity probe，再 burn terminal capability。两套独立 PostgreSQL中的同一
  canonical manifest不能跨库复用 capability，失败后原 capability仍可在 exact DB绑定一次；
- child/parent claim携带 opaque expiry，restricted executor在同一个 verified TX-B/TX-C内、
  schema-qualified semantic function之前用 `clock_timestamp()`再次校验。claim-before-expiry、
  semantic-after-expiry会烧毁 claim并保持 durable sequence不变。
- provider session intent在 verified sequence 7 transaction中 `INSERT ... ON CONFLICT DO NOTHING`
  并 exact read-back；owner facade把它绑定到 exact owner/attempt/revision/Coordinator/egress/
  first request/expiry并分别 one-shot claim/consume。packaged TTY子 JVM覆盖 wrong、expired、
  replay、双线程竞争、commit前/后 hard-kill与 restart。commit前 kill回滚 intent，commit后 kill
  保留 intent；两者均没有 credential/client/model/session/HTTP effect。
- broker返回的 lease现在保留 exact owner authority、durable session intent、revision、
  Coordinator、egress与 expiry；claim、client factory、model factory和 session open每个 effect
  boundary前都同时执行本地 Clock与 PostgreSQL DB-time freshness复核。动态 expiry测试证明
  intent过期后 `keyReads/clientFactories/modelFactories/sessionOpens`均为 0。
- compose成功后，`ProviderSession`仍持有同一 lease/Coordinator/egress/Clock；每次 `next`先
  freshness复核，真正 provider invocation observer又在 ordinal mutation、durable intent与 SDK
  HTTP supplier之前复核一次。mutable Clock Acceptance证明 compose后过期仍停在
  `MODEL_CREATED`，`providerIntents=0`且没有 HTTP effect。
- sequenced Clock loopback sentinel进一步让第一次 freshness read在 expiry前、第二次恰好落在
  expiry，证明第一道 session guard通过后，第二道 pre-HTTP observer仍以
  `requestCount=0 / providerIntents=0` fail closed。

### B · provisioning

bootstrap管理五个 exact role：

- `emergeos_pack010_schema_owner NOLOGIN NOINHERIT`：public schema、relation和非-terminal
  function owner；
- `emergeos_terminal_owner NOLOGIN NOINHERIT`：只拥有两个 V9 semantic function；
- `emergeos_graph_executor LOGIN NOINHERIT PASSWORD NULL`：只 EXECUTE两个 semantic function；
- `emergeos_graph_prefix_writer LOGIN NOINHERIT PASSWORD NULL`：exact prefix relation ACL及
  只读、非-SECDEF integrity assertion closure；
- `emergeos_graph_reader LOGIN NOINHERIT PASSWORD NULL`：exact 15 relation SELECT。

`scripts/provision-pack010-postgres-roles.sh --apply`要求 exact confirmation，并在同一个
`psql --single-transaction`内执行 bootstrap与 pure audit；audit失败会回滚 authority mutation。
`--check`只执行不会 reconciliation的 pure audit。audit以 dedicated database全 grantee
allowlist覆盖 role attributes、membership、database/schema/PUBLIC/default/column/relation/
sequence/function ACL、grant option、owners、trigger exact event/timing/row/WHEN/args topology，
以及 V9 helper的 exact signature/owner/language/SECDEF/volatility/parallel/search_path/body。
全部35个 public non-internal trigger的 relation/name/enabled/tgtype/column/WHEN/args/
constraint-deferrable/function identity被汇总为 shipping SHA-256 topology；provisioning preflight、
pure read-back与 terminal runtime startup/每 TX使用同一 expected fingerprint。pure audit还与
runtime共用16-function signature/result/properties/search_path/body SHA-256 closure，额外 trigger与
此前遗漏的 `agent_graph_head_transition_guard_v8` body drift均 fail closed。
未知 LOGIN role获得普通 CONNECT/USAGE/DML/EXECUTE或 helper ownership也会 fail closed。
真实 wrapper测试预置 default-ACL audit failure，证明五个 bootstrap role在同一 psql transaction
中全部回滚。SQL不接收密码；LOGIN
credential由外部 secret manager在 bootstrap后轮换。测试只使用未记录、未输出的 ephemeral
Testcontainers credential。

### C · writer composition

- `PostgresGraphRuntimeWriters`同时接收 fixed prefix/executor DataSource；prefix必须是 exact
  direct-login role、exact public schema与 trusted default search_path，且 relation/function ACL
  精确匹配。其 Store再对每个新 connection复核冻结 identity；
- `PostgresGraphTerminalExecutor`在每个 TX-B/TX-C transaction内固定
  `pg_catalog, public, pg_temp`，重新审计 role、membership、relation authority、两个 exact
  function、owner与 trigger topology，然后调用 schema-qualified semantic function；构造时
  连续两个独立 connection必须给出同一 frozen database/server identity，alternating/failover
  DataSource在返回 capability前失败；runtime还把两个 semantic body及 selector guard body
  与 shipping expected fingerprint比较，拒绝首次构造前的同形篡改，并要求 executor无
  database TEMPORARY authority；V9 semantic function的 transitive helper closure也按 exact
  signature/owner/ACL/runtime attributes/search_path/SHA-256 body固定，trigger topology额外固定
  `tgattr`；expiry复核与 schema-qualified semantic call合并为一个 SQL statement；
- App侧 `Pack010PostgresRuntimeComposition`在 TX-B前要求 verified sequence 14 /
  `PROVIDER_ATTRIBUTED`，TX-C前要求 sequence 15 / `CHILD_TERMINAL`；两次 request的 exact ordinal、
  actor、pricing fingerprint、64-character responseHash以及 input/cached/output/reasoning/total
  token约束必须先出现在 durable snapshot；它同时绑定 owner-issued terminal capability与
  exact manifest，payload principal/attempt在同一 semantic transaction内再次比对，拒绝
  cross-attempt confused-deputy splice；
- bytecode Gate固定唯一 composition owner与 compiled checkpoint-before-semantic order；
  `GraphEvalMain` incoming reference仍为 0。
- dormant broker的 compiled order固定为 provider-session intent claim/consume → egress consume →
  manifest check → credential marker → `System.getenv`；private credential lease之后才允许构造
  client/model/session。prefix writer的 database/schema/relation/function/column/default ACL snapshot
  在每个新 connection复核，startup后的 TEMP/ACL drift会在 burn terminal claim前失败。
- OwnerTty构造的 Coordinator只接收 authority-bound Store；runtime authority还要求 OwnerTty
  identity与 exact frozen prefix direct-login identity完全一致，admin/migrator/table owner不能
  只凭同库 OID/server identity进入 composition。provider-session intent的 cursor由 SELECT返回的
  sequence/head重新构造并 exact比对，V9另以复合外键绑定 canonical event；session-intent relation
  出现任何非 internal trigger时，provisioning、runtime startup与每连接 identity复核均 fail closed。
- terminal executor不再只学习一个 V9 trigger：它在构造和每个 TX-B/TX-C semantic call前都
  对全部35个 public non-internal trigger执行 shipping expected SHA-256 topology复核；任何新增、
  缺失、disable、retarget、event/timing/WHEN/column/args/constraint/function identity drift均拒绝。

## Reproducible evidence

```bash
./mvnw clean verify
# 11-module BUILD SUCCESS，06:05；134 XML reports / 774 unique testcases，
# 0 failure / 0 error；含 packaged process kill/two-JVM、
# runtime two-database/expiry/restart与全部 integration tests

./mvnw -pl adapters/postgres \
  -Dtest=V9GraphTerminalAuthorityMigrationTest,Pack010ProvisioningWrapperTest test
# 6/6 Green；fresh V9、populated V8→V9、session-intent exact schema/empty upgrade、
# provisioning rollback/idempotence/drift

./mvnw -pl adapters/postgres -am \
  -Dtest='PostgresGraphAttemptStoreTest#ownerProviderSessionIntentIsExactConcurrentExpiryAndRestartFailClosed' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 1/1 Green；packaged TTY/JVM exact binding、claim/consume races、expiry、
# commit前/后 hard-kill与 restart fail-closed

./mvnw -pl adapters/postgres -am test
# contracts 59、core 192、postgres 162；全部 Green

./mvnw -pl apps/graph-eval-runner -am test
# contracts 59、core 192、agent-loop 41、openai 22、postgres 162、App 48；全部 Green

./mvnw -pl apps/graph-eval-runner -am \
  -Dtest=Pack010PostgresRuntimeCompositionIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 1/1 Green；terminal capability、cross-attempt/concurrent negative、fixed roles、
# claim-to-TX expiry、cross-database no-burn、attribution-before-terminal、TX-B/TX-C、
# provider-session intent先落库、postgres restart reconciliation

./mvnw -pl adapters/postgres -am \
  -Dtest=Pack010ProvisioningWrapperTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 2/2 Green；default-deny/fake argv与真实 PostgreSQL audit-failure atomic rollback

./mvnw -pl apps/graph-eval-runner -am \
  -Dtest='GraphEvalArchitectureTest,Pack010ProviderCapabilityBytecodeGateTest,Pack010ProviderSessionEffectOrderingTest,Pack010LoopbackEffectOrderingSentinelTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 18/18 Green；dormant composition allowlist、Main incoming refs=0、compiled order；
# expiry恰好落在两道 guard之间时 loopback HTTP request/provider intent均为0

env -u EMERGEOS_PROVISION_CONFIRM PSQL_BIN=/definitely/not/used \
  scripts/provision-pack010-postgres-roles.sh --apply
# 在调用 psql前 default-deny，exit 4

./scripts/verify-contracts.sh
# 8 schemas / 70 fixtures / 10 synthetic task packs / 4 environments，全部 Green

./scripts/verify-doc-links.sh
# 94 Markdown files的本地链接全部 Green
```

runtime receipt：

```text
PACK010_RUNTIME_COMPOSITION_RECEIPT prefixRole=FIXED executorRole=FIXED
txB=SEMANTIC_ONCE txC=SEMANTIC_ONCE attributionBeforeTerminal=true
sessionIntentBeforeProviderEffects=true
postgresRestart=RECONCILED providerExactlyOnceClaim=false
providerCalls=0 providerNetworkCalls=0 billing=0 shippingExecute=DISABLED synthetic=true
```

## Evidence boundary

Engineering：PUBLIC synthetic data、本机 Testcontainers、real local PTY、test-only packaged
process/JVM、loopback/no-egress。真实 API key、provider/model、真实 r1/r2/r3、provider network、
billing均为 `0`。本轮没有声称 provider exactly-once；restart只证明 intent/cursor/session
attribution/terminal truth的 durable reconciliation。

Human-learning：学习计划暂停；没有 teach-back，也没有真实 owner逐次批准。

Commercial：没有访谈、报价、付款、留存或真实用户验证。

## Remaining risk / Gate

Authority/Live Gate继续 Red：shipping execute仍 disabled；真实 owner交互、credential/provider/
model/network/billing均未获授权；production provider attribution observer尚未形成可成功落库的
完整 response surface；同进程 handoff不支持跨 JVM resume。真实 provider session、credential
读取与网络从未执行，因此这里证明的是 fail-closed构造顺序与 effects=0，不是 live成功路径。

因此 A/B/C只能作为 focused Engineering Green，不是 live route或产品/商业验证。三路最终
post-fix review分别覆盖 A capability/effect ordering、B/C authority/runtime与 Gate/evidence；
review中发现的 sequenced-Clock sentinel和 Receipt drift均已回收，最终为
`P0=0 / P1=0 / P2=0`。
