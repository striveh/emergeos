# ExecPlan: Stage 1 Durable Correctness

Status: active

Owner: project owner + main Codex agent

Started: 2026-07-28

Review shape: four weekly checkpoints, not a promised delivery date

## Five-outcome alignment

- AI Coding: practice vertical slicing, outside-in TDD, bounded delegation and adversarial review.
- Agent Engineering: reach L3 in two mechanisms only: transactional concurrency and recoverable external
  action.
- Product/Production: a captured thought, its revision and an approved simulated action survive restart
  without losing ownership or silently duplicating the result.
- Career: produce a five-minute failure-recovery demo, Case Card and independently scored teach-back.
- Business: start founder dogfooding, problem interviews, Concierge delivery and one real price request on
  Day 1 rather than after the infrastructure is complete.

## Context and user result

The current prototype proves domain semantics in one process. Its in-memory repositories hide transaction,
restart and multi-instance failure. Stage 1 replaces those assumptions through four independently useful
vertical slices while a separate market lane tests whether the proposed outcome is worth building.

Desired user result:

> I can capture a thought with little friction, revise it and approve a local simulated action. A duplicate
> request or process restart does not lose my latest content, authority or the explanation of what happened.

Stage 1 proves capture, revision, approval, persistence and recovery. It does **not** prove that generated
content “像本人” or is worth paying for. Those claims require real Seeds and human + Codex Concierge
delivery in the market lane; `TemplateArtifactGenerator` is not evidence of personalization value.

Non-goals:

- real platform Connector or public publishing;
- Temporal integration;
- multi-tenant cloud authentication;
- AgentKernel or product model generation;
- streaming voice, VAD or generative UI;
- generalized Outbox/Inbox without an asynchronous event requirement;
- a normalized, comprehensive Working Self graph;
- a generic metrics platform or reversible down-migrations.

## Execution model

Three lanes start together. Work in progress is limited to:

- one engineering slice;
- one market experiment;
- one human learning proof.

An engineering slice must finish:

```text
Acceptance Red
→ Focused Red
→ minimum schema and implementation
→ integration/fault evidence
→ independent review
→ Receipt
```

Do not create all schemas or all failing tests up front. Do not begin the next engineering slice while the
current slice lacks its Receipt.

## Lane A · Four engineering slices

### S1 · Restart-safe Capture

User result: submit a text, link or voice-file reference through one thin capture adapter and retrieve the
same owned Capture after a new application process starts.

- Time-box a 2–4 hour choice between a same-device loopback-only local form and a trusted local OS shortcut;
  implement one. LAN/mobile access waits for authentication and is not part of Stage 1.
- Define `clientNonce + requestHash` replay/conflict semantics.
- Add an outside-in HTTP failure test, then the smallest focused domain/adapter tests.
- Add only the PostgreSQL tables and Flyway migration needed by this result.
- Derive principal only from server configuration, never a client Header/Body. Enforce owner-scoped reads and
  uniqueness in the database; test isolation with two application instances configured as different principals.
- Refuse non-loopback binding in this unauthenticated stage.
- Run the packaged application against PostgreSQL, stop it, start a new process and retrieve the Capture.

Receipt:

- same nonce + same request returns the original Capture;
- same nonce + different request returns an explicit conflict;
- another principal receives the same response shape as a missing Capture;
- the captured value survives a real process restart.

#### S1 slice delta · 2026-07-28

- Observable result: `POST /api/v1/captures` accepts one synthetic text, link or voice-file
  reference and `GET /api/v1/captures/{id}` returns the same owner-scoped Capture after the
  packaged application is killed and a different JVM starts against the same PostgreSQL.
- First Red: a Failsafe HTTP acceptance starts the packaged jar and PostgreSQL as independent
  processes, then expects create/read/restart/read. Before implementation it must fail at the
  missing Capture HTTP behavior rather than at build, container or application startup.
- Owned files: the minimal Capture types/port in Core, one `adapters/postgres` module and migration,
  thin API wiring and S1 tests/docs. Existing Manifestation repositories remain in-memory; S2/S3
  persistence is out of scope.
- Predicted failures: check-then-insert races create two results; client-controlled identity or hash
  defeats isolation/idempotency; an in-JVM context rebuild falsely appears to prove restart safety.
- Fault cases: same nonce/same hash replays the committed original; same nonce/different hash is a
  non-leaking conflict; foreign-owner and missing reads are indistinguishable; wildcard/non-loopback
  binding fails before the web server starts.
- Receipt target: Red summary, database-backed focused tests, two distinct application PIDs, dual
  configured principals sharing one database, review findings, and exact verification commands.

### S2 · Conflict-safe Revision

User result: revise an Artifact without silently overwriting a newer revision.

- Add Artifact lineage and expected base version/hash to the API and schema.
- Use compare-and-swap rather than a read-then-write check.
- Race two application instances against the same base version.
- Return an explicit conflict with enough safe information for a later user choice.

Receipt: exactly one revision wins, the loser is observable as a conflict, lineage remains intact and the
result survives restart.

#### S2 slice delta · 2026-07-28

- User result: starting from one owned, PostgreSQL-backed S1 Capture, create Artifact v1 through
  `POST /api/v1/artifacts`, then revise it through `PUT /api/v1/artifacts/{artifactId}` with
  `expectedBaseVersion + expectedBaseHash`. `GET /api/v1/artifacts/{artifactId}` returns the
  owner-scoped lineage. This is a separate durable Artifact boundary; the Stage 0 Manifestation,
  ActionPlan, approval and Reflection graph remains in memory.
- First Acceptance Red: add a Failsafe `ConflictSafeRevisionHttpIT` that starts one PostgreSQL and
  two separately packaged application processes configured as the same principal, creates the
  owned Capture and Artifact v1 over HTTP, then releases two concurrent revisions against the
  same base. It expects exactly one success and one dedicated, non-leaking `409`, kills both
  applications, starts a different JVM and verifies the exact v1 → v2 lineage. Before production
  implementation, the test must reach healthy S1 applications and fail at the missing Artifact
  HTTP behavior, not at packaging, PostgreSQL, startup or Capture.
- Predicted failures: a read-then-insert sequence lets both writers create v2; checking only version
  accepts the wrong base hash; advancing a mutable head separately from immutable lineage leaves
  split state on failure; classifying a failed CAS before an owner-scoped lookup leaks foreign
  existence; returning a proposed timestamp instead of the database row changes the lineage after
  restart; rebuilding two services in one JVM does not prove process concurrency.
