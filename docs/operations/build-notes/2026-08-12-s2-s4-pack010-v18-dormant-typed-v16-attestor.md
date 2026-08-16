# Pack010 V18 dormant typed V16 attestor 工程回执

日期：2026-08-12

Stage：Stage 2 / S4

状态：`focused Engineering Gate Green`

结论：本切片增加一个production-source、已进入shipping app JAR但保持App未接线的typed V16
stage→verify→commit authority adapter。focused Acceptance、actual artifact Gate、最终root clean与三路
post-fix review均已Green。该结果不外推为PostgreSQL-native验签、App runtime、
provider provenance、Authority/Live或Commercial evidence。

```text
scope=DORMANT_TYPED_V16_STAGE_VERIFY_COMMIT_LOCAL_ONLY
productionTypedAuthorityAdapter=1
publicJavaRawStageMethod=0
publicJavaRawCommitMethod=0
shippingAppRouteConsumer=0
shippingLiveRoute=DISABLED
externalConfiguration=NOT_PROVEN
externalRuntimeInvocation=NOT_PROVEN
postgresNativeSignatureVerify=0
rawV16CredentialBypassInTcb=true
semanticSlice=STRUCTURED_FINAL_SUCCESS_ONLY
fixedPath=BASE13_REQUEST2_OVERLAY14
stageResultProjection=25_OF_62
challengeTranscript=JAVA_RECOMPUTED
componentHashes=DATABASE_MINTED_NOT_JAVA_RECOMPUTED_TCB
shippingSignerImplementation=0
keyCustody=NOT_IMPLEMENTED
providerNetwork=0
billing=0
live=NOT_PROVEN
```

## 当前 Acceptance 观察

- adapter在同一`REQUIRES_NEW`、`READ_COMMITTED` transaction与Spring-bound connection中完成
  authority recheck、V16 stage、25-field typed challenge mapping、成功stage后signer至多一次调用、V17 local verifier、
  V16 commit与closed ten-field typed receipt；没有public raw stage/commit method，也没有auto retry；
- wrong-key路径真实观察stage后只命中`AFTER_STAGE_MAPPED`，commit SQL精确调用0次，public-table
  rows/JSON/`xmin` image保持不变；
- commit function返回且typed receipt核验后注入fault，三个probe各一次、commit SQL精确一次，Spring
  outer transaction仍完整回滚，随后同一command可重新stage并成功；
- signer抛出自定义conflict与private sentinel时只得到固定、cause-free integrity error，不能伪造
  SQLSTATE provenance或泄露文本；
- 临时给V16 attestor role授予relation `SELECT`、给另一个LOGIN role授予V16表`INSERT`与commit
  `EXECUTE`、给transitive V14 framed-hash helper授予额外`EXECUTE`或替换body、给上游profile表增加
  未审`UPDATE`、给prefix-writer/reader增加role membership、给上游attempt表增加index、给V16 head
  增加rewrite rule，以及临时增加第五个non-internal V16 trigger；独立canary都在signer前固定fail
  closed，其中helper-body canary还精确观察stage SQL=0；移除drift后pure production roles audit恢复Green；
- 每次adapter调用审计四张V16 write-set与九张stage直接读取的V8-V15 prerequisite relation之
  authority-relevant catalog projection：column、constraint、index、relation owner/kind/partition/
  persistence/RLS/replica identity、零inheritance edge与零user rewrite rule，以及global ACL closure和
  相关runtime role topology；V16函数只validate/hash-bind读到的row value，不锁全部prerequisite rows，
  上游trigger/function mutation provenance仍是继承的V8-V15 DB TCB；
- valid path写入唯一V16 validation/attribution/event/head closure，typed command核心字段与receipt六个hash
  逐项绑定durable rows；legacy V8/V13 head保持seq13，V16 overlay head14不是legacy head14；
- typed replay在signer调用前得到固定cause-free conflict，full public-table JSON/`xmin` image与legacy
  head均不变；
- PostgreSQL仍不会验证Ed25519，raw V16 credential bypass、37个未映射DB-minted fields与caller process
  继续留在TCB。

## Focused evidence

| Receipt | 当前状态 |
|---|---|
| Core typed command/receipt invariants | `2/0` Green |
| PostgreSQL typed adapter surface | `2/0` Green |
| V16/V17/V18 PostgreSQL Acceptance | `2/0` Green |
| provider capability bytecode Gate | `12/0` Green |
| actual shaded-JAR typed adapter/nested-class parity | `1/0` Green |
| packaged historical crash/race regressions | `4/0` Green |
| root clean verify | `11 modules / 153 XML / 846 tests / 0 failures/errors/skips/flakes` Green |
| contracts / docs links / diff-check | `8 schemas / 70 fixtures` / `120 Markdown files` / Green |
| frozen aggregate / module+app JAR hashes | 见下方 frozen snapshot |
| three independent post-fix reviews | `P0=0 / P1=0 / P2=0` |

