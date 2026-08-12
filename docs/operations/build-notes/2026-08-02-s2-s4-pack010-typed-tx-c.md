# Pack010 typed TX-C capability 回执

日期：2026-08-02
Stage：Stage 2 / S4 / F6
结论：durable sequence 15 → strict success aggregate review → process-local opaque
parent command → exact V9 TX-C 的 focused Engineering evidence 为 Green；独占 root Gate为
137 reports / 790 tests / 0 failure / 0 error。第三版冻结快照已经三路实际 post-fix review：
focused A与B/C均为P0/P1/P2=0，Gate/evidence对代码与运行证据为P0/P1=0；其唯一doc-only P2即
“回填本结论”，现已完成。该结论不外推到总体Gate：failure terminal、跨 JVM resume、adapter raw
semantic ABI、production canonical payload producer与 shipping live route仍未收口；按总控账本
Authority/Live仍为P1=3/P2=1、Gate Red。

## Outcome

- production App composition已删除 `completeParentAndSeal(String)`，只保留
  `prepareParentAndSeal(String)`与
  `completeParentAndSeal(Pack010ParentTerminalCommand)`；command为 private-constructor、
  process-local、one-shot；
- prepare先从 restricted prefix writer重建 verified sequence 15，再严格解析 duplicate/trailing/
  size与12个 root keys、全部 relation row exact keys；`TaskEnvelope`、`ResultEnvelope`、
  `HarnessRunBundle`、完整 parent `AgentRun`、完整 `ArtifactLineage`、Trace、ResourceBinding、parent
  `GraphTerminalBinding`、event 16、`GraphTerminalSeal`与event 17被重建为 sequence 17
  `GraphAttemptSnapshot`，复用 Core terminal/lineage/parent-child/candidate/worker invariants；
- artifact/version/terminal Run/binding/event/seal/resource mirror不只验证 JSON shape：所有 identity、
  version、timestamp、sequence、manifest、principal、owner status与 typed mirror必须 exact 且一一匹配；
  relation row不能交换、复用或只靠 key shape通过；
- 同一约束也已前移到TX-B：child payload在 structured-final consume与Owner claim前重建完整 terminal
  child `AgentRun`、`GraphTerminalBinding`、event 15与 sequence 15 `GraphAttemptSnapshot`，并 exact绑定
  Candidate、WorkerResult、Trace、resource mirror及全部 Run denormalized relation；
- event 15/16/17 的 `committed_at`均不再属于 caller payload；V9 semantic function只接受业务 event字段，
  由 PostgreSQL default写入 audit timestamp，event 16/17在同一TX-C内取得同一transaction timestamp；
- command绑定 runtime owner、exact terminal capability、revision、Coordinator、egress、manifest、
  完整 seq15 cursor/head、两条有序 attribution hash、Candidate/WorkerResult/child-terminal hash及
  payload hash。wrong runtime、stale/replay与并发均 fail closed；
- Owner facade不接受 caller提供的 cursor。`claimParentTerminal`与 claim consume各自 read-back exact
  durable sequence 15，opaque claim内绑定 revision、Coordinator、egress与 durable cursor；仅凭
  内存 `CHILD_CLAIMED`、数据库仍在 sequence 14时不能再派生 parent authority；
- forged root/row、missing seal event、nested Bundle、parent binding、seal attribution、cross-run
  Trace、duplicate与trailing payload全部在 Owner claim前拒绝；之后 exact payload仍可 mint并提交，
  没有 burn；两个独立 parent commands并发只有一个 seq17 winner；
- command mint后注入 prefix authority drift会在 consume前拒绝；撤销 drift后同一 command仍可使用。
  PostgreSQL process restart发生在 command mint与TX-C之间，restart后 exact seq15 read-back不变，
  随后一次TX-C到seq17并完整 reconciliation；
- 全程只有 PUBLIC synthetic Testcontainers数据与本机进程。真实 API key、provider、model、网络、
  真实 r1/r2/r3与 billing均为0；shipping execute继续 disabled。

## Acceptance Red 与 Focused Red

Acceptance Red先只改 surface test，实际观察到：

```text
Tests run: 4, Failures: 1, Errors: 1
completeParentAndSeal仍接受java.lang.String
Pack010ParentTerminalCommand不存在
```

Focused Red随后加入 forged/no-burn、wrong runtime、两个 command并发、restart、replay矩阵，
test compilation因 `prepareParentAndSeal`与 typed command不存在而失败。最小实现后的首轮
integration仍正确保持 Red：canonical parent payload被 `TaskEnvelope` record equality拒绝。
根因是 PostgreSQL JSON read-back的 `budgetUsd` scale与内存对象不同；修复使用协议已定义的
`IntegrityHashes.taskHash`绑定完整 Task，而不是删除字段或容忍 drift。

