# Build Note：Pack009 PostgreSQL durable graph / provider-accepted crash

- Change class：`R`（durable Agent graph / provider egress / recovery truth）
- Status：Engineering Green
- Task：Stage 2 S4/F5 · Pack009
- Date：2026-07-31
- Commit：待本增量独立提交，以 Git history 为准
- RFC：
  [RFC-0006](../../rfcs/0006-postgresql-canonical-one-shot-graph-attempt.md)
- ADR：
  [ADR-0010](../../architecture/decisions/0010-postgresql-canonical-graph-attempt.md)

## Outcome

Pack009 首次把 exact parent/child Agent graph 的 one-shot claim、Run/profile binding、
append-only journal、provider intent 与 verified read 放进同一个 PostgreSQL authority。
选定故障窗口是：

```text
PUBLIC synthetic fixture 已由 test owner 预置
→ synthetic console 通过 composition-level operator gate
→ parent / child exact authorization + RUNNING
→ child-only egress consume
→ provider credential read marker durable
→ private ReviewedOpenAiClient + model lazy construction
→ PROVIDER_INTENT(requestOrdinal=1) durable
→ independent loopback provider durable 接收 exactly one request
→ writer JVM 被父测试真实强杀
→ provider response durable，但 attribution 不存在
→ 两个 fresh verifier JVM exact-equal 返回
  VALID / INCOMPLETE / billing UNKNOWN
→ least-authority replay 与完整 writer replay 都在第二次 provider request 前拒绝
```

这不是“网络调用 exactly-once”的证明。它证明的是：当 provider effect 与本地 transaction
无法原子提交时，系统不会把未知费用伪造成未调用、免费或可自动重试。

## Frozen identity

| 项目 | 冻结值 |
|---|---|
| Pack path | `evals/task-packs/synthetic/009-openai-read-only-worker-provider-accepted-crash.json` |
| Pack raw SHA-256 | `8af9e2a71f6479cdc612eef6c24924e3dff2c5b12419ba3d0a65f37894c221a8` |
| environment raw SHA-256 | `d6c3cb929a4aa5f9be75dc7f385f1c0afe589277e2622501f684ee8ee5df8899` |
| execution slot | `pack009-provider-accepted-crash-r1` |
| attempt ID | `68cbc47a23c50b02771881d68d2c585e0662af9fe586c588003d9355fe75287f` |
| Capture request hash | `4347bd28fa9788f9550ff27874b118a44b10cc718031d087098b0ad973ed3c08` |
| parent Task hash | `425052ee7fb426e8e78ba0ebbead02045b3943d56b2a366f0bc899fda28a3241` |
| child Task hash | `f761724620938d6d4ccab71ae7473cf26ee569d91d4780a8bd8837444ee593ca` |
| first request raw-body hash | `3b1a11c3bcac1ef123401e82e71bea71d7c0f796e6a8c3d8b9634c016e74b6db` |
| requested model identity | `gpt-5.4-mini-2026-03-17` |
| maximum provider requests | `2` |
| reservation | `$0.417000` |

`executionSlotId` 是 checked-in、server-owned 的费用槽位，不允许调用方传 nonce 或时间制造
新 attempt。rolling binary 即使改变 manifest hash，也不能绕过同一
`(principalId, executionSlotId)` unique claim。

## TDD evidence

### Acceptance Red

第一条 outside-in process IT 先成功完成 module package、strict preflight、PostgreSQL
启动与 V1–V6 migration，然后 writer 在 packaged/test-harness runtime 以
`V7_GRAPH_SCHEMA_MISSING` 退出。Red 位于缺失的 durable graph behavior，不是 compile、
Docker、asset parser 或 provider 环境错误。

review hardening 另产生并保留五条真实 Red：

1. typed request receipt 曾可绑定 opaque SDK client；反射回归先证明构造器不应存在；
2. observer 在 receipt hash 后修改 OpenAI SDK 公共全局 mapper，raw loopback body hash
   与 receipt 真实不一致；
3. multi-release JAR中的 forbidden package与 first-party forbidden reference曾绕过
   raw-entry prefix Gate；
4. 真实 `Pack009GraphCrashHarnessMain.class` 没有 forbidden constant-pool reference，
   曾可作为额外 executable surface绕过 package denylist；
