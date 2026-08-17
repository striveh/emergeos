# Pack010 durable attributed failure / cross-JVM atomic terminal resume 回执

日期：2026-08-09；V12 closure：2026-08-10
Stage：Stage 2 / S4 / F6
结论：forward-only PostgreSQL V11 durable typed failure provenance/claim与V12跨JVM atomic
failure TX-B/TX-C terminal resume的focused Engineering slice已Green；overall Authority/Live
Gate仍Red，shipping execute继续disabled。

## Outcome

### V12 atomic terminal resume closure

- V12新增一对数据库semantic completion函数与durable terminal receipt，把exact provenance、claim
  version、claimant、fence、DB-clock lease和live head的校验，同failure child TX-B或parent+seal TX-C
  放在同一个PostgreSQL transaction内。happy path从`READY/v1/head14`依次推进到
  `CLAIMED/v2`、`CHILD_CONSUMED/v3/head15`、`PARENT_CLAIMED/v4`、
  `TERMINAL_CONSUMED/v5/head17`；每个commit只允许version与head各推进一次；
- raw V10 failure TX-B/TX-C不再是可绕过入口：V12 deferred coherence guard要求closed attributed
  failure必须有matching V11 outcome与V12 receipt。无sidecar、claimed-but-unreceipted、wrong
  model/provenance/version/claimant/fence、过期lease、stale/replayed claimant全部整transaction
  fail closed；latest V12上的V10 success TX-B/TX-C仍保持exact one winner与restart Green；
- production `PostgresAttributedFailureResumeStore`以typed API完成child/parent terminalization；completion
  内部绑定restricted reader DataSource，并按database name/OID/server address/port拒绝cross-database
  snapshot splice。semantic function回执与同transaction read-back必须逐字段一致，Java不做
  check-then-write，也不接受public raw JSON payload；
- packaged fresh JVM分别claim与complete TX-B/TX-C；child/parent两个不同JVM race每阶段exact one
  winner，并按loser PID锁定唯一`reason=FENCED`回执。TX-B commit后执行PostgreSQL immediate
  restart，fresh JVM继续parent claim与TX-C。
  test-only DataSource proxy在Spring真实`Connection.commit()`入口、delegate commit之前分别
  `Runtime.halt(76/77)`：V12 function与同transaction read-back已经执行，但数据库完整JSON/`xmin`
  仍回滚；backend消失后fresh JVM重试成功；
- completion负向矩阵以真实DB clock先证明child与parent各自lease过期并分别reclaim，再在20秒live
  claim上逐项执行wrong provenance/version/claimant/fence、child model与expired lease canary；每个
  live-field canary调用前再次由DB clock证明租约余量。11个typed completion API case加1个raw V12
  wrong-model SQL canary共12项，均保持全部public table完整JSON/`xmin`不变；
- V11→V12 migration对合法READY/CLAIMED head14保持V1–V11 history、业务row与`xmin`不变；对历史
  claimed/no-sidecar raw V10 head15/head17均以SQLSTATE `55000`整migration回滚，V12 table、六个函数、
  两个trigger不残留。fresh V12在provisioning前已证明六函数PUBLIC EXECUTE为0，其中五个函数
  为SECURITY DEFINER，唯一invoker helper仍固定`search_path`；
- active resumer surface从V11 read/claim切换为exact四个V12函数且relation ACL继续为0；executor继续
  保持V10 success pair并把V12 assertion function body纳入runtime fingerprint。membership、relation
  ACL、extra EXECUTE、owner/body/trigger closure drift均fail closed；
- bytecode Gate把新增completion descriptor固定为first-party reviewed caller only，并同时拒绝adapter
  direct与reflection绕过；shipping shaded JAR不包含test harness/CLI route。所有Acceptance均使用本机
  PostgreSQL/Testcontainers与PUBLIC synthetic data，provider/client/model/key/HTTP/billing effect未启用。

### V11 durable provenance / claim basis

- `OpenAiResponsesModel`先完成exact response receipt与typed outcome判定，再由
  `ExactProviderOutcomeObserver`交给Pack010 composer；request-2 attribution、closed failure code
  与durable failure sidecar在同一个PostgreSQL transaction内提交。probe rollback时只能看到
  sequence 13且sidecar为0；成功时只能看到sequence 14且sidecar恰为1；
- forward-only V11新增不可由caller拼装的durable provenance：绑定principal、attempt、manifest、
  revision、sequence-7 session intent与expiry、sequence-13 predecessor、sequence-14 cursor、两条
  ordered attribution hash、request/response hash、resolved model、exact closed failure code及
  canonical provenance SHA-256。V1–V10 bytes/history不改写；populated V10→V11原row与`xmin`
  保真；
