# Pack010 V15 exact provider TX-A requirement guard 回执

日期：2026-08-12

Stage：Stage 2 / S4

结论：V15 本地 `REQUIREMENT_GUARD_ONLY` Engineering slice 已 Green；
`markerAt=SEQ13`、`productionJavaApi=0`、`exactPicoAttribution=NOT_IMPLEMENTED`、
`TX-A=NOT_IMPLEMENTED`、`providerNetwork=0`、`billing=0`。overall Authority、
shipping Live、真实 provider compatibility 与 Commercial evidence 继续为 Red。

## Outcome

- forward-only V15 保留 V8-V14 relation/canonical identity，在 exact seq13/head13 增加
  `PICO_OVERLAY_V1` requirement marker；没有 round 或重解释 V8/V13 integer nano-USD truth；
- `agent_graph_require_exact_tx_a_v15(varchar,char,char,varchar)` 在 PostgreSQL 内锁 durable head，
  自行读取 head 与 ACTIVE/effective V14 profile hash，并写入不可变 marker。caller 不能自报 head hash
  或 profile hash；
- 四个 deferred constraint trigger 保护 marker、历史 V8 attribution、event 与 head。marker 存在后，
  历史 request-2 13-to-14 TX-A 固定以 `55000 / V15 exact provider attribution is required`
  回滚整个 transaction；
- 无 V13 policy 的 production legacy typed TX-A 与合法 V13 REQUIRED + test-only Ed25519 signer 路径
  均由数据库提交 authority 拒绝。后者明确到达 `STAGED=1 / VERIFIED=1 / COMMITTED=0 / signer=1`，
  随后完整回滚到 REQUIRED/head13；
- 两个独立 `READ COMMITTED` backend 以真实 `pg_blocking_pids` 证明双方锁序：marker-first 时 V13
  阻塞后被 fenced；V13-first 时 marker enrollment 阻塞后被 fenced。每个方向只有一个 durable winner；
- 未登记 marker 的 V13 positive control 保持原行为，合法走到 validation CONSUMED/head14；
- V14→V15 populated migration 保持 V1-V14 rows/`xmin`、V13/V14 function catalog、canonical sample、
  旧 trigger image 与 Flyway history 不变；V15 只新增一个空 marker relation、两个 function 与四个 trigger；
- 独立 `emergeos_provider_attestor_v15 LOGIN NOINHERIT` 只有 CONNECT、schema USAGE 与 require
  function EXECUTE，relation ACL 为 0；pure audit 冻结 owner/body/ACL/relation/trigger topology；
- production Java V15 domain/port/mapper/signer/attestor/public API 增量为 0。first-party Bytecode Gate
  对 V15 function/relation/role 四个 authority 常量无 allowlist，directory、synthetic JAR 与真实 shaded
  app JAR 均证明 shipping consumer 为 0。

## Acceptance Red → minimum fix

1. V8/V13 只能保存 integer nano-USD/token，无法无损表达 2.8 nano-USD/token。minimum fix 没有制造
   rounded compatibility attribution，而是先建立 seq13 one-way requirement guard；
2. 首个 Red 在 latest schema 上缺少 V15 role/function/relation surface。minimum SQL 只增加 marker、require
   function 与 deferred guard；没有提前增加无法消费的 Java exact-pico statement/attestor surface；
3. 仅测无 V13 policy 的 legacy path 可能被已有 V13 guard 代打。扩展 Acceptance 让有效 V13 stage 与签名
   verification 真正发生，并锁 `COMMITTED=0`、底层 exact V15 `55000` 与 full rollback；
4. 新 trigger 改变了全局 trigger authority identity。minimum fix 在三个既有 runtime authority class、
   provisioning 与 pure audit 中冻结同一新 topology hash，没有排除 V15 trigger 或放宽审计；
5. 人工 latch 不能证明数据库锁竞争。最终 race canary 只在 statement entry 观测，真实阻塞必须由
   `pg_blocking_pids(blocked_backend)=winner_backend` 证明，然后再释放 winner；
6. provisioning、latest schema、public-table inventory、cleanup 与 packaged migration resource 全部扩到 V15，
   同时保持历史 target/custom-schema fixture 的旧版本边界；
7. Gate 增加 first-party-wide、无例外的四常量 negative，并由 shade 后真实 app JAR 再执行一次
   `shippingJarViolations`，避免只扫 test-classes 或旧 artifact 的假绿。

## 本地 executable evidence