- File ownership: main thread only. S2 may add the minimum framework-neutral Artifact lineage,
  command/result/service and port in Core; one forward-only V2 Flyway migration and thin SQL adapter
  in `adapters/postgres`; one Artifact HTTP controller, dedicated conflict mapping and S2 tests in
  `apps/api`; and S2-specific docs/receipts. Existing S1 Capture code changes only for shared test
  support or a demonstrated regression fix. No AgentKernel, Temporal, outbox, model, UI,
  authentication, Connector or horizontal Manifestation repository work is owned.
- Fault/receipt target: the database predicate contains principal, artifact ID, expected current
  version and expected current hash; two independent application PIDs produce one v2 and one
  explicit conflict without exposing content; owner-scoped foreign and missing lookups remain
  indistinguishable; a new application PID returns exactly two immutable versions whose v2
  `baseVersion/baseHash` points to v1; S1 packaged restart and all repository checks remain green.

### S3 · Recoverable local Action

User result: approve a simulated action and understand whether it completed even when the response is lost.

- Persist an `ActionAttempt` before dispatch with the minimal states required by the fault matrix.
- Enforce unique `(connector, accountRef, idempotencyKey)`.
- Run Fake Provider as an independent process/container with persisted idempotency state; it must not share
  the application JVM or memory.
- Simulate provider success followed by response loss and process termination.
- Actually terminate the application JVM, start a new service process and reconcile against the still-running
  provider by idempotency key.

Receipt: the Fake Provider exposes one provider-observable **simulated object**, the application has one
successful Receipt after a real application-process restart, and the transition history explains
`UNKNOWN → RECONCILING → SUCCEEDED`. This is not claimed as a real-platform result.

#### S3 slice delta · 2026-07-28

- Baseline and user result: start from clean `main@6c5713bcb883777f759f879c46b42c51c45cb8ca`.
  From one current, owned S2 Artifact, an explicit loopback-only local approval creates a durable
  ActionPlan and exact Capability, persists an ActionAttempt before any provider call, and returns
  an inspectable outcome. If a separate simulated provider creates its object but loses the
  response, killing the application and starting a new JVM must reconcile the same idempotency key
  into one successful Receipt without creating a second simulated object.
- State machine:
  `PLANNED --atomic capability-use claim--> DISPATCHING`;
  `DISPATCHING --> SUCCEEDED | FAILED | UNKNOWN`;
  `UNKNOWN --atomic capability-use claim--> RECONCILING`;
  `RECONCILING --> SUCCEEDED | FAILED | UNKNOWN`.
  `SUCCEEDED` and `FAILED` are terminal; `UNKNOWN` is a durable holding state and the only automatic
  recovery entry is reconciliation. A response timeout without a provider identifier returns to
  `UNKNOWN`; it must not create a Receipt or infer success. Every transition and capability-use
  count is PostgreSQL-canonical.
- Capability boundary: the persisted grant must bind the configured principal, exact ActionPlan ID
  and hash, configured connector audience and account, current Artifact hash, idempotency key and
  expiry. Provider-call budget is persisted and consumed by an atomic database predicate before
  dispatch or reconciliation; two service instances cannot spend the same use.
- First Acceptance Red: add a Failsafe `RecoverableLocalActionHttpIT` that starts one PostgreSQL,
  an independent Fake Provider process with file-backed idempotency state, and two separately
  packaged application JVMs. It creates the S1 Capture and S2 Artifact over HTTP, configures the
  provider to create once and drop the first response, then races the same local-action request
  through both applications. Before S3 production code exists, the test must reach healthy
  provider/app processes and fail only because
  `POST /api/v1/artifacts/{artifactId}/actions` returns `404`.
  After Green it must observe one durable attempt/object, kill both applications, start a new JVM,
  reconcile `UNKNOWN → RECONCILING → SUCCEEDED`, and read one Receipt.
- Predicted failures: dispatch before the PLANNED commit loses the recovery anchor; a Java lock or
  read-then-increment budget lets two JVMs call the provider; blind retry after response loss creates
  two objects; treating timeout as success fabricates a Receipt; checking only Capability ID misses
  audience/account/plan/hash/key/expiry substitution; state and Receipt in separate transactions can
  split completion; an unscoped conflict lookup leaks a foreign attempt; an in-JVM fake or memory-only
  provider state falsely appears to prove crash recovery.
- File ownership: the main thread is the only writer. S3 may add only the framework-neutral local
  ActionPlan/Capability/Attempt/Receipt state and ports in Core; one forward-only V3 migration and
  thin SQL adapter in `adapters/postgres`; one non-production HTTP adapter for the simulated provider;
  one loopback-only Action API, packaged-process fixture/tests and S3 documentation. Existing S1/S2
  files change only for demonstrated migration/test cleanup or wiring. No Temporal, real Connector,
  generic outbox, AgentKernel, model, UI, authentication, public listener or real user data is owned.
- Fault/receipt target: PostgreSQL enforces exact
  `(connector, account_ref, idempotency_key)` uniqueness and current Artifact provenance; the
  independent provider and application have distinct PIDs and storage; two app instances spend one
  initial provider call; the provider state file contains exactly one simulated object; after both
  application PIDs die, a new JVM creates exactly one Receipt by reconciliation. Audience, account,
  plan, hash, key and expiry mismatches fail before provider access; a no-object timeout remains
  `UNKNOWN`; foreign and missing GET/reconcile use the same response shape. A fresh V3 install and a
  V2 database containing S1/S2 data upgraded to V3 both pass, followed by all S1/S2 regressions.

### S4 · Operating and interview evidence

User result: another developer can run the durable flow and distinguish readiness from failure.

- Verify fresh install and upgrade from the previous schema.
- On incompatible migration, fail fast; demonstrate backup restore or a forward-fix path instead of
  promising a universal down-migration.
- Add database/migration readiness and only the structured events needed to explain the fault matrix.
- Package the HTTP demo, sanitize its trace, update run instructions and record exact limitations.
- Complete an independent production review and resolve all P0/P1 findings.

Receipt: repeatable demo, deterministic verification, migration/recovery evidence, Case Card and Build Note.

#### S4 slice delta · 2026-07-29

- Baseline and user result: start from clean
  `main@b64f90316ffe15d4b4c166df18ed0c41e5ad878f`. A developer starting from a clean
  checkout must be able to run one loopback-only, synthetic HTTP demo and tell apart a serving
  application (`READY`), an unresolved Action (`UNKNOWN`) and a reconciled Action
  (`SUCCEEDED`). The demo and runbook must name the exact recovery entry and must not imply a real
  Connector, provider-call exactly-once or production readiness.
