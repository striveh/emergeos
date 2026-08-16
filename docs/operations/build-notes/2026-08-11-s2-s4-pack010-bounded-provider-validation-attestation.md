# Pack010 bounded provider validation attestation 回执

日期：2026-08-11
Stage：Stage 2 / S4
结论：forward-only PostgreSQL V13 bounded provider-validation attestation 的 local-only
focused Engineering slice 已完成实现与 focused Gate；shipping Authority/Live 仍为 Red。

## Outcome

- Reviewed OpenAI adapter 只在 bounded response 完成 body hash、strict JSON parsing、attribution
  与 decision validation 后私有 mint outcome receipt。receipt、safe decision hash、typed statement、
  signature transcript 和 durable validation row 共同绑定 exact
  `executionBindingHash = manifestHash`；不保存 raw request/response、headers、credential、reasoning、
  exception text 或 private key；
- V13 为新 attempt 显式 enrollment validation policy。数据库在同一 JDBC transaction 中为 seq13/head13
  mint one-shot challenge；test-only Ed25519 signer 对 canonical framed transcript 签名，独立
  `emergeos_provider_attestor LOGIN NOINHERIT` JVM 使用数据库选择的 public anchor 验签，然后调用
  唯一 semantic commit。receipt、request-2 attribution、event/head14 与 optional V11 closed-failure
  outcome 同 transaction 提交或回滚；
- PostgreSQL 18 与当前 standard extensions 没有 detached Ed25519 verifier。因此 attestor credential
  与 reviewed verifier JVM 明确属于本实验 TCB；本回执不宣称 PostgreSQL-native crypto、hostile DBA
  resistance 或 attestor credential 泄露后的安全性；
- validation-required attempt 的 historical raw attribution path 被 deferred database guard 拒绝。
  missing/revoked key、invalid signature、transport/parser/schema、challenge、attempt、request/response、
  attribution、decision、failure code、cursor/head、expiry、replay、cross-attempt execution binding 与
  template-cloned cross-database policy均 fail closed；负例逐项保持全部 public table 完整 JSON/`xmin`
  digest 不变；
- local reviewed-loopback E2E 在 provider 调用前完成 seq7 policy/claim；invocation observer 在 HTTP 前
  durable 写 intent，request-1 outcome 先 durable 写 attribution 再对测试可见；request-2 outcome 经
  production mapper 与 attestor 推进 seq13→seq14/`CONSUMED`。request/response id、tool-call id、final
  content 与 synthetic key 同时按明文和`bytea` hex representation扫描，全部 public table匹配数为0；
- process/fault Gate 在 stage 后 `Runtime.halt` 与真实 JDBC `Connection.commit()` delegate 前 halt
  均证明 full JSON/`xmin` rollback；fresh JVM retry后 commit survives，PostgreSQL immediate restart后
  fresh reader仍验证 durable truth。two-JVM race exact one winner/one `FENCED` loser；closed failure
  同 transaction写V13 receipt与V11 outcome；
- populated V12→V13 migration保持历史rows、`xmin`与V1–V12 Flyway history逐字不变；历史V12
  `CHILD_CONSUMED/v3/head15`保持可读，新V13 sidecar为空，不做伪回填。fresh V13 provisioning与pure
  audit冻结角色、ACL、owner、function body/search path、relation shape与trigger topology；selected
  anchor的fingerprint/FK/status/time在policy、stage与commit逐次绑定复核，但inventory/lifecycle未冻结；
- post-review hardening在validation durable row新增
  `execution_binding_hash IS NULL OR execution_binding_hash = manifest_hash`同表CHECK；独立raw admin
  canary即使临时禁用USER trigger仍精确命中`23514`。真实V13 closed failure提交到
  `CONSUMED/head14`后，production V12 resume Store连续推进
  `READY/v1 -> CLAIMED/v2 -> CHILD_CONSUMED/v3/head15 -> PARENT_CLAIMED/v4 ->
  TERMINAL_CONSUMED/v5/head17`；V13 validation完整JSON/`xmin`不变，V11 outcome从CLAIMED/v2到
  terminal seal完成保持完整JSON/`xmin`不变。typed child/parent completion只接受exact `FAILED`，并把
  输入`startedAt`与durable RUNNING run精确比对；wrong-lifecycle与wrong-start负例均在任何mutation前
  以固定typed reason拒绝且全库digest不变；