- durable cursor以数据库truth从`READY/version=1`进入`CLAIMED/version+1`。claim在同一
  `SELECT ... FOR UPDATE` transaction内绑定claimant、fence token、DB-clock lease、session expiry、
  exact live head与expected version；wrong/stale/replayed/concurrent/expired全部fail closed；
- packaged JVM A在outcome commit后直接`Runtime.halt(71)`，fresh JVM可通过production
  resume store读取并claim数据库中的durable provenance；
  两个不同PID的fresh JVM同时claim同一version时exact one winner。winner在claim commit后
  `Runtime.halt(72)`，restart前的too-early successor在lease内被fence；PostgreSQL immediate
  restart后等待DB clock lease到期，另一个fresh JVM以version 2原子reclaim到version 3，旧进程与
  重复successor均被fence；
- 在V11 target上，独立provisioning继续与Flyway schema migration分离。新增
  `emergeos_failure_resumer LOGIN NOINHERIT`只取得schema-qualified V11 read/claim exact
  `EXECUTE`，relation ACL为0；generic prefix writer只可执行V11 recorder，`graph_executor`仍只有
  active V10 TX-B/TX-C pair，restricted reader只读，terminal owner保持NOLOGIN。V11 migration自身
  对三个function执行`REVOKE EXECUTE FROM PUBLIC`；
- V11 slice的resumer store在构造时双读冻结server/database/role identity与三个V11 function body hash，每次
  load/claim又在同一transaction复核Java侧frozen identity/body fingerprint；V11 read/claim函数自身
  复核role membership、DB/schema/relation/column/sequence/function ACL、owner、function properties、
  fixed search_path与trigger topology。membership、direct relation ACL、extra EXECUTE、owner及body
  drift均在live call fail closed；
- shipping Main对resume store仍无入口，provider/client/model/credential capability没有交给successor。
  process Acceptance全程使用PUBLIC synthetic data、ephemeral Testcontainers credentials和本机
  PostgreSQL；harness不构造credential/client/model/HTTP/provider对象，shipping bytecode又把resume
  store固定为no-consumer，所以对应effect path不可达。子进程输出的`effects=0`是scope marker，
  不是六个独立runtime counter；本slice不声称provider exactly-once。

## Acceptance Red → minimum fix

1. runnable Acceptance首次在旧快照得到`relation agent_graph_attributed_failure_outcomes does not
   exist`，证明typed failure verdict与claim状态没有durable truth；
2. 同一Acceptance启动两个独立packaged JVM（PID 14144、14145），两边各自持有process-local
   `AtomicBoolean`并都输出`PACK010_DURABLE_FAILURE_CLAIMED`，证明进程内one-shot不能作为
   restart/cross-JVM fence；
3. 原provider route先durable attribution、后解析final/usage，存在sequence 14已经commit而
   `MODEL_RESPONSE_MALFORMED`或`MODEL_USAGE_LIMIT_EXCEEDED`仍只在内存的kill窗口；minimum
   fix引入exact outcome observer，并让Pack010 failure route只调用一次
   `providerFailureAttributed`，在一个transaction里写attribution/event/head与sidecar；
4. 最初沿用prefix writer作为read/claim identity会扩大generic runtime authority；minimum fix新增
   dedicated resumer role，并把prefix/executor/resumer surface拆成record-only、V10 pair-only、
   V11 read+claim-only；provisioning第二次幂等执行中owner EXECUTE会被全量revoke，最终以exact
   owner regrant闭合；
5. latest migration从V10前进到V11后，历史latest-version与public table/reader allowlist断言形成
   focused Red；修复只更新current-V11 evidence及新增sidecar read surface，V9/V10 historical tests
   继续显式target各自版本；
6. V11 typed-failure专属probe进入旧successful TX-A通用37-point matrix后无法到达。该probe没有被
   删除：它移入本slice专属rollback/hard-kill/two-JVM/restart Acceptance；通用terminal matrix保留
   其余37个有效probe（TX-A 4、TX-B 14、TX-C 19）。
7. post-fix review发现bytecode Gate只冻结`GraphAttemptStore.providerFailureAttributed`的interface
   dispatch，concrete `PostgresGraphAttemptStore`调用可绕过。minimum fix为concrete owner+descriptor
   增加first-party-wide no-consumer rule，并以adapter-package directory/shipping-JAR negative同时锁定
   `invokeinterface`与`invokevirtual`；
