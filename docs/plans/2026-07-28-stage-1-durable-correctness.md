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

## Outcome and next hypothesis

S1 engineering is complete. Stage 1 remains active: S2–S4, the owner-led Teach-back/transfer exercises
and Lane B market evidence are still open. The next engineering hypothesis is that PostgreSQL
compare-and-swap can make two separately configured application instances produce exactly one Artifact
revision winner without silent overwrite. Stage 2 begins only when the full engineering, market and
human-learning Gates are decided; a failed market hypothesis leads to a new vertical product result,
not automatic runtime expansion.
