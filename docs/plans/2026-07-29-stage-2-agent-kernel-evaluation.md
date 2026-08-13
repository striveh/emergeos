# ExecPlan: Stage 2 AgentKernel and Eval-Driven Development

状态：进行中；S1、S2 工程完成，S3 protocol adapter、isolated synthetic Eval Runner、
本地 attempt durability 与 terminal record create-only repair 工程切片已通过；
live-provider PASS 尚未取得；曾有一次不可独立复现的bounded intermediate request在
`RESPONSE_METADATA_MISMATCH`处fail closed。S4 已完成
首个 deterministic Verifier comparison、canonical durable report、packaged
multi-writer/process-kill 与 fresh-JVM independent replay 工程切片，并完成首个
Tool arguments pre-dispatch fault及 read-only Tool post-dispatch deadline fault。
Pack007 的 typed read-only Worker contract/runtime、V6 PostgreSQL truth、strict
replay 与 packaged success/crash-gap evidence 已 Green。Pack008 已完成
child-only model profile、zero-egress Worker preflight、process-local exact attempt
permit、loopback graph、PostgreSQL multi-profile/fresh-store baseline 与 full Maven
verification command set；exact metering P1 已转成回归，两路 post-fix 独立复审
均确认 `P0=0、P1=0`，Engineering Gate 已关闭并独立提交。Pack009 又完成
PostgreSQL V7 canonical graph attempt、provider-accepted writer强杀、两个 fresh
verifier、least-authority + 完整 writer replay与 executable architecture Gate；
同一 loopback provider最终 request count为 `1`，billing truth保持 `UNKNOWN`。
Pack009 shipping execute/DB verify仍禁用，也没有 real TTY/key/provider result。
Pack010当前已推进到V20 test-only PostgreSQL immediate-restart overlay readback focused Gate：V16独立exact-pico
overlay head14不改写legacy head13；V16冻结时production verifier/API为0，V17新增未接线的
production-source verifier primitive，V18新增单一typed `complete` adapter，V19再新增专属13表SELECT-only
role与四态reader，在单一RR/RO snapshot独立重算V13-V16 canonical closure和Ed25519。public Java raw
stage/commit method、App consumer与shipping signer仍为0，configuration/runtime仍未证明，Authority/Live继续Red。
V20冻结时只以test-only独立keepalive Testcontainer证明同container/system identifier/PGDATA上的PostgreSQL
immediate process restart后，另一fresh packaged JVM可重验同一`Attributed` receipt；当时production delta为0。
其后Linux CI真实TTY启用所暴露的PostgreSQL microsecond portability缺口已在两个dormant production
consumer的7个durable sinks与3个TTY test-harness callsites精确canonicalize，raw expiry clock保持不变；
第二轮Linux CI又暴露test-only provider-session expiry fixture的host nanosecond输入，bridge现于事务前fail fast，
16个positive callers与默认synthetic binding显式对齐PostgreSQL `MICROS`，production exact equality/hash不变；最终root
回归发现的offline claim创建窗口也已确定性收口，真实unsafe metadata仍fail closed。App consumer与Live仍为0/Red。
PostgreSQL不验证Ed25519，credential/attestor caller与V19 reader role/process仍在TCB；TX-B/TX-C、host/
power/storage/HA fault、connection-loss/reconcile/race、live、billing与pre-egress均未关闭。完整live smoke、stochastic Harness、真实 Seed与
用户价值 Gate尚未完成。

Owner：项目所有者 + main Codex agent

进入条件：Stage 1 工程切片已完成，但 human-learning 与市场决策仍然开放。
2026-07-30，项目所有者明确暂停学习计划，并授权 Stage 2 技术主线在 Stage 1 总 Gate
尚未关闭时继续。这是 Roadmap 顺序例外，不会把缺失的人类、市场或
stale-`DISPATCHING` 证据改写成已完成 Gate。

## Five-outcome alignment

- AI Coding: frame one observable Agent behavior, establish the first Red,
  delegate read-only research, review the critical loop and diagnose a fault.
- Agent Engineering: implement and explain a framework-free model/tool loop,
  typed boundaries, budgets, Trace and repeated Harness evaluation before
  selecting a Runtime.
- Product/Production: turn one owned, durable Capture into one inspectable local
  Artifact without external side effects or model access to undeclared tools.
- Career: produce a runnable tool-loop demo, Trace, experiment report and
  failure-attribution Case Card.
- Business: use one consented private Seed in Concierge/dogfood, measure
  seed-to-draft and edit time, and keep real content out of the repository.

## Context and user result

在 S1 baseline，仓库只有稳定的 `TaskEnvelope`、`ResultEnvelope` 与
`HarnessRunBundle` contract，没有可执行 AgentKernel、model port、tool registry、
tool loop、Agent Trace 或 Eval runner。S1 增加 bounded Fake Agent product loop；S2
增加 durable AgentRun/Trace/Bundle truth 与 offline golden runner；S3 已加入隔离的
OpenAI Responses protocol adapter，并形成只接受 frozen PUBLIC synthetic Task 的独立
Eval Runner。S3 现在另有 production read-only journal verifier、hard-link create-only
terminal record 与 8-point fat-JAR process-kill/restart evidence。S4 Task Pack 004 已
冻结 reference-grounding Verifier
对照的输入和 expected matrix；独立 offline module 已以 fixed path、raw hash 与 exact
semantics 加载它，并完成 12 次 shared candidate generation、24 次
VerifierEvaluation、integrity-bound report、canonical durable bytes 与独立
deterministic replay。Packaged writer 使用 create-only hard-link logical commit；
两个 fresh JVM 可从相同 report bytes 再次得到 `VERIFIED_PASSED`，选定
process-kill windows 的 pre-link incomplete evidence 保持 `UNKNOWN`，committed
residue 保持 `FINAL`；另有冲突/不可信 authoritative evidence 固定为 `INVALID`。
Pack 005 又冻结了 schema-invalid Tool arguments 的第一组单变量 control/fault，
并通过 production Agent draft vertical path 证明 fault 在 Tool dispatch 前结束：
Fake Model step 和 `RUNNING → FAILED` truth 仍被如实记录，但 Tool execute、
Tool-backed Capture read 与 Artifact 都为 0。
当前仍没有 live-provider smoke、real model result、billing receipt 或 stochastic
Harness comparison。较早的
`TemplateArtifactGenerator` 仍是确定性的 Stage 0 scaffolding。

S2 已补齐 durable AgentRun、Safe Trace、HarnessRunBundle integrity binding 与
deterministic Offline golden runner；仍没有 real model、mid-run checkpoint/resume
或 stochastic Harness experiment。上段保留的是 S1 baseline，不代表当前能力上限。

The first user-visible result is:

> Given one owned PostgreSQL Capture, I can ask a local Agent draft endpoint to
> create an Article Artifact. A scripted Fake Model must obtain the Capture
> through the one allowed read tool, return a structured result linked to that
> evidence, and expose a safe execution summary. Invalid tools, malformed output
> or exhausted limits create no Artifact.

This replaces one variable only: deterministic generation with a bounded Fake
Agent loop. It does not yet claim model quality, personalization, durable Agent
checkpointing or production autonomy.

## Architecture and constraints

- `modules/contracts` remains the stable Task/Result/Bundle boundary.
- `modules/core` may own a narrow product `AgentKernel` port and Agent draft use
  case. It receives no vendor SDK, Spring, database or framework type.
- The first framework-free loop and scripted Fake Model stay in the existing
  outer in-memory adapter unless implementation evidence justifies a separate
  module. Do not create empty future modules.
- The model sees references and tool results, not unrestricted database rows or
  mutable chat history.
- A deterministic product service validates the structured final result and
  commits the Artifact. The model never writes PostgreSQL directly.
- Tool registration, Task allowlist and principal-scoped access are separate
  checks. A model request cannot expand any of them.
- Model adapter 只保留 bounded、immutable、redacted raw Tool arguments；具体 schema
  由 Tool 在无副作用 validation phase 解释，typed arguments 形成后才允许一次性
  dispatch。
- `agent-tools-v2` 是 exact manifest，不是标签：缺失、额外 Tool 或 schema drift 都
  fail fast；当前只绑定 `capture.read` argument schema v1。
- Trace contains typed decisions, tool names, statuses, usage and references;
  it never stores hidden chain-of-thought.
- S1 uses fixed model-step/tool-call limits, deadline checks and cooperative
  cancellation checked before and between steps. An asynchronous/persisted
  cancellation API is not claimed. Token/cost accounting begins with a real
  model adapter.
- No public contract change is allowed without an RFC. In particular, the
  observed `runId/resultRef` and Bundle consistency gaps are S2 decisions, not
  ad hoc S1 fields.
- Existing Artifact lineage and PostgreSQL truth remain canonical. No V4
  migration is planned for S1.

Non-goals for this stage:

- Temporal, real Connector, public publishing, OAuth or production secrets;
- streaming voice, dynamic UI or arbitrary model-generated executable UI;
- autonomous Self Model mutation;
- choosing AgentScope, Pi or another framework before a fixed baseline exists;
- parallel Agents sharing mutable Artifact writes;
- treating a Critic model as experiment truth.

## Slices and stage gate

### S1 · Framework-free Fake Agent Draft Loop

Observable result:

1. create an owned Capture through the existing API;
2. call `POST /api/v1/agent-drafts` with its reference and a bounded intent;
3. the Fake Model requests only `capture.read`;
4. the tool returns owner-scoped evidence;
5. the Fake Model returns a structured Article draft;
6. deterministic validation commits Artifact v1;
7. the response returns a `ResultEnvelope` plus an endpoint-local inline safe
   Trace summary. S1 leaves `traceRef` null rather than emit a dangling
   reference; S2 owns resolvable Trace/run binding.

First Acceptance Red: `AgentDraftHttpIT` reaches a running application with a
real PostgreSQL Capture and fails because the endpoint is absent. After Green it
must observe this order:

```text
MODEL_STEP
→ TOOL_REQUEST(capture.read)
→ TOOL_RESULT
→ MODEL_STEP
→ STRUCTURED_FINAL
→ ARTIFACT_COMMITTED
```

Fault cases:

- a spy Fake Model proves its initial request contains only Task/input/evidence
  references, not Capture content; the final Evidence provenance must originate
  in the `capture.read` tool result;
- unknown, unregistered or Task-undeclared tool → `BLOCKED`, zero Artifact;
- foreign and missing Capture → indistinguishable failure shape;
- malformed tool result or structured final → `FAILED`, zero Artifact;
- final output without the required Evidence reference → rejected;
- model/tool step limit or deadline exhausted → explainable non-success;
- cancellation before or between steps → `CANCELLED`, no later model/tool call
  and zero Artifact;
- Capture text that asks the model to ignore its allowlist → allowlist unchanged.

Allowed files: the minimum Core port/use case, existing in-memory adapter, thin
API wiring/tests, one synthetic Task Pack and S1 evidence documents. No
PostgreSQL migration, real model SDK, Runtime framework or external action.

Human checkpoint: without notes, draw the loop and personally change the Fake
Model to request a forbidden tool or omit Evidence. Predict the terminal status,
then locate it from the Trace.

#### S1 start delta · 2026-07-30

- Baseline: clean `main@1ff640e`; durable Capture and Artifact lineage exist,
  but `/api/v1/agent-drafts`, AgentKernel, model/tool loop and Agent Trace do not.
- First Acceptance Red:
  `apps/api/src/test/java/io/emergeos/api/AgentDraftHttpIT.java` starts the
  packaged jar against PostgreSQL, creates an owned synthetic Capture, then
  expects `POST /api/v1/agent-drafts` to return `201`, one ResultEnvelope,
  an inline safe event sequence and a persisted Artifact. Before implementation
  it must reach the healthy packaged app and fail only because the route returns
  `404`.
- Owned behavior: server constructs authority, tool allowlist and versioned
  policy from configuration; the client supplies only `captureId` and `intent`.
  The initial model turn receives references, the one allowed `capture.read`
  tool obtains content, and deterministic validation alone commits Artifact v1.
- File ownership: main thread is the only writer. S1 may change the minimum
  Core port/use case, existing in-memory adapter, thin API wiring/tests, one
  synthetic Task Pack and S1 evidence. No public Schema, V4 migration, real
  model SDK, Runtime framework, Temporal, Connector or external credential.
- Predicted failures: a decorative tool event hides preloaded raw content; model
  tool requests bypass the Task allowlist; malformed/evidence-free final output
  commits product truth; cancellation still permits a later step; Trace leaks
  raw Seed or hidden reasoning; the API accepts client-owned identity or tool
  authority.
- Receipt target: packaged Red/Green, focused loop/authorization/cancellation
  tests, Artifact GET, independent security/runtime review and full repository
  verification. The paused human learning exercise is not part of S1 closure.

### S2 · Trace and HarnessRunBundle binding

- Decide through an RFC whether v1 contracts need `runId`, result binding,
  stable Trace events or additional cross-field constraints.
- Replace S1's endpoint-local summary with a resolvable run/Trace reference only
  after its ownership, retention, redaction and integrity rules are defined.
- Add deterministic Java/JSON contract parity tests and Bundle consistency
  checks.
- Define canonical integrity hashing and create an offline runner that emits
  reproducible Fake runs.
- Promote S1 failures into regression Task Packs.

#### S2 start delta · 2026-07-30

- 基线是干净的 `main@d88ece3`。S1 已能让 Artifact 跨真实 JVM 重启存活，但
  `ResultEnvelope.traceRef` 仍为 `null`，也没有 durable AgentRun、Trace 查询、
  Bundle verifier 或 replay runner。
- [RFC-0001](../rfcs/0001-persistent-agent-run-trace-and-bundle.md) 冻结了一次
  unpublished-v1 协同修正：产品 `AgentRun` 是持久执行真相；
  `HarnessRunBundle` 是从 terminal Run 生成的自包含 Eval projection。
- 第一条 Acceptance Red 是 `AgentRunPersistenceHttpIT`：packaged JVM A 创建
  owner-scoped Capture 与 Agent draft，读取同一份 safe Run/Trace/Bundle，然后强制
  终止 A；JVM B 使用同一 PostgreSQL 启动后，必须取回相同的 integrity-bound
  aggregate。基线先因缺少 `runRef`、`traceRef` 和 Run route 而失败。
- Transaction invariant：server 在调用 AgentKernel 前提交 `RUNNING`；成功终态把
  Artifact v1、immutable Artifact binding、最后一个 safe Trace event、Result、
  Bundle 与 terminal Run 原子提交。Artifact insert 与 terminal update 之间的失败
  必须整体回滚，不能产生 ghost success。
- Privacy boundary：persistent Trace 只保存 typed、allowlisted metadata 和 hash，
  不保存 prompt、Capture/Artifact 内容、tool arguments/raw results、exception
  message、stack trace、reasoning 或 chain-of-thought。
- Replay boundary：S2 支持 verified read，以及在 fresh 隔离 store 中执行 frozen
  synthetic Task Pack。它不声称恢复 stale `RUNNING`、恢复 checkpoint、导出真实用户
  Evidence 或提供 production authenticity。
- Integrity boundary：`emergeos-length-prefixed-sha256-v1` 使用版本化 domain
  separation、确定性 UTF-8 length-prefixed encoding 和 Java/Node golden vector。
  它能检测 corruption 与未同步 tampering，但不是 signature。
- Work ownership：一个 writer 负责 contract、Core、PostgreSQL 与 API 的协同变更；
  read-only Agent 并发审查 migration、API/security 与 S3 provider Adapter。
- Receipt 目标：contract/hash Red/Green、V4 populated upgrade rehearsal、原子回滚、
  packaged forced restart、offline replay、独立 privacy/transaction review、全仓验证
  和中文 Build Note。

### S3 · One real model adapter and fixed baseline

- Verify exact provider/API behavior from installed versions and official
  documentation at implementation time.
- Add one adapter behind the same AgentKernel port; keep the scripted Fake.
- Fix model, Task Pack, tools, budgets and repetitions before running the
  baseline.
- Record resolved model version, tokens, cost, latency and failure attribution.
- Do not place credentials, prompts containing private data or live responses
  in the repository.

#### S3 adapter delta · 2026-07-30

- OpenAI Java SDK 固定为 `4.43.0`，位于独立 `adapters/openai`；Maven Enforcer
  禁止它依赖 API、Spring、Temporal、PostgreSQL、LangChain4j 或其他上层 Agent
  framework。普通 API 依旧只装配 Scripted Fake，不获得 OpenAI transitive dependency。
- adapter constructor 只接受 server-owned `AgentExecutionProfile` 与已经装配的
  `OpenAIClient`，不读取环境变量、不解析 key、不选择 base URL、不创建 live client。
  一个 Run 对应一个 Session，manual replay state、encrypted reasoning item 与 function
  call id 不跨 Run 共享。
- 第一轮 Responses 固定 `store=false`、`parallel_tool_calls=false`、
  `service_tier=default`、strict `capture_read` 和 forced function choice；第二轮继续
  replay reasoning/function/tool output，移除全部 tools，并要求 strict structured final。
  不使用 `previous_response_id` 或 conversation。
- 每个 socket 前先检查 cancellation 与单次 worst-case reservation；request timeout
  使用剩余 deadline。可信 usage 按 frozen PricingProfile 归因；若 output 语义错误但
  model/usage 可归因，返回 paid `Failed` receipt，不把费用丢进 exception。