- Evidence correction and scope decision: two independent read-only audits reproduced that
  `RecoverableLocalActionHttpIT` releases the provider, waits for both requests and durable
  `UNKNOWN`, and only then kills the application JVMs. It therefore does not satisfy ADR-0004's
  provider-success-before-local-outcome crash window. Safe stale-`DISPATCHING` takeover would
  require PostgreSQL-canonical ownership/fencing, database time, expiry/heartbeat semantics,
  recovery authority, atomic budget rules and false-takeover/concurrency tests. That is a new
  runtime slice, not S4 operating evidence. S4 will not add it; ADR-0004 returns to `Proposed`,
  and the real-Connector Gate remains closed.
- State/operating model: the S3 state machine remains
  `PLANNED → DISPATCHING → SUCCEEDED | FAILED | UNKNOWN` and
  `UNKNOWN → RECONCILING → SUCCEEDED | FAILED | UNKNOWN`.
  S4 adds no transition. Operational readiness reports database and migration truth plus
  owner-scoped unresolved counts. `UNKNOWN` has the existing explicit reconcile entry;
  `DISPATCHING` and `RECONCILING` are visible but S4 does not pretend they have a safe stale
  takeover entry.
- First Acceptance Red: extend the packaged-process
  `RecoverableLocalActionHttpIT`, which already owns real PostgreSQL, an independent file-backed
  Fake Provider JVM and separately packaged application JVMs. Spring Boot 4.1 already exposes
  `GET /actuator/health/readiness`; before production implementation the request must return the
  built-in response but fail the test because the owner-scoped `stage1Durability` component and
  its database/migration/Action truth are absent. After Green the same outside-in scenario must
  observe initial `UP`, `OUT_OF_SERVICE` while `DISPATCHING`/`UNKNOWN`, and an exact
  `SUCCEEDED` Action after reconciliation. The earlier no-object `UNKNOWN` intentionally keeps
  final operational readiness out of service; S4 must not hide it merely to print a green ending.
- Predicted failures: a readiness endpoint that only mirrors process liveness hides database or
  migration failure; treating `UNKNOWN` as application-down makes its recovery API unusable;
  ignoring `DISPATCHING` repeats the ADR evidence gap; a global count leaks another configured
  principal; querying tables before Flyway completion creates startup races; a migration rehearsal
  that checks only table existence misses content/hash/lineage/attempt/Receipt drift; a same-cluster
  object reconstruction is not backup restore; a static trace with real paths, credentials or
  process-specific identifiers is not a sanitized clean-checkout demo.
- Migration and recovery evidence: rehearse populated V1 → current, populated V2 → current,
  populated V3 → current and fresh current install against PostgreSQL 18.4. Compare stable S1
  request/content hashes, S2 head and complete lineage, and S3 plan/artifact hashes,
  transitions/budget/Receipt before and after. Corrupt an applied migration checksum and prove the
  packaged application fails before readiness. Run a real PostgreSQL logical backup, inject
  destructive synthetic data loss, restore into a clean database/schema, measure the observed
  recovery interval and re-run the stable-data assertions. No universal down migration is promised.
- File ownership: the main thread is the only writer. S4 owns one thin PostgreSQL operations probe,
  one loopback HTTP readiness boundary, targeted PostgreSQL/package-process tests, one replay
  script/runbook and S4 evidence documents. Existing V1–V3 schema and S1–S3 behavior change only
  for demonstrated test/evidence correction. S4 owns no lease runtime, Temporal, real Connector,
  AgentKernel, model, UI, authentication, LAN/public listener, generic outbox/metrics platform or
  real user data. Read-only subagents own only audit, test design and final production review.

## Lane B · Product and commercial discovery

This lane starts on Day 1 and does not wait for PostgreSQL.

1. Keep a 14-day Founder Seed Log: capture time, source, intended outcome, workaround, elapsed time and
   abandonment reason. Private content stays outside the public repository.
2. Conduct 2–3 recent-behavior problem interviews per week until at least 10 are complete.
3. Ask at least 3 target users to provide a real Seed and observe whether they return with another one or why
   they do not.
4. Use human + Codex Concierge to turn selected real Seeds into the proposed “有来源、像本人、可审阅母稿”.
   Label this as a manual service, not product automation.
5. Make at least one explicit price request. Payment and refusal are both evidence.
6. Decide `continue / narrow / pivot` for the user segment and first output type.

External recruitment, interviews and real Seeds follow the
[Research Data Protocol](../business/research-data-protocol.md): default no recording, pseudonymous IDs,
minimum collection, private storage, fixed deletion dates and aggregate-only public evidence.

Every two engineering slices require at least one completed user or price experiment before more
infrastructure scope is added. Seven consecutive days without user contact freezes new infrastructure work.

## Lane C · Human learning and career proof

Stage 1 has only two L3 targets:

1. transaction boundaries, uniqueness and optimistic concurrency;
2. idempotent external action, unknown outcomes and reconciliation.

Architecture, migrations, TDD, Testcontainers and Codex collaboration are implementation tools and target
L1/L2 unless stronger evidence appears.

For each engineering slice:

- before implementation, the owner predicts the state changes, main failure window and first Red;
- the owner reviews the full critical Diff and records a Codex suggestion that was rejected/changed, or
  explicitly records `none` rather than manufacturing disagreement;
- the owner gives a no-notes whiteboard explanation;
- an independent Reviewer designs the fault check, while the owner personally diagnoses at least one
  previously undisclosed failure during Stage 1;
- one week later, the owner answers a variant question or repairs a related variant;
- evidence includes the failing test, fix commit, score and demo/recording location.

Codex output cannot raise the owner’s mastery score. Only unaided explanation, implementation review and
diagnosis can.

## Architecture constraints

- Keep Core framework-neutral; database types stop in `adapters/postgres`.
- PostgreSQL is product truth, not a cache for model context.
- Use explicit transactions, database uniqueness and optimistic versions.
- Persist ActionAttempt before provider dispatch.
- Temporal may later coordinate waiting/retry; it cannot replace constraints or reconciliation.
- Use installed versions and official dependency documentation before selecting exact APIs.
- Default data-access hypothesis: direct SQL through a thin adapter plus Flyway; change only if a short spike
  demonstrates a clearer fit without hiding transaction semantics.

## Fault matrix

Before each slice begins, its short inline delta in this ExecPlan or linked Issue must enumerate:

