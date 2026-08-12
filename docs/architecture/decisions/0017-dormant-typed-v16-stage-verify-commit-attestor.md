# ADR-0017: dormant typed V16 stage-verify-commit attestor

- Status: Proposed
- Date: 2026-08-12
- RFC: [RFC-0013](../../rfcs/0013-dormant-typed-v16-stage-verify-commit-attestor.md)

## Context

V16 supplies an atomic exact-pico overlay protocol and V17 supplies a reviewed
public verifier. Neither provides a production typed orchestration boundary.
Leaving future callers to reproduce a 62-column stage ABI, transaction scope,
signature ordering and ten-field receipt would create multiple incompatible
authority implementations.

## Decision

Add one dormant, packaged PostgreSQL adapter implementing a narrow typed port.

- Public surface is `open(DataSource)` and `complete(command, signer)` only;
  raw stage and commit remain private implementation details.
- Stage, local Ed25519 verification and commit run on one Spring-bound
  connection in one `REQUIRES_NEW`, `READ_COMMITTED` transaction.
- Authority identity is double-read at construction and rechecked in every
  transaction against the V16 runtime role, database/schema authority, exact
  function-grantee closure, direct function and transitive framed-hash-helper
  bodies/results, the audited authority-relevant catalog projection and global
  ACL closure for the four V16 write-set plus nine prerequisite relations,
  prerequisite role topology, zero inheritance/rewrite edges, and the four V16
  deferred triggers.
- Local `RuntimeException` is normalized separately from SQL failures;
  caller-supplied runtime exceptions cannot forge SQLSTATE provenance or leak
  text/cause.
- The adapter maps the 25 fields required for the reviewed challenge. The
  other 37 stage fields and their component hashes remain explicitly
  database-minted TCB inputs, not a claimed provider-semantic mapping.
- Add no migration, role, grant, App composition or production signer.

## Consequences

- A future composition owner has one reviewed typed transaction boundary
  instead of raw JDBC fragments.
- Wrong signatures are rejected before commit SQL on this path, and faults
  after the commit function still roll back the outer transaction.
- The shipping artifact contains a public authority adapter, but no first-party
  App consumer or signer implementation; packaged does not mean configured or
  running.
- Raw V16 credential holders can still bypass Java verification, so
  PostgreSQL-native signature authority and overall Authority/Live remain Red.
- Legacy graph readers still observe head 13; V16 overlay head 14 remains a
  separate protocol.
- Upstream V8-V15 trigger/function mutation provenance remains inherited DB
  TCB. V18 freezes only the audited prerequisite catalog projection, owner and
  ACL; the V16 functions validate and hash-bind values they read, but do not
  lock every prerequisite row or independently re-prove how existing rows were
  produced. Privileged/concurrent DDL after the audit remains in the TCB.

## Alternatives rejected

- **Expose public `stage` and `commit`.** That permits callers to split the
  transaction or verify after commit.
- **Trust the 62-column result without frozen mapping and authority.** That
  permits silent ABI, ACL or trigger drift.
- **Pass through signer exceptions.** That lets caller text/cause leak and
  impersonate a database conflict.
- **Ship a local signer.** Key custody is not implemented and a test key must
  never become production authority.
- **Call this live wiring.** Static bytecode and artifact evidence prove a
  dormant packaged capability only.

## Rollback

Keep App consumers at zero, stop exposing the adapter in a future artifact and
supersede this Proposed decision. No V16 database rollback is required because
this slice changes no migration, role, grant or durable schema.
