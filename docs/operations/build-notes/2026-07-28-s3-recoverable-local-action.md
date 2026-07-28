# Build Note · 2026-07-28 · S3 Recoverable local Action

- Change class: `V`
- Task / commit: Stage 1 S3, this focused commit

## Outcome

Added one PostgreSQL-backed local Action slice around the current owned S2 Artifact. The application
persists an approved ActionPlan, exact Capability, ActionAttempt, transition history and atomic provider-call
budget before dispatch. A separate loopback-only Fake Provider JVM owns its own file-backed idempotency state.
When that provider creates one simulated object and drops the response, the application records `UNKNOWN`;
after both original application JVMs are forcibly terminated, a new packaged JVM reconciles the same object
into one durable successful Receipt.

This proves one simulated local recovery protocol. It does not prove a real Connector, a real platform
side effect, exactly-once provider calls, public access or production authentication.

## Why this matters

An external write can succeed while its response is lost. Treating the timeout as failure and blindly
executing again can duplicate the external object; treating it as success fabricates a completion. S3 makes
the ambiguity durable and inspectable, then separates `EXECUTE` from `RECONCILE_ONLY` so a restarted process
can recover without guessing.

## Evidence

- First Acceptance Red, recorded before production implementation:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RecoverableLocalActionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` built the packaged
  application, started PostgreSQL, an independent file-backed provider JVM and two application JVMs,
  created the S1 Capture and S2 Artifact over HTTP, then failed because
  `POST /api/v1/artifacts/{artifactId}/actions` returned `404` instead of `202`.
- Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core -am
  -Dtest=ActionAttemptInvariantTest -Dsurefire.failIfNoSpecifiedTests=false test` failed compilation
  because the framework-neutral ActionCapability, ActionAttempt states/transitions and S3 Receipt did not
  exist.
- Focused Green covered the exact Capability mutation matrix, legal transition graph, provider-call-before-
  durable-claim guard, definite failure, no-identifier ambiguity, PostgreSQL race/rollback/current-Artifact
  predicates, server-owned authority and loopback-only provider origin.
- PostgreSQL migration evidence uses `postgres:18.4-alpine` twice in one Testcontainers test: an empty schema
  installs V1 → V3, while a second schema stops at Flyway V2, writes real S1 Capture and S2 Artifact rows
  through their adapters, then upgrades to V3 without changing the old content/hash/lineage.
- Preliminary packaged-process Receipt at 18:34 +08:00:
  `providerPid=60902`, `firstAppPid=60903`, `secondAppPid=60916`,
  `restartedAppPid=60956`, `otherPrincipalPid=60966`, `attemptRows=1`,
  `receiptRows=1`, `capabilityUses=2`, `providerObjects=1`, `providerCalls=2`,
  `states=PLANNED>DISPATCHING>UNKNOWN>RECONCILING>SUCCEEDED`,
  `audienceMismatchPid=60926`, `accountMismatchPid=60939`,
  `audienceMismatch=409`, `accountMismatch=409`, equivalent foreign/missing GET and reconcile `404`,
  `simulated=true`.
- At the provider hold point, PostgreSQL already contained one `DISPATCHING` attempt, one budget use,
  `PLANNED → DISPATCHING`, and no Receipt, while the provider had received one request but created no object.
  After release, its state file contained one object and the database held `UNKNOWN` with no Receipt. Both
  original application processes were then forcibly terminated while the provider remained alive.
- A no-object provider timeout followed by reconciliation stayed `UNKNOWN`, consumed the declared two-call
  budget, produced zero provider objects and zero Receipts. Replaying a terminal reconciliation returned the
  same Receipt without another provider call or budget use.
- First independent production review found `P0=0`, `P1=4`: a torn two-query read, reconciliation incorrectly
  tied to the current Artifact head, an audience-free success echo and nanosecond expiry/hash drift. All four
  were fixed with focused regressions rather than waived.
- The independent post-fix review found `P0=0`, `P1=0` and separately reran the 7 PostgreSQL Action-store plus
  2 provider-adapter tests. The post-review packaged Receipt at 18:43 +08:00 used provider PID `67839`,
  competing application PIDs `67841/67859`, restarted PID `67889`, foreign-principal PID `67900` and
  wrong-authority PIDs `67878/67879`; it retained one attempt, one simulated object, one Receipt, two calls,
  the exact five-state history and normalized foreign/missing responses.