## Artifact claim boundary

- actual app JAR包含typed adapter的top-level与`AuthorityProbe`、`LocalFailure`、`Probe`、`ProbePoint`、
  `RuntimeIdentity`、`Stage`全部nested classes，以及Core command、receipt/state和两个port；
- 这些class在target/classes、各自module JAR与actual app JAR逐字一致；test access、surface test与
  Acceptance不进入shipping artifact；
- bytecode Gate只允许typed adapter持有8个V16 authority token、四个只读prerequisite-relation token并
  调用signer/verifier/challenge/receipt；
  directory与synthetic JAR中的未审consumer被拒，App-owned `open/complete/sign` consumer为0；
- shipping JAR中signer type的first-party reference集合只包含typed adapter与两个capability ports；没有
  signer implementation。本项不排除外部注入、runtime code generation或raw credential caller；
- `externalConfiguration=NOT_PROVEN`、`externalRuntimeInvocation=NOT_PROVEN`；打包和可调用不等于
  App已接线或Live。

## Remaining boundaries

- PostgreSQL-native Ed25519与raw credential bypass closure未实现；
- production signer、KMS/HSM、key custody/rotation与App composition均未实现；
- 当前typed slice只覆盖`BASE13/REQUEST2/OVERLAY14`的`STRUCTURED_FINAL`成功路径，reasoning output=0、
  failure code=NULL；37个stage fields由DB-minted hash闭包信任，本轮不声称完整provider semantic mapper
  或provider provenance；
- overlay reader、TX-B/TX-C、legacy-head推进、同attempt typed race、process hard-kill/restart/reconcile均未证明；
- privileged DDL/admin、catalog aggregate delimiter ambiguity，以及per-call audit完成后的并发DDL仍在TCB；
  本轮不声称serializable catalog snapshot，删除必要grant只证明运行时fail closed而非availability；
- DeepSeek live、pricing freshness、billing与pre-egress authority仍Red；
- 此前工具调用记录暴露的owner credential仍必须撤销/轮换；仓库/artifact marker scan不能抵消该事件。

## Frozen snapshot

```text
HEAD=6d914d8b44498c857768ea5da97fa204db562966
dirtyStatusEntries=213
orderedSliceFiles=21
orderedSliceAggregateSha256=9fb8c4c924bab2b5c238a15a275fc5f1ef4a1e31c1455ef6b394bae10fecf46f
coreJarSha256=86ac5f19e19194b79391cf4fef0d5983f48372c4d43e71e5f932ed03b6d94aed
postgresJarSha256=68a0d4c9645d7ecc140fa390476f86021c231f961f29f84f462c713e4ad4666c
shippingAppJarSha256=4a566d212e27753ebfa15fd457656fa5715727b939ae4919262b070e95b1ffc1
```

ordered aggregate不包含本Build Note与living ExecPlan，避免自引用。算法为：按下列固定顺序对每个
文件执行`shasum -a 256`，保留完整标准输出行并按原序拼接，再对拼接bytes执行一次
`shasum -a 256`。21个精确路径为：

```text
README.md
docs/README.md
docs/rfcs/README.md
docs/rfcs/0013-dormant-typed-v16-stage-verify-commit-attestor.md
docs/architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoProviderValidationCommand.java
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoProviderValidationReceipt.java
modules/core/src/main/java/io/emergeos/core/port/GraphExactPicoProviderValidationAttestor.java
modules/core/src/main/java/io/emergeos/core/port/GraphExactPicoProviderValidationSigner.java
modules/core/src/test/java/io/emergeos/core/domain/GraphExactPicoProviderValidationTypesTest.java
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresExactPicoProviderValidationAttestor.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresExactPicoProviderValidationAttestorSurfaceTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/adapters/postgres/PostgresExactPicoProviderValidationAttestorTestAccess.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalArchitectureTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayTxAAcceptanceTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V18TypedAttestorShippingJarIT.java
docs/operations/build-notes/2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md
docs/operations/build-notes/2026-08-12-s2-s4-pack010-v17-dormant-v16-ed25519-verifier.md
ROADMAP.md
```

本轮未commit、push、release或deploy；dirty tree边界被保留。未执行provider网络请求，也未使用owner
credential。

## Design references

- [RFC-0013](../../rfcs/0013-dormant-typed-v16-stage-verify-commit-attestor.md)
- [ADR-0017](../../architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md)
- [V17 verifier Build Note](2026-08-12-s2-s4-pack010-v17-dormant-v16-ed25519-verifier.md)