- bytecode与shaded-JAR Gate first-party-wide拒绝未审 direct、reflection、MethodHandles、condy/indy、
  private signing/JCA 与 raw-body consumer。shipping JAR包含 dormant typed protocol与mapper，但
  mapper/attestor production consumer count为0，test signer/private key/harness不入JAR，shipping
  execute保持disabled。

## Acceptance Red → minimum fix

1. 初始 raw request-2 attribution 可在没有validation transcript时从seq13推进seq14。V13 migration
   以per-attempt policy、one-shot challenge、semantic commit和deferred guard将同一调用改为SQLSTATE
   `55000`，并以全库JSON/`xmin`不变证明不是Java-only precheck；
2. 最初设计没有DB-minted challenge到签名再到atomic TX-A的可达状态机。minimum fix在同一显式
   transaction内执行`REQUIRED -> STAGED -> CONSUMED`，STAGED不可单独commit；
3. 最初roles/audit与既有V12 runtime fingerprints不了解V13函数、关系和trigger。minimum fix把
   dedicated attestor exact surface、global trigger topology、function prosrc SHA、relation/constraint/
   index shape和CONNECT/EXECUTE closure全部冻结；
4. 最初signature null、DELETE immutability、post-stage key revocation、wrong request/head与cross-DB
   证据存在fail-open或假绿窗口。minimum fix显式拒绝NULL signature、先拒绝DELETE、在commit重新
   校验anchor，并加入DB-level SQLSTATE/message与full JSON/`xmin` canary；
5. 最初 generic transcript没有把reviewed semantic outcome绑定到graph attempt。minimum fix让
   private receipt、decision hash、statement、transcript和durable row共同携带manifest hash；mapper、
   Store与PostgreSQL分别验证provider-observable fields、graph-owned actor/manifest与durable head；
6. 最初 reviewed outcome→mapper与DB attestation是分离正例。minimum fix以本机loopback response走
   production `ReviewedOpenAiClient -> OpenAiResponsesModel -> OpenAiProviderValidationStatements ->
   PostgresProviderValidationAttestor`，并把intent/attribution durable ordering放在真实HTTP调用链；
7. post-fix review发现隐私扫描只查`to_jsonb(row)::text`明文，无法发现`bytea`中的hex raw bytes。
   final fix同时搜索UTF-8 sentinel与其hex编码，并在输出进入失败诊断前执行constant-message secret
   scan；
8. 首次full-root `clean verify`命中历史V8 custom-schema drift test继续误跑latest V13，从而在没有
   `public` V13 relation的fixture中编译失败。该测试的真实范围是V7→V8 constraint fidelity；minimum
   fix显式target V8并保留fresh public V13及populated V12→V13独立测试。修复后focused 19/19与第二次
   独占root Gate均Green；
9. post-signoff review保留两项non-blocking P2。第一项先以禁用USER trigger的raw UPDATE稳定证明旧表
   shape会接受cross-attempt execution binding，再加入同表CHECK与exact constraint topology hash；第二项
   直接续写V13 process race产生的真实FAILED/CONSUMED attempt，首次Red暴露test fixture terminal时间早于
   DB-issued seq14时间，最小改为保留durable run原`startedAt`、仅把`completedAt`设在前一event之后。
   最终typed TX-B/TX-C完整到head17，未重造failure outcome，也未扩大production API。
10. 最终独立review继续发现V12 resume Store没有像普通Store一样验证terminal input的`startedAt`；V10
    payload虽携带该字段，SQL CAS却刻意保留durable原值。child wrong-start Red证实旧实现会接受并提交，
    minimum production fix在verified reader snapshot后锁定run id、principal、task、terminal lifecycle与
    `startedAt`，child/parent两项negative均在mutation前以固定reason拒绝并保持全库JSON/`xmin`不变。
11. Java/API review另保留failure completion只检查“任意terminal lifecycle”的taxonomy P2。matching
    `SUCCEEDED` child与`BLOCKED` parent把run id、principal、task、`startedAt`全部保持为durable truth；
    旧实现继续进入downstream validation，child Red稳定表现为同属`IllegalArgumentException`但reason漂移。
    minimum fix把typed boundary收紧为exact `FAILED`，两项均以Store固定reason拒绝、全库digest不变，
    随后同一claim仍能沿合法FAILED TX-B/TX-C推进head15/head17。该Red不外推为DB authority bypass或
    两项均曾到达SQL `55000`。

## 可复现 evidence

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=OpenAiResponsesModelProtocolTest,V11DurableAttributedFailureMigrationTest,GraphEvalArchitectureTest,Pack010ProviderCapabilityBytecodeGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=Pack010ProviderValidationAttestationAcceptanceTest,Pack010ProviderValidationAttestationProcessIT' \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
# 35 tests / 0 failure/error/skipped；BUILD SUCCESS

