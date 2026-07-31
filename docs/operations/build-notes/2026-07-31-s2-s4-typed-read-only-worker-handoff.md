# Build Note：S4/F3 typed read-only Worker handoff

- Change class：`R`（Agent Runtime / public contract / PostgreSQL truth）
- Status：Engineering Green
- Task：Stage 2 Pack 007
- Date：2026-07-31
- Commit：随本增量提交，以 Git history 为准

## Outcome

EmergeOS 现在有第一条可持久核验的 bounded Worker vertical：

```text
owner-scoped Capture
→ server-owned parent Task + Fake Conductor
→ typed WorkerCall
→ registered child Task/Run（serial，depth=1）
→ capture.read
→ durable WorkerResult + terminal child Trace/Bundle
→ verified parent HANDOFF
→ parent-only Artifact + terminal parent Run
```

普通 `apps/api` 仍只运行 deterministic Fake，不读取 real key，也不调用 live model。
新增能力严格限制为一个 server-owned、synchronous、read-only Worker。它不是通用
multi-agent system，也没有 parallel Worker、checkpoint/resume、lease/fencing、
write-capable Worker 或 distributed workflow runtime。

## Why this matters

“让另一个 Agent 帮忙”如果只是一段 prompt 或普通 Tool call，系统无法回答：

- child 到底拿到了什么权限和 context；
- child 是否真的启动、用了什么 Model/Tool、消耗多少预算；
- child 在 process crash 后留下了什么 durable output；
- parent 实际消费的是哪一个 child、哪一份 proposal；
- 最终 Artifact 到底由谁写入。

Pack007 把这些问题变成 typed contract、独立 Run、durable WorkerResult、safe Trace、
hash relation 与 PostgreSQL transaction boundary。当前最重要的产品不变量是：

> Worker 负责有边界地提出 proposal；parent application service 验证并成为唯一
> Artifact writer。

## Adopted architecture

[RFC-0004](../../rfcs/0004-typed-read-only-worker-handoff.md) 与
[ADR-0008](../../architecture/decisions/0008-typed-read-only-worker-handoff.md)
冻结以下设计：

- `AgentModel.Decision.WorkerCall` 是 typed delegation，不是 Tool alias；
- `AgentWorkerRuntime.prepare → Prepared.execute()` 是 provider-neutral two-phase、
  one-shot seam；
- Worker registry/profile identity 由 product composition 精确校验，mismatch
  startup fail-fast，不创建伪 terminal Run；
- child Task 继承并只能收缩 principal、policy、state、context、tool registry、
  environment、budget 与 deadline；
- Model 的有效 Tool authority 是 exact registry 与 Task allowlist 的交集：parent
  Task 固定 `requiredTools=[]`，child 才有 `requiredTools=[capture.read]`；handoff
  不会把 child Tool authority 返还给 Conductor；
- child output 使用 `WorkerResultEnvelope`；child Bundle 以
  `WORKER_RESULT` binding 绑定 result hash，parent Bundle 以 `HANDOFF` binding
  绑定 exact child Run/Bundle；
- V6 新增 `agent_worker_results` 与 parent/child relation；
- child terminal commit 与 parent verified consume 是两个 transaction boundary；
  中间 crash 可以留下 child terminal + WorkerResult durable、parent `RUNNING`
  的真实 incomplete state，当前不自动 resume；
- Agent Loop/Core 继续不依赖 Spring、PostgreSQL、Temporal、Pi、AgentScope 或 provider
  SDK。

## TDD receipt

### First runnable Acceptance Red

第一条 Red 没有停在 compile error。旧 Kernel 把 `worker.delegate` 当普通 Tool，
执行 production class path 后得到：

```text
parent = BLOCKED / TOOL_NOT_ALLOWED
Run start / complete = 1 / 1
child Run / Model / read = 0
WorkerResult / HANDOFF binding / Artifact = 0
```

而 acceptance 明确要求 parent/child 两个 terminal Runs、一次 child read、一个
durable WorkerResult、一个 exact Handoff 与一个 parent Artifact。当时执行命令为：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/inmemory -am \
  -Dtest=OfflineReadOnlyWorkerHandoffTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Minimum Green and adversarial hardening

minimum implementation 依次补齐：

1. Task delegation、WorkerResult、Trace/Harness bindings 的 Java/JSON contract；
2. `WorkerCall`、two-phase runtime、deadline/budget/cancellation/child-status precedence；
3. durable child output、pair verifier 与 parent-only Artifact commit；
4. strict Pack loader、两个 fresh runner exact replay 与 Node semantic validator；
5. V6 migration/store/constraints；
6. packaged success 与 real process-kill crash gap。

