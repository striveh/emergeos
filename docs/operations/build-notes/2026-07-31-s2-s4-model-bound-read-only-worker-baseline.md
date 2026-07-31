# Build Note：Pack008 child-only model-bound Worker baseline

- Change class：`R`（model authority / Agent Worker / durable verified read）
- Status：Engineering Green
- Task：Stage 2 S4/F4 · Pack008
- Date：2026-07-31
- Commit：待本增量独立提交，以 Git history 为准
- RFC：
  [RFC-0005](../../rfcs/0005-model-bound-read-only-worker-eval-baseline.md)
- ADR：
  [ADR-0009](../../architecture/decisions/0009-child-only-model-worker-eval-boundary.md)

## Outcome

Pack008 已把真实 model adapter 的 authority 从 root profile 下沉到 exact child
Worker，同时保持 parent 为无 Tool、无 provider route 的 deterministic Fake
Conductor：

```text
PUBLIC synthetic Capture
→ non-model-bound Task 1.0 parent
→ typed WorkerCall
→ exact model-bound Task 1.1 child
→ capture.read
→ terminal child + WorkerResult
→ verified parent HANDOFF
→ parent-only Artifact
```

shipping CLI 当前只增加：

```bash
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar \
  --worker-preflight
```

它是 zero-egress preflight，不是执行命令。Pack008 没有
`--worker-execute`；普通 API 仍只装配 Pack007 Fake Worker；没有读取 real key、
访问 external provider、产生 real model result 或费用回执。

## 为什么不是直接给 parent 换模型

parent 负责选择/验收 Worker 与提交最终 Artifact；child 才需要读取 Evidence 并调用
模型。若共用一个 model profile：

- parent 会获得不必要的 provider route；
- child usage/cost 无法独立归属；
- `requiredTools=[]` 的 parent authority 可能被 child `capture.read` 反向污染；
- historical Pack007 与新 generation 容易被“当前 profile”重新解释。

本切片增加 provider-neutral `ModelExecutionProfile`，让 root
`AgentExecutionProfile` 与 child `ModelBoundReadOnlyWorkerExecutionProfile` 都能被
adapter识别，但 runtime role、Task authority 与 durable identity 仍严格分开。

## parent budget 的真实语义

Pack008 non-model parent 的 `$0.417000` budget 是 fixed depth-one subtree
reservation，不是 parent 自己的 model budget：

- parent 不含 model provider/requested model、pricing profile 或 token upper bounds；
- child 才绑定 pricing/environment/model 并产生 usage/cost；
- parent Result 只聚合 verified child metering；
- parent Bundle 不出现 `model-adapter`，只保存 Worker profile、Conductor surface
  与 HANDOFF；child Bundle 保存 model component identity。

## Frozen identity

| 项目 | 冻结值 |
|---|---|
| Pack path | `evals/task-packs/synthetic/008-openai-read-only-worker-baseline.json` |
| Pack raw SHA-256 | `4803c227d88484dfe5796c286cb7b602242a54b452c39e5eb2be69f6a02bf1db` |
| environment raw SHA-256 | `440fe5ce81202d5083e33463849b19e310077c9906baab8d253c1f59fd7de968` |
| Capture request hash | `dd51522e6a5c2bfe2dd425bb3c6ad4a7e05bccce420e126040f14af01ce1ab00` |
| parent Task hash | `35465c2631b9616195beb85d5280c7debc26f80603ad8cf89bb5a722e4517470` |
| child Task hash | `ed8988df7c4c1aaabee266720ef43aa0124116751ab1ae6eef773772c70a2d04` |
| parent profile fingerprint | `b624df93b848c46b28be679dabdf463d8bca9fb9cdbc703a79c9965bb49f685c` |
| Worker profile fingerprint | `82a9081712a91b20ccfa39779d8e77637373a154d8d9eb2e1246f423f3b9ca82` |
| pricing fingerprint | `97484a33d9374dfe67b6f6e22260c4ff82750b285d0be2aaed36c2fdcf6a1b50` |
| prompt surface fingerprint | `1a7a8b31dd2e8e5d4e598b086692dfb97558a6861464b8f122a5047c6a64a9f4` |
| Conductor surface fingerprint | `7920292ff1f3605c152719e7c771f9420a18b0110329c837e55c0cc90c026fa6` |
| graph attempt ID v3 | `e3bfef65db2dbc1eeabf726e2162909ff52c92466ea14a4f9e65eb8270d17661` |
| requested model | `gpt-5.4-mini-2026-03-17` |
| maximum provider requests | `2` |
| full-run reservation | `$0.417000` |

