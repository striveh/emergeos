# Pack010 V19 fresh-JVM exact-pico overlay reader Build Note

Date：2026-08-12

Stage：Stage 2 / S4

状态：`focused Engineering Gate Green`

结论：本切片在schema V16之上新增一个production-source、shipping App未消费的V19四态只读
capability。它以专属13表SELECT role，在单一`REQUIRES_NEW / REPEATABLE_READ / READ ONLY`
transaction独立重算V13-V16 canonical closure和Ed25519；legacy head仍停在seq13。本回执在最终
root clean、shipping artifact、hash与三路post-fix review闭合后签收focused Engineering Gate Green。

```text
scope=DORMANT_FRESH_JVM_VERIFIED_V16_OVERLAY_READER_LOCAL_ONLY
schemaVersion=16
productionOverlayReader=1
verificationStates=MISSING_REQUIRED_ATTRIBUTED_INVALID
readerRole=EXACT_13_TABLE_SELECT_ONLY
shippingAppRouteConsumer=0
externalConfiguration=NOT_PROVEN
externalRuntimeInvocation=NOT_PROVEN
historicalMeaning=VALID_AT_COMMIT
currentAuthorization=NOT_PROVEN
postgresNativeSignatureVerify=0
rawV16CredentialBypassInTcb=true
legacyHeadSequence=13
providerNetwork=0
billing=0
live=NOT_PROVEN
```

## 当前证据

- Core四态surface与records invariants：`8/0` Green；PostgreSQL reader surface和role provisioning：
  `4/0` Green；Architecture/Bytecode Gate：`5/0 + 14/0` Green；
- fresh-JVM Acceptance：`3/0` Green。r1 unmarked seq13=`Missing`；r2 V15 marker且V13 row=0
  为`Required`；r3由V18 typed path完成后，即使key/profile当前为`REVOKED`且V16 challenge已过期，
  历史receipt仍为`Attributed`；
- r3三组test-only privileged fixture分别制造三张V16 child缺失、validation+attribution跨表一致的
  `response_hash` drift但stored canonical hashes不变、64-byte无效签名并重算signature/receipt hash；
  fresh JVM分别得到`EXACT_OVERLAY_PARTIAL`与两次`EXACT_OVERLAY_INVALID`，每次restore后再次
  `Attributed`；
- 每次read的immediate baseline前后，public regular/partition table row count与
  `to_jsonb(row)::text + xmin` image完全相同；legacy verified snapshot仍seq13且legacy seq14=0。
  这不声称sequence/catalog或整个数据库image不变；
- test role直连默认`transaction_read_only=off`，成功必须来自adapter自身enforced RR/RO transaction；
  临时V13 `SUPERUSER`、V15 `BYPASSRLS`与额外public view SELECT grant均令fresh JVM在读前固定
  fail closed，清理后重新`Attributed`；
- actual shaded-JAR artifact IT：`1/0` Green。5个Core public types对应10个class artifacts、reader
  top/nested 16个class artifacts均target/classes→module JAR→app JAR逐字一致；Acceptance/Main/surface
  test均不入包；roles/check resource source→target→postgres JAR→app JAR逐字一致；App open/find
  consumer=0，actual app bytecode Gate为空。

## Claim boundary

- `Attributed`是历史`VALID_AT_COMMIT`：reader校验签发/验证发生在当时key/profile/session/challenge
  window内，但不以read-time wall clock或当前`ACTIVE`判定；它不是当前授权、readiness或价格新鲜度；
- reader独立重算manifest/legacy chain、V13 policy、V14 profile、V15 requirement及V16 statement、
  attribution、event、head、challenge、transcript、receipt，另重算fingerprint/signature hashes和Ed25519；
- V19 dedicated role/process可读取13张关系中的bounded durable evidence及public verification material，
  属于confidentiality TCB；production App consumer仍为0；
- privileged/concurrent DDL、catalog aggregate delimiter ambiguity、V8-V16 writer/trigger/function provenance
  与raw V16 credential caller仍在TCB；本轮不声称serializable catalog snapshot；