首次 post-fix review随后发现 relation只验证 exact keys、没有把多项 value绑定到 canonical aggregate，
且 `committed_at`仍由 caller提供。新增 Acceptance伪造 `artifact_version.created_at`，实际观察到
`expected RuntimeException ... but nothing was thrown`；最小修复改为完整 `AgentRun`、
`ArtifactLineage`、`GraphAttemptSnapshot`重建与全 relation/mirror exact projection，并把 audit timestamp
收回 PostgreSQL。此后 architecture gate又抓到 enum switch生成未审计 synthetic `$1.class`；实现改成
显式 branch，不扩大 shipping allowlist，focused architecture/runtime suite 16/16 Green。

第二次实际 frozen review没有外推前述结果，而是发现TX-B仍要求完整 event relation row并把caller的
`committed_at`原样插入；child路径也只检查部分 Run/event/resource字段，DB rejection会发生在command
consume与Owner claim之后。新增四个 Acceptance分别伪造 child committed_at、agent version、resource
hash与event actor；首个测试实际在 `Pack010PostgresRuntimeCompositionIT.java:514`报
`Expected RuntimeException ... but nothing was thrown`。修复后这四类drift均在claim前拒绝，exact
payload随后仍能成功TX-B；V9改为DB-owned timestamp与显式event列插入，focused App 16/16、
PostgreSQL adapter 162/162 Green。

## 可复现 evidence

```bash
./mvnw -o -pl apps/graph-eval-runner -am \
  -Dtest='Pack010PostgresRuntimeCompositionIT,Pack010TerminalOutcomeBindingAcceptanceTest,Pack010ProviderCapabilityBytecodeGateTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 11/11 Green；另含 GraphEvalArchitectureTest时16/16 Green

./mvnw -o -pl adapters/postgres -am test
# 162/162 Green
# 包含 fresh/historical V9 migration、role/ACL/authority、fault与surface evidence

./mvnw -o -pl apps/graph-eval-runner -am \
  -Dtest='Pack010PostgresRuntimeCompositionIT,Pack010TerminalOutcomeBindingAcceptanceTest,Pack010ProviderCapabilityBytecodeGateTest' \
  -Dit.test='Pack010DurableGraphTerminalProcessIT' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
# TX-A/B/C 37-point process kill：OLD_PREFIX_ONLY + FRESH_JVM
# TX-A/B/C two-JVM：ONE_ADVANCED_ONE_CONFLICT，stale writer CONFLICT
# successor：kill rollback；two fresh JVM one winner
```

process matrix首次执行时三个 fault/race方法全部 Green，但 packaging boundary发现新 parent command
class尚未加入 reviewed shipping allowlist，suite因此 Red；加入 exact class allowlist后，独立
`shippingJarContainsVerifierButNoHarnessOrCliRoute`复跑 Green。后续 enum switch产生的 synthetic
class也被同一 gate拒绝；最终实现未把 synthetic class加入 allowlist。

```bash
./mvnw clean verify
# 独占执行；11 modules，BUILD SUCCESS，5:30
# 137 XML reports / 790 tests / 0 failure / 0 error / 0 skipped

node scripts/validate-contracts.mjs
# 8 schemas / 70 fixtures / 10 task packs / 4 environments

./scripts/verify-doc-links.sh
# 97 Markdown files

git diff --check
# Green
```

历史第一次完整 root执行期间有 reviewer focused Maven与共享 `target/`重叠，因此只保留为诊断，
不作clean evidence。TX-B post-review修复完成后再次确认无 Maven/Surefire进程并独占执行；以上计数
仅来自最新修复后的独占 root Gate。

## 剩余风险

- 本 slice只关闭 successful seq15→TX-C。pre-Candidate/failure terminal与 null Artifact路径仍无
  typed outcome，不得外推；
- parent command与 Owner terminal capability都是 process-local。JVM hard-kill后不能从 seq15
  自行恢复，只能 fail closed；若要求免再次 owner授权恢复，需要 forward-only V10 durable
  parent-intent/command design；
- V9 transaction本身已有 packaged 37-point kill/two-JVM证据；本 slice的 App typed command只有
  in-process JVM + PostgreSQL restart evidence，尚无直接穿过 production composition的 packaged
  hard-kill harness；
- adapter仍公开 `PostgresGraphRuntimeWriters`的 raw String terminal sink，package-private executor也
  保留 raw overload；claim不可伪造、shipping Main不可达且 App path已有 typed review，但该更深
  ABI在 live前仍需收成 adapter-owned typed transition。`prefixWriter()` mutable Store surface与
  Owner/Coordinator两套 terminal typestate也仍需统一 reservation；
- parent payload来自 test-only staging/exporter；production App尚无 canonical parent plan encoder，
  shipping execute仍无 route。

## Evidence lane

- Engineering：本 focused implementation、DB/process/root evidence Green；第三版13-file aggregate
  `32596e034dc40694fa7d03a3788eb2adf7832163703e153c35f7b3e045572b9b`已完成三路实际
  post-fix review，focused A与B/C均为P0/P1/P2=0；总体Authority/Live仍为P1=3/P2=1、Gate Red；
- Human-learning：0；没有teach-back、真实访谈或owner live验证；
- Commercial：0；没有报价、付款、真实billing或客户验证；
- Human-in-the-loop：本 synthetic slice无需；任何真实owner授权、credential/provider/model/network/
  billing或shipping enablement都必须逐次等待owner授权。