| Fault | Allowed terminal/holding state | Recovery entry |
|---|---|---|
| duplicate Capture, same hash | original Capture returned | nonce lookup |
| duplicate Capture, different hash | conflict | new nonce or explicit user decision |
| two writers, stale base | one winner, one conflict | reload latest Artifact |
| provider timeout, outcome unknown | `UNKNOWN` | reconcile by idempotency key |
| independent provider success, response lost and local `UNKNOWN` committed | `UNKNOWN`, then `SUCCEEDED` | provider lookup + Receipt commit after app restart |
| independent provider success, app killed before any local outcome commit | unresolved `DISPATCHING`; real Connector Gate blocked | future fenced stale-claim design; no S4 auto-recovery |
| incompatible migration | application not ready | backup restore or forward-fix |

No Gate may use “explainable” without mapping the observed condition to one of these states and a recovery
entry.

## Checkpoints

These are review points, not schedule promises:

- Week 1: S1, Founder Log active, first 2–3 interviews and first learning prediction.
- Week 2: S2, at least one real Seed/Concierge delivery and first no-notes teach-back.
- Week 3: S3, a price request and owner-led diagnosis of the undisclosed fault.
- Week 4: S4, 14-day log, 10 interviews, delayed variant test and `continue / narrow / pivot`.

If a slice slips, do not expand scope. If the commercial wedge is rejected, retain the engineering and career
evidence but choose a new vertical user result before Stage 2.

## Stage gate

Engineering:

- S1–S4 Receipts are reproducible from a clean checkout.
- The unauthenticated adapter remains loopback-only; identity comes from server configuration, with
  cross-principal isolation proven by separately configured instances.
- Fresh install and prior-version upgrade pass; incompatibility fails safely and recovery is demonstrated.
- The provider runs outside the application JVM and persists its state across a real app kill/restart.
- The simulated-action claim is labeled accurately; no real Connector or exactly-once claim is implied.

Product/business:

- 14 complete Founder Log days and 10 recent-behavior interviews;
- at least 3 people submit real Seeds;
- repeat use is observed or each non-return reason is recorded;
- at least one real price request has a payment or refusal result;
- an explicit `continue / narrow / pivot` decision names the segment, output and next falsifiable test.

Learning/career:

- the two target mechanisms have build, break, unaided explain and delayed-variant evidence;
- the owner personally diagnoses one undisclosed failure;
- the demo, Case Card and claims point to commits/tests and state their limits.

## Delegation and ownership

| Role | Bounded task | Write scope | Expected return |
|---|---|---|---|
| Main | Behavior, constraints, first Red, integration and acceptance | Whole slice | Verified Receipt |
| Architecture Researcher | Installed APIs, source and official documentation | Read-only | Sources and trade-offs |
| Test Designer | State table, acceptance/fault cases | Read-only | Executable test design |
| Worker | One accepted vertical slice | One worktree and declared files | Code + targeted tests |
| Production Reviewer | Isolation, race, recovery, migration and claim audit | Read-only | Prioritized findings |

Use at most two parallel read-only explorers by default. The implementation Worker and Reviewer are different
agents. One working tree has one writer; do not split one state model across Schema/Repository/Service writers.
The main Agent owns the final Diff, targeted/full verification and real acceptance. The human owns teach-back.

## Progress

- [x] 2026-07-28: foundation and current failure boundaries documented.
- [x] 2026-07-28: Stage 1 recut into concurrent engineering, market and learning lanes.
- [x] 2026-07-28 14:38 +08:00: confirmed clean `main` at
  `7d1b1576adbe0173c4a46951f606f613cac132f5` before S1.
- [x] 2026-07-28 14:55 +08:00: S1 slice delta and packaged-process HTTP Acceptance Red
  recorded before production implementation.
- [x] 2026-07-28 15:15 +08:00: minimum Core/schema/adapter/API and targeted concurrency,
  packaged restart, dual-principal and loopback fault checks green.
- [x] 2026-07-28 15:24 +08:00: independent production review closed with no remaining
  P0/P1; complete post-review packaged-process Receipt recorded.
- [ ] Lane B Day 1: start Founder Log, interview recruitment and Concierge offer.
- [x] 2026-07-28 15:25 +08:00: S1 restart-safe Capture engineering Receipt complete.
- [x] 2026-07-28 16:05 +08:00: S2 slice delta and file ownership recorded before
  production implementation.
- [x] 2026-07-28 16:13 +08:00: S2 packaged-process HTTP Acceptance Red and
  framework-neutral Core Focused Red recorded before production implementation.
- [x] 2026-07-28 16:23 +08:00: minimum S2 Core/schema/adapter/API, transaction-fault
  tests, two-process CAS, third-JVM restart, owner isolation and S1 packaged regression green.
- [x] 2026-07-28 16:34 +08:00: S2 independent production review closed with
  `P0=0`, `P1=0`; post-review process Receipt and final repository verification passed.
- [x] 2026-07-28 18:01 +08:00: confirmed clean `main` at
  `6c5713bcb883777f759f879c46b42c51c45cb8ca`; recorded the S3 user result,
  state machine, first Acceptance Red, predicted faults and single-writer file ownership.
- [x] 2026-07-28 18:22 +08:00: S3 Acceptance Red and framework-neutral Focused Red
  recorded before production implementation.
- [x] 2026-07-28 18:34 +08:00: S3 minimum Core/schema/adapter/API, migration,
  exact-Capability tests and preliminary multi-process fault Receipt green.
- [x] 2026-07-28 18:43 +08:00: independent review's four P1 findings fixed; focused
  regressions and the packaged response-loss/kill/reconcile scenario rerun green.
- [x] 2026-07-28 18:47 +08:00: independent post-fix review closed the then-known
  implementation findings with `P0=0`, `P1=0`; its ADR-0004 Required-evidence conclusion is
  superseded by the 2026-07-29 S4 crash-window re-audit.
- [x] 2026-07-28 18:47 +08:00: S3 required final verification passed; engineering
  Receipt and focused commit prepared.
- [x] 2026-07-29: S4 baseline and independent ADR-0004 evidence-gap audit recorded;
  stale-`DISPATCHING` runtime expansion rejected and ADR returned to Proposed.
- [x] 2026-07-29 16:49 +08:00: S4 readiness Acceptance/Focused Red and
  owner-scoped packaged-process Green recorded.
- [x] 2026-07-29 17:02 +08:00: populated V1/V2/V3/fresh migration,
  new-database backup restore and incompatible packaged migration fail-fast evidence passed.
- [x] 2026-07-29 17:18 +08:00: S4 clean replay/evidence documents and independent
  post-implementation review complete with post-fix `P0=0`, `P1=0`, `P2=0`.