- loopback Acceptance 已覆盖：两次请求 protocol、精确 cost、cancellation/budget 的
  zero socket、usage 缺失与不一致、reviewed token ceiling、401/4xx/429/5xx 映射、
  timeout/no retry、raw error 不进入 public message、foreign tool/evidence、invalid JSON
  与歧义 JSON、reasoning + message、safe model mismatch、跨 step model drift，以及两个
  交错 Session 的 replay 隔离。provider 已接受但结果未知时，同一个 Session 会 terminal，
  不允许再次 egress。
- 以上测试只访问 `127.0.0.1`，使用 sentinel key；没有真实 API key、外网请求或 live
  provider result。RFC-0002 已在 compiled synthetic catalog、one-shot operator permit、
  packaged zero-egress runner、safe receipt 与独立最终审查全部通过后转为 Accepted；
  bounded live smoke 仍须 owner 另行明确批准。

#### S3 adapter 验证回执 · 2026-07-30

- Acceptance Red 先因 `adapters/openai` 与实现类不存在而失败；protocol Happy Path Green
  后，审查驱动的 Red 依次暴露并修复：`403` 误分类、raw provider exception cause 泄漏、
  structured final 合法 reasoning item 被拒、safe model mismatch 丢 usage、invalid typed
  final 在 SDK parser 前丢 attribution，以及 timeout 后同 Session 可重复 egress。
- OpenAI adapter focused tests 为 `18/18`，provider-neutral Agent Loop session tests 为
  `8/8`；独立复审关闭为 `P0=0、P1=0、P2=0`。
- 最终 `./mvnw --batch-mode --no-transfer-progress clean verify` 共通过 `215` tests：
  contracts 26、Core 65、Agent Loop 8、OpenAI adapter 18、in-memory 21、PostgreSQL 45、
  API unit + packaged integration 32；failures/errors/skipped 均为 0。
- `./scripts/verify-contracts.sh` 通过 5 个 Schema、33 个 fixtures、2 个 Task hash vectors
  与 2 个 synthetic Task Packs；`./scripts/verify-doc-links.sh` 通过 68 个 Markdown files；
  `apps/api` dependency tree 不含 `com.openai:*`；`git diff --check` 通过。
- 这些回执仍全部是离线/loopback evidence，不是 live provider receipt，也不证明真实模型
  质量、账单金额或产品价值。
- 完整回执见
  [Stage 2 S3 Build Note](../operations/build-notes/2026-07-30-s2-s3-openai-responses-adapter.md)。

#### S3 bounded synthetic Eval Runner delta · 2026-07-30

- `apps/eval-runner` 是普通 `apps/api` 之外的独立 packaged 入口，只能读取
  checked-in、hash-frozen、`PUBLIC` 且 literal synthetic 的 Task Pack 003 与
  environment manifest；它不依赖 API、PostgreSQL、in-memory 产品 Adapter、Spring、
  Temporal、Self Model、Connector 或真实用户数据。
- 默认命令只执行 preflight，核验 Java release、pack/environment raw hash、
  Capture request hash、完整 Task hash、execution/pricing profile fingerprint、
  provider request 上限、deadline、token 上界与 whole-run reservation。默认路径不创建
  marker，不读取 credential，不构建 client/model，不启动 Run，也不发起网络请求。
- 显式 `--execute` 先要求 real TTY，再在当前 owner home 的私有 POSIX 目录内用
  `CREATE_NEW` 创建 exact attempt marker。Operator 必须输入完整
  `EXECUTE {attemptId}`；marker 在 challenge 前创建，因此错误 challenge 也会烧掉当前
  host 上这一次 attempt。
- 30 秒 `OneShotExecutionPermit` 同时绑定 server-owned execution profile 与完整
  Task hash；AgentKernel egress 前以 CAS 消费，过期、Task drift 或重复消费都在 socket
  前失败。只有 marker、challenge 与 permit 全部通过后才允许单次读取
  `OPENAI_API_KEY`。
- production client 固定 `https://api.openai.com/v1`、`Proxy.NO_PROXY`、
  `maxRetries(0)`、Run deadline timeout 与 `LogLevel.OFF`；CLI、环境变量或 Task 不能
  覆盖 model、pricing、base URL、tool、budget 或 key source。
- Attempt journal 在 credential read、client creation、每个 provider SDK create intent、
  attributed usage 与 terminal record publish 周围写入 append-only、hash-chained、
  `fsync` event。`PROVIDER_SDK_CREATE_INTENT` 是保守边界：它表示调用可能发生，不证明
  provider 接收、执行或计费。
- Terminal record 在同一私有目录先以 `CREATE_NEW` 写入 `.pending`、`fsync`、read-back
  校验，再用 `ATOMIC_MOVE` 发布 `{attemptId}.run.json` 并 `fsync` 目录。当前
  cooperative marker flow 会发布完整 synthetic AgentRun、Artifact、Bundle、observed
  usage/cost 与 effects counter；但 target 已存在时 move 是否替换是 provider-specific，
  record store 自身的 no-overwrite hardening 仍开放。它不与 provider 调用形成事务，
  也不替代 S2 PostgreSQL product `AgentRun`。
- Billing 使用三态：零 provider SDK create 为 `NOT_INVOKED`；每次 create 都有可信
  model/usage 为 `ATTRIBUTED`；至少一次 create、但 attribution 不完整为 `UNKNOWN`。
  `UNKNOWN + observedCostUsd=0` 只表示没有观测到费用，不能解释成免费。Reservation 是
  egress 前的 authorization ceiling；provider 已返回的 observed usage 即使超过
  reservation 或 requested budget，也必须保留并形成 paid failure，而不能截断、清零或
  丢进 exception。Billing 与后续对账必须读取 terminal record 顶层
  `observedCostUsd/observedTokenCount`；`runCostUsd/runTokenCount` 来自经过 product
  sanitizer 的 Run/Bundle，失败路径可以合法为 `0`。`meteringMatchesRun=false` 明确记录
  两者不同，Run usage 不能充当 invoice truth。
- 这个 one-shot Gate 只覆盖当前 POSIX host 与当前 owner home。它不防同 UID 恶意进程、
  owner/root 删除或重写文件、换主机重放，也不是 provider-side idempotency、签名、
  WORM、全账户 hard spend cap 或 invoice reconciliation。
- Runner 的 engineering-complete 状态只有在当前 focused/package/full verification、
  contract/doc checks 与独立 security/metering review 全部通过后才成立。即使成立，也仍
  没有读取 real key、访问 live provider、产生 real model result 或 billing receipt；
  owner 尚未批准或执行 bounded live smoke。
- 独立回执见
  [Bounded Synthetic Eval Runner Build Note](../operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)。

#### S3 durable attempt evidence delta · 2026-07-30

- `PosixAttemptJournalVerifier` 只读验证 marker、bounded journal、hash chain、事件状态机、
  billing prefix、pending/final record state，以及 terminal event 引用的完整 Run record；
  输出 `VERIFIED / UNKNOWN / INVALID`，不会把 incomplete snapshot 改写成 success。
- `trustedPrefix=true` 只认证本次读取到的完整 journal prefix；它不是 closed billing
  ledger，也不能把仍可能追加的 `UNKNOWN` attempt 解释成终止或免费。
- 一个 test-only crash harness 通过 `PropertiesLauncher` 加载 shipping fat JAR 与
  `target/test-classes`，分别在 Gate、credential read、provider intent、provider
  attribution、record pending、record final、terminal journal 这 7 个 durable phase
  阻塞；parent 强制终止 child 后，新 JVM 两次核验结果一致且不改写证据。
- 每个 crash case 的 replay 都被既有 marker 拒绝，loopback HTTP request count 不增加；
  shipping CLI 拒绝 crash injection 参数，crash harness class 不在 shipping fat JAR。
- exact commit `23e1773` 的 isolated clean verify 通过 Contracts 26、Core 65、
  Agent Loop 8、OpenAI 19、Eval Surefire 45、Failsafe 2，共 165 tests；7 个 crash
  cases 位于一个 Failsafe method 内，总耗时 `26.531 s`，0 failure/error/skipped。
- 独立审查为 `P0=0、P1=0`。保留的 P2 包括 pathname/owner check 的 TOCTOU、marker
  只作 locator、`trustedPrefix` 只作 snapshot、observer 只覆盖选定 phase，以及尚未逐个
  断言 production class CodeSource。没有物理断电、NFS、packet capture 或真实 provider
  证据。
- 完整回执见
  [Durable Eval Attempt Evidence Build Note](../operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)。

#### S3 run-record create-only repair delta · 2026-07-31

- code commit：`2b50c66`（`fix: make eval run record create-only`）。
- 系统结果：`apps/eval-runner` 的 terminal run record 自身形成 create-only logical
  commit；即使竞争者在 precheck 之后创建 target，writer 也固定返回
  `RUN_RECORD_ALREADY_EXISTS`，不能覆盖已有 evidence。
- Acceptance Red 同时固定两个失败：在 `RUN_RECORD_PENDING_DURABLE` 后创建
  owner-private competing target 时，旧 `ATOMIC_MOVE` 会在 macOS provider 上覆盖它；
  同 inode target+pending residue 也会被旧 verifier 误判为 `INVALID`。
- Green 顺序固定为 `CREATE_NEW pending → file fsync/read-back → directory fsync →
  createLink(target,pending) → directory fsync → RUN_RECORD_LINK_COMMIT_COMPLETE →
  cleanup → directory fsync → target revalidation`。hard link 不可用时
  `RUN_RECORD_LINK_COMMIT_UNSUPPORTED`，其他 I/O failure 保守失败；从不回退到 move。
- cleanup failure 只允许两个单调结果：pending 与 target 仍为同一 non-null identity，
  或 pending 已不存在；不同 inode 必须 fail closed。
- verifier 从不把 pending bytes 当 authoritative。pending-only 是
  `UNKNOWN / PENDING_NON_AUTHORITATIVE`；target-only 或同 inode
  target+pending 且无 terminal 是 `UNKNOWN / FINAL_UNSEALED`；不同 inode 即使 bytes
  相同也 `INVALID`；`VERIFIED` 仍要求 terminal journal 与 authoritative target
  完整绑定。
- test-only fat-JAR matrix 从历史 7 个窗口扩展为 8 个；新增窗口在 link commit 的
  directory `fsync` 完成后、pending cleanup 前强制终止。两个 fresh JVM 只读核验
  verdict 不变；evidence directory 的 fileKey、mode、owner，以及各 evidence file
  的 fileKey、mode、owner、size、mtime、SHA-256 snapshot 均不变；
  marker 继续阻断 replay，loopback HTTP count 不增加。
- focused suite 通过 39 tests；isolated clean process verification 通过 195 tests：
  Contracts 26、Core 79、Agent Loop 8、OpenAI 19、Eval Surefire 61、Failsafe 2，
  总耗时 `18.555 s`。两名独立 code reviewer 均为 `P0=0、P1=0`。
- 非目标：不把 process kill 冒充 power-loss/NFS durability；不解决 same-UID/root
  pathname 攻击、`dirfd/openat` anchoring、provider-side idempotency、invoice
  reconciliation 或 live-provider smoke。完整回执见
  [Eval Run Record Create-only Build Note](../operations/build-notes/2026-07-31-s2-s3-eval-run-record-create-only.md)。

### S4 · Harness comparison, faults and bounded handoff

- Compare a minimal H0 loop with one H1 Harness variable at a time.
- Inject tool schema error, timeout/rate limit, Context Drift and Prompt
  Injection.
- Add one typed, read-only Worker handoff only after the single-Agent baseline:
  no shared mutable Artifact, one Conductor writer；Pack007 最多一个 child Worker。
- Compare verified outcome, edit time, cost and latency across repeated runs.
- Evaluate AgentScope, Pi or another candidate only as a replaceable adapter;
  retain it only if the fixed Harness evidence justifies the added surface.

#### S4 O1 comparison foundation delta · 2026-07-30

- Task Pack 004 冻结第一项单变量对照：H0 `schema-only-eval-v1` 与 H1
  `agent-draft-verifier-v1` 的 reference-grounding discrimination。
- 4 个 deterministic Fake cases、2 个 arms、3 次 repetitions 定义了 24 runs /
  12 paired candidates；pack 中的 H0/H1 totals 是 expected matrix，不是 observed
  result。
- Repository validator 已固定 literal synthetic provenance、zero network/real
  model/Connector、arms/cases/repetitions 与 totals。
- commit `623abf3` 新增独立 `apps/offline-harness-runner` strict loader：固定 Pack
  relative path、64 KiB bounded `NOFOLLOW_LINKS` read、duplicate/unknown/trailing/
  missing/null JSON rejection、raw SHA 与完整 semantic binding；default-deny Maven
  dependency allowlist 及 contracts/core/offline production bytecode gate 阻止已知
  JDK network/process escape。
- Loader foundation 当时只完成可信 preflight；后续 execution/replay delta 见下一节。

#### S4 O1 execution and replay delta · 2026-07-30

- commit `693a3c7` 冻结 comparison-local canonical encoding、Unicode/safe-integer
  边界与独立 Node candidate micro-vector。
- commits `c1cb73c`、`da54365` 增加 offline production product-runtime bytecode
  denylist，并隔离证明 list-order mutation。
- commit `8fa96cd` 固定 authoritative Runner 组件：每个 case/repetition 只生成一个
  immutable Candidate，H0/H1 消费同一 object identity；H1 直接调用 production
  `AgentDraftReferenceGrounding` facade。
- 12 个 shared candidates 已生成，H0/H1 各评估 12 次。H0 接受 12 个，其中 9 个
  reference faults；H1 接受 3 个 grounded candidates，并以 3 个
  `MISSING_REQUIRED_EVIDENCE`、6 个 `INVALID_EVIDENCE_CLAIM` 拒绝 9 个 faults。
- 三种“已读取”mode 先调用 typed in-memory `LiteralSyntheticCaptureReader`，成功校验
  canonical synthetic Capture ref 后才产生 obtained refs 与 counter；这不是 product
  `capture.read` Tool。
- 独立 verifier 的 bytecode 不引用 Runner 或 generator；它重建 candidate、重跑 H0/H1、
  重算 matrix/order、IDs、nested hashes、Summary、OwnedEffects、Issues 与 status，
  得到 `VERIFIED_PASSED`。
- 完整 hash-chain re-sign attack、missing/extra/duplicate/reorder、outcome/summary/
  effects/status mutation 均被固定 failure code 拒绝；最终双独立复审
  `P0=0、P1=0`。
- 当前 report 只存在内存。Replay equivalence 不证明历史 Runner invocation、producer
  identity、系统级零副作用或 signature；durable report store 与跨新 JVM 验证是下一切片。

以上最后一条是 commit `8fa96cd` 时的历史边界；后续 durable delta 如下。

#### S4 O1 durable report delta · 2026-07-30

归档日期取切片开始日；code commit `a2cb02b` 实际完成于
`2026-07-31T00:04:29+08:00`。

- commit `a2cb02b` 固定最多 `1 MiB` 的 canonical UTF-8 JSON：exact fields/order、
  Unicode scalar、safe integer、duplicate/unknown/trailing/null rejection，以及
  decode 后 byte-for-byte canonical re-encode。
- owner-local state 固定在
  `${user.home}/.emergeos/offline-comparisons`。claim、pending、target 只允许固定名称；
  `0700/0600`、owner、regular file、symlink、size、可见 foreign `ALLOW` ACL 与
  read identity 共同 fail closed。
- writer 依次执行 `CREATE_NEW claim → directory fsync → CREATE_NEW pending →
  file fsync → read-back/decode → directory fsync → createLink(target,pending) →
  directory fsync → unlink pending → directory fsync → final read-back`。
- 第一版 `ATOMIC_MOVE` no-overwrite 假设被 adversarial Red 否决：Java/provider
  允许 atomic move 替换已存在 target。最终用 hard-link create-only 作为 logical
  commit；不支持 hard link 时固定拒绝，不降级为可覆盖 move。
- reader 从不 repair、unlink 或 rewrite。claim-only 或 claim+pending/no-target 是
  `UNKNOWN`；pending 无 claim 是 `INVALID`。target 与 pending 为同一 regular inode
  是 committed crash residue；不同 inode、unsafe path、unexpected entry 或 invalid
  authoritative target bytes 是 `INVALID`。安全且有界的 pending-only bytes 不做
  canonical decode，始终保持 non-authoritative。
- packaged `--execute` 先做 in-memory independent verify，再写 report 并 post-publish
  verify；`--verify` 只做 bounded read、canonical decode 和 independent replay，
  不引用 Runner/generator/writer，也不创建 state。
- 两个真实 packaged writer JVM 竞争同一空目录，最终恰好一个成功；两个 fresh read-only
  JVM 对相同 file snapshot 得到同一 verdict、IDs、hash 与 byte length。test-only crash
  harness 覆盖 7 个 durable phase，shipping JAR 不包含 crash injection surface。
- 固定 report 是 28,343 bytes，file SHA-256 为
  `b1152849fc2807d536d59e7a1412bfe4336df4ded51a74ec412bc94d840b12a7`，
  independent replay verdict 为 `VERIFIED_PASSED`。
- 边界仅是 tested local POSIX filesystem、frozen PUBLIC synthetic report 与
  cooperative writer。它不是 power-loss/NFS durability、hostile-local-user
  authorization、`dirfd/openat` path anchoring、signature、producer attestation、
  WORM 或 product `AgentRun/HarnessRunBundle`。

#### S4 F1 Tool arguments pre-dispatch fault delta · 2026-07-31

