# Build Note · 2026-07-28 · S2 Conflict-safe Revision

- Change class: `V`
- Task / commit: Stage 1 S2, this focused commit

## Outcome

Added one PostgreSQL-backed Artifact lineage slice. Starting from an owned S1 Capture, the loopback-only
API creates Artifact v1. When two separately packaged application processes revise the same expected
base, exactly one creates v2 and the other receives a dedicated, non-leaking conflict. After both
processes are terminated, a third JVM returns the exact pre-restart lineage.

## Why this matters

An editable Artifact is unsafe if a stale client can silently replace a newer version. S2 establishes a
database-owned linear history and explicit recovery entry without persisting the Stage 0 Manifestation,
Working Self, ActionPlan or Reflection graph.

## Evidence

- First Acceptance Red, recorded before production implementation:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=ConflictSafeRevisionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` packaged the
  application, brought two independent JVMs to health, created the S1 source Capture with `201`, then
  failed because `POST /api/v1/artifacts` returned `404` instead of `201`.
- Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core -am
  -Dtest=ArtifactLineageServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` failed compilation
  because the framework-neutral lineage, commands, store port, dedicated conflict and service did not
  exist.
- Focused Green:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am
  -Dtest=ArtifactLineageInvariantTest,ArtifactLineageServiceTest,PostgresArtifactLineageStoreTest,ArtifactHttpTest
  -Dsurefire.failIfNoSpecifiedTests=false test` passed 6 selected Core tests, 5 PostgreSQL tests and
  4 HTTP tests. They cover continuous parent version/hash links, the expected version/hash mismatch
  matrix, exact-base foreign-owner rejection, database time canonicalization, client-field rejection
  and foreign/missing GET/PUT response equivalence.
- Transaction fault evidence: a Testcontainers trigger forces the v2 lineage insert to fail after the
  head CAS. The transaction rolls back to head v1 with exactly one immutable version. A separate
  database test proves that v2 with a NULL base pair is rejected.
- Preliminary packaged-process Receipt at 16:23 +08:00:
  `firstPid=98600`, `secondPid=98615`, `restartedPid=98625`,
  `otherPrincipalPid=98636`, `winner=200`, `conflict=409`, `artifactRows=1`,
  `versionRows=2`, `currentVersion=2`, `baseLinked=true`, `foreignGet=404`,
  `missingGet=404`, `foreignPut=404`, `missingPut=404`. The test also proves that the losing
  content has zero database rows, v1 is unchanged, the pre/post-restart JSON is identical and all
  process IDs are distinct.
- S1 packaged regression at 16:23 +08:00:
  `firstPid=98878`, `restartedPid=98891`, `otherPrincipalPid=98895`,
  `restart=200`, `replay=200`, `conflict=409`, `foreign=404`, `missing=404`; wildcard binding
  rejection also remains in that suite.
- Independent read-only production review found `P0=0`, `P1=0`. It identified two P2 evidence/test
  risks: failure-path process cleanup and the absence of a V1-with-data → V2 migration rehearsal.
  The process group now performs best-effort cleanup of every JVM and temporary log even when startup
  or a sibling cleanup fails; the second item remains explicitly unclaimed for the later Stage 1
  operating/upgrade Gate.
- Post-review targeted Receipt at 16:33 +08:00:
  `firstPid=4515`, `secondPid=4529`, `restartedPid=4539`,
  `otherPrincipalPid=4540`, with one `200`, one non-leaking `409`, two lineage rows,
  current version 2, linked base, equivalent foreign/missing GET/PUT `404`, and all modules green.
- Required final verification at 16:34 +08:00:
  `./scripts/verify-contracts.sh` passed 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` passed 54 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 18 Core, 6 in-memory adapter,
  11 PostgreSQL adapter, 17 API and 3 packaged-process tests. The final full run printed S2 PIDs
  `5136/5142/5152/5162` with one winner, one conflict and the same database/restart/isolation
  assertions; S1 regressed green with PIDs `5177/5187/5188`.

## Design and version-sensitive checks

- Core adds a separate `ArtifactLineageEntry/ArtifactLineage` boundary rather than weakening the existing
  Stage 0 `ArtifactVersion`, whose evidence, Working Self and generator fields remain mandatory.
- Flyway V2 adds only an Artifact head and immutable version table. Composite foreign keys bind an
  Artifact to an owned Capture, every v2+ row to the exact preceding version/hash, and the head to the
  exact current row.
- One explicit `READ COMMITTED` transaction executes
  `UPDATE artifacts ... WHERE principal_id + artifact_id + current_version + current_hash`, appends
  the version only after one head row wins, then returns the database-canonical ordered lineage. A zero
  update is classified only with an owner-scoped head lookup.