- [x] 2026-07-29 17:19 +08:00: S4 required contracts, doc links and full Maven
  verification passed.
- [x] 2026-07-29 17:21 +08:00: S4 final Diff/scope checks passed and one focused
  commit was prepared; its Git ID and clean-worktree status are the post-commit task Receipt.
- [ ] Stage gate and `continue / narrow / pivot` decision.
- [x] 2026-07-29: owner rejected further screenshot-evidence questioning as
  off the core Agent learning path and selected strict Gate path A: personally
  diagnose one undisclosed Stage 1 fault and complete one real
  Seed/Concierge/price experiment before Stage 2 code begins.
- [x] 2026-07-29: isolated
  [UNKNOWN reconciliation diagnosis exercise](../learning/exercises/2026-07-29-stage1-unknown-fault.md)
  seeded outside `main`; the focused PostgreSQL test reproducibly fails with
  expected `Claimed`, actual `Rejected`.
- [ ] Owner diagnoses and fixes the isolated fault without inspecting the seeded
  Git Diff first, then records focused Green evidence.
- [x] 2026-07-29: private P-001 invitation, consent/interview/price worksheet and
  Concierge workspace template prepared; this is preparation only.
- [ ] Owner selects one eligible participant and authorizes/sends the invitation;
  consent, Seed, delivery, price response and payment remain unclaimed.

## Decisions

- Keep the modular monolith and add one real adapter; no new service.
- Build one durable vertical result at a time before AgentKernel/Temporal.
- Use a Snapshot rather than prematurely normalizing a full Working Self relation model.
- Defer Outbox/Inbox until an asynchronous event flow requires it.
- Treat product discovery as a parallel lane with a hard infrastructure freeze rule.
- 2026-07-28, S1: add a separate thin `/api/v1/captures` vertical boundary and one Capture
  store rather than partially serializing the existing Manifestation aggregate or horizontally
  replacing its five in-memory repositories. The Stage 0 Manifestation flow remains explicitly
  in-memory until its later slices.
- 2026-07-28, S1: compute a versioned request hash in pure Java from validated semantic fields;
  scope the database nonce uniqueness by configured principal. The client supplies neither
  principal nor request hash.
- 2026-07-28, S1: use one explicit `READ COMMITTED` transaction around
  `INSERT ... ON CONFLICT DO NOTHING` and the winner select. Always return the row read from
  PostgreSQL, including after an initial insert, so first response and replay share the same
  database-canonical timestamp.
- 2026-07-28, S1: register an early environment guard for the packaged application. The default
  and any override of the main listener must resolve only to loopback; a separately configured
  management port also requires an explicit loopback address.
- 2026-07-28, S2: add a separate durable `ArtifactLineage` boundary rather than reuse the Stage 0
  `ArtifactVersion`. The latter requires real evidence, Working Self and generator provenance; S2
  must not fabricate them or make those existing invariants nullable.
- 2026-07-28, S2: keep one owner-scoped Artifact head plus immutable version rows. A deferred
  head-to-version foreign key, exact parent version/hash foreign key and one transaction make the
  head CAS, version append and canonical read one commit-or-rollback unit.
- 2026-07-28, S2: a CAS miss is classified only by an owner-scoped head lookup. The dedicated
  `409` returns `currentVersion` and requires GET reload; it never returns content or a
  content-derived hash. Foreign and missing exact-base PUT remain the same `404`.
- 2026-07-28, S3: add only three durable tables: ActionAttempt with its immutable authority
  snapshot/current state/budget, append-only transitions, and one terminal Receipt. Exact
  `(connector, account_ref, idempotency_key)` uniqueness does not include principal because
  the simulated provider identity and duplicate-object boundary are connector-account scoped.
- 2026-07-28, S3: a same-key semantic replay ignores newly generated IDs and timestamps and
  returns the database winner; a different principal, Artifact/version/hash, connector/account,
  action target, policy, audience or budget is a generic conflict. Dispatch and reconciliation
  each consume one of the selected two provider calls with a guarded database update and transition
  in one transaction; provider HTTP executes after that transaction returns.
- 2026-07-28, S3: current Artifact head is required only when entering `DISPATCHING`. Once an
  exact plan was dispatched and became `UNKNOWN`, a later Artifact revision must not prevent
  reconciliation of the already possible provider object; reconciliation still uses the persisted
  principal/plan/hash/connector/audience/account/key and budget.
- 2026-07-28, S3: provider success is trusted only when its complete authority/plan/Artifact/key
  echo matches the request. Missing, malformed or substituted echoes remain `UNKNOWN`. S3
  canonicalizes generated ActionPlan time to microseconds before hashing so PostgreSQL
  `TIMESTAMPTZ` round-trip cannot change the plan identity.
- 2026-07-29, S4: do not infer safe stale-`DISPATCHING` recovery from a later
  `UNKNOWN`-state restart. A timeout threshold or startup reset cannot distinguish a dead owner
  from a slow live provider call. PostgreSQL-canonical ownership/fencing is a future vertical
  runtime slice, so S4 corrects ADR-0004 to Proposed and exposes the unresolved state without
  inventing a recovery path.
- 2026-07-29, Stage Gate: follow strict path A. Stage 2 planning may continue,
  but production AgentKernel code remains frozen until the owner-led unknown
  fault diagnosis and one consented real Seed/Concierge/price experiment are
  recorded. Neither activity may be replaced by Codex self-assessment.

## Surprises, failures and verification receipts

Append dated discoveries and exact Receipt locations here. Self-reported completion and green unit tests alone
do not count.

- 2026-07-28 14:55 +08:00 · S1 Acceptance Red:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RestartSafeCaptureHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` built the
  packaged jar, started Testcontainers `postgres:18.4-alpine`, and reached application health.
  The first outside-in request failed at the intended missing behavior:
  `POST /api/v1/captures` expected `201` but received `404`. No production Capture code or
  migration existed when this Red was recorded.
- 2026-07-28 14:56 +08:00 · S1 Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core -am
  -Dtest=CaptureCommandTest -Dsurefire.failIfNoSpecifiedTests=false test` reached Core test
  compilation and failed because the server-owned `CaptureCommand` and its canonical
  `requestHash` behavior did not exist. The test fixes the v1 hash golden value, payload fields,
  identity/nonce exclusion, source-type allow-list, and nonce bounds before implementation.
