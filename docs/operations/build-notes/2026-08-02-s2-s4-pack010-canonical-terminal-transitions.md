# Pack010 adapter-owned canonical terminal transition 回执

日期：2026-08-02
Stage：Stage 2 / S4 / F6
结论：adapter-owned typed terminal transition focused Engineering Gate 已进入最终独立复核；
overall Authority/Live 账本仍为 `P1=3、P2=1`，Gate Red，shipping execute 继续 disabled。

## Outcome

- production App 不再构造或接收 TX-B/TX-C raw JSON；`prepareChild` 只接收 opaque
  attributed outcome、`AgentRun`、`HarnessCandidateEnvelope`、`WorkerResultEnvelope`，
  `prepareParentAndSeal` 只接收 `AgentRun` 与 `ArtifactLineage`；
- PostgreSQL adapter 新增唯一 canonical projector，在 validated sequence 14/15 snapshot 上用
  Core/contract factory 先重建 terminal binding、event、seal 与完整 aggregate，再投影 V9 exact
  relation rows。caller 不能传入 `String`、`JsonNode`、`Map` 或 `byte[]` payload；
- projector 产出的 private-constructor transition 绑定 exact writer instance、manifest、cursor、
  Run/Bundle/Candidate/WorkerResult/child-terminal/Artifact hash 与 payload hash，并且 one-shot；
  public writer 不再暴露 mutable prefix Store；
- `PostgresGraphTerminalExecutor` 的 raw payload overload 已变为 private。既有 raw SQL、custom GUC、
  wrong role、expiry、replay、fault 与 restart negative 通过 test-only reflection bridge继续验证，
  没有重新开放 shipping ABI；
- sequence 14 上 wrong run/worker typed truth在 owner outcome claim前拒绝且不烧毁 authority；exact
  child command双线程只有一个 sequence-15 winner。sequence 15 上 wrong Artifact principal拒绝；
  PostgreSQL restart后两个 parent commands只有一个 sequence-17 winner，再次 restart后 fresh read
  精确重建 terminal truth；
- complete `responseHash` 与 input/cached/output/reasoning/total token attribution先 durable，才允许
  构造 terminal transition。event 15/16/17 `committed_at`全部由 PostgreSQL生成且不早于
  `occurred_at`；
- shipping Main对 runtime仍无 incoming reference，`--execute`不存在。`keyReads=0`、
  `clientFactories=0`、`modelFactories=0`、`httpRequests=0`、`providerCalls=0`、`billing=0`；
  不声称 provider exactly-once。

## Acceptance Red 与 Focused Red

Acceptance 要求：shipping terminal path只能消费 adapter-minted opaque transition；任何 raw payload、
mutable prefix writer或 public transition constructor必须在 live route之前 fail closed。

首轮 Red：6 tests中2 failures，production App preparation与 public writer仍接受 raw `String`，
executor也存在 callable raw overload。扩展到 transition constructor与 mutable writer surface后为
7 tests中3 failures。最小实现后，architecture/bytecode Gate首先因仍冻结旧 raw-`String`
descriptor而出现 17 tests中2 failures；只把 exact descriptor改为
`ChildTerminalTransition` / `ParentTerminalTransition`，consumer集合仍固定为唯一
`Pack010PostgresRuntimeComposition`。

首次 post-fix reviewer又发现 `prepareChild` 的 no-consumer Gate仍引用上一版
`Outcome + Candidate + String` descriptor，使防线成为 vacuous guard。该 focused P2 已修为 exact
`Outcome + Candidate + AgentRun + WorkerResultEnvelope` descriptor，directory scanner与独立
consumer assertion都锁定当前 ABI；修复后 focused 17/17与第二次 root Gate均Green。

integration 首次 Red是 fixture convenience overload带入旧 manifest/profile，导致 exact runtime
truth被正确拒绝；测试改为显式使用 shipping `Pack010GraphEvalCatalog` manifest/profile，没有放宽
production校验。

## 可复现 evidence