- commit `33d1b9f` 将 `AgentModel.ToolCall` 改为携带最多 65,536 UTF-8 bytes 的
  opaque `ToolArguments`；对象 defensive-copy、value-equality、`toString()` redacted，
  malformed/duplicate/unknown JSON 不再被 provider adapter 预先归一化。
- `AgentTool` 现在显式分成 pure `validate` 与 typed `execute`。
  `CaptureReadTool` 使用项目实际解析版本 Jackson 3.1.4，将该 Tool 的 argument 上限
  收紧到 1,024 bytes，并拒绝 duplicate keys、trailing tokens、unknown/missing/
  wrong-type 字段和非 canonical
  `capture://[A-Za-z0-9][A-Za-z0-9._~-]{0,199}` reference。
- `AgentToolRegistry` 的 `agent-tools-v2` exact manifest 固定
  `capture.read → urn:emergeos:tool:capture-read-arguments:v1`。Task registry version
  在打开 Model session 前核对；Tool name/schema/完整集合、Task allowlist 与 Task input
  reference 分层核验。只有全部通过才产生 one-shot `PreparedToolExecution`。
- 未注册或未被 Task 声明的 Tool 在 validation 前直接 `BLOCKED`；对 registered +
  declared call，registry 完成 validation 与 input-ref authority 检查后，Kernel 会在
  接受 rejection 或写入 `TOOL_REQUEST` 前再次检查 cancellation/deadline。该
  cooperative check 不是与同步 `execute` 原子化的强制中断。schema-invalid 路径形成
  `FAILED / TOOL_ARGUMENTS_INVALID` 和
  `MODEL_STEP → TOOL_REJECTED`；Trace 的 Tool name 只来自 declared Tool，
  reference 为 null，raw arguments、`unexpected` 字段和 synthetic sentinel 不进入
  Result、Trace 或 Bundle。
- Pack 005 的 control/fault 使用相同 Task、Capture、Fake Model path、预算、时钟和
  component versions，只增加一个 `unexpected` PUBLIC synthetic argument。control
  counters 是 Model 2、validation 1、Tool execute 1、Tool-backed read 1、Artifact 1；
  fault 是 Model 1、validation 1、Tool execute 0、Tool-backed read 0、Artifact 0。
  两个 case 分别在两个 fresh fixture 中得到 exact-equal terminal observation。
- 两个 case 的 Task hash 都是
  `aea6ef82d51c34b69e5ee81924b029bd460942502d2eee75fae99a9fdee3d3cf`。
  control Trace/Bundle hashes 为
  `2c69c3c5e347d0ad510ee45c62a06f3bc800178bccefeea91169c43310be1fde` /
  `6efc58076a2914f6e2eb3d28c0b66e31ef98f0257d9cea49c897ef4a6692c3c4`；
  fault 为
  `d5157c4273548d58596335485437bb472e70459b3203a277912461dd073126c9` /
  `1370f686ecd5cb3697b08f8774834d61ffbf327c8ea1dec02ece5210c09e7163`。
  Pack raw SHA 是
  `64b7cf77942e444ee871d766c4dbcd38fa45fa1cdf0d2bf6e96d87f7b1211ece`。
- active execution profile、Pack 002 与 Eval Runner 已迁移到 `agent-tools-v2`。
  `openai-responses-synthetic-v1.json` 保留原 bytes/raw SHA 作为历史 identity；
  v2 是 active environment，validator 固定两份 path/hash/version，并证明两者只改变
  `toolRegistryVersion`。冻结的 Pack 004 与 v1 contracts/golden/compatibility
  fixtures 不执行当前 `AgentToolRegistry`，继续保留历史 identity。
- “零副作用”只指 fault 后零 Tool dispatch、零 Tool-backed read、零 Artifact；
  不抹掉已发生的 Fake Model step、AgentDraftService preflight 或进程内
  `RUNNING → FAILED` terminal AgentRun/lifecycle truth。Pack 005 vertical 使用
  `RecordingRunStore`，没有新增 PostgreSQL、process restart 或跨 JVM durability
  receipt。该 vertical 本身没有网络访问；完整验证只使用本机 loopback HTTP 与本地
  PostgreSQL/Testcontainers TCP，没有访问外网、live provider、Connector 或真实用户
  数据，也不证明模型质量、账单、用户价值或任意第三方 Tool 安全。

#### S4 F2 post-dispatch read-only Tool deadline start · 2026-07-31

- 系统结果：`capture.read` 已 dispatch 后才观察到 deadline 越界时，系统保留真实
  dispatch/read 与 elapsed time，但不接受 late result、不再调用 Model、不提交
  Artifact，并让 Result、terminal AgentRun、Trace 与 Bundle 表达同一 truth。
- 起始风险：当时 Kernel 先写 `TOOL_RESULT / SUCCEEDED`，三层 aggregate 又以
  `latencyMs > deadlineMs` 拒绝真实 outcome，最终把 deadline attribution mask 成
  `UNSAFE_AGENT_OUTCOME`。
- 本次原理：deadline 是 success acceptance boundary；cooperative failure 的实际
  observed latency 可以超过 deadline，不能 clamp 或清零。
- Contract decision：
  [RFC-0003](../rfcs/0003-post-dispatch-read-only-tool-deadline-truth.md) 与
  [ADR-0007](../architecture/decisions/0007-observed-latency-and-post-dispatch-tool-deadline.md)
  已接受，冻结 `TOOL_REJECTED / DEADLINE_EXCEEDED`、typed failure、strict `>`、
  cancellation precedence 与 write-capable non-goal。
- 第一条 Red：相同 synthetic Task 下，Fake `capture.read` 在 execute 中实际完成一次
  read 并推进 frozen monotonic clock。旧路径应暴露 Model 1、validation 1、
  execute/read 1、Artifact 0，但错误 Trace/Result attribution。
- 完成条件：Pack 006 两个 fresh fixture exact-equal；三层 latency masking、Trace
  pairing、null/malformed/throwing late completion 与 success-over-deadline 均有
  adversarial test；focused/full/contracts/doc links 与独立 review Green。
- 非目标：异步抢占、线程 interrupt、write-capable Tool、`UNKNOWN` reconciliation、
  live provider、真实用户数据或外部动作。

#### S4 F2 post-dispatch deadline closure · 2026-07-31

- Acceptance Red 确认旧 Kernel 先接受 late `TOOL_RESULT`，随后三层 aggregate 把真实
  over-deadline failure 改写成 `UNSAFE_AGENT_OUTCOME`。
- `ObservedExecutionLimits` 现在成为 Result/AgentRun/Bundle/Trace 的 shared Java
  policy；Node semantic validator 镜像同一规则：
  success latency 必须 `<= deadline`，typed post-dispatch deadline failure 必须
  `FAILED && latency > deadline`。
- Kernel 在 Tool 返回或抛错后、接受任何 result 前重新观察 clock。late
  valid/throw/null/wrong-reference adversarial paths 全部形成一次 dispatch/execute、
  零 accepted Tool Result、零 Evidence/binding/Artifact、零后续 Model；Pack 中的
  valid fault 另以 production vertical 证明一次 Tool-backed Capture read。
- exact `5ms / 5ms` 不冒充 exceeded：合法 Tool Result/Evidence 保留，下一 boundary
  以 `DEADLINE_EXHAUSTED` 结束。late+cancellation 采用 deadline canonical
  attribution；cancellation-only control 保留 within-deadline result，再于下一 Model
  前 `CANCELLED`。该 boundary 在最后一个允许的 Model step 后也必须执行，不能被
  `MODEL_STEP_LIMIT_EXHAUSTED` 覆盖。
- Pack 006 的 4ms control 与 7ms fault 在两个 fresh fixture 中 exact-equal；
  PostgreSQL terminal round-trip 与 fresh store read 保留 FAILED、typed failure、
  actual 7ms latency、三步 Trace 和零 Artifact/Evidence。
- Eval Runner 的旧 `UNSAFE_AGENT_OUTCOME + zero usage` regression 已迁移：Model
  response 已被 provider attribution 后才耗尽 deadline 时，保留
  `DEADLINE_EXHAUSTED`、resolved Model、actual latency 与 run/observed usage，
  `meteringMatchesRun=true`。
- 完整命令、计数器、hash、验证总数、独立审查与 non-claims 见
  [Pack 006 Build Note](../operations/build-notes/2026-07-31-s2-s4-post-dispatch-deadline.md)。

Stage gate:

- another developer can replay the Fake and real-model experiments;
- every run binds Task, model, tools, policy, Trace, result and verifier;
- conclusions use repetitions and failure categories, not the best run;
- tool authority and product truth remain outside model control;
- a cancelled run stops before any later model/tool step and cannot commit an
  Artifact;
- the owner can diagnose one undisclosed loop fault and explain
  AgentKernel/Runtime/Harness/Workflow boundaries;
- a real Seed experiment shows whether the Agent draft reduces useful-output or
  editing time compared with the current Concierge/template baseline.

## Test and evaluation plan

- Contract: JSON fixtures plus Java parity/cross-field tests.
- Domain: structured-final evidence and Artifact commit invariants.
- Loop: scripted decisions, tool authorization, malformed values, limits and
  cancellation/deadline boundaries.
- Integration: HTTP → PostgreSQL Capture → AgentKernel → Artifact lineage.
- Fault: forbidden tool, poisoned tool result, timeout, Context Drift and
  Prompt Injection.
- Agent Eval: fixed Task Packs, resolved model, budgets and at least three
  repetitions per arm when stochastic behavior begins.
- Product: private Seed-to-useful-draft time and human edit time; no real user
  data enters Git.
- Full verification:

```bash
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
./mvnw --batch-mode --no-transfer-progress verify
```

## Delegation and ownership

| Role | Bounded task | Write scope | Expected return |
|---|---|---|---|
| Main | Requirements, architecture, first Red, integration, Diff and verification | Whole active slice | Verified Receipt |
| Architecture researcher | Installed APIs, source and official docs | Read-only | Versioned evidence and trade-offs |
| Test designer | Loop state table, fault and Eval cases | Read-only | Executable Red design |
| Worker | One accepted slice after its Red and ownership boundary exist | One isolated worktree | Focused Diff and targeted tests |
| Reviewer | Tool authority, data leakage, limits, Trace and test gaps | Read-only | Prioritized findings |

One working tree has one writer. A model/framework researcher cannot select the
production adapter; the main thread compares its evidence against the fixed
baseline.

## Progress

- [x] 2026-07-29: owner stopped a screenshot-evidence drill because it was not
  the next core Agent Runtime/Harness learning target.
- [x] 2026-07-29: two independent read-only audits confirmed that contracts and
  a synthetic Task Pack exist, while executable AgentKernel, model/tool loop,
  Trace and runner do not.
- [x] 2026-07-29: proposed S1 narrowed to one framework-free Fake Agent draft
  loop with no real model, framework, migration or external action.
- [x] 2026-07-29: independent plan review found and closed missing proof of
  ref-only initial model context, unowned cancellation semantics and a dangling
  S1 Trace-reference ambiguity; post-review `P0=0`, `P1=0`.
- [x] 2026-07-29: owner selected strict Gate path A—complete one owner-led
  undisclosed-fault diagnosis plus one real Seed/Concierge/price experiment
  before Stage 2 implementation.
- [x] 2026-07-30: owner superseded the sequencing part of path A, paused the
  learning plan and explicitly authorized product-system implementation as the
  primary lane. Stage 1 remains incomplete; its evidence is parked, not waived.
- [x] 2026-07-30: S1 delta, single-writer boundary and first packaged-process
  Acceptance Red target recorded before production code.
- [x] 2026-07-30: after correcting one test-only ambiguous `assertEquals(null,
  ...)` compilation error, the packaged-process Acceptance Red reached a healthy
  jar and PostgreSQL, created the owned Capture, then failed only at
  `/api/v1/agent-drafts`: expected `201`, actual `404`. Command:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=AgentDraftHttpIT -Dfailsafe.failIfNoSpecifiedTests=false`.
- [x] 2026-07-30: focused Red failed compilation because `AgentKernel`,
  `AgentDraftService` and their typed outcomes did not exist. The minimum Green
  introduced a provider-neutral Core port/use case and a framework-free outer
  loop without changing public schemas, PostgreSQL migrations or Maven
  dependencies.
- [x] 2026-07-30: focused Green passed 5 Core verifier tests and 13 loop tests:
  direct-final Evidence forgery and overclaim rejection, ref-only initial
  context, exact successful event order, registered-but-undeclared and
  declared-but-unregistered tool blocking, malicious references, malformed
  tool-result rejection, prompt-injection isolation, cancellation, deadline
  exhaustion and model/tool step limits. Command:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core,adapters/inmemory
  -am -Dtest=AgentDraftServiceTest,FrameworkFreeAgentKernelTest
  -Dsurefire.failIfNoSpecifiedTests=false test`.
- [x] 2026-07-30: packaged-process Green built the executable jar, started real
  PostgreSQL, created a Capture over HTTP, ran the Fake Agent and observed the
  six safe events in order. The test records the first JVM PID, asserts that it
  is alive, forcibly terminates it, asserts that it is dead, starts a different
  JVM PID against the same PostgreSQL container and reads Artifact v1 through
  the original `Location`. The same command as Acceptance Red now passes.
- [x] 2026-07-30: an additional PostgreSQL-backed API security test proved
  `X-Principal-Id` cannot replace server identity and JSON attempts to inject
  `principalId`, `requiredTools`, `model` or `budgetUsd` return `400`; its two
  cases also prove foreign/missing Capture equivalence and that one successful
  draft creates one Artifact and zero ActionAttempt/Receipt rows.
- [x] 2026-07-30: independent architecture/security and test-coverage review
  closed at `P0=0`, `P1=0`, `P2=0`. Full `./mvnw verify` passed 98 tests;
  contracts passed 4 Schemas, 8 fixtures and 1 Task Pack; all 63 Markdown links
  and `git diff --check` passed.
- [x] 2026-07-30: S2 began with three independent read-only contract/data,
  architecture/replay and test/threat audits. Their common P0 is the atomic
  Artifact + terminal AgentRun boundary; their common contract finding is that
  Run、Result、Trace、immutable resource binding 和 Bundle 必须形成同一个
  owner-scoped aggregate。
- [x] 2026-07-30: RFC-0001 accepted the durable AgentRun truth boundary,
  safe hashed Trace, embedded Result, immutable Artifact binding, V4 migration,
  exact owner-scoped query API and frozen synthetic replay semantics before
  production implementation.
- [x] 2026-07-30：packaged Acceptance 已转为 Green。JVM A 写入 terminal
  AgentRun/Trace/Bundle 后被真实强制终止；不同 PID 的 JVM B 使用同一 PostgreSQL
  读取出语义一致的三份 representation。
- [x] 2026-07-30：Task 的 `maxModelSteps/maxToolCalls`、有界 USD/整数/时长域、
  explicit nullable keys、Unicode scalar string 和 canonical ResourceBinding 顺序已
  同步到 Java、JSON Schema、Node verifier 与 PostgreSQL。
- [x] 2026-07-30：hostile Kernel outcome 会先经过字段 allowlist，再经过确定性的
  Trace state machine；孤立或错配的 Tool Result、超 Task model/tool limit、
  failure 后继续 success、超 budget/deadline，以及 success 但缺 resolved model
  都会被收敛为固定 terminal failure，不泄漏 metadata，也不遗留永久 `RUNNING`。
- [x] 2026-07-30：PostgreSQL focused evidence 已证明成功 Artifact 与 terminal Run
  原子回滚、FAILED + Evidence/no Artifact round-trip、双线程 terminal completion
  恰好一个赢家、Artifact head 到 v2 后历史 Run 仍绑定 exact v1，以及 forged
  Evidence hash 被 composite FK 拒绝。
- [x] 2026-07-30：Agent API 的 malformed JSON、unsupported media type、method not
  allowed、owner success、foreign/missing 和 owned corruption 都使用
  `Cache-Control: private, no-store`，且不返回 ETag。
- [x] 2026-07-30：`ContractText` 把 Java、JSON Schema 与 Node 的字符串域统一为
  有界 Unicode scalar、no NUL 和 frozen Unicode whitespace 语义；长度按 code
  point 计算。single-variable fixtures 固定 success-without-model、NUL、
  全空白和 typed Evidence binding 等反例，Task Pack 的长 Seed 不套用 2048 限制。
- [x] 2026-07-30：ghost success 已在 Bundle、AgentRun aggregate 和 PostgreSQL
  Store 三层封死：`CREATE_ARTICLE_DRAFT + SUCCEEDED` 必须有且只有一个 immutable
  Artifact binding 和 trailing `ARTIFACT_COMMITTED`，Store 必须收到同一 transaction
  的 proposed Artifact。
- [x] 2026-07-30：PostgreSQL numeric boundary test 真实 round-trip 共同最大合法
  cost/token/latency，并以 raw SQL 证明 7 位小数、超 JavaScript-safe token 和超
  24 小时 latency 被 DB constraint 拒绝。
- [x] 2026-07-30：S2 最终 Merge Gate 通过。稳定快照完成 full Maven verify、
  contracts、doc links、packaged forced-restart、两轮独立复审与中文
  [Build Note](../operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)；
  下一步自动进入 S3。
- [x] 2026-07-30：S3 OpenAI Responses adapter 的 loopback protocol/safety slice
  完成；它没有进入普通 API，也没有读取 real key 或访问 live provider。
