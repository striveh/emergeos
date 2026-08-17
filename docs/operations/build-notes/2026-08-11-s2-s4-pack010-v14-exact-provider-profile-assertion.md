# Pack010 V14 exact provider profile assertion foundation 回执

日期：2026-08-11
Stage：Stage 2 / S4
结论：V14 本地、dormant、只读 provider profile assertion Engineering slice 已 Green；
`PROFILE_ASSERTION_ONLY`，`TX-A=NOT_IMPLEMENTED`。graph truth、provider attestation、
durable receipt、billing、shipping live route 与真实 DeepSeek compatibility 仍为 Red。

## Outcome

- forward-only V14 保留 V13 relation、canonical transcript、trigger topology 与 integer
  nano-USD 语义；没有回填或改写历史 graph truth；
- `agent_graph_provider_profiles_v14` 冻结 provider/protocol、transport/parser/schema/
  model-resolution 与 observed pricing material。价格使用 integer pico-USD/token；migration
  不 seed production DeepSeek profile，Acceptance 只由 admin 写入 bounded synthetic fixture；
- `agent_graph_assert_provider_statement_v14(jsonb)` 是零 DML 的 SECURITY DEFINER assertion：
  只接受 34-key closed JSON object，限制为 16 KiB，验证 ACTIVE/effective profile、exact identity、
  token arithmetic 与 claimed pico-USD cost，只返回 profile hash、statement hash 和 exact cost；
- wrong session 固定为 `42501 / V14 provider statement authority rejected`；missing、extra、
  malformed field、top-level non-object 与真实 `pg_column_size > 16384` 输入固定为
  `22023 / V14 provider statement input is invalid`；wrong provider/profile/pricing/rate/cost 固定为
  `55000 / V14 provider statement was fenced`；
- synthetic usage `100 input / 20 cached / 10 output` 以 `140000 / 2800 / 280000`
  pico-USD/token 精确得到 `14,056,000` pico-USD；Java 按相同 length-framed contract 独立重算
  profile 与 statement SHA-256，alternate opaque request hash 会产生不同 statement hash；
- assertion 前后全部 public base-table JSON/`xmin`、seq13/head13 与 V13 validation row 不变；
  历史 V13 raw TX-A 在 valid V14 assertion 前后都继续以 missing-attestation `55000` 拒绝；
- 独立 `emergeos_provider_attestor_v14 LOGIN NOINHERIT` 只有 CONNECT、schema USAGE 与 assertion
  EXECUTE，relation ACL 为 0；terminal owner 只有 profile SELECT 与 helper/assert EXECUTE，不能写 profile；
- production Java API 增量为 0。first-party Bytecode Gate 对 V14 function/relation/role 三个常量
  无 allowlist，directory、synthetic JAR 与真实 shaded app JAR 均证明 shipping consumer 为 0。

## Acceptance Red → minimum fix

1. V13 的 integer nano-USD/token 不能无损表达 DeepSeek cache-hit `2.8 nano-USD/token`。minimum fix
   没有 round、重解释或扩写 V13，而是新增 assert-only pico-USD profile foundation；
2. 初始 profile assertion 容易被误读为 graph-bound statement。minimum fix 把 execution-binding、request、
   response 与 decision hash 明确限定为 opaque bounded inputs，并以 `BOUND_NOT_GRAPH_VERIFIED` 回执；
3. 初始 `SELECT ... FOR SHARE` 要求 SECURITY DEFINER owner 具备 profile UPDATE privilege，与只读 ACL
   冲突。minimum fix 改为同一 statement snapshot 的普通 SELECT；函数零 DML、runtime role 零 relation ACL；
4. 初始 input guard 把 non-object 与 `jsonb_object_keys` 放在同一 OR 条件，无法依赖 PostgreSQL 求值顺序
   保证 fixed diagnostic。minimum fix 先独立拒绝 null/non-object/oversized，再执行 closed-key 与 field guard；
5. provisioning 最初没有独立 V14 role/function/relation exact audit，rollback canary 也没有覆盖全部 managed
   roles。minimum fix 冻结 8-role topology、2-function body/owner/ACL、27/29/2/1 relation catalog image，
   rollback 证明 8 个 role 全部不残留；
6. latest-schema fixtures 最初仍以 V13 为最新。minimum fix 把 public-schema fresh/install、API readiness、
   public table inventory 与 packaged migration resource 更新为 V14，同时保持历史 custom-schema target 不变；
7. 初始 Gate 不识别 V14 authority 字面量。minimum fix 增加 first-party-wide、无例外的三常量 rule 与
   directory/synthetic-JAR negative，并由 shade 后真实 app JAR 再验证一次。

