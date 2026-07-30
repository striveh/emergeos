# ExecPlan: Stage 2 AgentKernel and Eval-Driven Development

状态：进行中；S1、S2 工程完成，S3 protocol adapter loopback Green，下一步是
synthetic Eval runner 与默认 zero-egress packaged preflight

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

At the S1 baseline the repository had stable `TaskEnvelope`, `ResultEnvelope`
and `HarnessRunBundle` contracts, but no executable AgentKernel, model port,
tool registry, tool loop, Agent Trace or evaluation runner. S1 now adds the
bounded Fake Agent product loop described below. It still has no real-model
adapter, persistent run/Trace binding or evaluation runner. The older
`TemplateArtifactGenerator` remains deterministic Stage 0 scaffolding.

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
  provider result。RFC-0002 仍为 Proposed；只有 compiled synthetic catalog、
  one-shot operator permit、packaged zero-egress runner、safe receipt 与 Eval runner
  独立最终审查完成后，才可能请求 owner 批准一次 bounded smoke。

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
  与 2 个 synthetic Task Packs；`./scripts/verify-doc-links.sh` 通过 67 个 Markdown files；
  `apps/api` dependency tree 不含 `com.openai:*`；`git diff --check` 通过。
- 这些回执仍全部是离线/loopback evidence，不是 live provider receipt，也不证明真实模型
  质量、账单金额或产品价值。

### S4 · Harness comparison, faults and bounded handoff

- Compare a minimal H0 loop with one H1 Harness variable at a time.
- Inject tool schema error, timeout/rate limit, Context Drift and Prompt
  Injection.
- Add one typed, read-only Worker handoff only after the single-Agent baseline:
  no shared mutable Artifact, one Conductor writer, maximum two Workers.
- Compare verified outcome, edit time, cost and latency across repeated runs.
- Evaluate AgentScope, Pi or another candidate only as a replaceable adapter;
  retain it only if the fixed Harness evidence justifies the added surface.

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
hash-chain Trace 与 HarnessRunBundle 可以跨 JVM verified read。仍没有 durable
checkpoint/resume、real model 或 stochastic Harness experiment。

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
或外部动作，也能形成持久、完整性绑定、可离线重执行的 AgentRun truth。最终 Merge
Gate 已在稳定快照中重复全部证据。下一条可证伪假设进入 S3：
一个 real provider adapter 能否在保持相同 Task/Trace/Result 边界、默认零 live call
和无 credential 落盘的前提下，产生固定 baseline。学习与市场工作仍暂停且未完成。