8. post-fix review发现离线provisioning check不能代替runtime drift fail-closed。Acceptance新增
   membership、direct relation ACL、extra EXECUTE、function owner/body五类provisioning后漂移；minimum
   fix让Java frozen identity/body fingerprint与V11 SECURITY DEFINER内exact topology在同一transaction
   双重拒绝，SQLSTATE `42501`归一为domain integrity failure。正常topology首次因owner ACL entry的
   grant-option位产生false positive；最终以exact `proowner`证明owner authority，非owner ACL仍只接受
   exact non-grantable allowlist；
9. 第二轮review发现上述五类drift Acceptance使用的attempt没有V11 sidecar，删除全部runtime guard也会因
   空查询而抛出同类异常，属于false Green。最终Acceptance先以production prefix writer生成真实
   sequence-14 `READY/version=1` sidecar并证明baseline load成功；五类drift分别断言load/claim拒绝、
   清理后与baseline逐字段等值。body drift使用仍可返回完整provenance的函数作为canary：raw resumer
   调用成功，而frozen store因body SHA变化在调用前拒绝，证明不是由空结果或坏SQL旁路触发。

## 可复现 evidence

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=__NoSuchUnitTest__ \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=Pack010DurableFailureTerminalResumeProcessIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
# 3 process tests / 0 failure/error/skipped；pre-commit halt 76/77、
# fresh-JVM retry、PG restart、two-JVM race exact FENCED loser、
# 12项completion negative matrix与raw V10 fence均Green

./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/postgres -am \
  -Dtest=V11DurableAttributedFailureMigrationTest,Stage1MigrationAndRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 7 migration/authority tests / 0 failure/error/skipped；BUILD SUCCESS

./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=__NoSuchUnitTest__ \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=Pack010DurableAttributedFailureResumeProcessIT \
  -DfailIfNoTests=false verify
# Acceptance Red：2 tests / 1 failure / 1 error；missing relation；两个JVM均claim
# minimum fix：3 tests / 0 failure/error/skipped

./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=__NoSuchUnitTest__ \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=Pack010DurableGraphTerminalProcessIT,Pack010ExactProviderAttributionPostgresIT,Pack010PostgresRuntimeCompositionIT,Pack010DurableAttributedFailureResumeProcessIT \
  -DfailIfNoTests=false verify
# 11 focused integration tests / 0 failure/error/skipped；BUILD SUCCESS

./mvnw --batch-mode --no-transfer-progress clean verify
# 11 modules / 142 TEST XML reports / 815 tests
# 0 failure / 0 error / 0 skipped；BUILD SUCCESS；6m29s