## 本地 executable evidence

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=V11DurableAttributedFailureMigrationTest,Pack010ProvisioningWrapperTest,\
Pack010ProviderCapabilityBytecodeGateTest,Pack010ProviderValidationAttestationAcceptanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
# focused: migration 5 + provisioning 2 + Acceptance 1 + Bytecode 10 = 18/18 Green

./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
```

最终独占 root `clean verify` 于 23:25–23:31 完成，11 modules、146 `TEST-*.xml` reports、
831 tests、0 failure/error/skipped/flake，`BUILD SUCCESS`，总时长 6m27s。contracts 为 8 schemas/
70 fixtures；108 Markdown files 的 local links 与 `git diff --check` 均 Green。

关键 freshness：V14 migration source 23:22:18 < target resource 23:25:12 < migration XML 23:25:23；
Acceptance source 23:22:02 < class 23:27:59 < XML 23:28:08；Bytecode class 23:27:59 < XML
23:28:10；actual app JAR 23:28:13 < packaged ProcessIT XML 23:30:14。

## 冻结快照

HEAD 保持 `6d914d8b44498c857768ea5da97fa204db562966`；工作树仍非 git-clean，没有 commit、push、
deploy 或 release。

V14 17-file ordered slice aggregate 为
`7a03810f30518cb72edcd4968e157c1449ce847c413f5b4c86cacecbd9702085`；
shipping graph-eval app JAR SHA-256 为
`64f611a7f409048c5f07513b983a1f1cbefce6abb290d392e60b518d4c673377`。

该 aggregate 按以下固定顺序对逐文件 `shasum -a 256` 完整输出再次执行 `shasum -a 256` 得到；
不包含本 Build Note 或 living ExecPlan：

1. `README.md`；
2. `docs/README.md`；
3. `docs/rfcs/README.md`；
4. `docs/rfcs/0009-exact-provider-profile-assertion-foundation.md`；
5. `docs/architecture/decisions/0013-exact-provider-profile-assertion-foundation.md`；
6. `adapters/postgres/src/main/resources/db/migration/V14__exact_provider_profile_authority.sql`；
7. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql`；
8. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql`；
9. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java`；
10. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java`；
11. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V11DurableAttributedFailureMigrationTest.java`；
12. `apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java`；
13. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
14. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java`；
15. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java`；
16. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`；
17. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderValidationAttestationAcceptanceTest.java`。

V14 migration、roles 与 pure roles-check 的 source、`target/classes`、adapter JAR 与 shipping app JAR
entry 逐字一致，SHA-256 分别为：

- `a6e4fc7d87c80dba0b074919da9cbfa5ac80b99d73b8568ef831fb24d4451007`；
- `30f45930eda697638d78a7af7461e56513137e571ab6dd48e264ecef410944ed`；
- `e939772e52ade8e6667726006e21fd31749606f7e3a96b540b237f40add41006`。

V14 canonical helper 与 assertion 的 PostgreSQL `prosrc` SHA-256 分别为
`e0be5f17a805405befc5630145182c73ff8b325522b4ac00ee341acc58bd1edf` 与
`4f2cfa38fa65f1c8bf75f47c5f90558c13b5c8bbfbb4f1d5f7a9fc43c4bee950`，与 provisioning
和 pure audit 常量一致。三路独立 post-fix review 均未发现 P0/P1/P2 后，才可签 focused Engineering Green。

## Remaining boundaries / 下一条 Acceptance Red

- V14 profile 是 admin synthetic fixture；migration 不 seed production provider profile。owner provisioning、
  review、revocation、price-source freshness 与 compromised DBA 都不在本 slice 的信任外证明中；
- opaque execution/request/response/decision hashes只被长度和statement hash绑定，数据库不证明其 graph、
  provider 或 reviewed mapper 来源；本 slice 没有 V14 statement/challenge/signature/transcript/attestation/
  durable receipt/commit，`TX-A=NOT_IMPLEMENTED`；
- 本轮 V14 工作没有读取或使用 provider credential，没有真实 provider request。先前 intermediate DeepSeek
  one-shot REJECTED observation 属另一个、不可复现的 historical receipt，不能并入本 Gate；
- exact model/backend provenance、provider retention、actual usage/pricing、invoice、billing、latency、
  availability、cross-host/power-loss 与 hostile DBA 均未证明；shipping live route 继续 disabled；
- 下一条 durable multi-provider Acceptance Red 必须使用新的 versioned pico-priced graph attribution，
  把 exact provider/profile/pricing、attempt/head、challenge、transcript、signature 与 atomic TX-A 一起绑定；
  同时还需独立 pre-egress token/cost authority，拒绝时证明 provider HTTP requests 为 0。不得把本轮只读
  assertion Green 改写成 attestation、live PASS 或 Commercial 证据。