5. 若把新 Main误放进 graph App自己的 `src/main/io/emergeos/core`，最终 JAR无法仅按
   package区分它与 dependency class，曾可绕过 app-package allowlist。

第二条现在由 `ReviewedOpenAiClient` 的 private codec template、per-client private copy
与不暴露 mapper 的 request hasher关闭。observer 再修改全局 `INDENT_OUTPUT` 时，raw
HTTP bytes仍与 receipt hash exact-equal。第三、四条由 multi-release logical-entry
normalization、app-owned shipping class exact allowlist、真实 harness bytecode negative
fixture与全量 test-class resource排除共同关闭。第五条由对 App
`target/classes`中每一个 `.class`执行 package-independent exact allowlist关闭，并用
synthetic `io.emergeos.core.InjectedMain`先 Red 后 Green。

### Minimum Green

- Core 新增 framework-neutral `GraphAttemptManifest/Event/Snapshot/Verification`、
  typed state 与 `GraphAttemptStore` port；
- `GraphAttemptCoordinator` 以 opaque composition tokens固定
  `TTY → claim → challenge → approval → parent → child → egress → provider intent`
  顺序；
- PostgreSQL V7 新增 manifest、run binding、CAS head、append-only event 与 disabled
  seal skeleton，并为 graph-bound `agent_runs` 增加 all-or-none selector；
- store 每次 mutation 都从 durable prefix重建状态，event insert与 head CAS同事务；
- verified read使用单个 `READ ONLY REPEATABLE READ` snapshot，并重算 manifest、
  selection、journal、Run 与 seal truth；
- `VALID / INVALID`、`INCOMPLETE / SUCCEEDED / FAILED` 与
  `NOT_INVOKED / ATTRIBUTED / UNKNOWN` 三个轴独立解释；
- graph Eval App使用 direct-dependency default-deny、first-party classfile
  constant-pool Gate、multi-release entry normalization、app-owned class exact allowlist
  与 shaded JAR closure Gate。

## Process fault evidence

最终 targeted process Gate：

```text
PACK009_PROCESS_RECEIPT
providerPid=92914
writerPid=92915
verifierPids=92929,92930
replayPids=92942,92944
sequence=11
phase=PROVIDER_PENDING
billing=UNKNOWN
providerCount=1
provider=LOOPBACK_SYNTHETIC
realKey=false
realModelResult=false
usageAttribution=false
terminalSeal=false
console=SYNTHETIC_TEST
headHash=f0dad4ddb07d0dabc3bdb2bcccbf7a4b7e3fe3d59251671f1e0c433c2010e501
dbSnapshotSha256=b6de22ff39693f66b3c381ea660eae05f3541ec4bde4954dafac248a5db8bf85
```

父测试对这条回执背后的因果链做了以下断言：

- provider、首个 writer、两个 verifier、least-authority replay、完整 writer replay
  都是独立 JVM，且 production classes来自 shipping shaded JAR；
- provider 只接受 exact `POST /v1/responses`、exact Bearer sentinel、exact raw-body hash
  与 frozen request semantics；
- DB password与 synthetic provider key使用两个有界 stdin frame；key只在
  `CREDENTIAL_READ_STARTED` durable 后送入；
- kill紧前 writer仍存活、provider response尚不存在，
  `ProcessHandle.destroyForcibly()` 返回 `true`，随后 writer non-zero退出；
- provider accepted ledger与 response ledger都使用 create-new、force、read-back，
  response hash exact-equal；
- 两个 fresh verifier PID不同、stdout byte-equal，均为 read-only；
- minimal replay没有 provider endpoint/key；完整 writer replay只有 DB password，
  在 challenge与 provider key read前以 exit `10`拒绝；
- provider在两条 replay期间保持在线，handler全部 drain后最终 request count仍为 `1`；
- 15 张 public business/Flyway tables的完整 row JSON + `xmin` snapshot，在 response、
  两次 verify与两次 replay前后 exact-equal。

V7/Capture 是父测试的 fixture provisioning，不属于 operator-authorized attempt。
writer只做 exact read-check，不在 real-TTY/claim前 migrate或创建 Capture。

## Migration and compatibility

PostgreSQL 18.4 Testcontainers覆盖：

