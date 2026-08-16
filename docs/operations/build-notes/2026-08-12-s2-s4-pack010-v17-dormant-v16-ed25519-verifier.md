# Pack010 V17 dormant V16 Ed25519 public verifier 工程回执

日期：2026-08-12

Stage：Stage 2 / S4

状态：`focused Engineering Gate Green`

结论：本切片只增加production-source、已进入shipping app JAR但保持未接线的V16 Ed25519
public-key verifier primitive。compiled Acceptance、bytecode/actual shaded-JAR Gate、独占root clean、
冻结hash与三路独立post-fix review均已Green。该结果不外推为PostgreSQL-native验签、production
stage/commit orchestration、Authority/Live或Commercial evidence。

```text
scope=DORMANT_PACKAGED_V16_ED25519_PUBLIC_VERIFIER_LOCAL_ONLY
compiledProductionVerifierPrimitive=1
packagedVerifierPrimitive=1
productionV16StageCommitApi=0
shippingConsumer=0
configured=0
running=0
postgresNativeSignatureVerify=0
rawV16CredentialInTcb=true
challengeComponentHashes=DATABASE_MINTED_TCB
keyCustody=NOT_IMPLEMENTED
providerNetwork=0
billing=0
live=NOT_PROVEN
```

## 当前 Acceptance 观察

- V16 raw attestor credential可以把任意128位小写hex签名提交为`CONSUMED`；测试只在显式事务中
  观察后rollback，证明PostgreSQL-native验签仍为0，不把credential认证冒充为密码学认证；
- production-source verifier先核X.509 public-key DER的SHA-256 fingerprint，再对完整V16 framed
  transcript执行Ed25519 verify；invalid signature、wrong signer key与fresh-challenge replay均在测试
  调用commit之前被固定cause-free integrity error拒绝，full public-table JSON/`xmin` image不变；
- valid signature由test-only ephemeral key生成，test harness先调用production verifier primitive，再
  调用V16 commit；这只证明local ordering，不构成production stage/commit orchestration；
- expiry由PostgreSQL clock在commit返回SQLSTATE `55000`并完整回滚，Java verifier不是expiry authority；
- verifier重算challenge与transcript；statement/attribution/event/head component hashes仍是
  database-minted TCB inputs。完整62-field production mapper/semantic attestor未实现；
- raw provider request/response、credential、private key与完整transcript不进入durable row或Receipt。

## Focused evidence

| Receipt | 当前状态 |
|---|---|
| Core canonical-invariant / ephemeral Ed25519 verifier test | `3/0` Green |
| V16/V17 PostgreSQL Acceptance | `1/0` Green |
| provider capability bytecode Gate | `11/0` Green |
| graph architecture Gate | `5/0` Green |
| actual shaded-JAR verifier inclusion/test exclusion/material scan | `1/0` Green |
| root clean verify | `11 modules / 150 XML / 839 tests / failures=errors=skipped=flakes=0`；`BUILD SUCCESS`；`8:15` |
| contracts / fixtures | `8 schemas / 70 fixtures` Green |
| docs links / `git diff --check` | `117 Markdown files` / Green |
| frozen aggregate / app JAR hash | `17-file aggregate`与actual app JAR均已冻结，见下节 |
| three independent post-fix reviews | Java/Gate/artifact、SQL/TCB/Acceptance、evidence/docs：`P0/P1/P2=0` |

## Artifact claim boundary

最终artifact Gate必须单独证明：

- actual app JAR恰含
  `GraphExactPicoProviderSignatureVerifier.class`与
  `GraphExactPicoProviderValidationChallenge.class`；
- verifier test、Acceptance、test signer与private-key fixture均不进入app JAR；
- first-party bytecode中private-key JCA types和
  `initSign/sign/generatePrivate/getPrivate`仍零引用；本切片未新增V17 production signer
  implementation。历史V13 signer capability interface仍存在，但shipping first-party code不提供其
  implementation或private key；
- actual JAR逐entry解压扫描4种private-key PEM header、文件名同时含`private`且扩展为`.pem/.key`
  的resource，以及delimiter-bounded DeepSeek-style `sk-*` value marker为0；该规则不证明所有
  credential格式或第三方private-key Java类型为0；
- 两个production class在target/classes、Core JAR与app JAR逐字hash一致，且source < class < JAR <
  packaged IT XML。

## Remaining boundaries

- PostgreSQL-native Ed25519、raw credential bypass closure、production stage/commit API、signer/key
  custody/rotation、reader与App route均未实现；
- provider provenance仍是trusted-caller输入；DeepSeek live、pricing freshness、billing与pre-egress
  authority均未证明；
- V16 overlay TX-B/TX-C、process hard-kill、race、restart与durable re-verification均未证明；
- 本地compiled或packaged不等于configured、running、Authority或Live。

## Frozen snapshot

```text
HEAD=6d914d8b44498c857768ea5da97fa204db562966
dirtyStatusEntries=202
orderedSliceFiles=17
orderedSliceAggregateSha256=d06f8ab4ce919b2cb6b44fff60ccc65c4b29821b70d828451bd6bbbaef6536ba
coreJarSha256=4f8092afb36686e10f5fa0f614e2de00e43e88157016e0c44687d301d3986227
shippingAppJarSha256=4f6f52e3e809e2c23ceb6cc7c1d2fa46217cafaec7fed96addc3e00663f31bd3
verifierClassSha256=12700d2ddda55fbfd70c3f34b4892679fe2075084dde9c525b8db25f7361d630
challengeClassSha256=83996767c7a56a15dbaff94dc1c40127ef107fa2e9172e1475526b3be255bfc3
```

两个production class在`target/classes`、Core JAR与actual app JAR三路逐字一致。freshness为：
source `10:52:44` < class `10:59:57` < Core JAR `10:59:59` < app JAR `11:04:01` <
artifact IT XML `11:07:13`。Core verifier XML为`3/0`，V16/V17 Acceptance为`1/0`，provider
capability Gate为`11/0`，graph architecture Gate为`5/0`。

ordered aggregate不包含本Build Note与living ExecPlan，避免自引用。算法为：按下列固定顺序对每个
文件执行`shasum -a 256`，保留完整标准输出行并按原序拼接，再对拼接bytes执行一次
`shasum -a 256`。17个精确路径为：

```text
README.md
docs/README.md
docs/rfcs/README.md
docs/rfcs/0011-exact-pico-provider-tx-a-overlay.md
docs/rfcs/0012-dormant-v16-ed25519-public-verifier.md
docs/architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md
docs/architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md
docs/operations/build-notes/2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoProviderValidationChallenge.java
modules/core/src/main/java/io/emergeos/core/domain/GraphExactPicoProviderSignatureVerifier.java
modules/core/src/test/java/io/emergeos/core/domain/GraphExactPicoProviderSignatureVerifierTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009ProcessSupport.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayTxAAcceptanceTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V17VerifierShippingJarIT.java
```

本轮未commit、push、release或deploy；dirty tree边界被保留。Artifact marker scan只证明明确列出的
private-key PEM header/named resource与DeepSeek-style credential value marker为0，不能覆盖所有
第三方type或credential格式。另有一次审查流程将owner提供的credential放入本机命令参数；仓库与
shell history均无落盘，但工具调用记录已暴露该值，因此必须撤销/轮换，不能用本地marker=0抵消该事件。

## Design references

- [RFC-0012](../../rfcs/0012-dormant-v16-ed25519-public-verifier.md)
- [ADR-0016](../../architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md)
- [V16 overlay Build Note](2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md)