- [x] 2026-07-30：isolated Eval Runner 的独立安全审查关闭为
  `P0=0、P1=0`。审查要求把 provider-observed metering 与经过 sanitizer 的 Run usage
  分栏保存，并将本地 POSIX、journal、atomic publish 与 billing-unknown 边界写入规格。
- [x] 2026-07-30：runner focused/package/full verification、contracts、doc links
  与 diff check 全部通过，engineering Gate 关闭。该 Gate 没有触发或冒充
  live-provider smoke。
- [x] 2026-07-30：production read-only journal verifier 完成 `VERIFIED / UNKNOWN /
  INVALID`、billing prefix 与 record-state 联合核验；invalid evidence fail closed，
  incomplete crash evidence 保持 `UNKNOWN`。
- [x] 2026-07-30：7-point fat-JAR process-kill/restart matrix 通过。新 JVM 两次只读
  核验不改写 evidence，marker 阻断 replay 且 loopback HTTP count 不增加；独立审查
  `P0=0、P1=0`。
- [x] 2026-07-31：commit `2b50c66` 关闭 eval run-record no-overwrite 缺口。旧
  `ATOMIC_MOVE` 被 adversarial Red 否决；hard-link create-only、cleanup 三分支、
  same/different identity 状态机与第 8 个 packaged-process kill window 全部 Green。
  isolated clean verify 通过 195 tests，两名独立 code reviewer 均为
  `P0=0、P1=0`。
- [x] 2026-07-30：Task Pack 004 与 repository validator 冻结首个
  reference-grounding Verifier comparison foundation；该时点尚未执行 comparison。
- [x] 2026-07-30：commit `623abf3` 增加独立 strict Pack loader。两项原始 P1 与三轮
  adversarial follow-up 已转为 executable Red/Green，独立最终复审
  `P0=0、P1=0`；该结果仍不等于 comparison 已执行。
- [x] 2026-07-30：commit `8fa96cd` 完成 fixed-component comparison Runner、
  integrity-bound report 与 independent replay verifier。12 次 shared generation /
  24 次 VerifierEvaluation 得到 `VERIFIED_PASSED`；完整 re-sign attack 仍被独立
  candidate oracle 拒绝。
- [x] 2026-07-30：single Candidate truth、identity audit、typed literal reader、
  private verdict construction、verifier outer/nested bytecode independence 与四种
  Node golden fingerprints 经两名独立 reviewer 复核为 `P0=0、P1=0`。
- [x] 2026-07-30：commit `a2cb02b` 将 28,343-byte canonical report 持久化为
  owner-local cooperative append-once evidence；packaged `--execute/--verify`、
  两个 fresh JVM、两个竞争 writer 与 7-point process-kill/restart matrix 均为 Green。
- [x] 2026-07-30：adversarial Red 依次否决可覆盖 target 的 `ATOMIC_MOVE`、ancestor
  symlink retarget、missing file identity、read 后 target replacement、pending cleanup
  误报，以及 pending/target 同删或同字节换 inode。最终三名独立 reviewer 均为
  `P0=0、P1=0`。
- [x] 2026-07-30：durable focused reactor 连续通过 188 tests；latest full reactor
  通过 362 tests，contract verifier 通过 5 Schemas / 33 fixtures / 2 golden vectors /
  4 Packs / 1 environment。完整回执见
  [Durable Offline Comparison Build Note](../operations/build-notes/2026-07-30-s2-s4-durable-offline-comparison-report.md)。
- [x] 2026-07-31：S4/F1 Acceptance Red 证明旧 Loop 会把 extra-property arguments
  送入 Tool；补充 canonical-ref 与 empty-registry adversarial Red 后，分别观察到
  expected `TOOL_EXECUTION_FAILED`/valid-tilde failure 与 v2 空 manifest 被接受。
- [x] 2026-07-31：commit `33d1b9f` 完成 opaque raw arguments、Tool-owned strict
  validation、typed one-shot dispatch、exact registry v2 manifest、safe typed failure
  与 Pack 005 vertical evidence。canonical ref、registry completeness、cancel/deadline
  precedence、duplicate JSON、privacy 与 frozen identity 的审查 P1 全部转为回归。
- [x] 2026-07-31：focused Core/Agent Loop/In-memory checks 通过 65 tests；
  contracts 通过 5 Schemas / 33 fixtures / 2 golden vectors / 5 Packs /
  2 environments；全仓 `clean verify` 通过 390 tests，0 failure/error/skipped。
  三路独立 final audit 对代码均为 `P0=0、P1=0`。

## Decisions

- 2026-07-29: learn and test the transparent loop before selecting AgentScope,
  Pi or another Harness. Framework selection becomes a measured adapter
  decision.
- 2026-07-29: prove one single-Agent tool loop before adding Subagents. The
  first handoff is read-only and has no shared mutable write.
- 2026-07-29: keep Artifact validation/commit in the Product Control Plane; a
  model final response is a proposal, not product truth.
- 2026-07-29: do not use screenshot-evidence details as Agent loop/Harness
  mastery evidence.
- 2026-07-29: keep Stage 2 production code frozen until the selected Stage 1
  learning and market checks have real evidence; planning is not Gate passage.
- 2026-07-30: supersede the freeze above for sequencing only. Begin Stage 2 S1
  under an explicit owner-authorized technical-lane exception while preserving
  every unfinished Stage 1 claim and keeping the learning exercise paused.
- 2026-07-30：产品 `AgentRun` 作为 durable execution truth；
  `HarnessRunBundle` 只作为 terminal Run 的 Eval projection。两者交叉绑定，但不互相
  冒充。
- 2026-07-30：S2 使用项目自有、版本化的
  `emergeos-length-prefixed-sha256-v1` 做 Java/Node golden parity；它提供
  corruption detection，不宣称 signature 或 authenticity。
- 2026-07-30：Trace 必须证明事件顺序、Tool request/result 配对、Task limit 和
  terminal sequence；“每个字段都在 allowlist”不是执行真实性。
- 2026-07-30：V4 是 additive migration，但除了三张新表，还为 Capture
  identity/request hash 添加 composite unique constraint，以便 Evidence binding
  由 owner-scoped FK 约束。
- 2026-07-30：S3 的 one-shot permit 是当前 POSIX host/owner home 内的操作防误触
  边界，不是跨主机 exactly-once 或 provider idempotency；outcome-unknown 不自动 retry。
- 2026-07-30：reservation 是调用前 authorization ceiling，不是 observed metering 的
  截断上限。Billing/对账读取 terminal record 顶层
  `observedCostUsd/observedTokenCount`；Run/Bundle usage 只表达经过 product sanitizer
  的 execution projection。
- 2026-07-30：durable attempt verification 必须同时解释 `Verdict`、billing prefix 与
  record state；`trustedPrefix=true` 只描述当前 snapshot，不能关闭一个
  `UNKNOWN` attempt。
- 2026-07-30：S4 第一项 Harness 对照只改变 reference-grounding Verifier。H0 只能存在于
  isolated Eval path；冻结 expected matrix 不等于执行过 experiment。
- 2026-07-30：S4 输出统一称为 12 次 shared candidate generations 与 24 次
  VerifierEvaluations，不把 Pack legacy `runs` 字段包装成 product `AgentRun` 或
  `HarnessRunBundle`。
- 2026-07-30：`VERIFIED` 只表示固定 Pack 上 deterministic replay-equivalent；unkeyed
  hash 提供 corruption/tamper detection，不提供历史执行 attestation、signature 或
  producer authentication。
- 2026-07-30：durable comparison 的 pending 永不 authoritative；claim-only 或
  claim+pending/no-target 是 `UNKNOWN`，pending 无 claim 是 `INVALID`；
  target+pending 同一 regular inode 是 committed crash residue，不同 inode 或冲突
  路径是 `INVALID`。logical commit 使用 hard-link create-only，不把
  `ATOMIC_MOVE` 冒充 no-replace。
- 2026-07-30：local POSIX 检查统一解释为 owner/mode-restricted cooperative boundary。
  只有 filesystem provider 暴露 ACL 时才能声称拒绝 visible foreign `ALLOW` ACL；
  不可检查不等于已验证，也不构成 hostile-local-user authorization。
- 2026-07-31：eval terminal record 的 logical commit 使用
  `createLink(target,pending)`，不再用 `ATOMIC_MOVE` 充当 no-replace。目录 `fsync`
  完成后才暴露 link-complete process phase；pending cleanup residue 只按 non-null
  file identity 解释，pending bytes 永不 authoritative。
- 2026-07-31：Model adapter 不拥有具体 Tool schema。它必须把 bounded raw arguments
  原样交给 Tool validation，避免 provider-specific normalization 隐藏 duplicate/
  unknown/malformed 输入；Tool execute 只接受 typed validated arguments。
- 2026-07-31：`toolRegistryVersion` 必须对应 exact server-owned manifest。当前
  `agent-tools-v2` 只绑定 `capture.read` schema v1；缺失 Tool 的 runtime 不能继续自称
  v2。第三方 Tool implementation 仍是 Trusted TCB，不能仅凭 name/schema 获得信任。

## Surprises and failures

- S2 开始时 public contracts 的字段多于 executable system，且
  `HarnessRunBundle` 没有直接绑定 Result/Run。RFC-0001 与 unpublished-v1 协同修正
  已把它们变成可执行、可 golden-test 的 contract；这仍不是完整 Harness 实验。
- The Stage 1 plan blocks automatic Stage 2 expansion while human and market
  gates remain undecided. The 2026-07-30 owner decision is an explicit
  exception, not an automatic interpretation of engineering completion.
- The first Green compile exposed an omitted `TaskEnvelope.intent` argument.
  Fixing the explicit constructor call kept the Task server-owned and showed why
  the stable envelope must be exercised by executable code, not treated as
  decorative documentation.
- Spring Framework 7.0.8 deprecates `ResponseEntity.unprocessableEntity()` in
  favor of `unprocessableContent()`; the implementation follows the repository's
  installed API rather than stale examples.
- The first post-implementation review found four P1 boundaries that the happy
  path did not expose: claimed Evidence was not yet proof of tool execution,
  untrusted tool fields could enter Trace, a final could arrive after
  cancellation/deadline, and invalid model content could be misclassified as
  client input. Focused adversarial tests now hold all four fixes.
- A second review found that cross-JVM persistence behavior was proven but the
  process-death evidence did not explicitly assert liveness, death and distinct
  PIDs. Tightening those assertions closed the final P2 without changing
  production code.
- S2 第一版只验证每个 Trace event 的字段 allowlist；独立审查证明孤立
  `TOOL_RESULT`、超 Task limit 和 failure 后继续 success 仍可能伪造执行证据。修复后
  同一 state machine 同时守住 adapter outcome 与 durable aggregate。
- `SUCCEEDED + artifactRefs=[]` 曾因空列表彼此相等而形成 ghost success。对当前
  `CREATE_ARTICLE_DRAFT` 明确要求 exactly one Artifact，比全局禁止“无 Artifact 的
  success”更能兼容未来 read-only Agent。
- SafeText parity 暴露了三个容易忽略的跨语言差异：Java UTF-16 `.length()` 与
  JSON Schema code-point 计数不同；不同 runtime 的 `isBlank/trim` 不同；ECMAScript
  lookahead 中 `.` 默认不跨换行。v1 因此冻结 whitespace code points，并用
  `[\s\S]` 保持多行中文合法。
- typed ResourceBinding 不能只靠 Java constructor。Schema、Node semantic
  verifier 与 PostgreSQL typed shape 必须共同约束 `capture://...` 和
  `artifact-version://...`。
- S3 security review 证明“Run 中 usage 为 0”不等于“provider 没有 usage”。失败结果
  可能被 sanitizer 合法归零，而 adapter 已在失败前观察到 model/usage。Terminal
  record 因此同时保存 `observedCostUsd/observedTokenCount` 与
  `runCostUsd/runTokenCount`，并用 `meteringMatchesRun=false` 显式暴露差异。
- Provider SDK create intent 与 provider acceptance 之间没有原子边界。进程在其间死亡
  时不能证明是否计费；journal 必须保留保守证据，后续状态为 `UNKNOWN`，不能当成免费。
- 当前 eval-runner Process-kill matrix 使用 observer 在 8 个选定 operation 后停住进程，因此能
  精确验证这些 boundary，却不是 arbitrary-instruction 或物理断电测试。Path 安全检查仍有
  same-UID TOCTOU；marker body 不参与验证；test launcher 也尚未逐个检查 production
  class 的 CodeSource。
- Task Pack 004 的 24-run totals 是执行前冻结的 legacy expected matrix。Strict loader
  Green 当时只证明输入可信；后续才通过 fixed Runner 与 independent verifier 得到
  24 次 VerifierEvaluation 的 observed result。
- 第一版 Runner 同时接受独立 snapshot 与 production Candidate，hash 可能绑定 A、
  H0/H1 实际消费 B；同一入口还可注入未绑定 generator/observer。独立 review 将两项
  认定为 P0。最终结构只保留一个 Candidate truth、固定 authoritative components，并
  用 object identity audit 封住每对两臂消费。
- 第一版 fixture read 只是手动加 counter，不能支持“已读取”的说法。最终加入 typed
  literal reader：canonical ref 校验成功、返回 literal content 后才记录 read；回执仍
  明确它不是 product Tool 或历史执行 attestation。
- Durable report 第一版把 `ATOMIC_MOVE` 当作 atomic no-replace。一个在 precheck
  之后创建竞争 target 的 Red 证明 macOS provider 会替换该 target；最终改为
  `createLink(target,pending)` 的 create-only commit，并对不支持 hard link 的
  filesystem fail closed。
- hard-link commit 又暴露两组 reader race：正常 writer unlink pending 曾被误判为冲突；
  放宽为 `SAME_FILE → ABSENT` 后，pending 与 target 同删或 target 同字节换 inode又可能
  隐藏变化。最终 read receipt 绑定 non-null
  fileKey/size/mtime/creationTime，decode 与 pending transition 后无条件重验 target。
- macOS JDK 不暴露 `AclFileAttributeView`，所以原先“拒绝 foreign ACL”的宽泛表述不成立。
  实现与文档已收窄为“provider 可见时拒绝 foreign `ALLOW` ACL”；POSIX mode 只是
  cooperative boundary，不是 hostile-local-user security claim。
- eval-runner 复用 S4 durable-report 的 hard-link 思路时，不能直接复制 reader
  状态机：这里没有 claim file，并且 terminal journal 是闭合条件。新增 Red 固定了
  pending-only 永不 authoritative、同 inode residue 可以前进、不同 inode不能因
  bytes 相同或随后消失而升级。
- OpenAI adapter 原先提前把 function arguments 解析成 reference，并要求它等于
  Task input。这样虽然能拒绝异常，却会把 Tool schema 藏在 provider adapter 中，
  duplicate/unknown/malformed shape 也无法到达统一的 Tool validation 边界。现在
  adapter 只保留 bounded raw arguments，schema authority 回到 Tool。
- `toolRegistryVersion` 原先只是字符串标签；一个空 registry 也能自称
  `agent-tools-v2`。新增 Red 证明版本必须对应 exact manifest，并保留 v1 environment
  原 bytes 作为历史 identity，避免为修当前 runtime 改写旧执行环境。
- `capture.read` 第一版 validation regex 与 durable ResourceBinding contract 漂移：
  接受前导标点/colon，却拒绝合法 `~`。canonical-ref adversarial Red 将两层语法统一。

## Verification receipts

S1 的历史回执保留在对应 Build Note，不再用它冒充 S2 结果。

S2 当前已确认：

- Acceptance Red/Green、atomic rollback、FAILED + Evidence、并发 terminal CAS、
  exact Artifact v1 after v2、forged Evidence FK、owner/security/cache 与 offline
  replay 的 focused checks 均已记录在 Progress；
- contract verifier 当前通过 5 个 Schema、25 个 fixture 和 2 个 synthetic Task
  Pack；
- `PostgresAgentRunStoreTest` 12 个案例通过，包含共同数值上界 round-trip 与 raw
  invalid write rejection；
- `./mvnw test`、`verify-contracts.sh` 与 `git diff --check` 已通过；
- final `clean verify` 共 147 tests（Contracts 16、Core 43、In-memory 21、
  PostgreSQL 35、API 32），0 failure/error/skipped；packaged forced-restart 包含在
  其中；
- doc links 通过 66 个 Markdown file，`git diff --check` 通过；
- transaction/replay 独立复审为 `P0=0`、`P1=0`；最终 Merge Audit 为
  `P0=0`、`P1=0`、`P2=1`，P2 是已记录的非阻塞 exception layering debt；
- 完整证据与 non-claims 见
  [S2 Build Note](../operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)。

S3 bounded runner 当前状态：

- 实现、frozen assets、one-shot POSIX Gate、attempt journal、hard-link create-only
  local run record 与 loopback fault tests 已形成；read-only journal verifier 与 8-point
  process-kill/restart matrix 也已形成；
- create-only code commit `2b50c66` 的 focused suite 通过 39 tests；isolated clean
  process verification 通过 195 tests（Contracts 26、Core 79、Agent Loop 8、
  OpenAI 19、Eval Surefire 61、Failsafe 2），总耗时 `18.555 s`；
- latest full clean reactor 通过 378 tests：Contracts 26、Core 79、Agent Loop 8、
  OpenAI 19、In-memory 21、PostgreSQL 45、API 32、Eval Runner 65、Offline
  Harness 83；contract verifier 通过 5 Schemas / 33 fixtures / 2 golden vectors /
  4 Packs / 1 environment，doc link verifier 通过 74 个 Markdown files；
