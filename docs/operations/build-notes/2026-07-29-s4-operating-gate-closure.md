# Build Note · 2026-07-29 · S4 Operating and Gate-Closure Evidence

- Change class: `V`
- Task / commit: Stage 1 S4, this focused commit

## Outcome

Added a thin, owner-scoped PostgreSQL operations probe and a loopback readiness
boundary for the existing S1–S3 durable flow. A clean-checkout script now replays
the packaged JVM/Fake Provider recovery scenario and explains `READY`,
`UNKNOWN`, `RECONCILING` and `SUCCEEDED` without claiming production
availability.

The slice also rehearsed populated V1, V2 and V3 upgrades, a fresh V3 install,
an incompatible applied migration and a real PostgreSQL logical backup/restore
into a new database. The stable Capture, Artifact lineage, ActionAttempt,
transition, budget and Receipt data remained value-for-value equal at the
Java/JDBC snapshot boundary.

S4 first corrected an evidence error: the S3 packaged fault kills the
application after the provider object exists and the database has recorded
`UNKNOWN`, not between provider success and the local outcome/Receipt commit.
There is no safe stale-`DISPATCHING` takeover. Implementing one requires a
PostgreSQL-canonical lease owner, fencing epoch, database clock, heartbeat and
fenced outcome commits, which is a new runtime slice. S4 did not hide that gap
behind a timestamp reset. ADR-0004 is therefore `Proposed`, and the real
Connector Gate remains blocked.

## Why this matters

A process can be live while its durable work is not ready for more action.
Equally, an unresolved Action must not prevent the new process needed for
reconciliation from starting. The new boundary keeps liveness separate from
durable readiness:

- database or migration failure is `DOWN`;
- owner-scoped `PLANNED`, `DISPATCHING`, `UNKNOWN` or `RECONCILING` is
  `OUT_OF_SERVICE`;
- current migrations, reachable PostgreSQL and zero unresolved work is `UP`;
- fixed recovery labels explicitly say which states have an entry and which do
  not.

## Evidence

- Baseline: clean
  `main@b64f90316ffe15d4b4c166df18ed0c41e5ad878f`.
- Independent pre-implementation review: two read-only reviewers traced the
  packaged process test and store transitions. They agreed on `P0=0` and three
  P1 evidence/Gate findings: the unhandled `DISPATCHING` crash window, the
  overstrong ADR/ExecPlan claim and false-takeover risk from time-threshold
  recovery. A separate read-only test-design review reached the same no-schema,
  no-Core S4 boundary.
- Acceptance Red:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RecoverableLocalActionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false`
  reached Boot's existing `200/UP` readiness from real packaged processes and
  then failed only because the required `stage1Durability` component was absent.
- Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl adapters/postgres -am
  -Dtest=PostgresStage1OperationsProbeTest
  -Dsurefire.failIfNoSpecifiedTests=false test` failed compilation because the
  owner-scoped operations probe did not exist.
- Readiness Green: the real PostgreSQL probe counted all six Action states for
  the configured principal, ignored a foreign principal and made no transitions
  or Receipts. The packaged scenario observed initial `UP`,
  `OUT_OF_SERVICE` during `DISPATCHING`/`UNKNOWN`, exact state counts after
  reconciliation and `UP` for the foreign configured principal. One intermediate
  failure showed that process startup must poll liveness rather than global
  health; the fix preserves truthful `503` readiness while allowing recovery.
- Database fault:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=DatabaseUnavailableReadinessHttpIT
  -Dfailsafe.failIfNoSpecifiedTests=false` started a packaged ready application,
  stopped its real PostgreSQL container, and observed readiness `DOWN` while
  liveness stayed `UP`. The bounded response exposed only
  `DATABASE_OR_MIGRATION_UNAVAILABLE` and contained no password, JDBC URL,
  mapped port or PostgreSQL driver exception.
- Upgrade evidence:
  `./mvnw --batch-mode --no-transfer-progress -pl adapters/postgres -am
  -Dtest=Stage1MigrationAndRecoveryTest
  -Dsurefire.failIfNoSpecifiedTests=false test` upgraded independent, populated
  V1, V2 and V3 schemas and installed V3 fresh. Exact stable-table snapshots
  before and after matched; the fresh install contained six Stage 1 business
  tables.
- Recovery game day: the same Testcontainers test ran real `pg_dump -Fc`,
  injected `TRUNCATE captures CASCADE`, created a clean destination database,
  ran `pg_restore --single-transaction --exit-on-error`, validated Flyway and
  compared all six business tables. One observed run took 229 ms from recovery
  start through verification. This is a local sample, not an RTO/RPO or SLA.
- Incompatible migration:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=IncompatibleMigrationFailsFastHttpIT
  -Dfailsafe.failIfNoSpecifiedTests=false` applied V1–V3, changed the recorded
  V3 checksum and started the packaged jar. The process exited non-zero, never
  reached readiness, named the checksum mismatch and did not log the synthetic
  database password.
- Replay and sanitized trace:
  [Stage 1 Operating Runbook](../stage1-operating-runbook.md) and
  [S4 synthetic operating trace](../traces/2026-07-29-s4-synthetic-operating-trace.json).