attempt v3 material 显式覆盖 Pack/environment、Capture hash 与 identity、双 Run/Task、
Artifact、fixed start time、双 profile、pricing、prompt/Conductor surface、
reservation、request cap 与 Harness experiment。process-local permit 只有收到 exact
attempt ID 才能 arm；test graph `Spec` 在构造任何 Run 前做相同 exact binding。

## TDD evidence

### Acceptance Red

1. packaged process test 请求 `--worker-preflight`，基线 CLI 返回
   `ARGUMENTS_INVALID`，证明 route 不存在且已经到达 runnable jar。
2. graph permit Acceptance test 加载专用 `Pack008GraphExecutionPermit`，基线以
   `ClassNotFoundException` 失败。
3. post-review hardening 把 attempt identity 升级到 v3 后，catalog test 真实失败：
   expected 旧 attempt
   `805d40...a634cd`，actual 新 canonical attempt
   `e3bfef...d17661`；更新 frozen constant 后 Green。

### Minimum Green

- `ModelExecutionProfile` 解耦 model adapter 与 root role；
- exact Pack008 parent/Worker profile、Task 1.0/1.1 与 inherited-intent Conductor；
- strict Pack/environment loader 与 explicit `--worker-preflight`；
- process-local `PREPARED → ARMED → CONSUMED` graph permit；
- exact attempt arm、parent→child authorization order、child Run/principal consume；
- nested Worker authority composition-time rejection；
- expiry crossing、observer failure 与 concurrent second consume 都在 delegate 前烧掉
  one-shot；
- test-only production graph + loopback OpenAI protocol integration；
- multi-profile PostgreSQL verified read。

## Zero-egress evidence

Packaged `--worker-preflight` 使用 hostile environment 与临时 owner home：

- CLI 不接受 `--worker-execute`、Pack path、model、base URL、API key 或 mixed mode；
- preflight class没有 credential reader、client/model factory、Run store、marker、
  socket/provider invocation construction dependency；
- 指定 local HTTP sentinel 收到 `0` request；
- owner home 与 temp effects保持空；
- receipt 不输出 synthetic content、key 或任何 credential。

这里的“0”不是 system-wide socket instrumentation。它证明的是当前可达代码路径与指定
sentinel，没有把静态 receipt counter 冒充 OS packet capture。

## Loopback graph evidence

`ModelBoundWorkerEvalRunner` 位于 `src/test`。它通过 production
`AgentDraftService → AgentLoopKernel → DurableReadOnlyWorkerService` 执行：

- one parent Run；
- one child Run；
- two loopback Responses requests；
- one child `capture.read`；
- one WorkerResult；
- one parent Artifact；
- child observed cost `$0.000275`，parent exact 聚合同一 cost/token；
- malformed second response 时 child/parent 都 non-success，WorkerResult 与 Artifact
  都为 0。

测试使用 local `HttpServer` 与 sentinel key，不访问外部网络。

## PostgreSQL compatibility evidence

V6 无需 migration。真实 PostgreSQL Testcontainer regression 覆盖：

- Pack007 与 Pack008 完整 graph 同库共存；
- reverse profile registration order 后仍 exact verified read；
- Pack008 child terminal / parent `RUNNING` gap；
- gap 后由 fresh Store instance读取并完成 parent，再由另一 fresh Store验证；
- parent 不能冒充 child model profile；
- missing/ambiguous profile 在 root Run INSERT 前失败，`agent_runs=0`；
- terminal profile/experiment drift 让 parent、child 与 `findWorkerOwned` 三个 read
  surface 一致 fail closed；
- 10 张业务表 + Flyway history 的 PK/`xmin` snapshot 在 gap/full verified reads
  前后不变。

这里的 fresh Store 都是同一 JVM 的新 adapter object。Pack008 没有 real
process-kill 或 fresh-JVM evidence；不能借用 Pack007 packaged crash test扩大声明。

## Compatibility

- Pack007 Task Pack 保持 `8,443` bytes，raw SHA-256
  `808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c`；
- Pack007 Worker profile fingerprint保持
  `e5f705bcc49d3e0302ab0a159f18ad597df01199fc7c0594e375b40011d519ac`；
- Pack007 fixed-intent Conductor 与 replay hash不变；
- terminal reader只按 exact durable identity选择 profile，不使用“最新”或注册顺序；
- `RUNNING` truth 目前依赖 unique full-Task match。引入 Task-compatible profile
  revision 前必须新增 V7 durable selector（或等价设计）。