- 两名独立 code reviewer 均为 `P0=0、P1=0`；
- exact commit `e90c704` 的隔离 full `clean verify` 共通过 `246` tests；focused
  Eval reactor、contracts、doc links 与 diff check 同时为 Green；
- exact commit `23e1773` 的 isolated `clean verify` 通过 165 tests：Contracts 26、
  Core 65、Agent Loop 8、OpenAI 19、Eval Surefire 45、Failsafe 2；7 个 crash cases
  全部通过，总耗时 `26.531 s`；
- live-provider smoke 未执行；没有 real key read、real provider result、real token
  receipt 或 billing receipt；
- 完整边界与验证结果见
  [S3 Runner Build Note](../operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)
  与
  [S3 Durable Attempt Build Note](../operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)，
  后续修复见
  [Eval Run Record Create-only Build Note](../operations/build-notes/2026-07-31-s2-s3-eval-run-record-create-only.md)。

S4 的 in-memory 历史快照完成 Task Pack 004、repository validator、strict Pack loader、
fixed comparison Runner 与 independent report verifier。2 arms × 4 cases ×
3 repetitions 已实际形成 12 次 shared candidate generations / 24 次
VerifierEvaluations，并由独立重放得到 `VERIFIED_PASSED`。该阶段 focused clean
reactor 为 159 tests、full reactor 为 333 tests；证据见
[S4 Verified Offline Comparison Build Note](../operations/build-notes/2026-07-30-s2-s4-verified-offline-comparison.md)。

commit `a2cb02b` 在此基础上完成 canonical durable report：

- focused clean 及两次重复 reactor 各通过 188 tests；
- latest full Maven reactor 通过 362 tests：Contracts 26、Core 79、Agent Loop 8、
  OpenAI 19、In-memory 21、PostgreSQL 45、API 32、Eval Runner 49、
  Offline Harness 83；
- contract verifier 通过 5 Schemas / 33 fixtures / 2 golden vectors /
  4 Packs / 1 environment；
- doc link verifier 排除 local-only `.workbuddy` 后通过 73 个 Markdown files，
  `git diff --check` Green；
- packaged app JAR 的 Main-Class、test-only crash isolation、production
  CodeSource/test-class shadowing 与 dependency license/NOTICE checks 为 Green；
- 真实双 writer、两个 fresh read-only JVM 与 7-point process-kill matrix 为 Green；
- 三名独立最终 source reviewer 均为 `P0=0、P1=0`。

完整机制、hash、状态表、commands 与 non-claims 见
[Durable Offline Comparison Build Note](../operations/build-notes/2026-07-30-s2-s4-durable-offline-comparison-report.md)。
该 report 仍不是 historical process attestation、signature、power-loss evidence 或
产品/商业结果。

S4/F1 Tool arguments fault 当前已确认：

- code commit `33d1b9f`；
- Pack 005 raw SHA、Task/Trace/Bundle hashes、control/fault counters、ID 消耗与
  fresh-fixture equality 均被 Java vertical test 和 repository validator 固定；
- focused Core / Agent Loop / In-memory suite 通过 65 tests；Tool/OpenAI/Trace
  扩展 focused evidence 共 77 tests；
- `./scripts/verify-contracts.sh` 通过 5 Schemas、33 fixtures、2 golden vectors、
  5 Packs 与 2 environments；strict parser 自检包含 1 个 valid control 与
  7 类 negative regressions；
- full `./mvnw --batch-mode --no-transfer-progress clean verify` 通过
  390 tests、73 个 XML report files，0 failure/error/skipped；10 个 reactor modules
  全部 `SUCCESS`，总耗时 `01:19 min`；
- doc-link verifier 通过 75 个 Markdown files，`git diff --check` Green；
- 三路独立 final audit 对代码均为 `P0=0、P1=0`。保留的 P2 是
  invalid+over-limit precedence、第三方 Tool implementation identity/Trusted TCB，
  以及后续 fault 扩展的通用 Trace provenance；
- 没有读取 credential、访问外网、live provider 或 Connector；完整验证只使用本机
  loopback HTTP 与本地 PostgreSQL/Testcontainers TCP。完整证据与边界见
  [Tool Arguments Fault Build Note](../operations/build-notes/2026-07-31-s2-s4-tool-argument-fault.md)。

## AI Coding receipt

本切片继续使用单 writer + 多个 read-only reviewer。流程是 packaged Acceptance
Red → focused Red → minimum Green → real-process Green → adversarial review →
regression Green。审查不是“看代码觉得可以”，而是持续提交可复现 P1：ghost
success、Trace 配对、SafeText parity、typed binding 和 DB numeric evidence，再由
主线逐项转成测试与不变量。
S4/F1 又把 reviewer 找到的 cancel/deadline precedence、canonical ref、exact
manifest、duplicate JSON、raw-byte hash 和 privacy leakage 逐项变成 Red/Green；
主线程保持唯一 writer，三名 subagent 只做边界化只读审查。

## Agent Engineering receipt

当前机制已经从 inline summary 进化为 durable Agent execution truth：server-owned
Task、adapter outcome、Trace state machine、owner-scoped Evidence、structured
proposal、deterministic verification、atomic Artifact commit、Result、safe
hash-chain Trace 与 HarnessRunBundle 可以跨 JVM verified read。S3 已形成 real-model
protocol adapter 与受控 synthetic execution path，但尚无 live result；仍没有 durable
checkpoint/resume 或 stochastic Harness experiment。
S4/F1 新增统一 Tool boundary：Model 决策携带 opaque raw arguments，Tool 负责 pure
validation，registry 负责版本化 schema/Task authority，execute 只接收 typed
arguments。它证明了“模型已经调用”与“Tool 已 dispatch”必须分开记账。

## Career receipt

仓库现在能展示一个生产级面试故事：为什么“模型声称读过”不是 Evidence、为什么
allowlist 不是 Trace state machine、怎样防止 ghost success，以及怎样用 PostgreSQL
transaction/FK/CAS 与跨语言 golden hash 形成可审计 Run。owner-led diagnosis、
脱稿讲解和 framework trade-off defense 随暂停的学习计划继续延期，不虚报已掌握。
新增案例可以解释为什么 Tool schema 不应散落在 provider adapter、为什么 registry
version 必须是 exact manifest，以及为什么“零 Tool side effect”不能抹掉已发生的
Model usage 与 Run truth。

## Business receipt

本切片没有新增访谈、真实 Seed、复用、报价、付款或收入证据；这些 Stage 1 商业事实
仍然开放。

## Outcome and next hypothesis

S2 的工程假设已在 focused evidence 中成立：不引入 Runtime framework、real model
或外部动作，也能形成持久、完整性绑定、可离线重执行的 AgentRun truth。S3 adapter、
bounded runner 与本地 durability engineering slice 已通过。S4 的第一项 deterministic
Verifier comparison 与 canonical durable report 已独立重放验证：hard-link
create-only commit、真实 process-kill 与 fresh JVM 能保持同一 verdict，incomplete
pre-link evidence 保持 `UNKNOWN`，committed residue 保持 `FINAL`；冲突或不可信
authoritative evidence 才是 `INVALID`。
S4/F1 也已验证 schema-invalid raw Tool arguments 在 dispatch 前以 typed failure
结束，且不会产生 Tool-backed read 或 Artifact。
S4/F2 已进一步验证 read-only Tool 在 dispatch 后严格越过 deadline 时，实际
dispatch/read、Model attribution、usage 和 latency 被保留，但 late result 不进入
Evidence/Artifact；exact boundary 与 cancellation precedence 也已冻结。

本次已执行并收口的最小可证伪假设是 Pack 007：
**typed read-only Worker handoff + child context-policy drift fail-closed**。它直接推进
One Self, Many Workers，而不是把 dormant `parentId/delegationChain`、`handoffRefs`
和 `HANDOFF` binding 继续只留在 schema 中。

Pack 007 先以 RFC-0004 冻结：

- 一个 server-owned Conductor 只委派一个
  `PROPOSE_ARTICLE_DRAFT` read-only Worker，depth=1、禁止 parallel；
- parent Task 的 `requiredTools` 固定为空，只有 child Task 获得
  `requiredTools=[capture.read]`；Tool authority 是 registry 与 Task allowlist 的交集；
- Worker 与 parent 同 principal，并 exact inherit policy/state/context/tool
  registry/environment；capability、risk、budget、deadline 与 limits 只能缩小；
- Worker 可通过 `capture.read` 产生 typed proposal/Evidence，但不能提交
  Artifact、Receipt、Action 或二次 handoff；
- parent 只消费 terminal、verified、hash-bound child Run，并保持唯一 Artifact
  writer；
- child `contextPolicyVersion` drift 必须在 child Run、Model 与 Tool dispatch 前
  `BLOCKED / HANDOFF_CONTEXT_POLICY_DRIFT`；
- parent Bundle 使用 typed `agent-run://{childRunId}` 与 child Bundle hash 绑定；
  PostgreSQL/fresh JVM 必须验证 parent→child ownership、terminal truth、
  delegation、authority inheritance 和 child no-write。

Acceptance Red 先证明当前系统只能把 handoff 冒充未注册 Tool，parent 会
`BLOCKED / TOOL_NOT_ALLOWED`，child Run/Model/read、HANDOFF binding 与 Artifact 均为
0。minimum Green 不引入 AgentScope、Pi 或 Temporal；先完成 provider-neutral typed
handoff、exact worker registry、safe Trace、V6 forward migration、process-kill gap
和两个 fresh JVM verified read。

Pack 007 不声明通用多 Agent、并行 Worker、shared mutable context/Artifact、
checkpoint/resume、lease/fencing、live provider、write-capable Worker、Self Model
学习或用户/商业价值。write-capable Tool timeout 仍必须进入
`UNKNOWN + reconciliation`，不能照搬 Pack 006。

唯一一次 bounded
live-provider smoke 仍由 owner 另行批准。没有 live receipt 时不得声称已有 real-model
fixed baseline；即使执行 smoke，也不能由一次结果证明模型质量、账单准确性或产品价值。
学习与市场工作仍暂停且未完成。

### S4 F3 · Pack 007 start delta · 2026-07-31

- Change class：`R`。本切片同时改变 Agent runtime、public contract、Trace protocol、
  PostgreSQL migration 与 parent/child authority boundary，继续使用本 living ExecPlan，
  不复制 Task Brief。
- 用户/系统结果：一个 server-owned Conductor 能委派一次
  `article-draft.read-v1` Worker；Worker 只读同一个 Capture 并形成 durable proposal，
  parent 验证后成为唯一 Artifact writer。fresh JVM 能验证完整
  parent → child → WorkerResult → proposal → Artifact hash chain。
- 主要风险：若 child proposal 只在 process memory，或 Handoff 只绑定 child Bundle，
  系统不能证明 parent 最终消费了什么；若把 Worker 冒充 Tool，则 child
  Task/Run/profile/status/usage 与 no-write boundary 都会消失。
- 本次原理：typed delegation 不是 prompt pattern，而是 capability、child Task、
  independent Run、durable output、safe Trace、hash relation 与 transaction boundary
  的组合。
- Architecture decision：先由
  [RFC-0004](../rfcs/0004-typed-read-only-worker-handoff.md) 冻结 single、
  synchronous、depth=1、read-only Worker；新增 `WorkerResultEnvelope`，不把 proposal
  偷运进 Claim/Trace，也不引入 Pi、AgentScope、Temporal 或 parallel graph。
- 三路 read-only audit 已完成：contract 审计定位 dormant schema/Trace/no-write
  缺口；runtime 审计确认需要 two-phase provider-neutral seam 与 durable output；
  persistence 审计确认 V6 应复用 normalized binding 并用 composite FK/unique/deferred
  constraint 证明 same-owner、terminal、exact child hash、single-use 和 parent atomic
  visibility。主线程仍是唯一 writer。
- First runnable Acceptance Red：现有 Model 暂把 `worker.delegate` 当 Tool，
  目标断言仍要求 two Runs、one child read、one parent Artifact 与 durable Handoff；
  当前预期实际为 `BLOCKED / TOOL_NOT_ALLOWED`，child Run/Model/read、
  HANDOFF/WorkerResult/Artifact 均为 0。Red 必须是测试失败而非 compile failure。
- Context drift fault：只改变 registered child profile 的
  `contextPolicyVersion`，预期
  `BLOCKED / HANDOFF_CONTEXT_POLICY_DRIFT`；child Run/Model/Tool/read 均为 0。
- Durable evidence target：V6 fresh/populated upgrade、terminal child 后 parent
  commit 前 process-kill、两个 fresh JVM verified read、cross-owner/missing/running/
  wrong-hash/self/cycle/reuse/tamper、parent transaction rollback 与双 parent
  concurrency one-winner。
- Nonclaims：不声明通用多 Agent、parallel Worker、checkpoint/resume、lease/fencing、
  live provider、write-capable Worker、Self Model 学习、真实用户或商业价值。

Pack007 progress：

- [x] 2026-07-31：三路只读 contract/runtime/persistence 审计收敛；
- [x] 2026-07-31：RFC-0004 建立为 `Draft`；
- [x] 2026-07-31：runnable Acceptance Red。命令
  `./mvnw --batch-mode --no-transfer-progress -pl adapters/inmemory -am
  -Dtest=OfflineReadOnlyWorkerHandoffTest
  -Dsurefire.failIfNoSpecifiedTests=false test` 到达 production class path
  （底层为 test recording stores）后按预期失败：
  parent 实际为 `BLOCKED` 而非 `SUCCEEDED`，Run start/complete 各 1 而非 2，
  child read、HANDOFF、WorkerResult、Artifact 均为 0；不是 compile 或环境失败；
- [x] 2026-07-31：contract/runtime/Core minimum Green；typed `WorkerCall`、
  two-phase `AgentWorkerRuntime`、durable WorkerResult、parent-only Artifact 与
  adversarial deadline/budget/cancellation/child-status precedence 已验证；
- [x] 2026-07-31：Pack007 strict loader 与两个 fresh runner replay exact-equal；
  Java 同时冻结 exact path、`8,443` bytes、raw SHA、exact keys/types/versions/
  receipts，Node validator 与 Pack007 graph receipts 交叉绑定；
- [x] 2026-07-31：V6 fresh/populated/legacy fail-fast、same-owner/terminal/hash/
  single-use/immutable/tamper、late rollback、concurrent consumer 与 moved-Handoff
  OLD+NEW owner guard 已验证；
- [x] 2026-07-31：packaged success graph 与 child-commit/parent-`RUNNING`
  real process-kill gap；creator 终止后两个 fresh JVM exact verified read，
  且 10 张业务表 + Flyway history 的 PK/`xmin` snapshot 不变；
- [x] 2026-07-31：least-authority/security hardening 关闭 parent direct Tool
  authority、post-request Handoff rejection forgeability、Java/Node/PostgreSQL
  self-parent parity，以及 child inheritance/`resolvedModel` sanitizer；
- [x] 2026-07-31：PostgreSQL earliest durable boundary P1 关闭。带 Worker
  capability、却仍有 direct Tool authority 的非法 root parent 在 INSERT 前被拒绝，
  `agent_runs=0`；root verified read（含 `RUNNING`）复验 exact parent profile，
  direct SQL 篡改以 integrity failure fail-closed；
- [x] 2026-07-31：Trace nonterminal P2 关闭。accepted Handoff 后伪造
  `MODEL_STEP_FAILED / AGENT_KERNEL_FAILED` 且缺 terminal event 的 outcome 由
  Acceptance Red 证明；Green 只允许真实 cancellation、
  `latencyMs >= deadlineMs` 的 deadline boundary 与
  `modelSteps == maxModelSteps` 的 step exhaustion implicit 结束，产品 sanitizer
  返回 `FAILED / UNSAFE_AGENT_TRACE` 且不创建 Artifact；
- [x] 2026-07-31：Pack007 重新冻结为 `8,443` bytes、raw SHA
  `808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c`；
  contracts Gate 通过 6 schemas、54 fixtures、23 graph relation negatives；
- [x] 2026-07-31：final `clean verify` 的 10 个 reactor modules 全部
  `SUCCESS`，92 个 XML reports 共 523 tests、0 failure/error/skipped，
  Maven 总耗时 `02:02 min`；
  contracts、81 个 Markdown links 与 `git diff --check` Green；
- [x] 独立 final review：least-authority、Trace/API 与 replay/contracts/docs
  reviewer 均完成 post-fix 复核，最终阻断结论为 `P0=0、P1=0`；物理 Tool
  footprint、packaged API 重复断言等 non-blocking P2/hardening 保留在 Build Note；
- [x] 2026-07-31：RFC-0004 Accepted、ADR-0008 与中文 Build Note 已按最终
  least-authority、Pack hashes、gate counts 与 nonclaims 更新。

### S4 F4 · Pack008 child-only model Worker baseline · 2026-07-31

- Change class：`R`。本切片改变 model/profile authority、Worker generation
  compatibility、Eval CLI 与 verified read 解释规则，但不改变 public JSON schema
  或 V6 database schema。
- 系统结果：同一个 provider-neutral graph 可以让 non-model-bound Fake Conductor
  委派 exact Task 1.1 model child；只有 child 绑定 OpenAI model/pricing/environment
  与 `capture.read`，parent 不获得 provider 或 Tool authority。
