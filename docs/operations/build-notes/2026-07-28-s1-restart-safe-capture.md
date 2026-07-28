# Build Note · 2026-07-28 · S1 Restart-safe Capture

- Change class: `V`
- Task / commit: Stage 1 S1, this focused commit

## Outcome

Added one PostgreSQL-backed Capture slice. A loopback-only packaged application can create a synthetic
`PUBLIC` or `PERSONAL` Capture, be forcibly terminated, and a different JVM can retrieve the exact
original from the same database.

## Why this matters

Stage 0 only proved in-process behavior. S1 establishes the first durable boundary without pretending
that the whole Manifestation aggregate, authentication, workflow runtime or external connectors are
production-ready.

## Evidence

- First Acceptance Red, recorded before production implementation:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RestartSafeCaptureHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` reached a healthy packaged
  application and failed because `POST /api/v1/captures` returned `404` instead of `201`.
- Focused Red:
  `./mvnw --batch-mode --no-transfer-progress -pl modules/core -am
  -Dtest=CaptureCommandTest -Dsurefire.failIfNoSpecifiedTests=false test` failed compilation because
  `CaptureCommand` and the canonical request hash did not exist.
- Database-focused verification:
  `./mvnw --batch-mode --no-transfer-progress -pl adapters/postgres -am test` passed 18 Core/adapter
  tests, including six real PostgreSQL tests for replay, owner isolation, database time canonicalization,
  concurrent same-hash convergence, concurrent different-hash conflict and preservation of the winner.
- Process/fault verification:
  `./mvnw --batch-mode --no-transfer-progress -pl apps/api -am verify
  -Dit.test=RestartSafeCaptureHttpIT -Dfailsafe.failIfNoSpecifiedTests=false` had an intermediate
  pass with `firstPid=59845`, `restartedPid=59883` and `otherPrincipalPid=59893`. Its printed receipt
  recorded restart `200` and indistinguishable foreign/missing `404` responses; it did not print
  replay/conflict fields and therefore is not used as their final receipt. The same packaged suite
  also proved that `server.address=0.0.0.0` fails before startup.
- Complete post-review process receipt at 15:24 +08:00:
  `firstPid=68652`, `restartedPid=68698`, `otherPrincipalPid=68735`, `restart=200`,
  `replay=200`, `conflict=409`, `foreign=404`, `missing=404`. All three were packaged
  application processes sharing one Testcontainers PostgreSQL; the first process was confirmed dead
  before the restarted process read the original.
- Independent read-only production review found no P0. Its one P1 identified the intermediate receipt
  overclaim above; the wording was corrected and this new complete receipt generated. The reviewer
  rechecked the fixes and found no remaining P0/P1.
- Final repository verification at 15:25 +08:00:
  `./scripts/verify-contracts.sh` passed 4 schemas, 8 fixtures and 1 synthetic task pack;
  `./scripts/verify-doc-links.sh` passed 52 Markdown files; and
  `./mvnw --batch-mode --no-transfer-progress verify` passed 39 Java tests across Core,
  in-memory, PostgreSQL, HTTP and packaged-process suites. The full Maven run printed
  `firstPid=69640`, `restartedPid=69651`, `otherPrincipalPid=69652`, `restart=200`,
  `replay=200`, `conflict=409`, `foreign=404`, `missing=404`.
- The migration creates only `captures` plus Flyway schema history. Database identity and SDK types
  remain in the adapter; Core remains pure Java and is still protected by Maven Enforcer.
- Repository versions were resolved from the Spring Boot `4.1.0` BOM: Spring JDBC `7.0.8`, Flyway
  `12.4.0`, PostgreSQL JDBC `42.7.11` and Testcontainers `2.0.5`. Version-sensitive use was checked
  against the official
  [Spring Boot Flyway guidance](https://docs.spring.io/spring-boot/how-to/data-initialization.html),
  [Flyway PostgreSQL module reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database),
  [Spring JdbcClient API](https://docs.spring.io/spring-framework/docs/7.0.x/javadoc-api/org/springframework/jdbc/core/simple/JdbcClient.html),
  [Spring Boot EnvironmentPostProcessor API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/EnvironmentPostProcessor.html)
  and [Testcontainers PostgreSQL documentation](https://java.testcontainers.org/modules/databases/postgres/).

## Failure and limits

- Docker was not initially running; the Red could not be accepted until the declared Testcontainers
  environment was available. This was an environment precondition, not a product Red.
- Main-thread Diff review found that returning the pre-insert Java object could expose nanosecond time on
  the first response while PostgreSQL rounds to microseconds. A focused PostgreSQL test first failed by
  revealing `123456789ns → 123457µs`; the adapter now returns the database-canonical row on both initial
  write and replay.
- Only Capture is durable. Manifestation, Revision, Action, Receipt and Reflection remain in memory.
- There is no login, LAN access, encryption, backup/restore evidence, upgrade-path evidence, Temporal,
  real model or real connector. Loopback binding plus a configured principal is a prototype containment
  boundary, not authentication.
- Only synthetic test values were used.
- No human Teach-back, transfer/debugging exercise, interview, reuse, price request or payment is claimed.
- Independent review left two non-blocking P2 risks for later operating work: a competing uncommitted
  transaction has no product-level wait budget, and the packaged test's release-then-bind ephemeral port
  selection has a low-probability local race. The review's NUL-input P2 was corrected at the Core boundary.

## Principle learned

Idempotency is a stored decision, not an in-memory retry trick: a database uniqueness boundary elects
one committed request, while a server-computed semantic hash distinguishes a safe replay from a conflicting
reuse of the same nonce.

## AI Coding and Agent Engineering receipts

- AI Coding capability practiced: Acceptance Red, Focused Red, minimum implementation, concurrency/fault
  evidence, Diff review and reproducible receipt.
- Agent Engineering concept and target mastery: database uniqueness, replay semantics and cross-process
  recovery; target `L3`, human level still unassessed.
- My explanation: draft for owner Teach-back — the unique `(principal_id, client_nonce)` key chooses one
  committed Capture; a subsequent `SELECT` in the same `READ COMMITTED` transaction reads that winner,
  and Core compares the versioned request hash.
- Failure I can now diagnose: check-then-insert races, client-controlled identity/hash and same-JVM
  “restart” demonstrations do not prove the required invariant.
- Codex/Subagent division: two read-only agents explored architecture and test design; the main thread was
  the only writer and owned Red evidence, implementation, integration and Diff review.
- What I rejected or corrected: persisting the entire existing Manifestation graph merely to satisfy S1;
  a separate thin Capture boundary preserved the vertical scope.
- What remains unlearned: owner-led no-notes explanation, a changed-constraint implementation and
  independent debugging exercise.

## Career and business receipts

- Demo / Case Card / interview question: the PID-backed restart and concurrency receipt can become a Case
  Card after the project owner completes Teach-back; it is not yet personal mastery evidence.
- User use, retention, payment or rejection evidence: none.
- Business next action: the project owner should begin real entries with the existing Founder Seed Log and
  problem-interview template. Creating the templates or this reminder does not count as an interview,
  reuse, price request or payment.

## Next falsifiable hypothesis

S2 should prove that two application instances revising the same Artifact version produce exactly one
winner through database compare-and-swap, with a stable conflict response and no silent overwrite.

## Public derivatives

- Short post: pending human approval and Teach-back.
- Visual/demo: pending human approval.
- Weekly long-form: pending human approval.