- 2026-07-28 15:09 +08:00 · S1 packaged-process/fault Green:
  the same targeted Failsafe command passed with distinct packaged application processes
  `firstPid=59845`, `restartedPid=59883` and `otherPrincipalPid=59893` against one PostgreSQL.
  Its printed receipt observed restart/retrieval `200` and matching foreign/missing `404` shapes.
  It did not print the replay/conflict fields, so those claims require the later final run rather
  than being attributed retrospectively to this PID line. A second packaged scenario rejected
  `server.address=0.0.0.0` before startup.
- 2026-07-28 15:14 +08:00 · main-thread Diff review/focused database failure:
  a new nanosecond timestamp case expected PostgreSQL to truncate but failed with its actual
  microsecond rounding, `123456789ns → 123457µs`. The adapter was corrected to return the
  selected database row on initial insert as well as replay; the corrected adapter run passed
  12 Core and 6 PostgreSQL tests.
- 2026-07-28 15:15 +08:00 · preliminary repository checks:
  `./scripts/verify-contracts.sh` validated 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` validated local links in 52 Markdown files. These preliminary
  checks do not replace the required final runs after independent review.
- 2026-07-28 · independent production review:
  no P0 was found. Its one P1 identified that the 15:09 PID line did not print replay/conflict
  fields while the draft Receipt attributed those observations to it; the claims were narrowed
  and require a new final run. A P2 NUL-input/500 boundary was fixed in Core. Remaining P2 risks
  are an unbounded wait behind a long uncommitted competing transaction and the packaged test's
  low-probability release-then-bind port race; neither expands S1 scope.
- 2026-07-28 15:24 +08:00 · complete post-review S1 process Receipt:
  the targeted Failsafe command passed on the reviewed code and printed
  `firstPid=68652`, `restartedPid=68698`, `otherPrincipalPid=68735`,
  `restart=200`, `replay=200`, `conflict=409`, `foreign=404`, `missing=404`.
  The first process was confirmed dead before the new JVM read the database value; the owner and
  other-principal applications shared the same PostgreSQL. The suite also passed the packaged
  wildcard-bind rejection.
- 2026-07-28 15:25 +08:00 · required final verification:
  `./scripts/verify-contracts.sh` passed 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` passed local links in 52 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 12 Core, 6 in-memory adapter,
  6 PostgreSQL adapter, 13 API and 2 packaged-process tests. The full run printed
  `firstPid=69640`, `restartedPid=69651`, `otherPrincipalPid=69652`,
  `restart=200`, `replay=200`, `conflict=409`, `foreign=404`, `missing=404`.
- 2026-07-28 16:08 +08:00 · S2 Acceptance Red:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=ConflictSafeRevisionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` packaged the
  application, started `postgres:18.4-alpine`, brought two independent application JVMs configured
  as `revision-owner` to health, and created the S1 source Capture with `201`. The first Artifact
  request then failed solely at the intended missing behavior:
  `POST /api/v1/artifacts` expected `201` but received `404`. No S2 production type, migration,
  adapter or controller existed when this Red was recorded.
- 2026-07-28 16:13 +08:00 · S2 Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core -am
  -Dtest=ArtifactLineageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` reached
  Core test compilation and failed because `ArtifactLineage`, its immutable entries, the
  owner-scoped store port, create/revise commands, dedicated conflict and service did not exist.
  The focused contract fixes server-owned Capture provenance, v1 hashing, exact expected
  version/hash forwarding, continuous base links and distinct owner-missing versus stale-base
  outcomes without introducing a framework type.
- 2026-07-28 16:18 +08:00 · S2 database/fault Green:
  the selected Core/PostgreSQL/HTTP command passed 6 Core, 5 real PostgreSQL and 4 HTTP tests.
  Two adapters racing v1/hash1 produced one v2 and one dedicated conflict. A test-only PostgreSQL
  trigger then forced the v2 insert to fail after the head UPDATE; the transaction left head v1,
  hash1 and one immutable row. A separate direct insert proved that v2 with a NULL base pair is
  rejected. The explicit NULL assertions were added after read-only architecture review identified
  PostgreSQL CHECK/foreign-key three-valued logic as a P1 loophole.
- 2026-07-28 16:20 +08:00 · integration fixture failure:
  the first full API path stopped before Failsafe because `CaptureHttpTest` still truncated
  `captures` alone after V2 added an Artifact foreign key. Persistent HTTP test cleanup now truncates
  `artifact_versions`, `artifacts` and `captures` together; the rerun reached the packaged S2 test.
- 2026-07-28 16:23 +08:00 · preliminary S2 packaged-process Receipt:
  `ConflictSafeRevisionHttpIT` passed against one real PostgreSQL with
  `firstPid=98600`, `secondPid=98615`, `restartedPid=98625`,
  `otherPrincipalPid=98636`, `winner=200`, `conflict=409`, `artifactRows=1`,
  `versionRows=2`, `currentVersion=2`, `baseLinked=true`, `foreignGet=404`,
  `missingGet=404`, `foreignPut=404`, `missingPut=404`. Both race processes were killed
  before the third JVM returned byte-for-byte identical lineage; v1 was unchanged and the losing
  content had zero database rows. This is preliminary until independent review and a post-review run.
- 2026-07-28 16:23 +08:00 · S1 regression before review:
  `RestartSafeCaptureHttpIT` passed with `firstPid=98878`, `restartedPid=98891`,
  `otherPrincipalPid=98895`, `restart=200`, `replay=200`, `conflict=409`,
  `foreign=404`, `missing=404`; its packaged wildcard-bind rejection also remained green.
- 2026-07-28 · S2 independent production review:
  the read-only reviewer found `P0=0`, `P1=0`. One P2 showed that a failed second application
  startup or first cleanup could leave a sibling packaged JVM/log behind; the process group now
  cleans every resource best-effort and preserves suppressed failures. The second P2 is an evidence
  gap, not a hidden pass: V2 is additive and fresh-install tested, but a V1 database populated with
  S1 data has not yet been migrated to V2 in a dedicated rehearsal. That remains open for the Stage 1
  operating/upgrade Gate.
- 2026-07-28 16:33 +08:00 · post-review S2 process Receipt:
  the targeted Failsafe command passed after the cleanup correction with
  `firstPid=4515`, `secondPid=4529`, `restartedPid=4539`,
  `otherPrincipalPid=4540`, `winner=200`, `conflict=409`, `artifactRows=1`,
  `versionRows=2`, `currentVersion=2`, `baseLinked=true`, and equivalent
  foreign/missing GET/PUT `404` responses.
