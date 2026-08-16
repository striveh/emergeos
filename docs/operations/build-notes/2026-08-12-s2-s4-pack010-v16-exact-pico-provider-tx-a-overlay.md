# Pack010 V16 exact-pico provider TX-A overlay 工程回执

日期：2026-08-12

Stage：Stage 2 / S4

状态：`focused Engineering Gate Green`

结论：`RAW_JDBC_LOCAL_OVERLAY_TX_A` focused Engineering Gate 已 Green。V16 在
V15-marked legacy seq13 上建立独立 exact-pico attribution/event/head14 overlay；独占 root
clean Gate、artifact parity、hash 与三路独立 review 已冻结。该结果仍不外推为 production
attestation、shipping Live 或 Commercial evidence。

```text
slice=RAW_JDBC_LOCAL_OVERLAY_TX_A
finalGate=GREEN_LOCAL_FOCUSED
v16HistoricalProductionVerifierAtSliceFreeze=0
v16HistoricalProductionJavaApiAtSliceFreeze=0
postgresEd25519Verification=NOT_IMPLEMENTED
testJvmEd25519=VERIFIED_LOCAL_ONLY
credentialAndAttestorRole=TCB
legacyHead=SEQ13_UNCHANGED
overlayHead=SEQ14_DISTINCT_FROM_LEGACY
txB=NOT_IMPLEMENTED
txC=NOT_IMPLEMENTED
transactionalMidCommitConstraintFault=PROVEN
processKillOrPrecommitCrash=NOT_PROVEN
race=NOT_PROVEN
restart=NOT_PROVEN
live=NOT_PROVEN
billing=NOT_PROVEN
preEgress=NOT_IMPLEMENTED
```

## Outcome candidate

- forward-only V16 新增独立 validation、exact attribution、exact event 与 exact head
  overlay；不向 V8/V13 integer nano-USD 列写入 rounded compatibility truth；
- `agent_graph_stage_exact_tx_a_v16(jsonb)` 由数据库锁 legacy head13，并绑定 V13 policy、
  V15 requirement、V14 profile、DB/schema/role identity、request/response、model、exact-pico
  rates/usage/cost 与 bounded decision；
- test JVM 对数据库 mint 的 canonical transcript 使用 ephemeral Ed25519 key 完成本地签名和
  验签，然后在同一 explicit JDBC transaction 调用
  `agent_graph_commit_exact_tx_a_v16(jsonb)`；
- PostgreSQL 不验证 Ed25519。它验证受限 session identity、key metadata、transcript hash、
  signature shape 与完整 durable closure，并保存 caller 提交的 signature；V16 login credential、
  attestor role 与 caller process 因而属于 TCB；
- response/model/usage/decision provenance同样由trusted caller提交；数据库只冻结并闭合这些
  exact values，不独立观察provider provenance；
- commit candidate 只创建 V16 `CONSUMED` validation、attribution、event 与 overlay head14。
  legacy V8 head保持seq13，legacy sequence14保持不存在；overlay head14不是legacy head14；
- positive commit从post-canary baseline起、每条negative fence从其immediate baseline起，
  V1-V15 public-table JSON/`xmin`除V16 allowlist外全部不变；fixture创建及profile/key status
  mutation单独记录，不归因于V16 stage/commit；
- V16冻结时production verifier、Java domain/port/mapper/reader、App route与shipping live
  consumer均不存在。后续dormant verifier primitive属于独立V17 slice，不改写本回执的历史hash与边界。

## Focused Acceptance 观察

当前 focused test：

`apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayTxAAcceptanceTest.java`

当前冻结源码与回执观察到：

- stage-only auto-commit命中固定`55000`并完整回滚；
- wrong base head、request、key、requirement与commit transcript均被fence；
- stage后profile/key revoke与challenge expiry都在commit命中固定`55000`，staged overlay完整回滚；
  test admin随后补偿恢复authority status，该fixture mutation及其`xmin`不属于overlay rollback claim；
- test-only event insert SQL fault完整回滚。它只是同进程transaction rollback canary，不能升级为
  process hard-kill、fault durability或restart证据；