最终冻结前执行并核对：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
```

独占 root `clean verify` 于 2026-08-12 00:56–01:03 完成，11 modules、147
`TEST-*.xml` reports、833 tests、0 failure/error/skipped/flake，`BUILD SUCCESS`，总时长 6m34s。
其中 V15 Acceptance 1/1、graph-eval surefire 70/70、graph-eval failsafe 20/20 Green；已有 packaged
crash/restart/race/resume 只作为 **未登记 marker 的历史路径 regression**，不是 marked-attempt process evidence。
contracts 为 8 schemas/70 fixtures；111 Markdown files 的 local links 与 `git diff --check` 均 Green。

关键 freshness：V15 Acceptance source 00:54:57 < class 00:59:49 < XML 00:59:53；
V14→V15 migration test source 00:33:18 < class 00:57:01 < XML 00:57:12；Bytecode Gate source
00:08:58 < class 00:59:49 < XML 01:00:03；actual app JAR 01:00:06 < packaged ProcessIT XML
01:02:08。

三条 executable receipt：

```text
PACK010_V15_EXACT_TX_A_REQUIREMENT_ACCEPTANCE_RECEIPT markerAt=REP1_SEQ13,REP2_SEQ13 legacyTypedTxA=DB_55000 v13TypedTxA=STAGED1_VERIFIED1_COMMITTED0_DB_55000 linearization=MARKER_FIRST_V13_FENCED,V13_FIRST_MARKER_FENCED dbBlocking=PG_BLOCKING_PIDS_BOTH_DIRECTIONS fullPublicTableJsonXmin=UNCHANGED rep1V13Policy=ABSENT rep2V13Policy=REQUIRED_UNCHANGED rep3Marker=ABSENT exactPicoAttribution=NOT_IMPLEMENTED txA=NOT_IMPLEMENTED providerNetwork=0 billing=0

PACK010_V15_FRESH_V12_AUTHORITY_RECEIPT migration=15 PUBLIC_EXECUTE=0 prefix=record-only executor=v10-pair-only resumer=v12-read+claim+terminal-only resumerRelationACL=0 v14ProfileAuthority=ASSERT_ONLY v15ExactTxARequirement=GUARD_ONLY v14RelationAclDrift=55000 runtimeDrift=MEMBERSHIP,RELATION_ACL,EXTRA_EXECUTE,OWNER,BODY secrets=ephemeral

PACK010_V14_TO_V15_POPULATED_FIDELITY_RECEIPT v13Key=1 v13Validation=1 v14ActiveProfile=1 rowsAndXminV1V14=UNCHANGED v13V14FunctionCatalog=UNCHANGED oldTriggers=UNCHANGED historyV1V14=UNCHANGED marker=EMPTY v15Functions=2 v15Triggers=4 v15Attestor=REQUIRE_ONLY v15RelationAcl=0 grantSelectDrift=55000
```

## 冻结快照

HEAD 保持 `6d914d8b44498c857768ea5da97fa204db562966`；工作树仍非 git-clean，没有 commit、push、
deploy 或 release。

V15 28-file ordered slice aggregate 为
`61e0c1b4d8297052b678c432e173f3354bda1722ccb5f79a52a2c6238c642ac2`；shipping graph-eval app JAR
SHA-256 为 `cfd0a29e7b2c683d6461273c93ef6026b151b89985f3bac2cabaca2a733d58cd`。

aggregate 按以下固定顺序对逐文件 `shasum -a 256` 完整输出再次执行 `shasum -a 256` 得到；
不包含本 Build Note 或 living ExecPlan：

1. `README.md`；
2. `docs/README.md`；
3. `docs/rfcs/README.md`；
4. `docs/rfcs/0010-exact-provider-tx-a-requirement-guard.md`；
5. `docs/architecture/decisions/0014-exact-provider-tx-a-requirement-guard.md`；
6. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresAttributedFailureResumeStore.java`；
7. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutor.java`；
8. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresProviderValidationAttestor.java`；
9. `adapters/postgres/src/main/resources/db/migration/V15__require_exact_provider_tx_a.sql`；
10. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql`；
11. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql`；
12. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java`；
13. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java`；
14. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresActionAttemptStoreTest.java`；
15. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresAgentRunStoreTest.java`；
16. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresArtifactLineageStoreTest.java`；
17. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresCaptureStoreTest.java`；
18. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`；
19. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresReadOnlyWorkerConstraintTest.java`；
20. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresReadOnlyWorkerRunStoreTest.java`；
21. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V11DurableAttributedFailureMigrationTest.java`；
22. `apps/api/src/test/java/io/emergeos/api/PostgresApiTest.java`；
23. `apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java`；
24. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
25. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java`；
26. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java`；
27. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactTxARequirementAcceptanceTest.java`；
28. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`。