- A `409` returns stable type/title/detail plus `currentVersion`; it does not expose content,
  `currentHash`, principal or Capture. The recovery entry is a fresh owner-scoped GET.
- Repository versions were resolved from the local Spring Boot `4.1.0` dependency graph: Spring JDBC
  `7.0.8`, Flyway `12.4.0`, PostgreSQL JDBC `42.7.11` and Testcontainers `2.0.5`.
  Version-sensitive behavior was checked against the official
  [PostgreSQL 18 transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html),
  [PostgreSQL constraints](https://www.postgresql.org/docs/18/ddl-constraints.html),
  [PostgreSQL date/time types](https://www.postgresql.org/docs/18/datatype-datetime.html),
  [Spring JdbcClient API](https://docs.spring.io/spring-framework/docs/7.0.x/javadoc-api/org/springframework/jdbc/core/simple/JdbcClient.html),
  [Flyway versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
  and [Flyway PostgreSQL support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).

## Failure and limits

- The first integrated Green attempt exposed a test-fixture regression: S1 HTTP tests truncated
  `captures` alone after V2 added a foreign key. Persistent API tests now truncate
  `artifact_versions`, `artifacts` and `captures` together.
- Read-only architecture review found that PostgreSQL CHECK expressions treat NULL as passing and a
  default composite foreign key skips a partially NULL key. V2 now explicitly requires both
  `base_version` and `base_hash` for v2+, with a real database regression test.
- Only Capture and the separate Artifact lineage are durable. Stage 0 Manifestation, Action, Receipt
  and Reflection remain in memory.
- There is no login, LAN access, encryption, Artifact data-class policy, backup/restore evidence,
  upgrade rehearsal, Temporal, real model or real Connector. Only synthetic non-sensitive values were
  used.
- A competing uncommitted head update still has no product-level lock-wait budget. This remains an
  accurately documented P2 operating risk rather than being misreported as a semantic `409`.
- The packaged test still has the low-probability release-then-bind loopback port race documented in S1.
- The independent review's failure-path cleanup P2 is fixed and covered by the post-review packaged run.
  A V1-with-data → V2 upgrade rehearsal remains a declared Stage 1 operating evidence gap; a fresh
  install through V1 and V2 is green, but it is not a substitute for that rehearsal.
- No human Teach-back, transfer/debugging exercise, interview, reuse, price request or payment is claimed.

## Principle learned

Optimistic concurrency is a database state transition, not a controller precheck: the winning predicate
must include authority and both expected base coordinates, and the mutable head plus immutable evidence
must commit or roll back together.

## AI Coding and Agent Engineering receipts

- AI Coding capability practiced: outside-in Red, focused Red, minimum implementation, exact-base
  adversarial tests, injected transaction failure, process evidence and independent-review handoff.
- Agent Engineering concept and target mastery: PostgreSQL compare-and-swap, linear lineage and
  non-leaking recovery; target `L3`, human level remains `L0 / unassessed`.
- My explanation: draft for owner Teach-back — both JVMs may submit the same v1/hash1, but PostgreSQL
  serializes updates to the same head; after the first commits v2, the second rechecks all four WHERE
  conditions, updates zero rows and reloads rather than overwriting.
- Failure I can now diagnose: read-then-insert races, version-only checks, principal-free CAS, separate
  head/version transactions, NULL base loopholes and stale foreign probes that falsely appear to test
  authorization.
- Codex/Subagent division: two read-only agents handled architecture/API-version research and test/fault
  design; a third performed the independent production review. The main thread remained the only writer
  and reviewed their conclusions and the complete Diff.
- What I rejected or corrected: reusing Stage 0 `ArtifactVersion` would require fake Working Self or
  weakened invariants; returning `currentHash` in a conflict would expose a content-derived fingerprint;
  a foreign PUT must use the exact current base or it can miss a principal-predicate defect.
- What remains unlearned: owner-led no-notes explanation, changed-constraint implementation, independent
  fault diagnosis and the spaced review.

## Career and business receipts

- Demo / Case Card / interview question: the four-PID race/restart Receipt and rollback injection can
  become a CAS Case Card after owner Teach-back; they are not yet personal mastery evidence.
- User use, retention, payment or rejection evidence: none.
- Business next action: the project owner should begin real entries with the existing Founder Seed Log
  and problem-interview template. This reminder and the templates do not count as an interview, Seed
  reuse, quote, price request or payment.

## Next falsifiable hypothesis

S3 should prove that a local ActionAttempt written before a fake side effect can be reconciled after a
real process crash without double execution or a false completion Receipt.

## Public derivatives

- Short post: pending human approval and Teach-back.
- Visual/demo: pending human approval.
- Weekly long-form: pending human approval.