- Architecture decision：
  [RFC-0005](../rfcs/0005-model-bound-read-only-worker-eval-baseline.md)
  与
  [ADR-0009](../architecture/decisions/0009-child-only-model-worker-eval-boundary.md)
  冻结 `ModelExecutionProfile`、content-addressed Conductor surface、exact
  multi-profile registry 和 Pack008 Eval-only boundary。
- First runnable Acceptance Red：
  `SyntheticEvalRunnerProcessIT` 对 `--worker-preflight` 期望 packaged
  `PREFLIGHT_READY`，原 CLI 实际在 argument parsing 以 `ARGUMENTS_INVALID` 结束；
  这证明 shipping surface 尚不存在，不是 compile failure。
- 第二条 Acceptance Red：graph permit test 通过反射加载专用
  `Pack008GraphExecutionPermit`，基线以 `ClassNotFoundException` 失败；minimum Green
  增加 `PREPARED → ARMED → CONSUMED`、双 Task ordering、child-only CAS consume 与
  fail-before-delegate observer。
- Frozen graph：Pack raw SHA
  `4803c227d88484dfe5796c286cb7b602242a54b452c39e5eb2be69f6a02bf1db`；
  parent/child Task hashes 分别为
  `35465c2631b9616195beb85d5280c7debc26f80603ad8cf89bb5a722e4517470` /
  `ed8988df7c4c1aaabee266720ef43aa0124116751ab1ae6eef773772c70a2d04`；
  attempt ID 为
  `e3bfef65db2dbc1eeabf726e2162909ff52c92466ea14a4f9e65eb8270d17661`。
- Zero-egress boundary：显式 `--worker-preflight` 只读 bounded、no-symlink、
  hash-frozen Pack/environment；已知可达 construction path 没有创建
  credential/client/model/provider invocation/Run/marker，owner home/tmp 保持空，
  指定 local HTTP sentinel 收到 0 request；这不是 system-wide socket
  instrumentation。无参数与 `--execute` 继续属于 Pack003。
- Durable compatibility：V6 PostgreSQL 可同时保存 Pack007/Pack008；
  terminal truth 以 exact registry/fingerprint/experiment/Harness/component map
  选择 profile，`RUNNING` graph 必须 unique full-Task match，registration order
  不参与解释。
- Metering integrity：审查发现 non-model parent 仅校验 `usage >= child`，可接受
  coherent inflation；runnable Core/PostgreSQL Reds 先证明旧实现错误接受，再只对
  Pack008 child-only model route 强制 cost/token exact-equal。cost-only/token-only
  分别覆盖 sanitizer、terminal verifier、transaction rollback 与 coherent SQL
  tamper 后三种 fresh Store read surface；Pack007 historical subtree aggregate
  保持 `>=`。
- Nonclaims：Pack008 graph execution 只在 test-only loopback fixture；
  process-local permit 不是 operator gate、durable marker/journal、cross-process
  one-shot 或 billing evidence。当前没有 Pack008 live route、real key/request/result、
  ordinary API wiring、fresh-JVM/process-kill、checkpoint/resume 或用户价值证据。
  same-JVM fresh Store 不能写成 fresh JVM。
- Forward constraint：V6 没有在 `RUNNING` start 时持久化 selected profile identity。
  当前 Pack007/Pack008 完整 Task authority 不同，unique matching 已足够；引入任何
  Task-compatible revision 前必须先以 V7（或等价 durable selector）关闭 rolling
  upgrade relabeling 风险，不能用 registration order fallback。

Pack008 progress：

- [x] runnable CLI 与 graph-permit Acceptance Red；
- [x] `ModelExecutionProfile` 与 child-only
  `ModelBoundReadOnlyWorkerExecutionProfile` minimum Green；
- [x] Pack008 strict catalog/preflight、双 Task/profile/pricing/prompt/Conductor
  surface 与 attempt hash冻结；
- [x] packaged `--worker-preflight` zero-effect、help/invalid combinations、
  symlink/oversize/strict JSON/environment drift regressions；
- [x] test-only graph permit ordering、expiry、wrong consumer、observer failure 与
  concurrent one-winner；
- [x] production graph classes + loopback OpenAI adapter integration；
- [x] Pack007 frozen replay保持 raw SHA
  `808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c`
  与历史 profile identity；
- [x] real PostgreSQL mixed-generation、reverse registry、fresh-store gap continuation、
  pre-insert fail-closed、terminal tamper 与 10 业务表 + Flyway PK/`xmin` snapshot；
- [x] 2026-07-31：exact metering 与 process-effect assertions 冻结后的 final
  `clean verify` 通过 10 个 reactor modules、97 个 XML reports、570 tests，
  0 failure/error/skipped，总耗时 `01:36 min`；module counts 为
  39 / 145 / 41 / 20 / 37 / 83 / 34 / 88 / 83；contracts 通过
  6 schemas / 54 fixtures / 8 Packs /
  2 environments，doc links 通过 84 个 Markdown files，Diff check Green；
- [x] 两路 post-fix 独立审查关闭到 `P0=0、P1=0`；其中一路 `P2=0`，另一路
  两个不阻断 P2 已在 Build Note 记录处置；
- [x] RFC-0005、ADR-0009 与中文 Build Note 已完成最终状态收口；
- [x] 独立 commit `19d781a55881d34885c22db580923f0ad5c254c0` 与 exact receipt。

### S4 F5 · Pack009 durable graph attempt / provider-accepted crash · 2026-07-31

- Change class：`R`。本切片新增 composition root、PostgreSQL migration、operator
  authority、one-shot attempt与 provider crash semantics；继续使用本 living ExecPlan，
  不复制 Task Brief。
- 系统结果：一个 fixed PUBLIC synthetic model-child graph 在 loopback provider 已
  durable 接收 exactly one request、writer却在 attribution commit前被强杀时，两个
  fresh verifier JVM都返回同一份
  `VALID / INCOMPLETE / billing UNKNOWN`；same-attempt restart在 provider
  credential/client/request前拒绝（完整 writer replay仍先读取 DB password），
  provider request count保持 1。
- 主要风险：把 `PROVIDER_INTENT` 误写成“provider未执行”或“可以重试”，会造成重复
  请求与费用；文件 marker + PostgreSQL会形成双 authority；test-only crash bypass
  若进入 shipping JAR，会绕过真实 owner approval；若只用 manifest hash作为 one-shot
  key，rolling binary可因 profile/codec drift生成第二个 ID并再次执行同一费用槽位。
- 本次原理：provider effect与数据库不可能原子提交。正确做法不是伪造 exactly-once，
  而是 durable intent、create-only claim、UNKNOWN truth、no automatic replay和后续
  reconciliation boundary。
- Architecture decision：
  [RFC-0006](../rfcs/0006-postgresql-canonical-one-shot-graph-attempt.md)
  与
  [ADR-0010](../architecture/decisions/0010-postgresql-canonical-graph-attempt.md)
  提议新建 `apps/graph-eval-runner`；Core持有 graph attempt语义，PostgreSQL V7持有
  manifest/binding/journal/seal truth，新 App只做 strict assets、operator gate、
  composition、execution与 read-only verification。
- Rejected placement：不放宽现有 `apps/eval-runner` 的 PostgreSQL禁令，不把 OpenAI
  接入普通 API，不用 POSIX marker与数据库争夺 attempt authority。
- First runnable Acceptance Red：先让新 module/Main/strict Pack009 preflight/test
  harness可 package/run；process IT启动 PostgreSQL、独立 loopback provider与 execute
  JVM后，基线应以 `V7_GRAPH_SCHEMA_MISSING`退出，测试因等不到 committed
  provider-intent phase而失败。失败必须在 runtime，不是 compile、asset parsing、
  Docker或 provider环境错误。
- Green target：V7 create-only manifest、exact Run/profile bindings、append-only
  hash-chain state machine、CAS head、graph-bound AgentRun selector与 stable
  checked-in execution slot unique claim；
  provider accepted + DB intent后强杀；两个不同 PID fresh
  verifier exact-equal且 read-only；replay拒绝；shipping JAR不含 test harness/JUnit/
  Testcontainers，shipping CLI仍不开放 execute。
- Nonclaims：不声明 live-provider call、real key/model result/token/cost/invoice；
  不声明 terminal graph success、product API、checkpoint/resume、reconciliation、
  provider-side idempotency、cross-database replay protection、power-loss/NFS、
  Temporal、parallel/write-capable Worker或用户价值。

Pack009 progress：

- [x] 三路 read-only placement/process/least-authority 调研与两轮 post-fix审查完成；
  main thread是唯一 writer；
- [x] RFC-0006、ADR-0010与本 slice delta已按最终 evidence收口为 Accepted；
- [x] strict Pack009 asset/preflight与 non-empty module skeleton Green：
  `GraphEvalCliTest` + `Pack009GraphPreflightTest` 共 8 tests Green；
  `GraphEvalArchitectureTest` 5 tests Green；
  contracts Green（6 schemas / 54 fixtures / 9 Packs / 3 environments）；
- [x] runtime Acceptance Red真实到达 `V7_GRAPH_SCHEMA_MISSING`：exact command
  `./mvnw --batch-mode --no-transfer-progress -pl apps/graph-eval-runner -am
  -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false
  -Dit.test=Pack009DurableGraphCrashProcessIT
  -Dfailsafe.failIfNoSpecifiedTests=true verify` 成功 package shipping JAR并启动
  PostgreSQL 18.4 + writer JVM；preflight与 V1–V6 migration均完成，writer以
  exit 4 / `GRAPH_HARNESS_REJECTED reason=V7_GRAPH_SCHEMA_MISSING`退出，
  process IT只因等不到 durable provider-intent phase而 Red；同一 IT中的
  shipping-JAR isolation/CLI rejection test已 Green；
- [x] V7 migration、Core domain/port与 PostgreSQL store focused Green：
  PostgreSQL graph Store 17 tests覆盖 create-only、hash chain、CAS、两个独立
  Store/DataSource concurrent one-winner、tamper与 read-only snapshot；
  V1/V2/V3 populated升级和 fresh install均到 V7，历史 business truth不改写；
- [x] provider-accepted process-kill、双 fresh JVM read-only与两类 replay Green：
  final targeted receipt为
  `providerPid=92914 writerPid=92915 verifierPids=92929,92930
  replayPids=92942,92944 providerCount=1 billing=UNKNOWN
  headHash=f0dad4...0e501 dbSnapshotSha256=b6de22...8bf85`；
  minimal replay没有 provider endpoint/key，完整 writer replay只收到 DB password，
  均在第二次 provider request前拒绝；
- [x] `ReviewedOpenAiClient` 使用 private codec template/per-client copy；global mapper
  在 receipt后 mutation的 runnable regression已 Green，typed hash仍与 raw HTTP body
  exact-equal；
- [x] direct dependency default-deny、production constant-pool Gate、actual shaded
  JAR closure、multi-release logical-entry normalization与 app-owned class-resource
  exact allowlist Green；真实 crash harness bytecode与 synthetic MR-JAR negative
  fixtures先 Red 后 Green；另以误放到 `io.emergeos.core` 的 synthetic Main证明
  `target/classes` 中每个 app-owned class不论 package都必须命中 exact allowlist；
  process IT还会 walk全部 `target/test-classes/**/*.class`（包括
  nested/anonymous），逐项拒绝进入 shipping JAR；shipping `--verify` 明确 disabled
  且 stderr receipt无 logging噪声；
- [x] full Gate Green：11 reactor modules、106 XML reports、616 tests，
  0 failure/error/skipped；contracts为 6 schemas / 54 fixtures / 9 Packs /
  3 environments；`git diff --check` Green；
- [x] recovery/process与 least-authority两类独立 review均关闭到
  `P0=0、P1=0`；中文
  [Build Note](../operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)
  已完成；
- [ ] 独立 commit（精确 staging，继续排除 `.workbuddy/`）。

Pack009 evidence boundary：

- synthetic console不是 real TTY或 owner真实批准；
- `billing=UNKNOWN`与 observed cost/token `0`不表示免费、未调用或可 retry；
- process provider是 independent loopback synthetic provider，不是 external/live
  provider，不含 real key/model result/token/cost/invoice；
- terminal seal仍为 false，graph outcome为 INCOMPLETE；
- concurrent one-winner实测为同 JVM内两个独立 Store/DataSource transaction；
  fresh进程实测为 sequential replay，没有 simultaneous cross-process/cross-host
  contention；
- opaque typestate目前是 composition-level约束，尚不是 library-level不可伪造
  authority；任何 product execute route开放前必须进一步收口 public Store mutation
  与 console capability。

### S4 F6 · Pack010 attributed terminal graph / shared-candidate Harness · 2026-07-31

- Change class：`R`。本切片把 Pack009 的 durable provider intent 扩展为完整 attributed
  terminal graph，并引入 shared-candidate Harness。继续使用本 living ExecPlan；不为
  implementation 复制第二份事实来源。
- Architecture decision：
  [RFC-0007](../rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)
  与
  [ADR-0011](../architecture/decisions/0011-attributed-terminal-graph-and-live-harness-pilot.md)
  当前仍为 Proposed。PostgreSQL canonical truth 与
  `HarnessEvaluationReport` 是两条独立但收敛的 Gate：Report 只能从三份 fresh verified
  sequence-17 snapshot派生，不能写进 V8 transaction，也不能用 synthetic contract
  golden冒充 live execution receipt。
- First Acceptance Reds：
  1. `PostgresGraphAttemptTerminalSurfaceTest` 证明三个 terminal Store方法仍由 port 的
     default `UnsupportedOperationException` 掩盖，失败为
     `expected port.isDefault() false, actual true`；
  2. `GraphAttemptMigrationTest#freshInstallReachesV8WithExactTerminalShape` 证明 latest
     migration仍停在 V7；
  3. `populatedV7PrefixesUpgradeToV8WithoutTouchingLegacyRows` 已用冻结的 test-only
     `V7GraphAttemptSqlSeeder` 构造 sequence 1/10/11，当前同样因 latest仍为 V7而 Red。
- Migration invariant：V8只能新增 forward migration，绝不修改 V7 bytes/checksum。
  升级测试比较旧 attempt/binding/event/head/Run 的 JSON、hash、timestamp、`xmin` 与
  Flyway 1–7 history；新 event列必须保持 NULL，新 terminal tables必须为空。
- PostgreSQL Green target：
  `TX-A attribution`、`TX-B child terminal`、`TX-C parent terminal + seal` 三个 semantic
  transaction；sequence 15可独立 durable，16只能存在于 TX-C 内部，17才是 sealed
  truth；fresh repeatable-read reader重建 exact attribution/Candidate/Run/Bundle/
  WorkerResult/Artifact/terminal binding/seal。
- Fault/concurrency target：每个 statement boundary 的真实 process-kill/restart、
  child-terminal recovery、两个 JVM same-cursor恰好一个 winner、generic
  `AgentRunStore`越权 terminalization拒绝、second seal/post-seal append/update/delete
  拒绝，以及 self-consistent tamper fail closed。
- Contract/Harness target：`HarnessCandidateEnvelope` 与 Core terminal pure invariants已进入
  focused Green；下一步冻结 `HarnessEvaluationReport 1.0` Java/Schema/Node golden、H0/H1
  deterministic evaluator和 complete-only reducer，所有 evaluator effects必须精确为 0。
- Authority/Live boundary：owner real-TTY facade、r1/r2/r3 predecessor claim、
  least-privilege V9 role/ACL semantic transaction与 packaged-bytecode successor
  kill/two-JVM已有本地 focused evidence；dormant exact credential broker/session composer与
  loopback failure ordering sentinel也已 Green；owner-approved cursor→Coordinator/egress同进程
  handoff、independent provisioning bootstrap及 dormant fixed-role V9 writer composition也已有
  focused evidence；exact provider response attribution与 actual success structured-final →
  process-local opaque outcome → typed TX-B command也已有 focused evidence；actual PUBLIC loopback
  outcome已经同一 capability链完成 exact PostgreSQL TX-B与 restart reconciliation，但仍是
  test-only harness，不是 shipping route。successful seq15→strict parent aggregate review→
  process-local opaque typed TX-C command→exact V9 TX-C也已取得 focused implementation/process
  evidence；Owner facade自行read-back并绑定 durable seq15，forged/no-burn、wrong runtime、
  two-command concurrency与PostgreSQL restart reconciliation均已覆盖。adapter-owned canonical
  TX-B/TX-C transition也已移除production raw payload ABI，并覆盖typed drift no-burn、并发唯一胜者
  与PostgreSQL restart reconciliation。failure terminal、forward-only durable outcome resume、
  跨JVM resume与 shipping App route仍未收口。
  Authority/Live Gate Green且独立 review
  `P0=0、P1=0` 之前，shipping execute保持 disabled；任何 Green均不自动授权真实模型调用。

Pack010 progress：

- [x] Candidate Java/JSON Schema/semantic fixtures/Chinese + astral Unicode golden与
  content-redacted logging focused Green；Candidate已绑定 exact request-2 response hash、
  child structured-final proposal hash和 durable obtained Evidence；
- [x] Core exact two intent/attribution、15/16/17、usage、Candidate/WorkerResult/Artifact/
  terminal seal pure invariant focused Green；
- [x] public read-only `GraphAttemptReader` surface与 Pack009 verifier capability narrowing
  focused Green；
- [x] terminal Store surface、fresh V8、populated V7 1/10/11 三条 runnable Acceptance Red；
- [x] PostgreSQL V8 migration与 TX-A/TX-B/TX-C；terminal protocol的
  `maximumProviderRequests`已由 Core与 PostgreSQL CHECK共同固定为 exact 2；