- fresh V1→V7、populated V1/V2/V3→V7与 fresh install；
- V1–V6 historical AgentRun、Bundle、WorkerResult bytes不被猜测或改写；
- graph tables升级后为空，旧 rows的 graph selector保持全 NULL；
- incompatible schema/checksum fail fast；
- manifest/binding/event不可 update/delete；
- reverse lock-order concurrency仍只有一个 claim winner；
- forged head/event/Run/profile/ordinal/seal与跨主体 read全部 fail closed。

并发 one-winner证据使用同一 JVM内两个独立 Store/DataSource transaction；进程证据是
随后启动的 fresh writer sequential replay。没有执行两个同时启动的 writer JVM或
cross-host contention。

## Shipping boundary

shipping `apps/graph-eval-runner` 当前只提供：

```text
no args / --preflight
--help
--verify（仅保留参数；完成 preflight 后明确拒绝）
```

它不提供 execute route。shipping JAR不含 synthetic console、provider admin、
writer/verifier/replay mains、crash coordination、JUnit或Testcontainers。Gate对
app-owned `.class`使用 exact resource allowlist，对 multi-release entry先恢复 logical
name再检查；App自身 `target/classes`中的每个 class不论声明 package都必须命中同一
allowlist，并逐项排除实际 `target/test-classes` 中所有 top-level、nested与 anonymous
classes。CLI拒绝 execute、JDBC、provider URL与 crash参数；`--verify` 返回
`SHIPPING_VERIFY_ROUTE_DISABLED`。真正读取 PostgreSQL的 verifier仍是 test-only
fresh process，不能被描述为 shipping product capability。

## Verification

当前稳定证据：

```text
contracts: 6 schemas / 54 fixtures / 9 task packs / 3 environments
Maven reactor: 11 modules / 106 XML reports / 616 tests
failures = 0 / errors = 0 / skipped = 0
Pack009 module: 13 unit + 2 process IT
```

主要 module counts：

```text
Contracts 41
Core 152
Agent Loop 41
OpenAI 22
In-memory 37
PostgreSQL 103
API 34
Eval Runner 88
Graph Eval Runner 15
Offline Harness 83
```

最终 Gate命令：

```bash
node scripts/validate-contracts.mjs
./mvnw --batch-mode --no-transfer-progress verify
./scripts/verify-doc-links.sh
git diff --check
```

## Independent review

最终 code freeze 后的 recovery/process 与 least-authority 两类独立复审均要求：

- process receipt不能借静态 counter、同进程 object或未实际 kill来冒充 evidence；
- request hash必须绑定 exact transport codec，而不是“通常相同”的全局 mapper；
- `terminalSeal=false` 必须来自同一 repeatable-read snapshot；
- shipping/test seam、secret frame、provider count与 replay rejection必须可执行验证；
- natural-language claim必须区分 synthetic、loopback、live、UNKNOWN与真实 billing。

两路阻断结论为 `P0=0、P1=0`。保留的 P2见下节。

## Nonclaims

本切片不声明：

- real TTY或项目所有者真实批准；本次 console是 test-only synthetic；
- real API key、external/live provider、real model result、真实 token/cost/invoice；
- `cost=0`、`token=0` 或 `billing=UNKNOWN` 表示免费、未调用或可 retry；
- terminal graph、terminal seal、成功 Artifact、product API或 Connector wiring；
- provider-side idempotency、reconciliation、checkpoint/resume、lease/fencing；
- 两个同时启动的 writer JVM、cross-host contention、cross-database replay protection；
- power-loss、NFS、hostile DBA、签名、producer attestation或 WORM；
- Temporal、parallel Worker、write-capable Worker、真实用户数据、模型质量或用户价值；
- library-level不可伪造 authority。当前 opaque typestate只约束遵循
  `GraphAttemptCoordinator` 的 composition；public Store mutation与可注入 console
  必须在未来开放任何 product execute route前进一步收口。

## Next

Pack009 不自动授权 live smoke。下一切片先冻结真实模型 baseline与 Harness protocol：

```text
exact owner-approved attempt
→ real provider receipt / model attribution
→ deterministic + stochastic Harness comparison
→ explicit cost and stop conditions
→ no product route promotion without reviewed evidence
```

任何 real key读取或外部请求仍需项目所有者对 exact attempt单独确认。