V15 migration、roles 与 pure roles-check 的 source、`target/classes`、adapter JAR 与 shipping app JAR
entry 逐字一致，SHA-256 分别为：

- `317c296f7af91f214eefa48bcf5529ce6920260e74b8061f5ea813f5400ce1be`；
- `8904cd4421fbc21768725007f2d9acea7269174cbd30f491af575173575ca5ea`；
- `feb511b49d6993821bbf97352749689d7186ef1f8f8a447eafd4c3fa786f33d3`。

三个实际承载 runtime authority topology 常量的 class，其 `target/classes`、adapter JAR 与 app JAR
entry 逐字一致：

- `PostgresAttributedFailureResumeStore.class`：
  `2a1186401b8298f3001147cf3f8815a7bc3971fd3a8f9b5c18ed8486e87ba10b`；
- `PostgresGraphTerminalExecutor$ExecutorAuthority.class`：
  `9ab450797cf947236ad99bae0f58f2350522de62fd85430520505541f858802f`；
- `PostgresProviderValidationAttestor.class`：
  `fcedb32b934456ce18c649331a380e31feb79ff4d56031b5dfb9c9aa4db0c8f9`。

V15 require 与 deferred guard 的 PostgreSQL `prosrc` SHA-256 分别为
`01207450245d44045b895a864c34ddf5704f47b172162d6ed4deb0c3b7478026` 与
`26c40c69c18c6b5f680abb5fc6789905a5f41774122264dd8962c1559a4e77fd`；全局 trigger topology 为
`42952b7368f7c747eab783b1e6fbebbf82e04e03e23cc601a9cce8422839eb6a`，protected-function
topology 为 `614e75833b1d785ce6333dafc0e13d1999f66b6fa63b651972c5c429569c9d8b`。

V15 relation catalog image 冻结为 17 columns/
`1a1a18b62f697870b372aa83c7c7bfb5d7281132221ac89745ec5e73d559e6c4`、24 constraints/
`04e4a9fb12a87cf6962a549445d8aa2481871938d11b119f389c60623f3a7142`、2 indexes/
`c3402d765391b9eb542c42d1e3e63a372cd61af0807b89888226c9cb7bfc18b4` 与 1 relation/
`dff070d8412651d4f2d970f7f96bc7e0a98d7fe13f29c919d9528a1160383588`。

Java/Gate/artifact、SQL/ACL/migration 与 Acceptance/evidence/docs 三路独立 post-fix review
最终均为 `P0=0 / P1=0 / P2=0`。

## Remaining boundaries / 下一条 Acceptance Red

- marker 在 seq13 登记，不是 pre-egress authority；此前 provider effect 已发生。本 slice 不能证明
  zero-egress rejection、预算 reservation 或 billing cap；
- V15 attestor 选择 V14 `provider_profile_id`。DB在 enrollment statement snapshot 中校验并读取
  ACTIVE/effective row，再把其hash冻结到marker；它不调用 V14 statement assertion，也不证明 profile
  与 request-2 model/provider response/usage/cost 相符；
- `requirement_hash` 只是 immutable fence marker identity，不是 provider validation、signature、attestation、
  completion receipt 或 graph truth；
- marked attempt 的 legacy V8 head 故意停在13；V12 TX-B/TX-C resume不能启动。marked attempt 没有
  crash/restart/reclaim/positive commit evidence，只有本轮同进程、双 backend 的 `READ COMMITTED` race；
- 更高 isolation level 仍应 fail closed，但本 slice 不承诺统一为 `55000`，可能由 PostgreSQL 返回
  serialization failure；
- 没有读取或使用 provider credential，没有真实 provider request。exact model/backend provenance、provider
  retention、pricing freshness、invoice、billing、latency、availability、hostile DBA 与 shipping Live 均未证明；
- 下一条 Acceptance Red 必须设计 versioned exact-pico attribution、overlay event/head/read model、challenge、
  transcript、signature 与 atomic TX-A；不得把 V15 guard-only Green 改写成 exact TX-A、attestation、Live PASS
  或 Commercial evidence。
