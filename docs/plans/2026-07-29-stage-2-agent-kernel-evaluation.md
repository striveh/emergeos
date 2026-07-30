# ExecPlan: Stage 2 AgentKernel and Eval-Driven Development

状态：进行中；S1、S2 工程完成，S3 protocol adapter、isolated synthetic Eval Runner
与本地 attempt durability 工程切片已通过；live-provider smoke 尚未执行。S4 已冻结
首个 Verifier comparison foundation，并完成 strict Pack loader；12 个 shared
candidates / 24 次 verifier evaluation 尚未执行

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
Eval Runner。S3 现在另有 production read-only journal verifier 与 7-point fat-JAR
process-kill/restart evidence。S4 Task Pack 004 已冻结 reference-grounding Verifier
对照的输入和 expected matrix；独立 offline module 已能以 fixed path、raw hash 与
exact semantics 加载它，但还没有 candidate generator、comparison runner 或 24-run
result。当前仍没有 live-provider smoke、real model result、billing receipt 或
stochastic Harness comparison。较早的 `TemplateArtifactGenerator` 仍是确定性的
Stage 0 scaffolding。

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
  校验，再用 `ATOMIC_MOVE` 发布 `{attemptId}.run.json` 并 `fsync` 目录。它原子发布完整
  synthetic AgentRun、Artifact、Bundle、observed usage/cost 与 effects counter；它不与
  provider 调用形成事务，也不替代 S2 PostgreSQL product `AgentRun`。
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

### S4 · Harness comparison, faults and bounded handoff

- Compare a minimal H0 loop with one H1 Harness variable at a time.
- Inject tool schema error, timeout/rate limit, Context Drift and Prompt
  Injection.
- Add one typed, read-only Worker handoff only after the single-Agent baseline:
  no shared mutable Artifact, one Conductor writer, maximum two Workers.
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
- Loader 只完成可信 preflight；candidate generator、24 次 verifier evaluation、
  comparison report、report verifier 与 durable report store 尚未实现。

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
- [x] 2026-07-30：Task Pack 004 与 repository validator 冻结首个
  reference-grounding Verifier comparison foundation；24-run experiment 未执行。
- [x] 2026-07-30：commit `623abf3` 增加独立 strict Pack loader。两项原始 P1 与三轮
  adversarial follow-up 已转为 executable Red/Green，独立最终复审
  `P0=0、P1=0`；该结果仍不等于 comparison 已执行。

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
- Process-kill matrix 使用 observer 在 7 个选定 durable operation 后停住进程，因此能
  精确验证这些 boundary，却不是 arbitrary-instruction 或物理断电测试。Path 安全检查仍有
  same-UID TOCTOU；marker body 不参与验证；test launcher 也尚未逐个检查 production
  class 的 CodeSource。
- Task Pack 004 的 24-run totals 是执行前冻结的 expected matrix。Strict loader
  Green 只证明输入可信；没有 run artifact 与 report verifier 时，不能把它写成
  Harness comparison 已通过。

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

- 实现、frozen assets、one-shot POSIX Gate、attempt journal、atomic local run record
  与 loopback fault tests 已形成；read-only journal verifier 与 7-point
  process-kill/restart matrix 也已形成；
- 独立安全审查为 `P0=0、P1=0`；
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
  [S3 Durable Attempt Build Note](../operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)。

S4 当前完成 Task Pack 004、repository validator 与独立 strict Pack loader。它已经
拒绝任意 Pack path、JSON/hash/semantic drift、非 allowlist dependency 及已知 JDK
network/process bytecode reference；2 arms × 4 cases × 3 repetitions 所代表的
12 个 shared candidate generations / 24 次 verifier evaluations 尚未执行，expected
totals 不能充当 experiment receipt。Loader 证据见
[S4 Offline Comparison Loader Build Note](../operations/build-notes/2026-07-30-s2-s4-offline-comparison-loader.md)。

## AI Coding receipt

本切片继续使用单 writer + 多个 read-only reviewer。流程是 packaged Acceptance
Red → focused Red → minimum Green → real-process Green → adversarial review →
regression Green。审查不是“看代码觉得可以”，而是持续提交可复现 P1：ghost
success、Trace 配对、SafeText parity、typed binding 和 DB numeric evidence，再由
主线逐项转成测试与不变量。

## Agent Engineering receipt

当前机制已经从 inline summary 进化为 durable Agent execution truth：server-owned
Task、adapter outcome、Trace state machine、owner-scoped Evidence、structured
proposal、deterministic verification、atomic Artifact commit、Result、safe
hash-chain Trace 与 HarnessRunBundle 可以跨 JVM verified read。S3 已形成 real-model
protocol adapter 与受控 synthetic execution path，但尚无 live result；仍没有 durable
checkpoint/resume 或 stochastic Harness experiment。

## Career receipt

仓库现在能展示一个生产级面试故事：为什么“模型声称读过”不是 Evidence、为什么
allowlist 不是 Trace state machine、怎样防止 ghost success，以及怎样用 PostgreSQL
transaction/FK/CAS 与跨语言 golden hash 形成可审计 Run。owner-led diagnosis、
脱稿讲解和 framework trade-off defense 随暂停的学习计划继续延期，不虚报已掌握。

## Business receipt

本切片没有新增访谈、真实 Seed、复用、报价、付款或收入证据；这些 Stage 1 商业事实
仍然开放。

## Outcome and next hypothesis

S2 的工程假设已在 focused evidence 中成立：不引入 Runtime framework、real model
或外部动作，也能形成持久、完整性绑定、可离线重执行的 AgentRun truth。S3 adapter 与
bounded runner 与本地 durability engineering slice 已通过。下一条自动执行的安全假设是：
以 Task Pack 004 实现最小 Verifier seam 与 isolated comparison runner，真实执行并重新
验证 24 个 deterministic runs；它仍不读取 credential、不访问 live provider。唯一一次
bounded live-provider smoke 仍由 owner 另行批准。没有 live receipt 时不得声称已有
real-model fixed baseline；即使执行 smoke，也不能由一次结果证明模型质量、账单准确性或
产品价值。S4 的 offline Harness/fault infrastructure 可以继续研发，但任何 live experiment
仍保持 Gate closed。学习与市场工作仍暂停且未完成。