- valid test signature只推进一组V16 overlay closure；从post-canary baseline起，V13 validation、
  V15 marker、legacy head13及V1-V15 JSON/`xmin`不变；
- consumed replay命中固定`55000`且不改写durable truth；
- test使用的`gpt-5.6-terra` profile和exact-pico rate只是synthetic precision canary，pricing
  source明确为test-only/noncommercial。它不是OpenAI、DeepSeek或任何provider的价格、invoice或
  billing truth。

## Final Gate 回执

| Receipt | 状态 |
|---|---|
| 独占 root `clean verify` | `11 modules / 148 XML / 834 tests / failures=errors=skipped=flakes=0`；`BUILD SUCCESS`；`6:46` |
| contracts / fixtures | `8 schemas / 70 fixtures` Green |
| docs links / `git diff --check` | `114 Markdown files` / Green |
| V15→V16 populated migration与latest-schema inventory | non-empty synthetic V15 marker；V1-V15 rows/`xmin`、V13-V15 functions、old triggers/history unchanged；Green |
| roles / ACL / owner / function body / trigger topology audit | 10 roles；V16 role仅stage+commit EXECUTE、relation ACL=0；Green |
| first-party directory / synthetic JAR / actual shaded-JAR zero-consumer Gate | 8 V16 authority tokens、shipping first-party consumer=0；Green |
| migration resource / target class / adapter JAR / app JAR parity | source→target/classes→adapter JAR→app JAR逐字一致；Green |
| source < class < XML freshness | Acceptance `03:28:32 < 03:31:52 < 03:32:15`；app JAR `03:32:18` < packaged IT XML；Green |
| ordered slice aggregate与app JAR SHA-256 | 28-file aggregate与actual app JAR已冻结，见下节 |
| 独立post-fix review | Java/Gate/artifact、SQL/ACL/migration、Acceptance/evidence/docs：`P0/P1/P2=0` |

## Frozen hashes / artifact parity

```text
HEAD=6d914d8b44498c857768ea5da97fa204db562966
dirtyStatusEntries=195
orderedSliceFiles=28
orderedSliceAggregateSha256=dc350ce2ff545a7468c9778d921a09a78dc951526dc24bf5d94be8255cebb793
postgresAdapterJarSha256=82b2f5cda1b59b6ca9ce68dad3806b9915c9455518ad2849170f4e4c99ec4dd9
shippingAppJarSha256=d57959e5a420a5f28087489ecb94bf97b45c20c593b7fcd39866fe5889128da6
v16MigrationSha256=fe2fc22803514443e2ad0a6d7e343925d8b666d2688270e4c81db609387d53f7
rolesSqlSha256=adc16736a1f5dd49eee63c90f276dec766ada31fd7e0a1a79a72c69f41b9f0ce
rolesCheckSqlSha256=e54c34a3d0242bf35334d5dc1d18f8f49bfdb9de87972e47afbf09cf55ee2345
stageProsrcSha256=593aab6d426090ea11d82970100ac90cbb2840463a7919030ac4b5fc970a5cf8
commitProsrcSha256=7d15cc5f1e613da201ecd2219e85d522bde2ce671354fdf1eb112f43064af07b
guardProsrcSha256=5083d24c002811e58d819b638e02ec2e83ba0c8f783c41362beef84ffb09de3f
globalTriggerTopologySha256=e9caa6b45389c919bb7b71afde34b5443afd0189d63721c99602c2b1772f304b
```

V16 relation topology 另冻结为：columns `148 / 3299b96870e4742e7d37b4bf59103ff026b24d09b02d85013701bca2de06c2df`；
constraints `188 / 884c50b44a54c53f1c39b8dc0403726140221895f40332032e66592a641465a7`；
indexes `13 / e1b91b24b602affd4e457ded6a3e14c0c5e4207ac25262c91fbe247dd6932bca`；
relations `4 / fd1821d10250508f87ac44c3ed7e48772631b1f1017085aa839a86b7a2bc4255`。
stage return ABI冻结为`1879 bytes / MD5 b53651040ad5e8695dd553dbdf34adcc`，公开同名V16函数
总数精确为3。