```bash
./mvnw -pl apps/graph-eval-runner -am \
  -Dtest='Pack010TerminalOutcomeBindingAcceptanceTest,GraphEvalArchitectureTest,Pack010ProviderCapabilityBytecodeGateTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 17 tests / 0 failure/error/skipped

./mvnw -pl apps/graph-eval-runner -am \
  -Dtest='Pack010PostgresRuntimeCompositionIT,Pack010ExactProviderAttributionPostgresIT' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 2 tests / 0；typedDriftNoBurn=true concurrentOneWinner=true
# postgresRestart=RECONCILED；realProvider=false；billing=0

./mvnw -pl adapters/postgres -am test
# PostgreSQL adapter reactor：413 tests / 0
# adapter module本身162 tests / 0；V9 authority与61条 graph fault tests均Green

./mvnw clean verify
# 11 modules；137 XML reports；792 tests；0 failure/error/skipped
# BUILD SUCCESS；5m28s（descriptor P2修复后的第二次独占root run）

node scripts/validate-contracts.mjs
# 8 schemas / 70 fixtures / 10 task packs / 4 environments，Green

./scripts/verify-doc-links.sh
# 98 Markdown files，Green
```

packaged graph-eval JAR：
`db6bcc29e1f7564dc9ac9ab23880a1893743f7860bff6ca0001415b26622977e`。

## 冻结快照与独立复核

HEAD：`6d914d8b44498c857768ea5da97fa204db562966`。本 aggregate只冻结本 slice delta，
不替代前序 V9/OwnerTty/Core aggregate与各自 root Gate。exact ordered 15-file list：

1. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalPayloads.java`
2. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphRuntimeWriters.java`
3. `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutor.java`
4. `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack010PostgresRuntimeComposition.java`
5. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphTerminalExecutorTestAccess.java`
6. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`
7. `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V9GraphTerminalExecutorProcessMain.java`
8. `apps/graph-eval-runner/src/test/java/io/emergeos/adapters/postgres/Pack010GraphTerminalStoreBridge.java`
9. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010GraphTerminalFixture.java`
10. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010PostgresRuntimeCompositionIT.java`
11. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactProviderAttributionPostgresIT.java`
12. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010TerminalOutcomeTestBridge.java`
13. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010TerminalOutcomeBindingAcceptanceTest.java`
14. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`
15. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`

复算命令是 `shasum -a 256 <上述有序文件> | shasum -a 256`；最终 slice-delta aggregate：
`dc25427dcd65c48ea7dfa78571e6a78172a2e52d4f99409fad48eef224f3efdb`。

三路 actual post-fix final review均先独立命中 code aggregate；首次 capability review发现 stale
`prepareChild` descriptor P2后，修复并重跑root Gate，再对新hash完成第二次实际复核：

- capability / threat model：`P0=0、P1=0、P2=0`；
- typed aggregate / PostgreSQL V9：`P0=0、P1=0、P2=0`；
- Gate / evidence / packaged JAR：`P0=0、P1=0、P2=0`。

Gate reviewer还独立确认 `137 reports / 792 tests / 0`、JAR hash、98 doc links、validators、
test bridge不进入JAR、shipping Main无runtime/provider capability/`--execute`引用。该结论只关闭本
focused slice，不把 `P1=0`外推为overall Gate Green。

## 剩余风险与下一条 Acceptance

- pre-Candidate / failure terminal尚无 typed aggregate与 semantic transaction；
- opaque outcome与 terminal transition仍为 process-local。hard-kill后 durable DB truth可安全重读，
  但不能重建已消失的 process authority；若产品要求继续执行，必须新增 forward-only durable
  outcome resume/fencing设计，不能从 attribution或 billing猜测 provider exactly-once；
- 本 slice只证明 dormant production composition、ephemeral PostgreSQL、loopback synthetic与 packaged
  process contract；没有 shipping App live route、真实 owner逐次批准或真实 r1/r2/r3；
- overall Authority/Live按总控账本保持 `P1=3、P2=1`、Gate Red。任何 owner批准、credential、
  provider/model/network/billing或 shipping enablement都需要独立 Human-in-the-loop。

## Evidence lane

- Engineering：本 focused slice Green（final review `P0=0、P1=0、P2=0`）；overall
  Authority/Live仍按总控 `P1=3、P2=1`、Gate Red；
- Human-learning：0；没有 teach-back、真实访谈或 owner live验证；
- Commercial：0；没有报价、付款、真实 billing或客户验证；
- Human-in-the-loop：当前 synthetic/local slice无需；live enablement需要。