## Verification

当前已完成：

```text
Pack008 focused unit: Green
Packaged --worker-preflight process IT: 5 Green
PostgreSQL mixed-generation class: 18 Green
contracts: 6 schemas / 54 fixtures / 8 task packs / 2 environments Green
Pack007 strict replay: Green
```

冻结行为代码且不再并发编辑后的最终稳定快照：

- `./mvnw --batch-mode --no-transfer-progress clean verify`：10 个 reactor modules
  全部 `SUCCESS`，97 个 XML reports 共 570 tests，0 failure/error/skipped，
  总耗时 `01:36 min`；
- module counts：Contracts 39、Core 145、Agent Loop 41、OpenAI 20、
  In-memory 37、PostgreSQL 83、API 34、Eval Runner 88、Offline Harness 83；
- `./scripts/verify-contracts.sh`：6 Schemas、54 fixtures、6 个 Pack007 Bundle
  semantic negatives、1 个 Handoff graph / 23 个 relation negatives、2 个 Task
  golden vectors、1 个 Worker Result golden vector、8 Packs、2 environments；
- `./scripts/verify-doc-links.sh`：84 个 Markdown files；
- `git diff --check`：Green。

最终 Maven 在 exact metering 与 worker preflight effects 断言冻结后独立重跑，
不借用此前与 hardening 同时发生的构建。两路 post-fix 独立复审又分别核验
policy scope、mutation coverage、process effects 与 XML reports，Engineering Gate
已关闭。

## Independent review

已关闭的 reviewer findings：

- Pack007 rejection 测试误读 Pack003；
- environment 校验错误绑定 Pack003 profile；
- Conductor runtime intent 与 frozen child Task 不一致；
- parent actor 未绑定 decision surface；
- provider request cap 未三方绑定；
- terminal parent/child `findWorkerOwned` 未统一 pair verification；
- permit 未绑定完整 attempt/Run/Capture/Artifact identity；
- permit wrapper 隐藏 nested Worker authority；
- “零 socket”措辞超出 sentinel 证据；
- non-model parent 的 usage 只做 `>= child`，会接受 coherent cost/token inflation；
- worker preflight 文档声称 home/tmp empty，但 process IT 只断言 `.emergeos` 不存在。

README 已改为“无 credential/client/model/provider invocation construction path +
指定 local HTTP sentinel 0 request”，并明确不是 system-wide socket
instrumentation。exact metering P1 先以 runtime 与 PostgreSQL runnable Reds 证明旧
实现接受伪造，再只对 child-only model route 强制 parent cost/token exact-equal；
cost-only/token-only 分别覆盖 runtime sanitizer、direct terminal verifier、
PostgreSQL transaction rollback，以及 coherent SQL tamper 后 parent/child/
`findWorkerOwned` 三个 fresh Store verified-read surfaces。Pack007 的历史 subtree
aggregate `>=` 语义保持 Green。worker process IT 也新增 owner home 与 temp
directory empty 断言并通过。

最终两路 post-fix review 的阻断结论均为 `P0=0、P1=0`。一路为 `P2=0`；另一路
记录两个不阻断的 P2：

1. ExecPlan 对 sentinel 的证明范围过宽；已改为“已知可达 construction path
   未创建 provider invocation，指定 sentinel 为 0，且不是 system-wide
   instrumentation”；
2. `.workbuddy/` 是无关且未跟踪的本地评审笔记；提交必须使用精确 staging 排除它。

## Nonclaims

本切片不声明：

- Pack008 live-provider call、real key、real model result、real token/cost/billing receipt；
- Pack008 shipping execute route；
- durable graph operator challenge、marker、journal、terminal graph record；
- Pack008 fresh JVM、process-kill、power-loss、NFS 或 cross-host one-shot；
- ordinary product API、真实用户数据、per-user egress consent；
- generic multi-agent、parallel Worker、checkpoint/resume、lease/fencing；
- write-capable Worker、model quality、用户价值或 production readiness；
- model implementation 的 cryptographic attestation。adapter/composition仍属于 trusted
  TCB。

## Next

下一切片不是直接加 `--worker-execute`，而是先建立：

```text
frozen graph attempt manifest
→ owner TTY approval
→ create-only marker
→ hash-chain journal
→ terminal graph record
→ selected-boundary process-kill
→ fresh-JVM read-only verifier
```

全部 Green 后，仍需项目所有者对最多一次 bounded live smoke 单独授权。