独立审查进一步产生并关闭两个 PostgreSQL P1：

- deferred graph trigger 在 Handoff `UPDATE` owner 时只检查 NEW graph，可能破坏 OLD
  parent 的 exact-one-child invariant；
- 缺少“terminal child 不能向仍为 `RUNNING` 的 parent 提交 Handoff”的直接回归证据。

修复后 trigger 对 INSERT/DELETE/UPDATE same-owner/moved-owner 分别检查正确的最终
OLD/NEW graph；两个 regression 都先证明 failure，再完成 Green。

post-fix composition/security/durable-boundary 审查又产生并关闭四个 P1：

- worker-bound parent Task 仍携带 `[capture.read]`。新增攻击测试先得到
  `expected [] but was [capture.read]`；修复后，Conductor 在 handoff 前或成功
  handoff 后请求合法 `capture.read` 都以 `TOOL_NOT_ALLOWED` 结束，Tool validation
  与 execute 均为 0；
- PostgreSQL 最早 durable boundary 曾允许带 read-only Worker capability、却仍携带
  direct Tool authority 的非法 root parent 先落成 `RUNNING`。Acceptance Red 证明
  首次 admission 会留下 durable row；修复把 exact parent-profile 校验前移到 root
  `start()` 的 INSERT 之前，并在 root verified read（包括 `RUNNING`）再次校验。
  回归固定非法首次写入后 `agent_runs=0`，direct SQL 篡改后的 `RUNNING` parent
  在 `findOwned` 以 integrity failure fail-closed；
- post-request `HANDOFF_REJECTED` 曾允许 Trace status 与 terminal
  status/failure 自由组合。负例先证明 `MALFORMED_RESULT +
  HANDOFF_DISPATCH_FAILED` 可通过；修复后使用 closed exact mapping，非法组合在
  `AgentRun` protocol boundary fail-closed；
- Java/Node/in-memory 曾接受 immediate self-parent，而 PostgreSQL 拒绝。Java 与
  Node Acceptance Red 分别证明旧行为后，Task constructor、Node semantic validator、
  Worker profile 与 fixture 已统一拒绝。

同轮还关闭三个 P2：child exact inheritance 增加
`dataClass/modalities/latencyClass`，所有 model identifiers 统一通过 safe domain
validator；不可信 child `resolvedModel` 被归一为
`FAILED / UNSAFE_AGENT_OUTCOME`，不持久化伪 Trace。另一个 Trace protocol Red
证明：accepted `HANDOFF_RESULT` 后，旧协议允许
`MODEL_STEP_FAILED / AGENT_KERNEL_FAILED` 在没有 terminal event 时结束。Green 后，
non-success Trace 必须显式进入 terminal；仅保留可证明的 cooperative cancellation、
`latencyMs >= deadlineMs` 的 deadline exhaustion，以及
`modelSteps == maxModelSteps` 的 step-limit exhaustion。伪 outcome 在产品边界被归一为
`FAILED / UNSAFE_AGENT_TRACE`，清空 Trace/Handoff refs，且不创建 Artifact。

## Frozen Pack007 receipt

Task Pack：

- path：
  `evals/task-packs/synthetic/007-offline-read-only-worker-handoff-context-drift.json`
- bytes：`8,443`
- raw SHA-256：
  `808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c`
- 唯一变量：registered Worker `contextPolicyVersion`
  `ref-only-v1 → ref-only-v2`
- provenance：literal、PUBLIC、synthetic；network/model/Connector 全部关闭

| Receipt | control | context-policy drift |
|---|---:|---:|
| parent status | `SUCCEEDED` | `BLOCKED` |
| child status | `SUCCEEDED` | 无 child |
| parent Model calls | 2 | 1 |
| child Model calls | 2 | 0 |
| child Tool executes | 1 | 0 |
| Capture reads | 3 | 1 |
| Run start / complete | 2 / 2 | 1 / 1 |
| WorkerResult | 1 | 0 |
| pair verification | 1 | 0 |
| Artifact | 1 | 0 |
| failure | null | `HANDOFF_CONTEXT_POLICY_DRIFT` |

control 的三次 Capture read 分别是 parent owner preload、child delegated
Tool-backed read，以及 durable Worker evidence binding 前的 owner-scoped read；
它们不是三次 Tool call。drift fault 只有 parent preload，child Run、Model、Tool、
delegated read、WorkerResult、Handoff binding 与 Artifact 全部为 0。