./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
# 均Green
```

关键可执行事实：

- outcome INSERT probe强制rollback后：sequence 13、outcome rows 0；retry后sequence 14、rows 1；
- `mint-and-halt`在commit后`halt(71)`；fresh JVM的production load/claim消费数据库中绑定的failure
  code、两条attribution、response hash、session intent、cursor/head与provenance。测试没有另建独立
  field-by-field/hash oracle，因此不声称由第二套实现重算完整provenance；
- two-JVM claim：两个独立PID与两个独立DB session，exit code集合为`{0,4}`，数据库只推进一次；
- `claim-and-halt`在claim commit后`halt(72)`；restart前too-early successor在lease内被fence；
  PostgreSQL `pg_ctl restart -m immediate`后等待DB clock expiry，fresh JVM再原子reclaim为version 3；
- wrong manifest、wrong provenance、stale version、invalid lease、expired session、early successor、
  stale killed claimant与replayed successor均保持head/row truth不变；
- live drift Acceptance以真实sequence-14 sidecar先证明baseline load；membership drift同时覆盖load与
  claim的exact integrity failure，relation ACL、extra EXECUTE、owner、body drift分别拒绝并在恢复后
  回到同一baseline；
- V11 fresh authority与populated V10→V11 fidelity、provisioning二次幂等、ACL/read-back/drift tests
  均进入同一root Gate。

## 冻结快照

HEAD保持`6d914d8b44498c857768ea5da97fa204db562966`；没有commit、push、deploy或release。

V12 closure的26-file ordered slice aggregate为
`6f51c0fbe0f4ec82ef0691579b6658fd5059aaae9674567048e5428b1387922e`；关键文件与shipping
artifact SHA-256为：

- V12 migration：`d4fbfc0939f403114a4a397e998a1db4ab9f5a5c5abf3e54426538f998d9a6cb`；
- roles：`606308390f263d6c74a92d0d4c9bebfb27c0a394becca99a1e052eb94c4be235`；
- pure audit：`5bbb6daecd886b6aa6e43cc73ea7369ec58b20ab54f45eda35d0a9b7d076a0fe`；
- production resume store：`198de08901fbec97104d69567a7ef030ed35e55b9e423e7ca8c9ff79b6476d51`；
- shipping graph-eval app JAR：`9d2981ca40d6a0dc2dc9dfcaa7db1cbef5f8caed198f4ef49c50c1c32d31872d`。

V12 migration、roles与pure audit在source、`target/classes`及shipping app JAR内逐字SHA-256
一致。

该aggregate按以下固定顺序对逐文件`shasum -a 256`完整输出再次执行`shasum -a 256`得到；不包含
本Build Note自身：

1. `adapters/postgres/src/main/resources/db/migration/V12__atomic_attributed_failure_terminal_resume.sql`；
2. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql`；
3. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql`；
4. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresAttributedFailureResumeStore.java`；
5. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutor.java`；
6. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters.java`；
7. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStore.java`；
8. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V11DurableAttributedFailureMigrationTest.java`；
9. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V9GraphTerminalAuthorityMigrationTest.java`；
10. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Stage1MigrationAndRecoveryTest.java`；
11. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java`；
12. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`；
13. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java`；
14. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
15. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`；
16. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010CommitBeforeDelegateHardKillDataSource.java`；
17. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009ProcessSupport.java`；
18. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableAttributedFailureResumeHarnessMain.java`；
19. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableFailureTerminalResumeProcessIT.java`；
20. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableFailureTerminalResumeRedHarnessMain.java`；
21. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableAttributedFailureResumeProcessIT.java`；
22. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010PostgresRuntimeCompositionIT.java`；
23. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java`；
24. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java`；
25. `apps/api/src/test/java/io/emergeos/api/PostgresApiTest.java`；
26. `apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java`。

以下V11冻结快照保留为历史基线。34-file ordered V11 slice aggregate为
`18ae990f1fd73f391a0a044d98c62f6f6fef9e67fa6cccc6ab37d26c9beefc0c`。复算方式是按下列
顺序对每个文件执行`shasum -a 256`，再对完整输出执行一次`shasum -a 256`：

1. `modules/core/src/main/java/io/emergeos/core/domain/GraphAttributedFailureCode.java`；
2. `modules/core/src/main/java/io/emergeos/core/port/GraphAttemptStore.java`；
3. `modules/core/src/main/java/io/emergeos/core/application/GraphAttemptCoordinator.java`；
4. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStore.java`；
5. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresAttributedFailureResumeStore.java`；
6. `adapters/postgres/src/main/resources/db/migration/V11__durable_attributed_failure_resume.sql`；
7. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql`；
8. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql`；
9. `scripts/provision-pack010-postgres-roles.sh`；
10. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphAttemptAccess.java`；
11. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters.java`；
12. `adapters/openai/src/main/java/io/emergeos/adapters/openai/OpenAiResponsesModel.java`；
13. `adapters/openai/src/main/java/io/emergeos/adapters/openai/ReviewedOpenAiClient.java`；
14. `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack010ProviderSessionComposer.java`；
15. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V7GraphAttemptSqlSeeder.java`；
16. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V11DurableAttributedFailureMigrationTest.java`；
17. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V9GraphTerminalAuthorityMigrationTest.java`；
18. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V10GraphChildAuthorityMigrationTest.java`；
19. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Stage1MigrationAndRecoveryTest.java`；
20. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java`；
21. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`；
22. `adapters/openai/src/test/java/io/emergeos/adapters/openai/OpenAiResponsesModelProtocolTest.java`；
23. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
24. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalArchitectureTest.java`；
25. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`；
26. `apps/graph-eval-runner/src/test/java/io/emergeos/adapters/postgres/Pack010GraphTerminalStoreBridge.java`；
27. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableAttributedFailureResumeHarnessMain.java`；
28. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableAttributedFailureResumeProcessIT.java`；
29. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java`；
30. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010PostgresRuntimeCompositionIT.java`；
31. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactProviderAttributionPostgresIT.java`；
32. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010PostgresHarnessReportIT.java`；
33. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java`；
34. `apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java`。

文件与shipping artifact SHA-256：

- V11 migration：`ed46f26b761ccd85619679224fedc99be9f4cad260488aa10db2a9b723655276`；
- roles：`c9230c81922b6e0465f817f46d6aced26613de07e1e83338919214ec2aabc266`；
- pure audit：`e7d8f92106aa3b5f15e85e60dfa0c078b520852aa1c5819c9ff316e368e28ac8`；
- provisioning wrapper：`72059564cfc19b72533801bf4e78061b40253cbf454a8ced6efc29b0978f5eb4`；
- shipping graph-eval app JAR：`e5b8b27a0e93355ad8e894fa1742c38db7d1521fdcaa2b0fba91c18f074af051`。

V11 record/read/claim function body SHA-256分别为
`4ddd44034bcaf7aa516c78785f0095c9cacec933b651630d2a1675a161de31b4`、
`d8e46321a9f61246a5dff147bab321f8985552a430042b208c42cbd8b0219d41`、
`546182f85842c6adba7db4f37260f111f87f55c50e311624c273025410a1955b`。
V11/roles/audit在source、`target/classes`与shipping JAR内逐字hash一致。

## V12当前独立复核

三路只读review均在最终候选与独占root Gate之后实际复算26-file aggregate、shipping app JAR、
142/815/0、contracts、doc links与diff，并复核各自代码边界：

- Acceptance/process evidence：`P0=0、P1=0、P2=0`，可签收focused slice。确认pre-commit seam
  位于真实`Connection.commit()` delegate之前；TX-B/TX-C完整public-table JSON/`xmin`回滚、fresh JVM
  retry、PostgreSQL immediate restart、双JVM单赢家且loser精确`reason=FENCED`、migration四格及
  raw V10 exact negative均成立。completion API的wrong provenance/version/claimant/fence/model与
  expired lease 11项typed negative及1项raw SQL model canary均可执行，并逐项保持全库JSON/`xmin`
  不变；
- ACL/provisioning/migration：`P0=0、P1=0、P2=0`。确认upgrade fail-close、durable state/lease/receipt
  invariant、raw V10 coherence guard、fresh pre-provision PUBLIC revoke、owner/ACL/function/trigger closure
  与source/target/JAR保真；
- typed protocol/bytecode：`P0=0、P1=0、P2=0`。确认production Store只有typed surface与same-DB
  binding，DB semantic function/receipt/read-back同transaction，direct/reflection Gate与shipping-JAR
  exclusion闭合。

三路结论只签收本focused Engineering slice，不外推overall Authority/Live Gate。工作树不是git clean；
这是由26-file aggregate与shipping JAR冻结的scoped candidate，不是commit/release readiness。

## V11历史独立复核

三路只读review均在上面最终冻结候选与root Gate之后实际重算34-file aggregate、shipping JAR、
141/807/0、contracts、doc links与diff，并复核各自代码边界：

- Acceptance/process evidence：`P0=0、P1=0、P2=0`。确认真实sequence-14 baseline关闭空查询
  false Green，五类drift恢复后等值，hard-kill/two-JVM/restart/fault矩阵与Receipt措辞一致；
- ACL/provisioning/migration：`P0=0、P1=0、P2=0`。确认load/claim authority drift均保持
  integrity failure，Java body SHA与SQL topology分层闭合，fresh/populated及source/target/JAR保真；
- typed protocol/bytecode：`P0=0、P1=0、P2=2`，可签收focused slice。两个non-blocking P2为：
  first-party direct bytecode consumer已闭合，但adapter reflection/MethodHandles与外部未扫描consumer
  仍属于defense-in-depth边界；process由production load/claim消费durable provenance，但没有第二套
  field/hash oracle，`effects=0`也只是结构性scope marker而非六路runtime counter。

三路结论只签收本focused Engineering slice，不外推overall Authority/Live Gate。

## 剩余风险与下一条 Acceptance

- V11记录的“claim后不能跨JVM完成failure TX-B/TX-C”缺口已由V12关闭；V12 scoped Gate不外推为
  provider semantic attestation或shipping live route Green；
- PostgreSQL能证明持久化的是closed typed failure code及其hash-bound provenance，但只凭
  response hash不能独立证明response bytes确实malformed。live semantic attestation若要求DB自身
  验证，需durable exact bytes/validation transcript或签名attestation；本slice明确不作该声明；
- provider请求可能在进程kill前已经到达provider；本slice没有、也不声称provider exactly-once。
  successor没有credential/client/model/egress capability，因此不会自行重放provider effect；
- lease只能在原owner/session expiry内claim或reclaim，不能续期。expiry后是否允许新owner
  reauthorization属于产品/authority决策，当前fail closed；
- shipping execute、live provider route、真实r1/r2/r3、API key、model、network与billing继续为
  disabled/0；overall Authority/Live Gate保持Red。

## Evidence lanes

- Engineering：V11 durable provenance/claim + V12 cross-JVM atomic terminal resume focused slice
  Green；overall Authority/Live仍Red；
- Human-learning：0；没有teach-back、访谈或owner live验证；
- Commercial：0；没有报价、付款、真实billing或客户验证；
- Human-in-the-loop：当前local/synthetic defensive slice不需要；live enablement、owner
  reauthorization与真实provider调用需要。
