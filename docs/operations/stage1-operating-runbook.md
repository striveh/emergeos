# Stage 1 Operating Runbook

This runbook replays the Stage 1 durable flow from a clean checkout. It uses only
synthetic data, PostgreSQL Testcontainers, packaged application JVMs and an
independent, file-backed Fake Provider JVM. It does not contact a real Connector
or a real user account.

## What the operator should be able to distinguish

| Observation | Meaning | Operator entry |
|---|---|---|
| Liveness `UP`, readiness `UP` | The packaged process is serving, migrations are current, the database is reachable and the configured principal has no unresolved Action. This is called `READY` in this runbook. | Continue the synthetic demo. |
| Readiness `OUT_OF_SERVICE`, `UNKNOWN > 0` | The process is serving but at least one provider result is unresolved. It has no success Receipt. | Inspect the owned attempt, then use `POST /api/v1/actions/{attemptId}/reconcile` while persisted authority and budget remain valid. |
| Action `SUCCEEDED` with one Receipt | A definite simulated provider object was reconciled and the terminal transition plus Receipt committed. | Treat only that Action as complete. |
| Readiness `OUT_OF_SERVICE`, `DISPATCHING > 0` or `RECONCILING > 0` | The application has an in-flight claim. Stage 1 has no safe stale-claim takeover. | Do not reset it by time or retry `EXECUTE`; escalate and keep the real-Connector Gate closed. |
| Readiness `DOWN`, migration fault | Flyway validation/current-version checks or database access failed. | Stop serving, repair or restore the database, then re-run validation. |

Liveness answers whether the process can serve. Readiness answers whether this
principal's durable boundary is fit for new work. An unresolved Action therefore
returns `503/OUT_OF_SERVICE` without preventing the recovery JVM from starting.
Readiness details are exposed only because this unauthenticated prototype is
loopback-only; they contain counts and fixed recovery labels, not identifiers,
content, credentials or connection details.

## Prerequisites and clean-checkout replay

Use Java 21 and a running Docker daemon. The Maven Wrapper downloads the declared
build dependencies. From the repository root:

```bash
git status --short
./scripts/run-stage1-operating-demo.sh
```

The first command should print nothing. The script runs the packaged-process
`RecoverableLocalActionHttpIT`. It starts PostgreSQL 18.4, two competing
application JVMs and a separate Fake Provider JVM. The provider owns an
independent temporary state file; it neither shares the application JVM nor uses
the application database as its object store.

A green run ends with `S3_PROCESS_RECEIPT` containing these stable facts:

```text
attemptRows=1 receiptRows=1 capabilityUses=2
providerObjects=1 providerCalls=2
states=PLANNED>DISPATCHING>UNKNOWN>RECONCILING>SUCCEEDED
initialReadiness=UP unresolvedReadiness=OUT_OF_SERVICE
finalAction=SUCCEEDED remainingUnknown=1 simulated=true
```

Process IDs and ports vary. Two provider calls are intentional: one `EXECUTE`
whose response is dropped and one `RECONCILE_ONLY`. The invariant is exactly one
observable simulated object and one Receipt, not exactly one provider call.
The remaining `UNKNOWN` comes from a separate no-object timeout and keeps
readiness honestly out of service.

The reviewable, identifier-free trace is
[2026-07-29 S4 synthetic operating trace](./traces/2026-07-29-s4-synthetic-operating-trace.json).

## Read readiness directly

For a manually started local application:

```bash
curl --fail --silent http://127.0.0.1:8080/actuator/health/liveness
curl --silent http://127.0.0.1:8080/actuator/health/readiness
```

Inspect:

```text
components.stage1Durability.details.migration
components.stage1Durability.details.actions.unresolved
components.stage1Durability.details.actions.statusCounts
components.stage1Durability.details.actions.recoveryEntries
components.stage1Durability.details.realConnectorGate
```

Counts are scoped to the server-configured principal. A second application
configured for another principal sees zero counts for the first one; no client
Header or request body can select the readiness principal.

Replay a real database-loss readiness fault:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am verify \
  -Dit.test=DatabaseUnavailableReadinessHttpIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

