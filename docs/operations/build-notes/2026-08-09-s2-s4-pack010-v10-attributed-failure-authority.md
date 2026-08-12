# Pack010 V10 attributed failure authority / exact provisioning pair 回执

日期：2026-08-09
Stage：Stage 2 / S4 / F6
结论：V10 exact provisioning pair与数据库级attributed failure authority focused Engineering
slice已Green；overall Authority/Live仍按总控账本`P1=3、P2=1`、Gate Red，shipping execute继续
disabled。

## Outcome

- forward-only V10没有改写V1–V9 migration。fresh V10与populated V8→V9→V10均保持历史
  row、JSON/hash/timestamp、`xmin`及Flyway history fidelity；V9 function只作为历史定义保留，
  `graph_executor`对V9 `EXECUTE=0`；
- active authority surface只有schema-qualified child/parent V10 semantic pair。fresh migration自身
  对4个V10 function执行`REVOKE EXECUTE FROM PUBLIC`；pair为SECURITY DEFINER并固定trusted
  search path，function/helper/trigger topology由SHA-256 fingerprint闭合；
- role provisioning继续与Flyway完全分离。`terminal_owner NOLOGIN`、
  `graph_executor LOGIN NOINHERIT`、generic prefix writer与restricted reader只有exact ACL；
  migrator/table owner不进入runtime。pure audit覆盖role membership、SET ROLE、relation/function/
  schema/column ACL、所有grantee、owner、function/trigger definition，并对unknown function或
  unsupported view/materialized-view/foreign relation fail-fast；
- V10 child transaction只允许success或closed failure allowlist：H1 grounding failure必须绑定
  Candidate-derived attribution；只有`MODEL_RESPONSE_MALFORMED`与
  `MODEL_USAGE_LIMIT_EXCEEDED`允许pre-Candidate failure。forged Candidate及非allowlist failure
  整个TX-B rollback；`lifecycle_status`、Result status、Bundle outcome以及列级、Result、Bundle
  三处failure attribution必须逐层一致，successful Run的全部failure attribution必须为null；
- V10 parent transaction把failed child严格映射为parent `FAILED`、
  `HANDOFF_CHILD_FAILED`与`HANDOFF_REJECTED/CHILD_FAILED`。wrong lifecycle、failure attribution、
  handoff completion status均使TX-C整TX rollback；
- 两个独立executor数据库session同时提交同一个canonical payload时，TX-B与TX-C各只有一个
  winner。PostgreSQL在sequence 15、17后分别restart，fresh read保持seq14→15→17、无partial
  Candidate/WorkerResult/Artifact/seal，并直接read-back durable provider-session intent；
- shipping Main仍不可达execute path。bytecode reachability、dormant preflight与loopback
  effect-ordering sentinels合并证明credential/client/model/provider route不可达，loopback
  `requestCount()`与recording markers直接计量HTTP/client/model/provider effect为0；Receipt中的
  `keyReads=0`不是本IT单独的一只运行时counter。本slice不声称provider exactly-once，真实API
  key/model/provider/network/billing均为0。

## Acceptance Red → minimum fix

1. raw V10 TX-B以`MODEL_ATTRIBUTION_MISMATCH`作为pre-Candidate failure时在修复前能够commit，
   证明V10 SQL protocol比Core typed protocol更宽；
2. historical V9 tests在已有V10时仍调用latest Flyway并断言current=V9，形成stale Red；
3. 原concurrency tests由同一JVM的capability CAS提前截断，不能证明两个独立PostgreSQL session
   对semantic function的原子竞争；
4. provisioning audit遗漏view/materialized-view/foreign relation，unknown terminal-owner function
   的三值逻辑可漏报，active pair仍主要依赖MD5；
5. 首次root `clean verify`在packaged-process restart IT发现readiness仍固定V9：
   `expected 9 but was 10`；下一次root run又发现Pack009 crash IT仍断言9条Flyway history。
   两处latest-only断言都更新为current V10，historical V9 acceptance仍显式target 9，没有放宽
   runtime Gate；
6. post-fix review发现raw executor可以让列级allowlisted code保持不变，却伪造
   `result_envelope.failureReason`与`bundle.result.failureReason`；successful Run也能让列、Result、
   Bundle携带同一个非null failure code。两个Acceptance Red在旧V10都实际commit；修复后均以
   SQLSTATE `22023`及V10 TX-B semantic诊断fail closed，head仍为14且无partial row；