- 2026-07-28 16:34 +08:00 · required final S2 verification:
  `./scripts/verify-contracts.sh` passed 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` passed local links in 54 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 18 Core, 6 in-memory adapter,
  11 PostgreSQL adapter, 17 API and 3 packaged-process tests. The full run printed the S2 Receipt
  with PIDs `5136/5142/5152/5162` and the S1 regression Receipt with PIDs
  `5177/5187/5188`.
- 2026-07-28 18:15 +08:00 · S3 Acceptance Red:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RecoverableLocalActionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` packaged the
  application, started Testcontainers `postgres:18.4-alpine`, an independent file-backed provider
  JVM, and two independent packaged application JVMs against the same database. It created the S1
  Capture and S2 Artifact over HTTP, then failed at the intended missing behavior:
  `POST /api/v1/artifacts/{artifactId}/actions` expected `202` but received `404`. No S3
  production Action type, migration, adapter or controller existed when this Red was recorded.
- 2026-07-28 18:22 +08:00 · S3 Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core -am
  -Dtest=ActionAttemptInvariantTest -Dsurefire.failIfNoSpecifiedTests=false test` reached Core
  test compilation and failed because the durable `ActionCapability`, `ActionAttempt`, explicit
  statuses/transitions and S3 `ActionReceipt` did not exist. The focused contract fixes exact
  principal/connector/audience/account/plan/hash/key/expiry binding, the declared transition graph,
  persistent call-budget bounds and the rule that only a definite terminal provider result can
  create a Receipt.
- 2026-07-28 18:34 +08:00 · preliminary S3 packaged-process Receipt:
  the targeted Failsafe command passed with independent provider PID `60902`, competing packaged
  application PIDs `60903/60916`, restarted PID `60956` and foreign-principal PID `60966`.
  PostgreSQL contained one attempt, two atomic capability uses and one Receipt; the provider
  state file contained one simulated object after two calls. The exact history was
  `PLANNED → DISPATCHING → UNKNOWN → RECONCILING → SUCCEEDED`. Wrong-audience/account
  application PIDs `60926/60939` received `409` without a provider observation change; foreign
  and missing GET/reconcile were equivalent `404`. This remained preliminary until review.
- 2026-07-28 · first S3 independent production review:
  the read-only reviewer ran the packaged IT successfully and found `P0=0`, `P1=4`. The four P1s
  were a torn two-query ActionAttempt read under concurrent transition, an incorrect current-head
  predicate on reconciliation after Artifact revision, an incomplete provider success echo that
  omitted audience, and nanosecond ActionPlan expiry changing its hash after PostgreSQL
  microsecond round-trip. No P1 was waived.
- 2026-07-28 18:43 +08:00 · P1 corrections and post-fix fault Green:
  `findOwned` now assembles attempt/Receipt/transitions from one SQL statement and a concurrent
  read-versus-transition regression passed; a dispatched v1 plan reconciles after the Artifact
  advances to v2; the provider protocol echoes and validates principal, connector, audience,
  account, plan ID/hash, Artifact ID/version/hash and key; and a `123456789ns` fixed clock
  round-trips through the real store with a stable plan hash. The focused command passed 5 Core,
  7 PostgreSQL Action and 2 provider-adapter tests. The packaged Failsafe rerun then printed
  `providerPid=67839`, `firstAppPid=67841`, `secondAppPid=67859`,
  `restartedAppPid=67889`, `otherPrincipalPid=67900`, one attempt, one Receipt,
  two capability uses, one provider object, two provider calls, the exact five-state history,
  wrong-audience/account `409`, equivalent foreign/missing GET/reconcile `404`, and
  `simulated=true`.
- 2026-07-28 18:47 +08:00 · independent post-fix production review:
  the same read-only reviewer verified all four then-known P1 corrections, found no new
  implementation P0/P1, and independently reran 7 PostgreSQL Action-store plus 2
  provider-adapter tests successfully. It concluded that ADR-0004's five Required evidence items
  were mapped and changed the ADR from Proposed to Accepted. The 2026-07-29 S4 re-audit below
  supersedes that evidence conclusion because the crash was after durable `UNKNOWN`, not while
  still `DISPATCHING`. Other recorded P2s were startup temp-path cleanup and provider file reload.
- 2026-07-29 · S4 independent ADR-0004 evidence-gap audit and scope decision:
  two read-only reviewers traced the exact process test and production path. The provider is held
  before object creation with PostgreSQL at `DISPATCHING`; the test releases it, waits for both
  HTTP calls and durable `UNKNOWN`, confirms one object/no Receipt, and only then force-kills both
  application JVMs. Core and V3 expose reconciliation only from `UNKNOWN`; no owner, lease,
  fencing epoch, database-time staleness or `DISPATCHING` takeover exists. Audit result was
  `P0=0`; P1 findings were the unrecoverable crash window, overstrong ADR/ExecPlan claims and the
  false-takeover risk of an `updated_at` threshold. S4 chose no runtime expansion, corrected
  ADR-0004 to Proposed and kept real Connectors blocked. This is a documentation/Gate correction,
  not a claim that the missing fault has been fixed.
- 2026-07-29 16:42 +08:00 · S4 Acceptance Red:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RecoverableLocalActionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false`
  built the packaged jar, started PostgreSQL 18.4, an independent Fake Provider JVM and two
  packaged application JVMs, and reached Boot's existing
  `/actuator/health/readiness` with `200/UP`. It then failed only at the new outside-in
  expectation with `PathNotFoundException: Missing property in path $['components']`;
  the owner-scoped database/migration/Action durability component did not yet exist. Core,
  in-memory, PostgreSQL and API unit suites were green before the intended Failsafe Red.
