# ADR-0004: Persist action attempts before enabling real connectors

- Status: Proposed
- Date: 2026-07-28
- Evidence correction: 2026-07-29

## Context

An external platform can accept a write while the client times out before receiving or storing the
response. Retrying a plain `execute` call can then publish twice. An in-process idempotent stub can
exercise the basic retry contract, but it cannot prove recovery across process crashes, multiple
instances, or a provider that does not support idempotency keys.

## Decision

Before the first real connector is enabled:

1. Persist an `ActionAttempt` before dispatch, with a unique constraint on
   `(connector, accountRef, idempotencyKey)`.
2. Use explicit states:
   `PLANNED → DISPATCHING → SUCCEEDED | FAILED | UNKNOWN → RECONCILING`.
3. Require connectors to implement `executeOrReconcile`, always receiving the same idempotency key.
4. Prefer provider-native idempotency. Where it is absent, define a deterministic lookup marker or
   a human reconciliation path; never retry an ambiguous write blindly.
5. Bind the Capability to the exact ActionPlan, connector audience, account, artifact hash,
   idempotency key and expiry. Enforce call budgets in a persistent, atomic Capability Use Ledger.
6. Write a Receipt only from a provider response or reconciliation result. `UNKNOWN` must remain an
   explicit non-completed outcome until reconciled.

## Required evidence before acceptance

- A database-backed fault test kills the process after provider success and before Receipt commit.
- A retry reconciles the existing external object without creating another.
- Two service instances racing the same key produce one attempt and one external object.
- A provider with no external identifier after timeout remains `UNKNOWN`, not `SUCCEEDED`.
- Capability audience/account/plan mismatches are rejected deterministically.

## Implemented partial evidence

S3 implemented and verified the following simulated local Action boundary:

- [`RecoverableLocalActionHttpIT`](../../../apps/api/src/test/java/io/emergeos/api/RecoverableLocalActionHttpIT.java)
  runs one file-backed Fake Provider JVM and two packaged application JVMs against PostgreSQL. The provider
  persists one simulated object and drops the response; the database holds `UNKNOWN` with no Receipt, both
  application JVMs are forcibly terminated, and a new JVM reconciles the same object into one Receipt.
- The same test proves two service instances racing the exact provider key create one ActionAttempt, spend
  one dispatch call and expose one simulated object. A separate timeout-without-object path remains
  `UNKNOWN` with no Receipt.
- [`PostgresActionAttemptStoreTest`](../../../adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresActionAttemptStoreTest.java)
  proves exact uniqueness and Capability predicates, atomic call budgets, coherent concurrent reads,
  reconciliation after the Artifact head advances, and terminal-state/Receipt rollback.
- [`ActionAttemptInvariantTest`](../../../modules/core/src/test/java/io/emergeos/core/domain/ActionAttemptInvariantTest.java)
  and
  [`HttpSimulatedActionProviderTest`](../../../apps/api/src/test/java/io/emergeos/api/HttpSimulatedActionProviderTest.java)
  cover exact principal/plan/connector audience/account/Artifact/key/expiry binding and reject a substituted
  provider echo.
- The dated [S3 Build Note](../../operations/build-notes/2026-07-28-s3-recoverable-local-action.md) records
  Red, process IDs, database/provider counts, independent review and limitations.

That evidence kills the application only after the dropped provider response has already been recorded as
`UNKNOWN`. It does not kill the process while PostgreSQL is still `DISPATCHING`, between provider success
and the first durable local outcome. The current state machine has no safe stale-`DISPATCHING` ownership,
fencing or takeover policy. A 2026-07-29 independent read-only re-audit therefore corrected the earlier
overstatement that all Required evidence had been met.

The implemented `UNKNOWN → RECONCILING` boundary remains valid, but this ADR stays Proposed and blocks any
real Connector until the missing crash window has PostgreSQL-canonical ownership/fencing, database-time
expiry semantics, atomic budget handling, false-takeover/concurrency evidence and a real process-kill
acceptance test. `updated_at` thresholds, startup resets and sleep-based tests are explicitly insufficient.
Nothing here claims real-platform exactly-once calls, production authentication, Temporal or public access.

## Consequences

The PostgreSQL adapter and connector contract must be built before Temporal or a real publishing
integration can claim durable recovery. Temporal coordinates retries and waiting; it does not
replace the ActionAttempt table, database uniqueness, provider idempotency or reconciliation.