- Independent post-implementation review found `P0=0`, `P1=2`: the living
  ExecPlan still called S4 open, then used “real Connector Gate open” in a way
  that could be read as enabled. Both were corrected to S1–S4 engineering
  receipts complete, ADR-0004 `Proposed` and the real Connector Gate `blocked`.
  The reviewer rechecked the Diff and reported `P0=0`, `P1=0`, `P2=0`, with
  independent green PostgreSQL probe/migration/restore, packaged
  restart/reconcile, checksum fail-fast, database-loss readiness, doc links and
  `git diff --check`.

Required final verification at 17:19 +08:00 passed:
`./scripts/verify-contracts.sh` validated 4 schemas, 8 fixtures and 1 synthetic
task pack; `./scripts/verify-doc-links.sh` validated 60 Markdown files; and
`./mvnw --batch-mode --no-transfer-progress verify` passed 23 Core, 6 in-memory
adapter, 22 PostgreSQL adapter, 20 API and 6 packaged-process tests. The final
run repeated S1/S2/S3 process Receipts plus S4 migration/restore,
checksum-fail-fast and database-loss readiness Receipts.

## Design and version-sensitive checks

- No V4 migration, new domain state or general Repository was added. The
  PostgreSQL adapter owns one aggregate count query; the API adapter owns health
  interpretation. Core remains framework-neutral.
- Readiness exposes only bounded counts, migration status, fixed recovery labels
  and a fixed Connector Gate. It does not expose content, identifiers,
  principals, URLs, credentials, exceptions or arbitrary database text.
- The configured principal is injected by the server and remains part of every
  count predicate. A second application principal sharing the same database sees
  its own zero/unresolved counts.
- Repository truth is Java 21, Spring Boot `4.1.0`, Spring JDBC `7.0.8`,
  Flyway `12.4.0`, Testcontainers `2.0.5` and PostgreSQL `18.4`. Local jars and
  APIs were inspected before implementation. Version-sensitive behavior was
  checked against the official
  [Spring Boot health endpoint and group reference](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html),
  [Flyway validate reference](https://documentation.red-gate.com/flyway/reference/commands/validate),
  [PostgreSQL 18 pg_dump reference](https://www.postgresql.org/docs/18/app-pgdump.html)
  and
  [PostgreSQL 18 pg_restore reference](https://www.postgresql.org/docs/18/app-pgrestore.html).
- The backup uses PostgreSQL's custom archive and restores into a new database
  with single-transaction, exit-on-error semantics. This is one tested recovery
  path, not a promise that every migration can be reversed.

## Failure and limits

- `UNKNOWN` is serving-but-unresolved, has no Receipt and may use the explicit
  reconcile entry while persisted Capability authority and budget permit.
- `DISPATCHING` and `RECONCILING` are visible but have no safe stale takeover.
  Operator documentation says so instead of suggesting a restart or wall-clock
  reset.
- The Fake Provider is a separate JVM with its own file-backed idempotency
  state. Its one object is simulated. The evidence deliberately uses two
  provider calls and does not prove provider-call exactly-once.
- `show-details: always` is acceptable only inside the current enforced
  loopback-only, unauthenticated prototype and only because the fields are
  bounded and sanitized. It must be revisited before any LAN/public or
  authenticated deployment.
- There is no automated failover, capacity test, scheduled reconciliation,
  alerting service, generic metrics platform, generic outbox, Temporal,
  AgentKernel, real model, UI, authentication, real Connector or real user data.
- No owner Teach-back, no-notes replay, independently diagnosed unknown fault,
  Founder Seed, interview, repeat use, quote, price request or payment is
  claimed.

## Principle learned

Operational evidence must make unsupported recovery boundaries more visible,
not make them disappear: liveness permits repair, readiness reports durable
truth, and a blocked Gate is more correct than an un-fenced retry.

## AI Coding and Agent Engineering receipts

- Practiced: outside-in packaged Red, focused PostgreSQL Red, failure injection,
  process-level fail-fast, logical restore, sanitized operational evidence and
  independent review handoff.
- Target concept: liveness/readiness separation, forward-only migration
  validation, restore verification and explicit recovery authority.
- Human mastery remains `L0 / unassessed`. The repository contains a rehearsal
  packet, not proof that the project owner can explain or reproduce it unaided.

## Career and business receipts

- A Case Card and clean replay now exist as drafts for an owner-led defense. They
  are not yet a verified interview story.
- Business evidence remains empty. The project owner should begin real,
  consent-aware records with the existing Founder Seed Log and problem interview
  templates. This reminder does not count as a Seed, interview, reuse, quote,
  price request or payment.

## Next falsifiable hypothesis

Before any real Connector, a future runtime slice must prove a fenced,
PostgreSQL-canonical recovery boundary for a process killed after provider
success but before a local terminal/`UNKNOWN` commit, including false-takeover
and concurrent-live-owner tests. Stage 2 does not begin merely because S4
engineering evidence is green; human and market Gates remain open.

## Public derivatives

- Short post: pending human approval and Teach-back.
- Visual/demo: pending human approval.
- Weekly long-form: pending human approval.