7. failure review/claim、runtime四个terminal method以及writer prepare/complete未进入bytecode
   exact consumer closure。directory与shipping JAR注入的allowed-name consumer在修复前未被拒绝；
   minimum fix把composer调用限定为exact RuntimeComposition，把shipping-dormant runtime terminal
   method固定为no-consumer，并同时冻结writer exact consumer；
8. unknown terminal-owner function drift测试原先会先撞function owner drift，不能隔离证明unknown
   EXECUTE allowlist。测试先把function owner改为exact schema owner，再只因unexpected
   terminal-owner EXECUTE失败；
9. post-fix review发现bytecode Gate只扫描app package，其他first-party package可以直接引用四个
   public failure writer method而不触发exact-consumer规则。adapter-package directory/JAR negative先
   复现该旁路；首次把全部历史capability规则整体扩大后，packaged JAR又以6个既有合法依赖引用
   形成Focused Red。minimum fix最终仅把四个pre-Candidate failure writer direct member规则扩到
   全部first-party class，其余app-only规则保持原作用域，packaged process Gate重新Green。

minimum fix只收窄V10 semantic protocol、ACL/fingerprint audit与latest-version evidence；没有把V9
重新授予executor、没有引入caller-minted GUC permit，也没有开放shipping execute。

## 可复现 evidence

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=V10GraphChildAuthorityMigrationTest,V9GraphTerminalAuthorityMigrationTest,Pack010ProviderCapabilityBytecodeGateTest,GraphEvalArchitectureTest,Pack010AttributedFailureTerminalAcceptanceTest,Pack010PostgresRuntimeCompositionIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 24 focused tests / 0 failure/error/skipped

./mvnw --batch-mode --no-transfer-progress clean verify
# 11 modules / 139 XML reports / 801 tests
# 0 failure / 0 error / 0 skipped；BUILD SUCCESS；5m32s

./scripts/verify-contracts.sh
# 8 schemas / 70 fixtures / 10 synthetic task packs / 4 environments，Green

./scripts/verify-doc-links.sh
# 100 Markdown files，Green