- fresh child JVM不是PostgreSQL restart、hard-kill、connection-loss、same-attempt race或restart/reconcile证据；
- 没有production signer/KMS/HSM、key custody、TX-B/TX-C、legacy-head推进、provider network、billing、
  DeepSeek r1/r2/r3或Commercial evidence；overall Authority/Live仍Red；
- 先前工具调用记录暴露的owner credential必须立即撤销/轮换；repo/artifact marker scan不能抵消该事件，
  轮换前不得再次使用。

## Final root evidence

- `./mvnw --batch-mode --no-transfer-progress clean verify`：11-module reactor全部`SUCCESS`，
  2026-08-12 16:23:40 +08:00完成，总时长`08:33`；
- 全树精确`158`个`TEST-*.xml`，聚合`862 tests / 0 failures / 0 errors / 0 skipped / 0 flakes`；
- focused evidence：Core types/surface `8/0`，PostgreSQL reader surface/provisioning `4/0`，
  Architecture `5/0`，Bytecode Gate `14/0`，fresh-JVM Acceptance `3/0`，actual shipping artifact `1/0`；
- `verify-contracts.sh`：`8`个JSON Schema / `70` fixtures Green；`verify-doc-links.sh`：
  `123`个Markdown文件链接Green；`git diff --check`无输出；
- `HEAD=6d914d8b44498c857768ea5da97fa204db562966`；签收时dirty entries=`228`，全部保留，
  未reset、checkout或覆盖owner work；
- Core JAR SHA-256：`47cc19fadbe2a59c788139db7a16fea51c26be07250d441cacb04f97cd819d56`；
- PostgreSQL adapter JAR SHA-256：`a10da3dde418a5c4447ac86547c25a760c18c003a06bfd0523965b14719c6de0`；
- shipping app JAR SHA-256：`fe3f870ecd2c0b77081fc83096578060e340502d964e495fea439b2ac5f5462f`；
- ordered 28-file slice aggregate SHA-256：
  `b33071c583d05d1ccd8f1c9ff4bf78e258a654a3b6a1fe32965de73163480fc2`；
- 三路post-fix review：`P0=0 / P1=0 / P2=0`；本轮未commit、push、release、deploy，
  未执行provider网络或付费调用。

## Frozen ordered slice

ordered aggregate不包含本Build Note与living ExecPlan，避免自引用。算法为：按下列固定顺序对
每个路径执行`shasum -a 256 <path>`，把28行完整标准输出逐字拼接，再对拼接bytes执行
`shasum -a 256`：

```text
README.md
docs/README.md
docs/rfcs/README.md
docs/rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md
docs/architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoOverlayRequirement.java
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoOverlayAttribution.java
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoOverlaySnapshot.java
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoOverlayVerification.java
modules/core/src/main/java/io/emergeos/core/port/GraphExactPicoOverlayReader.java
modules/core/src/test/java/io/emergeos/core/domain/GraphExactPicoOverlayTypesTest.java
modules/core/src/test/java/io/emergeos/core/port/GraphExactPicoOverlayReaderSurfaceTest.java
adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql
adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/Pack010ProvisioningWrapperTest.java
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresProviderValidationAttestor.java
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresExactPicoProviderValidationAttestor.java
adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresExactPicoOverlayReader.java
adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresExactPicoOverlayReaderSurfaceTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009ProcessSupport.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayReaderMain.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayReaderAcceptanceIT.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V19ExactPicoOverlayReaderShippingJarIT.java
docs/operations/build-notes/2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md
docs/operations/build-notes/2026-08-12-s2-s4-pack010-v17-dormant-v16-ed25519-verifier.md
docs/operations/build-notes/2026-08-12-s2-s4-pack010-v18-dormant-typed-v16-attestor.md
```

## Design references

- [RFC-0014](../../rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md)
- [ADR-0018](../../architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md)
- [V18 typed attestor Build Note](2026-08-12-s2-s4-pack010-v18-dormant-typed-v16-attestor.md)