- Required final verification at 18:47 +08:00:
  `./scripts/verify-contracts.sh` passed 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` passed 56 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 23 Core, 6 in-memory adapter,
  19 PostgreSQL adapter, 20 API and 4 packaged-process tests. The final S3 process Receipt used
  provider PID `70192`, competing application PIDs `70193/70206`, restarted PID `70238`,
  foreign-principal PID `70248` and wrong-authority PIDs `70216/70226`, with the same one
  attempt/object/Receipt, two-call, five-state and non-leaking assertions. S1 and S2 packaged regressions
  also printed fresh green PIDs.

## Design and version-sensitive checks

- Core adds pure Java ActionAttempt, Capability, provider result and store/provider ports. Spring, JDBC,
  Flyway, PostgreSQL and HTTP types stop in outer adapters.
- Flyway V3 adds only `action_attempts`, `action_attempt_transitions` and `action_receipts`. PostgreSQL
  enforces exact `UNIQUE (connector, account_ref, idempotency_key)`, exact owned Artifact
  version/hash provenance, exact flattened Capability bindings, legal states, bounded calls and a terminal
  state/Receipt foreign key.
- Each dispatch or reconciliation claim performs one guarded `UPDATE` that matches principal, plan ID/hash,
  connector, audience, account, persisted Artifact version/hash, idempotency key, expiry and unused budget.
  Dispatch additionally requires that version/hash to remain the current Artifact head; reconciliation must
  still resolve an already dispatched object after the head advances. The matching transition and budget use
  commit in the same transaction. Provider HTTP runs only after that transaction returns.
- A provider result becomes either a definite terminal Receipt or `UNKNOWN`. The terminal state,
  transition and Receipt share one transaction; a test-only failing Receipt trigger proves all three roll
  back together.
- The provider's idempotency identity is `(connector, accountRef, idempotencyKey)`. Its persisted object also
  stores principal, audience, ActionPlan ID/hash and Artifact ID/version/hash; `RECONCILE_ONLY` never creates
  a missing object.
- Repository versions were resolved from the local dependency graph: Java 21, Spring Boot `4.1.0`,
  Spring JDBC `7.0.8`, Flyway `12.4.0`, PostgreSQL JDBC `42.7.11`, Testcontainers `2.0.5` and
  PostgreSQL `18.4`. Version-sensitive behavior was checked against the official
  [Spring JdbcClient API](https://docs.spring.io/spring-framework/docs/7.0.x/javadoc-api/org/springframework/jdbc/core/simple/JdbcClient.html),
  [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html),
  [PostgreSQL 18 INSERT](https://www.postgresql.org/docs/18/sql-insert.html),
  [PostgreSQL 18 constraints](https://www.postgresql.org/docs/18/ddl-constraints.html),
  [Flyway target setting](https://documentation.red-gate.com/fd/flyway-target-setting-277579044.html),
  [Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
  and [Java 21 HttpServer](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html).

## Failure and limits

- `UNKNOWN` means the external result is unresolved. It has no Receipt and is never displayed as completed.
- The selected S3 budget is two provider calls: one `EXECUTE` and at most one `RECONCILE_ONLY`. Exhausted
  unresolved attempts remain `UNKNOWN` for explicit later operating/human handling; S3 does not add a retry
  scheduler.
- A process killed while still `DISPATCHING`, before it can record `UNKNOWN`, needs a later stale-claim
  recovery policy. The accepted S3 fault occurs after the provider object exists and the dropped response
  has made the database outcome `UNKNOWN`; no broader lease/runtime mechanism is claimed.
- Provider administration endpoints and file persistence exist only in the test process. The application
  adapter refuses non-loopback provider origins, but this is not a production Connector security design.
- Stage 0 Manifestation, Working Self and Reflection stores remain in memory. S3 does not add Temporal,
  AgentKernel, an outbox, a model, UI, login, LAN/public binding, secrets or real user data.
- The evidence proves one provider-observable simulated object, not one network call. The recovery scenario
  intentionally makes two provider calls: initial execute and reconciliation.
- No backup restore/forward-fix game day, alerting or full S4 operating receipt is claimed.
- No human Teach-back, transfer/debugging exercise, Founder Seed, interview, reuse, quote, price request or
  payment is claimed.

## Principle learned

Ambiguous external success is a durable state, not an exception to retry: commit the authority and recovery
anchor before dispatch, keep an unknown result non-terminal, and reconcile by the same provider idempotency
identity before creating a Receipt.

## AI Coding and Agent Engineering receipts

- AI Coding capability practiced: outside-in Red, focused domain Red, minimal schema/adapter, adversarial
  binding tests, transaction failure injection, multi-process fault evidence and independent-review handoff.
- Agent Engineering concept and target mastery: durable ActionAttempt, atomic Capability budget,
  provider idempotency and reconciliation; target `L3`, human level remains `L0 / unassessed`.
- Draft owner explanation: the first committed budget claim makes `DISPATCHING` observable before provider
  access; a lost response becomes `UNKNOWN`, so a restarted process may only ask the same provider identity
  to reconcile. A Receipt is written only when that lookup returns a definite object.
- Failure I can now diagnose: dispatch-before-persist, process-local locks, non-atomic budgets, blind execute
  retry, false success without an identifier, split terminal/Receipt transactions, identity-free uniqueness
  and a Fake Provider that secretly shares application memory.
- Codex/Subagent division: two read-only agents handled architecture/version research and fault-test design;
  a third independently reviewed the production Diff. The main thread remained the only writer and reviewed
  the complete Diff and returned findings.
- What remains unlearned: owner-led no-notes explanation, changed-constraint implementation, independent
  fault diagnosis and the spaced review.

## Career and business receipts

- Demo / Case Card / interview question: the provider/app PID Receipt, persisted provider object, exact state
  history and no-identifier holding path can become a recovery Case Card after owner Teach-back; they are not
  yet personal mastery evidence or a real-platform reliability claim.
- User use, retention, payment or rejection evidence: none.
- Business next action: the project owner should start truthful entries with the existing Founder Seed Log
  and recent-behavior interview template. This reminder and those templates do not count as a completed
  interview, Seed reuse, quote, price request or payment.

## Next falsifiable hypothesis

S4 should prove that a clean operator can install/upgrade, distinguish ready from unresolved, and execute a
documented backup restore or forward-fix path without overstating the S3 simulated result.

## Public derivatives

- Short post: pending human approval and Teach-back.
- Visual/demo: pending human approval.
- Weekly long-form: pending human approval.