The packaged process first reaches readiness, then its PostgreSQL container is
stopped. Readiness becomes `503/DOWN` with only the fixed
`DATABASE_OR_MIGRATION_UNAVAILABLE` code, while liveness stays `UP`. The test
rejects a response containing the database password, JDBC URL, mapped port or
driver exception.

## Migration rehearsal

Run the real PostgreSQL upgrade and restore game day:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/postgres -am \
  -Dtest=Stage1MigrationAndRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The test creates independent populated V1, V2 and V3 schemas plus a fresh
install. It compares every column of the stable business rows before and after
upgrade: S1 Capture/request/content hashes; S2 Artifact head, version and
lineage; and S3 Action plan/artifact hashes, budget, transitions and Receipt.
All paths end on V3 with six business tables.

Run the incompatible migration process check:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am verify \
  -Dit.test=IncompatibleMigrationFailsFastHttpIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

This applies V1–V3, deliberately changes the applied V3 checksum, then starts
the packaged application. Flyway validation must make the process exit non-zero
before readiness is ever `UP`. The test also checks that its synthetic database
password is absent from the failure log.

Migrations are forward-only. This runbook does not promise a generic down
migration. An incompatible history is a fail-fast incident that needs a reviewed
forward fix or a verified restore.

## Backup/restore game day

`Stage1MigrationAndRecoveryTest` performs these real PostgreSQL operations
inside the Testcontainers database:

```text
pg_dump -Fc --no-owner --no-privileges -U emerge \
  -d emerge_stage1_evidence -n game_day -f /tmp/emerge-stage1-s4.dump

TRUNCATE TABLE captures CASCADE;

createdb -U emerge -T template0 emerge_stage1_restored

pg_restore --single-transaction --exit-on-error \
  --no-owner --no-privileges -U emerge \
  -d emerge_stage1_restored /tmp/emerge-stage1-s4.dump
```

The destructive injection affects only synthetic container data. Restore goes
to a new database. The test re-validates Flyway and compares all six Stage 1
business tables to the pre-fault snapshot. One 2026-07-29 local run measured
229 ms from recovery start through verified comparison. That is one observation,
not an RTO, RPO, capacity result or SLA.

## Fault matrix

| Fault | Durable observation | Supported entry | Limit |
|---|---|---|---|
| PostgreSQL unreachable | Readiness `DOWN`, safe `DATABASE_OR_MIGRATION_UNAVAILABLE` code | Restore connectivity, then re-check migration and data truth | No generic automated failover |
| Applied migration checksum differs | Packaged process exits non-zero and never reaches readiness | Reviewed forward fix or verified restore | No universal down migration |
| Provider creates one object, response is lost, application records `UNKNOWN` | `UNKNOWN`, no Receipt, one simulated object | New JVM calls `RECONCILE_ONLY` with the same identity | Proven only for Fake Provider |
| Provider gives no identifier and creates no object | `UNKNOWN`, no Receipt, zero objects | One explicit reconciliation within persisted budget; if still unknown, escalate | Never fabricate success |
| Provider succeeds, process dies before local `UNKNOWN`/terminal commit | May remain `DISPATCHING` | No safe entry in Stage 1 | Requires a future PostgreSQL-canonical lease/fencing slice |
| One principal inspects another's Action | Same `404` shape as missing | None | Server-configured prototype principal is not production authentication |

## Scope and open Gates

- Fake Provider objects are simulated and contain no real user data.
- There is no real Connector, authentication, LAN/public listener, Temporal,
  AgentKernel, model, UI, general outbox or metrics platform.
- ADR-0004 is `Proposed`: S3 proves recovery after durable `UNKNOWN`, but not
  safe takeover from a crash that leaves `DISPATCHING`.
- The real-Connector Gate remains blocked.
- Owner Teach-back, an independently diagnosed unknown fault, Founder Seed Log,
  interviews, reuse, quote, price request and payment evidence remain incomplete.
  The project owner should begin real entries with the existing templates; a
  reminder is not evidence that any of those activities happened.