./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
# 最终独占root Gate与冻结统计见“冻结快照”
```

Focused executable receipts：

- reviewed loopback：`STRUCTURED_FINAL_TO_CONSUMED`；raw bypass为`55000`；full JSON/`xmin`
  unchanged；cross-attempt/cross-database均`FENCED`；
- process：stage rollback、pre-commit rollback、immediate PostgreSQL restart、replay/race loser
  `FENCED`与failure outcome atomic均Green；同一真实V13 failure继续V12 typed terminal resume到
  `TERMINAL_CONSUMED/v5/head17`，validation与claimed outcome完整JSON/`xmin`不变；
  child `SUCCEEDED`与parent `BLOCKED`均`TYPED_REJECTED`，
  child/parent wrong `startedAt`均typed fail closed且全库digest不变；
  `realProvider=false`、`externalProviderNetwork=0`、`loopbackPostgresTcp=true`、`billing=0`；
- migration：V12 READY/CLAIMED/CHILD_CONSUMED rows、history与`xmin`保真，V13 sidecar为空。

## 冻结快照

HEAD保持`6d914d8b44498c857768ea5da97fa204db562966`；没有commit、push、deploy或release。

2026-08-11 10:52–11:00的最终独占root `clean verify`为11 modules、144 `TEST-*.xml`
reports、821 tests、0 failure/error/skipped/flake，`BUILD SUCCESS`，总时长7m28s。V13 Acceptance
source 10:01:23 < clean-built class 10:56:13 < XML 10:56:22；V13 Process source 10:48:59 <
clean-built class 10:56:13 < XML 10:59:37，说明最终报告覆盖当前源码与最终receipt措辞。

V13 closure的53-file ordered slice aggregate为
`bfd1965a6a972784da6715d82cb0f96f92ce37034ede583b94b2fafda5942577`；shipping graph-eval
app JAR SHA-256为
`6339b81d581efab48b559ba1fa6eed53712e6b8f64f32af939912bf7eb9cd10b`。关键文件hash：

- V13 migration：`e2bda999acc50b7edf3bf40385a9a3421bb893d35bd30ec9e67c5d6fcbbd3ffa`；
- roles：`af508a68a0152eb43c611a4cedd2cce7fd6bb39f98637c85f33f6c5fa91bbdca`；
- pure audit：`879490e3c63a27818b35484e84fe4097bf551e272940f1881a71b6aaa56e073f`；
- production attestor source：`9521fbc7d8405e6f4d1be11c2d7d11bb72262332c994cac06894d31a095228f9`；
- attributed-failure resume Store source：`328d3730a64303fca4f516b0b9fe06ed41bf0f5cd021c0c32469c605828a37cb`；
- reviewed mapper source：`98e913bb3e6636dc8b8c5467264702e37bae38d8c1c45edd10a3f15bc8d1e342`；
- transcript source：`b7af959e12968cbe2761dc80703860797d542e7f554e28cc02121dc29e3bb2ed`。

V13 migration、roles与pure audit在source、`target/classes`及shipping app JAR内逐字SHA-256
一致。production attestor、attributed-failure resume Store、reviewed mapper、mapper Policy、transcript与
statement class在各模块`target/classes`和shipping JAR内分别逐字一致；对应class hashes为
`5157599dc8ae3296a3569eedd845778049b6d0ca42e577635c0dd20cdfab8f6d`、
`bce8abdeb35bb5dc6b18fabb13e1e319fb691b8011e1f2c3d3107cb1551d3f2c`、
`98e335643206a19b110ec1af0da19991623820e8f5b0750e82283bfdee4aadf8`、
`4600b14abf949bbfd01305c06e08965a3fa8ad781fbeadbf0c43d49975ba27bd`、
`313b6ab358ce1f9e174d7511b878c19840acd5653b32edface6d93a87e148200`与
`0cca3d507c4a2bbf474d0700c173bb5b1b20d597fb88e817b09390a7b7b9c0fe`。

同一冻结快照的SQL/ACL、Acceptance/evidence与Java/API三路独立review均为`P0=0、P1=0、P2=0`，
focused Engineering Gate可签Green。上一版Java/API review保留的typed taxonomy debt已经由第11项
Acceptance Red与exact `FAILED` minimum fix关闭，未扩大public API、SQL/ACL或shipping consumer。

该aggregate按以下固定顺序对逐文件`shasum -a 256`完整输出再次执行`shasum -a 256`得到；不包含
本Build Note自身：

1. `adapters/openai/src/main/java/io/emergeos/adapters/openai/OpenAiResponsesModel.java`；
2. `adapters/openai/src/main/java/io/emergeos/adapters/openai/ReviewedOpenAiClient.java`；
3. `adapters/openai/src/main/java/io/emergeos/adapters/openai/OpenAiProviderValidationStatements.java`；
4. `adapters/openai/src/test/java/io/emergeos/adapters/openai/OpenAiResponsesModelProtocolTest.java`；
5. `modules/core/src/main/java/io/emergeos/core/domain/GraphProviderValidationAttestation.java`；
6. `modules/core/src/main/java/io/emergeos/core/domain/GraphProviderValidationCanonical.java`；
7. `modules/core/src/main/java/io/emergeos/core/domain/GraphProviderValidationChallenge.java`；
8. `modules/core/src/main/java/io/emergeos/core/domain/GraphProviderValidationDecision.java`；
9. `modules/core/src/main/java/io/emergeos/core/domain/GraphProviderValidationStatement.java`；
10. `modules/core/src/main/java/io/emergeos/core/domain/GraphProviderValidationTranscript.java`；
11. `modules/core/src/main/java/io/emergeos/core/port/GraphProviderValidationAttestor.java`；
12. `modules/core/src/main/java/io/emergeos/core/port/GraphProviderValidationSigner.java`；
13. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresProviderValidationAttestor.java`；
14. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStore.java`；
15. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresAttributedFailureResumeStore.java`；
16. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutor.java`；
17. `adapters/postgres/src/main/resources/db/migration/V13__bounded_provider_validation_attestation.sql`；
18. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql`；
19. `adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql`；
20. `scripts/provision-pack010-postgres-roles.sh`；
21. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/AgentRunMigrationTest.java`；
22. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/GraphAttemptMigrationTest.java`；
23. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V11DurableAttributedFailureMigrationTest.java`；
24. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Stage1MigrationAndRecoveryTest.java`；
25. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java`；
26. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresActionAttemptStoreTest.java`；
27. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresAgentRunStoreTest.java`；
28. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresArtifactLineageStoreTest.java`；
29. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresCaptureStoreTest.java`；
30. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`；
31. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresReadOnlyWorkerConstraintTest.java`；
32. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresReadOnlyWorkerRunStoreTest.java`；
33. `apps/api/src/test/java/io/emergeos/api/PostgresApiTest.java`；
34. `apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java`；
35. `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack009GraphVerifier.java`；
36. `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack010ProviderSessionComposer.java`；
37. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalArchitectureTest.java`；
38. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
39. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`；
40. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009ProcessSupport.java`；
41. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010CommitBeforeDelegateHardKillDataSource.java`；
42. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderValidationAttestationAcceptanceTest.java`；
43. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderValidationAttestationHarnessMain.java`；
44. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderValidationAttestationProcessIT.java`；
45. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java`；
46. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java`；
47. `docs/rfcs/0008-bounded-provider-validation-attestation.md`；
48. `docs/architecture/decisions/0012-db-authenticated-provider-validation-attestation.md`；
49. `docs/plans/2026-07-29-stage-2-agent-kernel-evaluation.md`；
50. `README.md`；
51. `ROADMAP.md`；
52. `docs/README.md`；
53. `docs/rfcs/README.md`。

## Remaining boundaries

- overall Authority/Live保持Red：production public-key verifier、anchor/key lifecycle、shipping App wiring、
  live model/endpoint、API key、network、billing、真实r1/r2/r3与owner逐次TTY授权均未完成；
- 本slice使用synthetic/loopback bytes、ephemeral test trust anchor与本机PostgreSQL/Testcontainers；不证明
  provider-origin signature、remote attestation、provider exactly-once、cross-host/failover、真实断电或
  commit-ACK后的power-loss durability；
- DB只证明attestor TCB接受bounded statement并原子绑定graph truth，不独立重放raw bytes或证明
  selected profile是全局唯一正确的production profile；
- V13→V12组合canary证明同一数据库内的协议连续性；它复用两套既有fault receipts，不额外宣称整个
  seq14→17组合经过新的cross-host、power-loss或每一阶段hard-kill证明；
- worktree仍包含大量既有tracked/untracked变化。本回执最终只冻结明确ordered slice与shipping artifact，
  不是git-clean、commit、push、release或deploy readiness。
