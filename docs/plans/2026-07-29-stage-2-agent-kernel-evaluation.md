# ExecPlan: Stage 2 AgentKernel and Eval-Driven Development

Status: active; S1 engineering complete, S2 not started

Owner: project owner + main Codex agent

Entry gate: Stage 1 engineering is complete, but its human-learning and market
decisions remain open. On 2026-07-30 the owner explicitly paused the learning
plan and authorized the Stage 2 technical lane to proceed while Stage 1 remains
incomplete. This is the Roadmap exception; it does not convert missing human,
market or stale-`DISPATCHING` evidence into completed Gates.

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

### S3 · One real model adapter and fixed baseline

- Verify exact provider/API behavior from installed versions and official
  documentation at implementation time.
- Add one adapter behind the same AgentKernel port; keep the scripted Fake.
- Fix model, Task Pack, tools, budgets and repetitions before running the
  baseline.
- Record resolved model version, tokens, cost, latency and failure attribution.
- Do not place credentials, prompts containing private data or live responses
  in the repository.

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

## Surprises and failures

- The current public contracts have more fields than the executable system:
  they must not be mistaken for a working Harness.
- `HarnessRunBundle` does not directly bind a result/run in the way repeated
  experiments may require. S2 must prove the need and use an RFC before changing
  the public schema.
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

## Verification receipts

- Acceptance Red/Green and the focused Green commands are recorded in Progress.
- S1 adds no contract fixture, migration, runtime framework, model SDK,
  credential or external action. PostgreSQL Capture and Artifact truth are
  reused.
- Independent review closed at `P0=0`, `P1=0`, `P2=0`.
- `./mvnw --batch-mode --no-transfer-progress verify` passed 98 tests: Core 28,
  in-memory 19, PostgreSQL 22, API unit 22 and packaged integration 7.
- After tightening the process assertions, the focused packaged
  `AgentDraftHttpIT` verification passed again with a confirmed forced stop and
  distinct restart PID.
- `verify-contracts.sh` passed 4 Schemas, 8 fixtures and 1 Task Pack;
  `verify-doc-links.sh` passed 63 Markdown files; `git diff --check` passed.

## AI Coding receipt

Two read-only agents inspected the proposed behavior and code gaps before Red.
Implementation then followed packaged Acceptance Red → focused Red → minimum
Green → real-process Green → adversarial review → regression Green. Two
independent read-only review passes closed at `P0=0`, `P1=0`, `P2=0`.

## Agent Engineering receipt

The executable mechanism is now bounded: model decision, registry plus Task
authorization, owner-scoped tool result, structured proposal, deterministic
evidence validation, Artifact commit and safe Trace projection. Cancellation
and deadline checks occur between steps. There is still no durable checkpoint,
real model, stochastic evaluation or persistent Trace.

## Career receipt

The repository now contains a replayable tool-loop implementation, failing/green
acceptance and safe Trace evidence. Owner-led diagnosis, no-notes explanation
and framework trade-off defense remain deferred with the paused learning plan.

## Business receipt

No new interview, real Seed, reuse, price request or payment was created by
this slice. Those Stage 1 facts remain open.

## Outcome and next hypothesis

The first Agent slice now works without a framework: one Fake Model, one read
tool, one validated structured draft, a PostgreSQL Artifact and zero external
effects. The next falsifiable hypothesis is S2: a persisted, integrity-bound
run/Trace and `HarnessRunBundle` can improve replay and failure attribution
without letting SDK types or model proposals own product truth. Learning and
market work remain paused and incomplete.