- 2026-07-29 16:43 +08:00 · S4 Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl adapters/postgres -am
  -Dtest=PostgresStage1OperationsProbeTest -Dsurefire.failIfNoSpecifiedTests=false test`
  failed at test compilation because `PostgresStage1OperationsProbe` did not exist. The
  test already fixed the required owner-scoped counts for all six Action states, the four-state
  unresolved total and the read-only no-transition/no-Receipt-mutation boundary before the
  adapter implementation.
- 2026-07-29 16:49 +08:00 · S4 readiness/fault Green:
  the focused PostgreSQL probe test passed with one row in every Action state for the configured
  principal, one foreign `UNKNOWN`, an unresolved total of four and no transition/Receipt
  mutation. The packaged scenario then passed with distinct PIDs
  `provider=96920`, competing apps `96921/96935`, restarted app `96988` and
  foreign-principal app `97004`. Readiness was initially `UP`, became
  `OUT_OF_SERVICE` for owner-scoped `DISPATCHING`/`UNKNOWN`, exposed the exact V3
  migration and recovery boundaries, and remained out of service after one Action reached
  `SUCCEEDED` because the no-object attempt was still honestly `UNKNOWN`. The foreign
  principal's readiness stayed `UP`. An intermediate run revealed that recovery-process startup
  must wait on liveness rather than global health; otherwise the truthful unresolved state
  prevents the very JVM needed for reconciliation from being recognized as started.
- 2026-07-29 16:59 +08:00 · S4 populated upgrade and backup/restore game day:
  `./mvnw --batch-mode --no-transfer-progress -pl adapters/postgres -am
  -Dtest=Stage1MigrationAndRecoveryTest
  -Dsurefire.failIfNoSpecifiedTests=false test` passed two PostgreSQL 18.4 tests.
  Independent populated V1, V2 and V3 schemas upgraded to current V3 without changing any
  stable S1 Capture/hash, S2 Artifact head/version/lineage or S3
  ActionAttempt/transition/budget/Receipt value; a fresh install produced V3 and six business
  tables. The recovery game day ran real `pg_dump -Fc --no-owner --no-privileges`, injected
  `TRUNCATE captures CASCADE`, created a new database, ran
  `pg_restore --single-transaction --exit-on-error`, revalidated Flyway and matched the complete
  six-table snapshot. The observed interval from recovery start through comparison was 229 ms in
  this one local run; it is not an RTO, RPO, capacity result or SLA.
- 2026-07-29 17:02 +08:00 · S4 incompatible migration packaged fail-fast:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=IncompatibleMigrationFailsFastHttpIT
  -Dfailsafe.failIfNoSpecifiedTests=false` applied V1–V3 to real PostgreSQL, deliberately
  changed the applied V3 checksum, and launched the packaged jar. Flyway reported the exact V3
  checksum mismatch, the process exited non-zero, readiness was never `UP`, and the captured log
  did not contain the synthetic database password. This proves failure-stop for one incompatible
  history; it does not promise a universal down migration.
- 2026-07-29 17:13 +08:00 · S4 live-process/database-loss boundary:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=DatabaseUnavailableReadinessHttpIT
  -Dfailsafe.failIfNoSpecifiedTests=false` started a packaged ready application and then stopped
  its PostgreSQL container. Readiness became `503/DOWN` with the fixed
  `DATABASE_OR_MIGRATION_UNAVAILABLE` code while process liveness remained `200/UP`.
  The response contained no database password, JDBC URL, mapped port or driver exception. The
  readiness group intentionally uses the bounded `stage1Durability` contributor rather than
  exposing Boot's raw database contributor under `show-details: always`.
- 2026-07-29 17:10 +08:00 · S4 clean-checkout replay command:
  `./scripts/run-stage1-operating-demo.sh` passed from the repository root. It rebuilt the
  packaged jar, started real PostgreSQL, an independent file-backed Fake Provider JVM and
  separately packaged application JVMs, and printed one simulated object, one Receipt, two calls,
  `PLANNED → DISPATCHING → UNKNOWN → RECONCILING → SUCCEEDED`, initial readiness `UP`,
  unresolved readiness `OUT_OF_SERVICE` and one remaining honest no-object `UNKNOWN`. The
  committed trace removes credentials, URLs/ports, PIDs, paths, content, principals and object
  identifiers; the runbook explicitly says this is not a real Connector or provider-call
  exactly-once.
- 2026-07-29 17:18 +08:00 · S4 independent post-implementation production review:
  the read-only reviewer inspected the complete production/test/documentation Diff and
  independently reran the PostgreSQL probe/migration/restore tests, packaged checksum fail-fast,
  packaged restart/reconcile, database-loss readiness, doc links and `git diff --check`.
  It found `P0=0`, `P1=2`: the outcome still called S4 open, then used “real Connector Gate
  open” in a way that could imply enablement. Both were corrected. The post-fix review reported
  `P0=0`, `P1=0`, `P2=0`, with ADR-0004 `Proposed`, the real Connector Gate explicitly
  `blocked`, no stale-`DISPATCHING` recovery claim and no fabricated human or business evidence.
- 2026-07-29 17:19 +08:00 · S4 required final repository verification:
  `./scripts/verify-contracts.sh` compiled 4 JSON Schema 2020-12 contracts and validated
  8 fixtures plus 1 uniquely keyed synthetic evaluation task pack;
  `./scripts/verify-doc-links.sh` validated local links in 60 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 23 Core, 6 in-memory adapter,
  22 PostgreSQL adapter, 20 API and 6 packaged-process tests. The full run repeated the S1
  restart, S2 two-process CAS, S3 independent-provider restart/reconcile, S4 populated
  migration/restore, incompatible-checksum fail-fast and database-loss readiness Receipts.
- 2026-07-29 17:21 +08:00 · S4 pre-commit integrity Receipt:
  after the final evidence update, contracts and doc links reran green; `git diff --check`,
  `bash -n scripts/run-stage1-operating-demo.sh` and `jq empty` on the sanitized trace all
  passed. The file list contains no Core or migration change and no forbidden runtime, Connector,
  model, UI, authentication, public listener, generic outbox/metrics or real-data artifact. One
  focused commit is the only remaining repository mutation; Git supplies its ID and clean-tree
  status outside this self-referential plan snapshot.
- 2026-07-28 18:47 +08:00 · required final S3 verification:
  `./scripts/verify-contracts.sh` passed 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` passed local links in 56 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 23 Core, 6 in-memory adapter,
  19 PostgreSQL adapter, 20 API and 4 packaged-process tests. The full run printed final S3
  PIDs `provider=70192`, competing apps `70193/70206`, restarted app `70238`,
  foreign-principal app `70248` and wrong-authority apps `70216/70226`, with one attempt,
  one simulated object, one Receipt, two calls and the exact five-state recovery history.
  S2 regressed green with PIDs `70074/70086/70097/70108`; S1 regressed green with
  PIDs `70133/70145/70147`.

## Outcome and next hypothesis

S1–S4 engineering slice Receipts are complete. Stage 1 remains active because ADR-0004 remains Proposed,
the real Connector Gate remains blocked, and owner-led Teach-back/transfer/unknown-fault exercises plus
Lane B market evidence are still incomplete. The S3/S4 result is exactly one recoverable simulated local
object after durable `UNKNOWN`; it is not a real Connector, an exactly-once provider-call claim or a safe
stale-`DISPATCHING` takeover. Stage 2 begins only when the full engineering, market and human-learning Gates are
decided; a failed market hypothesis leads to a new vertical product result, not automatic runtime
expansion.