主要 hashes：

```text
parent Task         d1dfaa518503963bb71c4401aa51e23000fb2f81b35892b29fb09e01229852e8
child Task          f18aeb3d9c5b58929f22c55ead9e71f5aa6f74c5708fddfc413f3ffee94cfbd3
parent Trace        eba203ca7e61046363308f297ab94e509d82badf70c601ce1136de2071b3faef
child Trace         ea07ac77589c9f71fcae95a5e0e52e16bbdca00343c48ecd70fea7257a5af9f5
parent Bundle       fa3d7840adfc0333dbd071db4229af0664654b50f17b2f1c8d6f6e03eb24efbe
child Bundle        c12b2771c506bf823139af860396ddc8736bb81a1f0bbd9b09a4138d83cd3e0a
WorkerResult        d69ecb08300f1f1cd378d826243add922c0deb75a15d9393a63d5d00d2a2f5a8
proposal / Artifact e5b6493d7741cf43b65fb45c29d0351f3d038e470df31f964e6f1fa9cc8f767b
fault parent Trace  b43c9c149a15e1e0cd7b96a29ad2f7b7c9f2b1e1422f84c41f316368e59bc2d9
fault parent Bundle 594793dc386abaef416390e4a4ae76e32fb94d19130c92a1ba0e2c39ad3f1e6e
```

`OfflineReadOnlyWorkerReplayTest` 是 strict test fixture，底层是 production classes
+ test recording stores；它不是 filesystem/product replay CLI。

## PostgreSQL and process evidence

V6 验证范围：

- fresh install 与 populated V1/V2/V3/V4/V5 → V6；
- 历史 Task/Result/Bundle JSON 与 hashes 不被改写；
- 无法证明的 legacy generic Handoff/orphan child atomic fail-fast 并保持 V5；
- same-owner、terminal parent/child、exact Task/Bundle/WorkerResult hash；
- exact one child、single-consume、immutable WorkerResult；
- cross-owner、missing、running、wrong-hash、self-link、cycle/reuse/tamper；
- Worker-capable root 在 INSERT 前验证 parent 无 direct Tool authority，且
  `RUNNING` root verified read 会复验并拒绝 durable tamper；
- late transaction rollback、两个 parent concurrency one-winner；
- Handoff move 的 OLD + NEW owner graph guard。

packaged success IT 在 creator 停止后冻结数据库，再让两个 fresh JVM 各自读取
parent/child Run、Trace 与 Bundle。crash-gap IT 在真实终止 writer 后固定：

```text
child = terminal + verified WorkerResult
parent = RUNNING
parent Artifact = 0
parent Trace HANDOFF = 0
fresh JVM reads = 2
fresh JVM product-truth snapshot unchanged = 2
```

snapshot 覆盖 V1–V6 的十张 product-truth 表与 Flyway history，使用完整 PK、稳定排序和
PostgreSQL `xmin`，不读取正文。它能证明两个采样点之间没有遗留的 durable
row-set/row-version 变化；不能证明没有 insert→delete、rollback、DDL、sequence、
advisory-lock 或任何瞬时 SQL activity，因此回执没有使用“零写入”措辞。

## Verification

可复制 commands：

```bash
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
./mvnw --batch-mode --no-transfer-progress clean verify
git diff --check
```

contracts Gate：

```text
6 JSON Schemas
54 fixtures
6 locally well-hashed Pack007 Bundle semantic negatives
1 Pack007 cross-run Handoff graph
23 locally valid relation negatives
2 Task hash golden vectors
1 WorkerResult hash golden vector
7 synthetic Task Packs
2 synthetic environment manifests
```

full Maven：

```text
Contracts               39
Core                   130
Agent Loop              41
OpenAI Adapter          19
In-memory               37
PostgreSQL              75
API                     34
Eval Runner             65
Offline Harness         83
                       ---
                       523 tests
```

`clean verify` 的 10 个 reactor modules 全部 `SUCCESS`；92 个 XML reports、
0 failure、0 error、0 skipped，总耗时 `02:02 min`。早期 full run 曾因一个旧 S3
packaged IT 仍把 migration current 写死为 `5` 而失败；更新为显式 V6 expectation 后
focused recovery IT Green。least-authority hardening 后的第一轮 full run 又捕获到两个
旧 verifier 负例仍试图先构造如今已被 protocol 禁止的伪 Handoff truth；测试改为在
`AgentRun` construction boundary 断言 fail-closed，并保留对“合法但无可信 child
observation”绑定的 pair-verifier 拒绝。后续 durable-admission 与 Trace-terminal
focused regressions，以及最终 full reactor 均为 Green。

