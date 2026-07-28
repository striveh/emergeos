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

### S2 · Conflict-safe Revision

User result: revise an Artifact without silently overwriting a newer revision.

- Add Artifact lineage and expected base version/hash to the API and schema.
- Use compare-and-swap rather than a read-then-write check.
- Race two application instances against the same base version.
- Return an explicit conflict with enough safe information for a later user choice.

Receipt: exactly one revision wins, the loser is observable as a conflict, lineage remains intact and the
result survives restart.

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

### S4 · Operating and interview evidence

User result: another developer can run the durable flow and distinguish readiness from failure.

- Verify fresh install and upgrade from the previous schema.
- On incompatible migration, fail fast; demonstrate backup restore or a forward-fix path instead of
  promising a universal down-migration.
- Add database/migration readiness and only the structured events needed to explain the fault matrix.
- Package the HTTP demo, sanitize its trace, update run instructions and record exact limitations.
- Complete an independent production review and resolve all P0/P1 findings.

Receipt: repeatable demo, deterministic verification, migration/recovery evidence, Case Card and Build Note.

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
| independent provider success, app response lost/JVM killed | `UNKNOWN`, then `SUCCEEDED` | provider lookup + Receipt commit after app restart |
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
- [ ] Lane B Day 1: start Founder Log, interview recruitment and Concierge offer.
- [ ] S1 restart-safe Capture.
- [ ] S2 conflict-safe Revision.
- [ ] S3 recoverable local Action.
- [ ] S4 operating and interview evidence.
- [ ] Stage gate and `continue / narrow / pivot` decision.

## Decisions

- Keep the modular monolith and add one real adapter; no new service.
- Build one durable vertical result at a time before AgentKernel/Temporal.
- Use a Snapshot rather than prematurely normalizing a full Working Self relation model.
- Defer Outbox/Inbox until an asynchronous event flow requires it.
- Treat product discovery as a parallel lane with a hard infrastructure freeze rule.

## Surprises, failures and verification receipts

Append dated discoveries and exact Receipt locations here. Self-reported completion and green unit tests alone
do not count.

## Outcome and next hypothesis

Open. Stage 2 begins only when the engineering, market and human-learning Gates are all decided; a failed
market hypothesis leads to a new vertical product result, not automatic runtime expansion.