- [x] fresh restricted terminal reader、37-point process-kill、two-JVM CAS、tamper与
  generic Store negative；
- [x] `HarnessEvaluationReport` contract/golden/evaluator/reducer，以及真实 PostgreSQL
  r1/r2/r3 sequence-17 → complete-only canonical Report integration evidence；
- [x] shipping preflight/packaging、root `verify` 11-module Gate与最终 read-only review
  `P0=0、P1=0`；execute继续 disabled；
- [x] production `OwnerTtyGraphAuthority`、server-owned R1/R2/R3 surface、private
  local permit object one-winner、local real-PTY no/piped/wrong/expired/replayed matrix，
  以及 r1→r2→r3 predecessor fresh verification + claim同 transaction；successor claim另有
  test-classpath hard-kill与 two-JVM one-winner evidence；
- [x] GUC live-safety Acceptance已转为 executable evidence：same writer raw SQL可在
  transaction内伪造 V8 custom GUC并通过 terminal Run UPDATE trigger，所以明确保持
  Authority/Live Gate Red，未接入 shipping execute；
- [x] forward-only V9 role/ACL split与 exact TX-B/TX-C semantic function；generic writer、
  executor raw GUC/DML、ACL/membership/owner/trigger drift matrix与三路 actual post-fix review
  focused Green；独立 provisioning artifact现已包含 dedicated DB全 grantee allowlist与
  single-transaction bootstrap + pure audit；
- [x] test-only shell加载 shipping fat-JAR production Store/Catalog的 successor
  `AFTER_HEAD_UPDATE` kill rollback与两个 fresh JVM one-winner；清空 ambient env、dynamic
  ephemeral credential只经 bounded stdin frame；shipping CLI仍无 execute route；
- [x] shipping artifact内 dormant exact credential broker/provider session composer；credential
  lease绑定 revision + exact coordinator/egress identity并 single-use，compiled order固定 local
  permit burn → durable credential marker → env read，以及 client/model markers → exact intent；
  descriptor-exact directory/shaded-JAR Gate同时拒绝 indirect reflection、ConstantDynamic与
  unreviewed InvokeDynamic bootstrap；shipping Main incoming refs仍为0；
- [x] 本机 loopback effect-ordering sentinel：durable intent persistence failure时 HTTP request
  exact 0；一次 PUBLIC synthetic response后 attribution因缺少完整 surface而 fail-closed，
  replay不增加 HTTP或 graph effect；该项未经过 production base URL，也不等于真实 provider；
- [x] owner-approved cursor→Coordinator同进程线性 capability handoff：exact caller/private
  permit origin、verified sequence-2 cursor、Coordinator/egress binding、DB-time expiry、local
  race/replay与 approval/adoption窗口 kill后 fresh-process fail-closed；两份 attribution后又能
  派生 exact one-shot terminal capability，wrong/replay/concurrent/terminal-binding hard-kill后
  restart均 fail closed；该项明确不是跨 JVM resume；
- [x] sequence-7 durable provider-session intent：绑定 exact owner/attempt/revision/
  Coordinator/egress/cursor/first request/expiry，claim与consume双线程各 exact one winner；
  wrong/expired/replayed拒绝，INSERT后 commit前 hard-kill完整回滚、commit后 hard-kill可重读，
  fresh process restart均 fail closed；compiled broker order保证 intent在 credential marker/env read/
  client/model/session/HTTP之前，shipping Main incoming ref仍为0；
- [x] production capability lease继续携带 exact durable intent/owner/expiry，在 key read、client
  factory、model factory与 session open每个 effect boundary前同时做本地 Clock与 DB-time复核；
  expiry后的动态 negative保持全部 effect counter为0；OwnerTty Coordinator固定使用
  authority-bound Store，不再接受构造时的原始 Store；
- [x] compose后 session继续持有同一 lease/Coordinator/egress/Clock；每次 `next`及 exact
  pre-HTTP observer在 ordinal mutation/provider intent之前分别复核 freshness，compose后过期
  保持 `MODEL_CREATED`、`providerIntents=0`与 HTTP effect=0；bytecode Gate固定两处调用顺序；
- [x] 独立于 Flyway 的 production role/ACL bootstrap artifact：transactional check/apply、
  default-deny确认、LOGIN password-null、NOLOGIN terminal owner、exact writer/executor/reader
  ACL、幂等 read-back、全 grantee database/schema/relation/sequence/function/default ACL allowlist、
  exact trigger topology与 drift fail-fast；V9 helper owner/signature/language/SECDEF/volatility/
  parallel/search_path/body也纳入 pure audit；真实 PostgreSQL注入 audit failure证明 bootstrap
  + pure audit同一 psql transaction回滚；
- [x] dormant V9 generic-prefix/executor-terminal writer composition：verified complete
  response hash/token attribution必须先落库，composition不暴露 generic writer并消费 exact
  owner terminal claim；OwnerTty DB identity与 prefix/executor runtime DB exact绑定且在 capability
  burn前验证，claim expiry与 schema-qualified semantic function在同一 SQL statement用 DB time
  复核；cross-attempt payload、two-database
  splice、alternating prefix/terminal DataSource与 capability replay/concurrent fail closed；runtime
  冻结 semantic function的 transitive helper closure与 trigger `tgattr`，拒绝 startup前 definition
  tamper、executor TEMP和 startup后 prefix TEMP/ACL drift，TX-B/TX-C各一次；PostgreSQL process
  restart后 reconciliation保持 provider-session intent/cursor/attribution/terminal truth，且不声称
  provider exactly-once；
- [x] runtime OwnerTty identity必须与 exact frozen prefix direct-login identity完全一致，
  admin/migrator/table owner不能仅凭同库 OID/server identity获得 authority；provider-session
  cursor按数据库 SELECT row重建并与调用方 exact比对，V9复合外键绑定 canonical event；
  provisioning/runtime audit拒绝 session-intent relation上的任何额外非 internal trigger；
- [x] provisioning preflight/pure audit与 terminal runtime startup/每 TX共同固定全部35个 public
  non-internal trigger的 shipping SHA-256 topology（relation/name/enabled/tgtype/columns/WHEN/args/
  constraint/function identity）以及相同的16-helper signature/result/properties/search_path/body
  SHA-256 closure；额外 terminal relation trigger与此前遗漏 helper body tamper均 fail closed；
- [x] production provider exact response attribution：bounded content-decoded entity bytes
  SHA-256、strict JSON envelope与完整 input/cached/output/reasoning/total token split先 durable，
  再做model/limit/output semantic判定；missing/inconsistent usage fail closed；真实PostgreSQL
  process restart、concurrent session隔离、attribution transaction fault与packaged hard-kill后
  fresh JVM execution-slot replay均已验证，且明确不声称provider exactly-once；
- [x] actual success structured-final → process-local opaque outcome → typed TX-B command focused
  binding：outcome绑定 exact revision/manifest/Coordinator/egress/credential lease expiry与完整
  attribution prefix；runtime在claim前按有序 attribution hash关闭 PostgreSQL NUMERIC scale drift，
  校验全部 V9 relation exact keys，并通过 contract constructor重算 Candidate/WorkerResult nested
  integrity，再绑定 terminal Run/binding/event、token/cost/model与payload hash；extra key、nested
  evidence一致篡改、duplicate/trailing JSON都不烧毁 outcome。actual PUBLIC loopback outcome沿同一
  owner/Coordinator/egress capability进入 exact PostgreSQL TX-B，restart后 seq15 reconciliation；
  post-fix review继续把TX-B terminal child重建为完整 `AgentRun`、binding、event 15与sequence-15
  `GraphAttemptSnapshot`，并把Candidate/WorkerResult/Trace/resource mirror/全部Run denormalized
  relation exact绑定；event 15的`committed_at`由PostgreSQL生成，不再接受caller输入；
  wrong model、usage超限、malformed final、expired/replayed/concurrent及 packaged outcome-mint
  hard-kill均 fail closed；该项只关闭success→TX-B focused Acceptance，不表示 live；
- [x] successful sequence 15 → strict parent aggregate review → process-local opaque typed TX-C
  command focused binding：production App不再接受 raw `completeParentAndSeal(String)`；prepare在
  Owner claim前冻结12个root key与全部relation row exact shape，typed重建Task/Result/Bundle/Trace/
  ResourceBinding/parent terminal binding/event16/seal/event17；command绑定runtime owner、exact
  terminal capability/revision/Coordinator/egress/manifest/cursor/head、两条 attribution、Candidate/
  WorkerResult/child terminal与payload hash并one-shot。Owner claim与consume均自行read-back exact
  durable seq15；seq14不能再仅凭内存state派生parent claim。forged/no-burn、wrong runtime、两个
  commands并发、authority drift与PostgreSQL restart→seq17 reconciliation已Green。post-fix review
  继续把完整 parent `AgentRun`、`ArtifactLineage`与 sequence 17 `GraphAttemptSnapshot`作为 Core
  aggregate重建，并把全部 relation/mirror value exact绑定；event16/17的 `committed_at`由PostgreSQL
  在同一TX-C内生成，不再接受caller输入。DB层复用37-point
  process kill与TX-C two-JVM one-winner。该项仅为successful TX-C focused slice，不覆盖failure、
  packaged production-composition hard-kill、adapter raw sink或跨JVM resume；
- [x] adapter-owned canonical terminal transition：production App与public writer不再接收 raw
  `String`/`JsonNode`/`Map`/`byte[]` payload，也不再暴露 mutable prefix Store；PostgreSQL adapter
  以 typed `AgentRun`/Candidate/WorkerResult/ArtifactLineage和verified seq14/15 snapshot先重建
  Core aggregate，再 mint private-constructor one-shot TX-B/TX-C transition。wrong typed truth在owner
  claim前拒绝且不烧毁authority；child/parent并发各一个winner，PostgreSQL restart前后重建
  seq15/17 exact truth；shipping raw executor overload保持private并只由test-only bridge验证negative；
- [x] forward-only V10 exact provisioning pair与 attributed failure semantic authority：V9仅保留
  historical migration，`graph_executor`对V9 function的`EXECUTE=0`；fresh V10 migration自行
  `REVOKE EXECUTE FROM PUBLIC`，active child/parent pair固定 trusted search path、SECURITY DEFINER、
  SHA-256 body fingerprint及全trigger/helper closure。独立 provisioning继续与Flyway分离，固定
  terminal owner NOLOGIN、executor LOGIN NOINHERIT、generic prefix writer/restricted reader exact
  ACL，并对unknown function、全部grantee及unsupported view/materialized-view/foreign relation
  fail-fast。child failure只接受Core closed allowlist；parent failure只接受exact
  `FAILED/HANDOFF_CHILD_FAILED/HANDOFF_REJECTED/CHILD_FAILED`映射。两个独立 PostgreSQL session
  对同一canonical TX-B/TX-C payload各只有一个winner，wrong TX-C mapping整TX rollback；两次
  PostgreSQL restart后保持seq14→15→17、无partial rows，并直接read-back durable session intent。
  最终defensive recovery又用raw executor实际复现并关闭nested Result/Bundle failureReason与
  successful nonnull attribution逃逸；SQLSTATE 22023绑定V10 semantic guard。directory与shipping
  JAR negative同时冻结failure composer、runtime与writer的exact consumer/no-consumer closure；
  post-fix review再发现adapter-package可绕过writer direct-consumer规则，最终Gate仅把四个public
  failure writer method扩到全部first-party direct member references，并由adapter-package directory/JAR
  negative与packaged process同时锁定；
  独立ACL drift test也隔离了unknown terminal-owner function allowlist。
  该项不证明跨JVM typed failure outcome resume或provider exactly-once；
- [x] V10 defensive recovery最终root `clean verify`为11 modules、139 XML reports、801 tests、
  0 failure/error/skipped；22-file ordered slice aggregate为
  `aea21ca80cdfca3fe0708bf5ce9ed18e9b5331aeb48ab4467ee8d60b1098cb7e`，shipping graph-eval
  JAR SHA-256为`9ee7fe7b02a0020b68e77d2d79f1428ccba6f6400516bf2f9cbc0fe1ce5eecd4`；
- [x] 同一最终快照三路actual post-fix review均独立命中aggregate/JAR/139/801/0：
  ACL/provisioning为`P0=0、P1=0、P2=0`，Acceptance/Gate为`P0=0、P1=0、P2=1`，typed
  protocol/bytecode为`P0=0、P1=0、P2=2`，均可签收focused Engineering Green。scoped P2分别为
  raw race诊断精度、non-app indirect reflection边界与failure claim-level cross-claim/concurrency
  matrix；不得外推为overall `P1=0`，总控仍为`P1=3、P2=1`、Gate Red；
- [x] forward-only V11 durable attributed failure provenance与cross-process claim fencing：
  request-2 exact outcome判定后，attribution/event/head与closed failure sidecar在同一个PostgreSQL
  transaction提交，rollback只能保留seq13/outcome0，commit只能形成seq14/outcome1。sidecar绑定
  principal/attempt/manifest/revision、session intent/expiry、seq13 predecessor、seq14 cursor、ordered
  attribution/request/response/model/failure及canonical provenance hash；fresh V11、populated V10→V11
  history/row/`xmin` fidelity均Green；
- [x] dedicated `emergeos_failure_resumer LOGIN NOINHERIT`只有V11 exact read/claim EXECUTE且relation
  ACL为0；prefix writer只有record，executor仍只有V10 TX-B/TX-C pair。两个fresh packaged JVM对同一
  version exact one winner；winner在claim commit后hard-kill，restart前too-early successor在lease内
  被fence；PostgreSQL immediate restart后等待DB clock expiry，另一fresh JVM从version 2原子reclaim
  到version 3。wrong manifest/
  provenance/version、invalid lease、expired session、early/stale/replayed successor均fail closed；
- [x] V11 resumer runtime在构造时双读冻结server/database/role identity与三个function body hash，
  每次load/claim由Java复核frozen identity/body fingerprint，SECDEF函数在同一transaction复核
  role/ACL/owner/properties/search_path/trigger topology；membership、relation ACL、extra EXECUTE、
  owner、body五类post-provision drift均fail closed。最终Acceptance先用production writer生成真实
  seq14/READY sidecar并证明baseline load；每类drift恢复后逐字段回到baseline，body canary可被raw
  resumer读取但被frozen store按SHA拒绝。bytecode Gate同时冻结interface与concrete store dispatch，
  adapter-package directory/shipping-JAR negative均Green；
- [x] V11最终root `clean verify`为11 modules、141 TEST XML reports、807 tests、
  0 failure/error/skipped；34-file ordered slice aggregate为
  `18ae990f1fd73f391a0a044d98c62f6f6fef9e67fa6cccc6ab37d26c9beefc0c`，shipping graph-eval
  app JAR SHA-256为`e5b8b27a0e93355ad8e894fa1742c38db7d1521fdcaa2b0fba91c18f074af051`；
- [x] 同一V11最终快照三路actual post-fix review均独立命中aggregate/JAR/141/807/0：
  Acceptance/process与ACL/provisioning均为`P0=0、P1=0、P2=0`，typed protocol/bytecode为
  `P0=0、P1=0、P2=2`，可签收focused Engineering Green。scoped P2是non-app reflection/
  MethodHandles及外部未扫描consumer的defense-in-depth边界，以及没有第二套provenance oracle/
  六路runtime effect counter；不得外推overall Gate；
- [x] 上一轮successful TX-B/TX-C与A/B/C根级 `clean verify`独占重建测试报告：
  11 modules、137 XML reports、790 testcases、
  0 failure/error/skipped；并发 focused build污染的首次结果已丢弃，最终快照全程没有第二个
  Maven writer；
- [x] 上一轮A/B/C第三版13-file frozen aggregate
  `32596e034dc40694fa7d03a3788eb2adf7832163703e153c35f7b3e045572b9b`已完成三路
  actual post-fix review：focused A、B/C均P0/P1/P2=0；不得外推，overall Authority/Live仍按
  总控账本P1=3/P2=1、Gate Red；
- [x] 本 adapter-owned transition slice在首次 post-fix review发现并修复 stale
  `prepareChild` bytecode descriptor P2；修正后的第二次独占root `clean verify`为11 modules、
  137 XML reports、792 testcases、0 failure/error/skipped。15-file ordered slice-delta aggregate
  `dc25427dcd65c48ea7dfa78571e6a78172a2e52d4f99409fad48eef224f3efdb`；该hash不替代前序
  V9/OwnerTty/Core evidence；三路第二次 actual post-fix review均独立命中该hash，capability、
  typed aggregate/V9与Gate/evidence均为`P0=0、P1=0、P2=0`；overall仍按总控
  `P1=3、P2=1`、Authority/Live Gate Red；
- [x] forward-only V12 durable failure terminal resume：exact provenance、claim version、claimant、
  fence、DB-clock lease与live head分别和failure child TX-B、parent+seal TX-C在同一个PostgreSQL
  transaction内校验并推进。happy path为`READY/v1/head14 -> CLAIMED/v2 ->
  CHILD_CONSUMED/v3/head15 -> PARENT_CLAIMED/v4 -> TERMINAL_CONSUMED/v5/head17`；raw V10
  failure TX-B/TX-C、wrong/stale/replayed/expired claim及cross-database snapshot splice均fail closed，
  V10 success path在latest V12仍保持exact one winner；