git diff --check
# Green
```

关键数据库回执：

```text
PACK010_V10_FRESH_RECEIPT migration=10 exactFunctions=4 helperClosure=17 authorityGuard=v10 integrityGuard=v8 sealCandidateFk=MATCH_SIMPLE
PACK010_V9_TO_V10_FIDELITY_RECEIPT populated=true rowsAndXmin=UNCHANGED historyV1V9=UNCHANGED
PACK010_V10_RAW_DB_CONCURRENCY_RECEIPT txBIndependentSessions=2 txBWinner=1 txCIndependentSessions=2 txCWinner=1 seq14to15to17=true partialRows=NONE postgresRestart=SEQ15_AND_SEQ17_RECONCILED providerExactlyOnceClaim=false synthetic=true
```

active function body SHA-256：

- child V10：`db30bd2a5792296ba8659d68d9e665c341b9a841bc0abe319a9c1cbaaea401ae`；
- parent V10：`57d7cce3f407c9198b2557e3b026e468ffea879d015071ee977085e4cc6c5d35`；
- executor guard V10：`bc77084ab3530fdabd0b8e03ebb8bd59e22395cc81fbbd91876bdd7b7a39ccd1`；
- integrity guard V10：`561eaf8977811117ceb8357bfe002f98ab6eba2c52db38885777552454affb0b`；
- helper closure：`3a86bdd6b2509fdcb7e8d3d15f931e0ac4a2f23818b4d225e61aa0957bc6202a`；
- trigger topology：`5bbd4e20e187296c0094b11f3aa4b6871d1bc40b9301a05e5669d418c887f84c`。

## 冻结快照

HEAD保持`6d914d8b44498c857768ea5da97fa204db562966`；没有commit、push、deploy或release。
22-file ordered V10 slice aggregate为
`aea21ca80cdfca3fe0708bf5ce9ed18e9b5331aeb48ab4467ee8d60b1098cb7e`；复算方式是按下列
顺序对每个文件执行`shasum -a 256`，再对完整输出执行一次`shasum -a 256`：

1. `adapters/postgres/src/main/resources/db/migration/V10__harden_graph_child_terminal_authority.sql`；
2. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql`；
3. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql`；
4. `scripts/provision-pack010-postgres-roles.sh`；
5. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters.java`；
6. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutor.java`；
7. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalPayloads.java`；
8. `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack010PostgresRuntimeComposition.java`；
9. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V10GraphChildAuthorityMigrationTest.java`；
10. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V9GraphTerminalAuthorityMigrationTest.java`；
11. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java`；
12. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`；
13. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Stage1MigrationAndRecoveryTest.java`；
14. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java`；
15. `apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java`；
16. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010PostgresRuntimeCompositionIT.java`；
17. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
18. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalArchitectureTest.java`；
19. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java`；
20. `apps/graph-eval-runner/src/test/java/io/emergeos/adapters/postgres/Pack010GraphTerminalStoreBridge.java`；
21. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010GraphTerminalFixture.java`；
22. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`。

shipping graph-eval JAR SHA-256：
`9ee7fe7b02a0020b68e77d2d79f1428ccba6f6400516bf2f9cbc0fe1ce5eecd4`。

## 独立复核

三路只读review分别负责threat/ACL、Acceptance/Gate、typed protocol；前一快照发现的nested
failure consistency、bytecode consumer closure、unknown-function test isolation均已进入上面的
minimum fix。三路均在最终root Gate之后独立命中22-file aggregate、shipping JAR与
`139/801/0`报告，并完成实际post-fix review：

- ACL/provisioning：`P0=0、P1=0、P2=0`，source、`target/classes`与shipping JAR内V10/roles/check
  三份SQL逐字hash一致；
- Acceptance/Gate：`P0=0、P1=0、P2=1`，nested/success/BLOCKED negatives、SQLSTATE 22023、
  rollback/restart与Receipt计数可签收；P2是raw race helper尚未在取得两条connection后同步、
  未断言不同backend PID与loser exact SQLSTATE/message；
- typed protocol/bytecode：`P0=0、P1=0、P2=2`，typed nested closure与first-party direct consumer
  closure可签收；P2为间接反射边界，以及failure claim本身的concurrent/replay和success↔failure
  双向cross-claim/no-burn尚无直接falsifiable matrix。

这些是同一focused snapshot的scoped review结果；不得把三路`P1=0`外推为overall Authority/Live
`P1=0`，scoped P2也只并入总控现有evidence-hardening风险，不重写总控`P1=3、P2=1`账本。

## 剩余风险与下一条 Acceptance

- 数据库raw semantic pair已经与Core failure mapping对齐，但process-local typed failure outcome
  尚无durable、hard-kill后可恢复且跨JVM fenced的provenance；这是overall P1，不能由DB函数Green
  替代；
- bytecode exact-consumer Gate已覆盖全部first-party class对四个public failure writer method的
  direct constant-pool member reference；dependency first-party class的反射/MethodHandles间接访问仍
  是后续defense-in-depth P2，不外推为任意内部反射均已闭合；
- failure outcome的success/failure claim共享同一one-shot CAS且源码fail closed，但还缺
  failure-claim concurrent/replay与success↔failure双向cross-claim/no-burn直接Acceptance；
- raw PostgreSQL race已取得两个独立connection的一胜一败与restart truth，但barrier位于connection
  acquisition之前，尚未记录不同`pg_backend_pid()`并锁定loser exact SQLSTATE/message；这是证据
  精度P2，不把当前结果外推为已证明实际事务重叠调度；
- 本slice证明独立DB session原子竞争与restart truth，不证明provider exactly-once，也没有真实
  owner逐次授权、真实r1/r2/r3、credential或网络调用；
- shipping execute、live provider route与billing继续为0/disabled；overall Authority/Live保持
  `P1=3、P2=1`、Gate Red；
- 下一条安全Acceptance应先设计durable typed failure outcome/resume fencing与cross-process
  evidence，再考虑任何live route；若涉及owner批准、credential、付费资源或外部写操作，必须
  Human-in-the-loop。

## Evidence lanes

- Engineering：本V10 focused slice Green；overall Authority/Live仍`P1=3、P2=1`、Gate Red；
- Human-learning：0；没有teach-back、访谈或owner live验证；
- Commercial：0；没有报价、付款、真实billing或客户验证；
- Human-in-the-loop：当前local/synthetic slice不需要；live enablement需要。