三份shipping SQL resource在source、`target/classes`、PostgreSQL adapter JAR与actual app JAR
四路hash逐字一致。三个legacy authority class也在`target/classes`、adapter JAR与app JAR三路一致：

```text
PostgresAttributedFailureResumeStore.class=aa1263146befe6ad80ee67afaeaa86001b5563d52665827ac0ea52b73798fab0
PostgresGraphTerminalExecutor$ExecutorAuthority.class=b33b4a0012acb76460b7ab0755293553b23a74365f0851a9cacafafb59f5773b
PostgresProviderValidationAttestor.class=61911d3db6db1cca1fba2fecb9b995ab1015c0875483333260d28d1227dd48be
```

ordered aggregate不包含本Build Note与living ExecPlan，避免自引用。算法为：按下列固定顺序对每个
文件执行`shasum -a 256`，保留其完整标准输出行并按原序拼接，再对拼接bytes执行一次
`shasum -a 256`。28个精确路径为：

```text
README.md
docs/README.md
docs/rfcs/README.md
docs/rfcs/0011-exact-pico-provider-tx-a-overlay.md
docs/architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresAttributedFailureResumeStore.java
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutor.java
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresProviderValidationAttestor.java
adapters/postgres/src/main/resources/db/migration/V16__exact_pico_provider_tx_a_overlay.sql
adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql
adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresActionAttemptStoreTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresAgentRunStoreTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresArtifactLineageStoreTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresCaptureStoreTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresReadOnlyWorkerConstraintTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresReadOnlyWorkerRunStoreTest.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V11DurableAttributedFailureMigrationTest.java
apps/api/src/test/java/io/emergeos/api/PostgresApiTest.java
apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayTxAAcceptanceTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java
```

本轮未commit、push、release或deploy；dirty tree边界被保留。

## 五结果边界

| 结果 | 本切片状态 |
|---|---|
| Engineering | V16 focused local Gate Green；仅限本文冻结边界 |
| Human-learning | 按总控继续暂停，不由本工程切片恢复 |
| Product / Production | `NOT_PROVEN`；没有shipping route或live result |
| Career evidence | 仅可在最终Gate后引用已复现的工程边界 |
| Commercial evidence | `NOT_PROVEN`；synthetic pricing不是provider定价或billing |

## Remaining boundaries / 下一条 Acceptance Red

- `TX-B=NOT_IMPLEMENTED`、`TX-C=NOT_IMPLEMENTED`：V12 legacy terminal resume不能被外推到
  V16 overlay head14；
- `transactionalMidCommitConstraintFault=PROVEN`：test-only event INSERT constraint在attribution
  INSERT之后精确触发`23514`，同一事务的stage/attribution/event/head全部回滚；
- `processKillOrPrecommitCrash=NOT_PROVEN`：没有pre-commit hard-kill、commit-before-delegate crash
  或process recovery；
- `race=NOT_PROVEN`：没有两个fresh JVM对同一V16 transcript/closure的一胜一负证据；
- `restart=NOT_PROVEN`：没有PostgreSQL/JVM restart后V16 overlay read-back或继续推进证据；
- `live=NOT_PROVEN`、`billing=NOT_PROVEN`：没有V16真实provider请求、provider metadata、invoice
  reconciliation、pricing freshness或Commercial evidence；
- `preEgress=NOT_IMPLEMENTED`：V15 requirement在legacy seq13登记，不能证明provider effect前预算或
  authority拒绝；
- PostgreSQL没有Ed25519 verifier；在production verifier、key custody/rotation和typed read model经
  独立设计与Red冻结前，不得新增shipping consumer；
- 下一条safe slice应一次只关闭一个边界：可另立V16 process fault、race、restart或production verifier
  设计Red；不得用本focused Gate替代这些未证明结果。

## Design references

- [RFC-0011](../../rfcs/0011-exact-pico-provider-tx-a-overlay.md)
- [ADR-0015](../../architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md)
- [V15 requirement guard Build Note](2026-08-12-s2-s4-pack010-v15-exact-tx-a-requirement-guard.md)