- [x] V12 packaged process/fault evidence：fresh JVM分别完成child/parent claim与terminalization，
  两个不同JVM每阶段race exact one winner；test-only DataSource在真实`Connection.commit()` delegate前
  `halt(76/77)`，完整JSON/`xmin`回滚后fresh JVM重试，TX-B后PostgreSQL immediate restart再完成TX-C。
  V11->V12合法READY/CLAIMED history/row/`xmin`保真，历史claimed/no-sidecar raw V10 head15/head17
  migration均SQLSTATE 55000整transaction回滚；fresh/provisioned ACL、runtime function/trigger body、
  bytecode direct/reflection与shipping JAR Gate均Green；
- [x] V12最终独占root `clean verify`为11 modules、142 TEST XML reports、815 tests、
  0 failure/error/skipped；26-file ordered closure aggregate为
  `6f51c0fbe0f4ec82ef0691579b6658fd5059aaae9674567048e5428b1387922e`，shipping graph-eval
  app JAR SHA-256为`9d2981ca40d6a0dc2dc9dfcaa7db1cbef5f8caed198f4ef49c50c1c32d31872d`；
- [x] 同一V12最终快照三路actual post-fix review均独立命中aggregate/JAR/142/815/0：
  ACL/provisioning/migration、typed protocol/bytecode与Acceptance/process均为
  `P0=0、P1=0、P2=0`。child/parent race loser均按PID精确锁定`reason=FENCED`；completion API的
  wrong provenance/version/claimant/fence/model与expired lease 11项typed negative加1项raw SQL
  model canary均保持全库JSON/`xmin`不变。focused Engineering Gate可签收Green；不得外推overall
  Authority/Live；
- [ ] provider semantic attestation与shipping App live route；
  - [x] V13 bounded provider validation attestation local-only Engineering slice：按
    [RFC-0008](../rfcs/0008-bounded-provider-validation-attestation.md)与
    [ADR-0012](../architecture/decisions/0012-db-authenticated-provider-validation-attestation.md)
    完成test-only Ed25519、独立`emergeos_provider_attestor` JVM验签、DB one-shot challenge与
    receipt/attribution/event/head/optional failure outcome同transaction。真实loopback reviewed
    outcome先以exact manifest hash私有mint，再经production mapper、signature transcript与raw SQL
    execution-binding DB fence推进TX-A；raw bypass、tamper/replay/expiry/cross-attempt/cross-DB、
    stage/commit hard-kill、PostgreSQL restart与two-JVM race均fail closed；不保存raw response/private
    key，不接真实provider，不启用shipping execute；
  - [x] V13最终独占root `clean verify`为11 modules、144 `TEST-*.xml` reports、821 tests、
    0 failure/error/skipped；contracts 8 schemas/70 fixtures、104 Markdown links与`git diff --check`
    均Green。53-file ordered slice aggregate与shipping graph-eval app JAR SHA-256已按最终源码冻结在
    同slice Build Note；
    三路独立post-fix review均为P0=0/P1=0，focused Engineering Gate可签Green；overall
    Authority/Live继续Red；
  - [ ] production public-key verifier、anchor/key lifecycle与shipping App wiring。当前PostgreSQL 18
    standard extension不提供detached Ed25519 verification；是否批准audited verifier、production key
    custody及live wiring需要owner另行授权；
  - [x] post-review hardening follow-up：durable validation row已增加
    `execution_binding_hash IS NULL OR execution_binding_hash = manifest_hash`同表CHECK；禁用USER
    trigger后的raw UPDATE仍精确`23514`且全库JSON/`xmin`不变。真实V13 FAILED/CONSUMED head14已通过
    production V12 resume Store连续推进`READY/v1 -> CLAIMED/v2 -> CHILD_CONSUMED/v3/head15 ->
    PARENT_CLAIMED/v4 -> TERMINAL_CONSUMED/v5/head17`，validation完整JSON/`xmin`不变，outcome在
    CLAIMED/v2后保持完整JSON/`xmin`不变；post-fix review发现并关闭resume Store未锁terminal
    `startedAt`的P1，child/parent wrong-start typed negative均在mutation前拒绝且全库digest不变；
    Java/API review保留的failure terminal taxonomy P2也已以matching `SUCCEEDED` child与`BLOCKED`
    parent Red关闭：typed boundary只接受exact `FAILED`，两项均以固定reason在mutation前拒绝，随后同一
    claim仍合法推进head15/head17；
  - [x] V13 local-only中文
    [Build Note](../operations/build-notes/2026-08-11-s2-s4-pack010-bounded-provider-validation-attestation.md)；
  - [x] DeepSeek V4 Flash Responses dormant compatibility probe：按官方Responses shape构造独立
    `deepseek-v4-flash`单请求probe，固定no retry/no redirect、strict structured output、wire
    `reasoning.effort=none`且final validator只接受zero observed reasoning items/tokens、usage arithmetic、
    dated post-response list-price profile与raw/credential privacy；actual
    shaded JAR包含production probe但不含test Main/Test，first-party shipping consumer为0。最终独占root
    Gate为146 XML/831 tests/0，9-file aggregate与app JAR已冻结在
    [DeepSeek compatibility Build Note](../operations/build-notes/2026-08-11-s2-s4-deepseek-v4-flash-responses-compatibility-probe.md)；
  - [x] V14 exact provider profile assertion foundation：forward-only保留V13 canonical bytes与nano-USD
    语义，新增独立provider/transport/parser/schema/model-resolution/pricing profile、pico-USD精确费率、
    `emergeos_provider_attestor_v14`零relation ACL与只读SECDEF assertion。synthetic admin fixture的
    wrong provider/profile/pricing/rate/cost在PostgreSQL内`55000`，missing/extra为`22023`、wrong role为
    `42501`；valid `100/20/10` usage精确返回`14,056,000` pico-USD，全部assertion保持public-table
    JSON/`xmin`、seq13/head13与V13 row不变，且V13 raw TX-A在assert前后都继续fenced。Java与shipping
    JAR consumer为0。该子切片仅为`PROFILE_ASSERTION_ONLY`，opaque graph hashes只进入statement hash，
    不被DB验证为graph truth；设计边界见
    [RFC-0009](../rfcs/0009-exact-provider-profile-assertion-foundation.md)与
    [ADR-0013](../architecture/decisions/0013-exact-provider-profile-assertion-foundation.md)，最终Receipt见
    [V14 Build Note](../operations/build-notes/2026-08-11-s2-s4-pack010-v14-exact-provider-profile-assertion.md)；
  - [x] V15 exact provider TX-A requirement guard：forward-only保留V8-V14 relation/canonical identity，
    在exact seq13/head13登记`PICO_OVERLAY_V1` requirement，并由四个deferred constraint trigger阻止
    已登记attempt继续写历史V8/V13 request-2 attribution/event/head。独立V15 role只有require
    function EXECUTE且relation ACL为0；production Java V15 API/consumer增量为0。legacy typed TX-A与
    valid signed V13 completion都在PostgreSQL提交边界以固定`55000`整transaction回滚，未登记V13
    正向仍到CONSUMED/head14；两个独立`READ COMMITTED` backend又以`pg_blocking_pids`证明
    marker-first与V13-first共享head锁线性化且各自exact one durable winner；V14→V15 populated
    rows/`xmin`、旧function/trigger/history保真，packaged
    unmarked historical crash/restart/race/resume regression继续Green。该子切片仅为
    `REQUIREMENT_GUARD_ONLY`，设计边界见
    [RFC-0010](../rfcs/0010-exact-provider-tx-a-requirement-guard.md)与
    [ADR-0014](../architecture/decisions/0014-exact-provider-tx-a-requirement-guard.md)，最终Receipt见
    [V15 Build Note](../operations/build-notes/2026-08-12-s2-s4-pack010-v15-exact-tx-a-requirement-guard.md)；
    `exactPicoAttribution=NOT_IMPLEMENTED`、`TX-A=NOT_IMPLEMENTED`，不得外推为pre-egress budget、
    DeepSeek live或商业计费证据。最终独占root `clean verify`为11 modules、147 XML、833 tests、
    0 failure/error/skipped/flake；28-file ordered aggregate为
    `61e0c1b4d8297052b678c432e173f3354bda1722ccb5f79a52a2c6238c642ac2`，shipping app JAR为
    `cfd0a29e7b2c683d6461273c93ef6026b151b89985f3bac2cabaca2a733d58cd`；Java/Gate/artifact、
    SQL/ACL/migration与Acceptance/evidence/docs三路post-fix review均为P0/P1/P2=0；
  - [x] V16 exact-pico provider TX-A overlay focused local Gate：按
    [RFC-0011](../rfcs/0011-exact-pico-provider-tx-a-overlay.md)与
    [ADR-0015](../architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md)，当前scope严格为
    `RAW_JDBC_LOCAL_OVERLAY_TX_A`。raw JDBC在同一transaction完成DB-minted stage、test JVM
    ephemeral Ed25519签名/本地验签与commit，候选结果只写独立V16 validation/attribution/event/head
    closure；legacy V8 head保持seq13，V16 overlay head14不是legacy head14。positive commit从
    post-canary baseline起、每条negative fence从immediate baseline起，V8/V13/V15 JSON/`xmin`及
    focused全V1-V15 public-table image保持不变；fixture/status mutation单独记录。synthetic GPT profile只是
    arithmetic precision canary，不是provider定价。PostgreSQL不验证Ed25519，V16 login credential、
    attestor role与caller process在TCB；V16冻结时`productionV16Verifier=0`、`productionJavaApi=0`。
    V17另立dormant public-key verifier primitive，但production stage/commit API、shipping consumer与
    PostgreSQL-native验签仍为0。stage-only、
    bounded tamper、profile/key revoke、challenge expiry、transaction-local SQL rollback与replay已Green；
    root clean 148 XML/834 tests/0、artifact/JAR parity、zero-consumer Gate、hash与独立review均已冻结；
    TX-B/TX-C、pre-egress为
    `NOT_IMPLEMENTED`，process fault/race/restart/live/billing为`NOT_PROVEN`。最终回执见
    [V16 Build Note](../operations/build-notes/2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md)；
  - [x] V17 dormant V16 Ed25519 public verifier focused Gate：按
    [RFC-0012](../rfcs/0012-dormant-v16-ed25519-public-verifier.md)与
    [ADR-0016](../architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md)，只新增production-source
    public-key verifier primitive；raw V16 credential bypass仍在TCB，PostgreSQL-native验签、production
    stage/commit API、shipping consumer、key custody与live均保持0/Red。compiled Acceptance、focused
    Bytecode Gate、actual shaded-JAR、150 XML/839 tests/0 root clean、17-file aggregate与三路review均
    已冻结；最终回执见
    [V17 Build Note](../operations/build-notes/2026-08-12-s2-s4-pack010-v17-dormant-v16-ed25519-verifier.md)；
  - [x] V18 dormant typed V16 stage-verify-commit attestor：按
    [RFC-0013](../rfcs/0013-dormant-typed-v16-stage-verify-commit-attestor.md)与
    [ADR-0017](../architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md)，在单一
    `REQUIRES_NEW` transaction内完成frozen authority recheck、V16 stage、25/62 typed mapping、signer、
    V17 verifier与V16 commit。wrong-key stage1/commit0、post-receipt stage1/commit1 outer rollback、signer
    provenance、relation ACL与global relation/function/helper-grantee/body drift、prerequisite role membership、
    index/rewrite rule/extra trigger、zero-inheritance/rewrite catalog closure、valid overlay14与typed replay均
    focused Green；actual shaded JAR包含全部failure-path nested classes且App consumer/signer implementation为0。
    root clean、artifact/hash与三路review已冻结；最终回执见
    [V18 Build Note](../operations/build-notes/2026-08-12-s2-s4-pack010-v18-dormant-typed-v16-attestor.md)；
  - [x] V19 fresh-JVM verified exact-pico overlay reader：按
    [RFC-0014](../rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md)与
    [ADR-0018](../architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md)，新增专属
    13表SELECT-only role与sealed `Missing / Required / Attributed / Invalid` production reader。
    focused Acceptance已证明无marker、无V13的V15 marker、历史Ed25519 receipt，以及partial、跨表一致但
    canonical stale和伪签名tamper；reader每次在单一RR/RO snapshot独立重算，legacy head保持seq13，
    App consumer为0。actual shaded-JAR parity、158 XML/862 tests/0 root clean、28-file aggregate与三路
    post-fix review均已冻结；最终回执见
    [V19 Build Note](../operations/build-notes/2026-08-12-s2-s4-pack010-v19-fresh-jvm-exact-pico-overlay-reader.md)；
  - [x] V20 PostgreSQL immediate-restart overlay readback：V19冻结后新增单一test-only Acceptance，
    V20冻结时production/schema/provisioning与shipping capability payload delta均为0；README/docs README的V20导航
    增量已单独记账，不伪称V19 frozen 28-path bytes未变。独立keepalive PostgreSQL 18.4 Testcontainer先通过
    V13+V15+V18 typed path形成完整overlay，fresh packaged JVM A为`Attributed`；随后同container ID、
    same `pg_control_system().system_identifier`与PGDATA上的`pg_ctl restart -m immediate -w`返回0，
    旧sentinel connection失效、新physical connection恢复且postmaster start严格前进；fresh packaged JVM B
    以不同OS PID再次为`Attributed`。restart前后及B读后，public table JSON/`xmin`、legacy seq13/no14、
    V16四行与typed receipt十项bounded identity均不变，restart后没有migrate/provision/fixture writer。
    focused `1/0`及post-portability/race recovery后的root `clean verify`均Green；最终为
    `160 XML / 866 tests / 0`，29-file aggregate、
    V19 normalized shipping payload parity与四个artifact hash均已冻结。证据只覆盖本机same-container
    PostgreSQL process restart，不覆盖host/power/storage/HA、connection-loss/reconcile/race、App/Live或current
    authorization；详细回执见
    [V20 Build Note](../operations/build-notes/2026-08-12-s2-s4-pack010-v20-postgres-immediate-restart-overlay-readback.md)；
    post-V20 addendum同时记录：Linux real-TTY时间精度Red已在7个production durable sinks及3个test-harness
    callsites修复，expiry比较仍用raw clock；offline cooperative writer的safe partial claim现在只会
    `COMMIT_INCOMPLETE`，稳定读取的malformed/unsafe metadata仍INVALID；孤立claim在读中发生identity/size
    变化只会保守UNKNOWN且不能获得publish authority。timestamp修复后的首轮Linux CI又暴露
    `session-intent-expired`的1秒test-only窗口会在durable intent落库前先过期；该场景现使用3秒、仍等待
    `TTL + 150ms`，并强制`PROVIDER_SESSION_INTENT_DURABLE`先于精确expiry rejection。focused连续3次、
    Store suite `61/0`与root clean均Green。随后Linux run `31609813991`（head `39b6267`）在4个graph-eval
    Acceptance中揭示test-only bridge收到sub-micro expiry并被production exact read-back正确回滚；确定性`+1ns`
    Red后，bridge改为事务前固定fail-fast，16个positive host-clock callers与默认synthetic binding均显式截断到
    `MICROS`，两个DB-clock callers保持不变。focused Acceptance `4/0`、全部bridge consumer `19/0`与最终
    `160 XML / 866 tests / 0` root clean已Green；精确code HEAD `c01bead266eea4ae8122e3e59c2faece3d2921af`
    又在GitHub Actions run `31614258555`完成11/11 modules `BUILD SUCCESS`，此前4个graph-eval错误均关闭。
    随后仅同步该结果的docs-only commit不反向冒充已被此run测试。当前只扩大Engineering证据，
    没有App wiring、provider egress或Authority/Live上调；
  - [ ] DeepSeek live PASS与durable multi-provider TX-A：本轮唯一真实请求在intermediate bytes上
    fail closed为`RESPONSE_METADATA_MISMATCH`，没有retry/redirect或第二请求，具体不兼容字段、billing与
    provider retention均未知。下一次请求必须使用owner经隐藏stdin重新提供的rotated credential；未来
    版本仍需exact pico-priced graph attribution、challenge/transcript/signature/commit与pre-egress budget
    authority，不能把V14 profile assertion或本地compatibility Green写成attestation/live PASS；
- [x] 本 offline terminal/Harness engineering slice的中文
  [Build Note](../operations/build-notes/2026-08-01-s2-s4-pack010-terminal-graph-harness-report.md)；
- [x] owner TTY / predecessor authority子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-owner-tty-predecessor-authority.md)；
- [x] V9 terminal authority / packaged successor子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-v9-terminal-authority.md)；
- [x] dormant provider capability / loopback ordering子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-dormant-provider-capabilities.md)；
- [x] production capability handoff / role provisioning子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-capability-handoff-provisioning.md)；
- [x] exact provider response attribution子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-exact-provider-attribution.md)；
- [x] terminal outcome binding子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-terminal-outcome-binding.md)；
- [x] successful typed TX-C capability子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-typed-tx-c.md)；
- [x] adapter-owned canonical terminal transition子切片的中文
  [Build Note](../operations/build-notes/2026-08-02-s2-s4-pack010-canonical-terminal-transitions.md)；
- [x] V10 attributed failure authority / exact provisioning pair子切片的中文
  [Build Note](../operations/build-notes/2026-08-09-s2-s4-pack010-v10-attributed-failure-authority.md)；
- [x] durable attributed failure outcome / resume fencing子切片的中文
  [Build Note](../operations/build-notes/2026-08-09-s2-s4-pack010-durable-attributed-failure-resume.md)；
- [ ] owner逐次授权的真实 r1/r2/r3。该项不因工程完成而自动勾选。