## Independent review

本切片坚持单 writer + 多个 read-only reviewer：

- contract/runtime reviewer 检查 typed boundary、failure precedence、privacy 与
  composition；
- PostgreSQL reviewer 检查 migration、same-owner/terminal/hash、transaction、
  concurrency、recovery 与 snapshot；
- replay/docs reviewer 检查 Pack exactness、Java/Node parity、能力措辞、RFC/ADR、
  runbook 与 indexes。

审查发现并修复了 registry mismatch/observation precedence 文档矛盾、V6 moved-Handoff
OLD graph 漏检、RUNNING parent direct regression 缺失、parent Model Tool authority
过宽、非法 Worker parent 可先落 durable `RUNNING`、Handoff rejection mapping
可伪造、accepted Handoff 后 non-success Trace 可缺 terminal event、self-parent
跨层不一致、migration count 过时，以及把 PK/`xmin` snapshot 误称“零写入”的
evidence overclaim。最终 post-fix source/security review 结论在本增量收口时记录为
`P0=0、P1=0`。剩余 P2/hardening 不阻断本切片：parent process composition 仍物理
装配 `CaptureReadTool`，当前依靠 Task、Kernel 与 PostgreSQL 三层逻辑 authority
隔离；packaged API IT 没有直接重复断言 parent/child `requiredTools`，而是由底层
contract、runtime、store 与 Pack graph 共同覆盖；generic durable profile 的
binding validator 未单独固定“恰好一个 input”，production preparation 与 Pack
仍固定为一个 input，且不存在相对 parent 的 authority expansion。

## Failure and limits

- 当前只有 exact one child；没有 dynamic routing、parallel graph、fan-out/fan-in 或
  nested delegation。
- Worker execution 是 synchronous/cooperative；不能抢占 hung Model/Tool，也不是 hard
  deadline。
- child crash gap 不会自动恢复；没有 checkpoint、resume、lease、fencing 或 Temporal。
- read-only 由 exact server-owned Worker/Tool registry 和当前 implementation 共同保证，
  不是通用 effect system 或第三方 sandbox。
- Conductor Model 没有 direct Tool-backed Capture read authority；但 parent
  application service 仍持有 owner-scoped Capture store 做 preload/evidence read，
  当前不是进程级或 service-identity 级 capability isolation。
- public Pack/fixtures 只有 PUBLIC synthetic literal；local prototype route 只能读取同
  owner、非 `SENSITIVE/SECRET` Capture，但仍没有 production authentication 或 encryption。
- WorkerResult content 没有 public HTTP endpoint；当前 verified read 通过 Store、
  bindings 与 parent/child Run graph 完成。
- 没有读取 credential、访问外网、live provider、真实 Connector 或真实用户数据；
  不证明模型质量、Self Model 学习、用户价值、复用、报价、付款或收入。
- 学习计划保持暂停；工程证据更新不等于项目所有者已经掌握或完成 teach-back。

## Product, career and business receipts

- Product/Production：新增一条可解释、可重启核验的 Worker proposal vertical；没有新增
  外部副作用。
- Career：可用“为什么 Worker 不是 Tool”“为什么 child output 不是 Artifact”“为什么
  crash gap 不等于 resume”三个问题讲解 contract/runtime/durability 取舍。
- Business：无真实 Seed、用户、留存、报价、付款或收入证据；本 Build Note 不作商业声明。

## Principle learned

> 多 Agent 不是多个 prompt，而是多个可识别执行主体之间的 authority、context、
> budget、durable output、hash relation 与 recovery boundary。

## Next falsifiable hypothesis

下一步只替换一个变量：保持 Pack/authority/Tool/Verifier 与 durable truth 不变，把
scripted Fake Model 替换为已隔离的 real-model adapter，建立不读取真实用户数据的
bounded live-provider baseline。最小证据必须同时给出：

- operator 单次授权与 one-shot egress；
- provider request/result、usage、latency 与费用边界；
- same Task 的可重放 terminal Run/Trace/Bundle；
- model output 失败时不产生 Artifact；
- 明确区分 engineering receipt、model quality 与用户价值。

未得到项目所有者对真实 key/费用的单独批准前，只能完成 zero-egress preflight、
loopback protocol 与 baseline design，不能执行 live-provider call。
