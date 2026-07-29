# Case Card · Stage 1 Durable Operations

- Date: 2026-07-29
- Status: engineering rehearsal complete; owner Teach-back unassessed
- Data: synthetic only

## Problem

Make the S1–S3 PostgreSQL flow operable by another developer: distinguish a
live process from durable readiness, preserve truth through upgrades and restore,
and avoid claiming recovery for a crash window the implementation does not
support.

## Constraints

- one modular monolith and framework-neutral Core;
- loopback-only, server-configured prototype principal;
- PostgreSQL is the durable truth for Capture, Artifact lineage, ActionAttempt
  and Receipt;
- Fake Provider must remain an independent process with independent persistence;
- no Temporal, real Connector, authentication, general outbox/metrics platform,
  model, UI or real user data;
- S4 may add operating evidence, not a hidden runtime/lease subsystem.

## Options considered

1. Mark `DISPATCHING` stale after elapsed wall-clock time and reset it.
2. Add a full PostgreSQL-canonical owner/lease/fencing protocol in S4.
3. Correct the evidence claim, keep the Gate closed, and expose the unsupported
   state explicitly while completing bounded readiness, upgrade and restore
   evidence.

## Decision and why

Selected option 3. A timestamp reset can allow two live instances to dispatch or
commit the same Action. A safe option 2 needs database time, owner identity,
fencing epoch, heartbeat/expiry, fenced outcome commits and false-takeover tests;
that is a separate runtime slice. S4 therefore returned ADR-0004 to `Proposed`
and made `DISPATCHING`/`RECONCILING` visible as
`NO_SAFE_STALE_CLAIM_RECOVERY`. The real Connector Gate stays blocked.

## Failure injected or encountered

- Outside-in packaged readiness was initially only Boot's built-in `UP` and had
  no durable component.
- The first Green attempt waited for global health; an existing `UNKNOWN`
  correctly made global health `503`, so the recovery JVM was falsely treated as
  not started. Startup polling moved to liveness while readiness stayed truthful.
- Populated V1, V2 and V3 databases were upgraded independently and compared
  column-for-column with their pre-upgrade truth.
- A custom-format PostgreSQL backup was taken, all synthetic Stage 1 rows were
  removed through a cascading truncate, and the archive was restored into a new
  database.
- The recorded V3 Flyway checksum was deliberately changed; the packaged
  application exited non-zero before reaching readiness.

## Evidence

- [Stage 1 Operating Runbook](../../operations/stage1-operating-runbook.md)
- [S4 Build Note](../../operations/build-notes/2026-07-29-s4-operating-gate-closure.md)
- [Sanitized synthetic trace](../../operations/traces/2026-07-29-s4-synthetic-operating-trace.json)
- `PostgresStage1OperationsProbeTest`
- `Stage1MigrationAndRecoveryTest`
- `IncompatibleMigrationFailsFastHttpIT`
- `RecoverableLocalActionHttpIT`

Stable facts from the packaged Action scenario:

```text
initial readiness: UP
unresolved readiness: OUT_OF_SERVICE
recovered states: PLANNED > DISPATCHING > UNKNOWN > RECONCILING > SUCCEEDED
simulated provider objects: 1
provider calls for recovered attempt: 2
successful Receipts: 1
remaining no-object UNKNOWN attempts: 1
```

One local backup/restore run observed 229 ms from recovery start through the
verified comparison. It is not an RTO, RPO, load result or SLA.

## User / production result

No production user result exists. The automated clean-checkout replay and
runbook are ready for another developer to run; no human completion or
understanding has yet been observed. The provider object is simulated and no
real platform was contacted.

## What changed my mind

The previous ADR acceptance depended on a mistaken reading of the process fault.
Line-by-line review showed that the test waited until PostgreSQL already held
`UNKNOWN` before killing the applications. The exact evidence changed the
decision: retain the valuable `UNKNOWN → RECONCILING` proof, retract the broader
crash-window claim and block real Connectors.

## Remaining risk

- provider success followed by process death before local outcome persistence
  can leave an attempt in `DISPATCHING`;
- no safe lease/fencing/ownership takeover exists;
- readiness has no alert routing, automated failover or capacity evidence;
- loopback server configuration is not production authentication;
- owner no-notes Teach-back, changed-constraint implementation and independent
  unknown-fault diagnosis remain incomplete;
- interviews, real Seeds, repeated use, price requests and payment evidence
  remain incomplete.

## Owner defense prompts

These are future exercises, not completed evidence:

- In 30 seconds, distinguish process liveness, durable readiness, `UNKNOWN` and
  `SUCCEEDED`.
- Draw the exact transaction/provider window that can strand
  `DISPATCHING`. Explain why `updated_at < now - 30s` is unsafe.
- Explain why a successful restore needs data/hash/lineage/Receipt comparison,
  not merely a recreated table.
- Change the recovery design so a live old owner cannot be falsely taken over;
  name the PostgreSQL fields and fenced predicates before writing code.
