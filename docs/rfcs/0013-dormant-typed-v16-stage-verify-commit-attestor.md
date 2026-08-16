# RFC-0013: dormant typed V16 stage-verify-commit attestor

- Status: Experiment
- Date: 2026-08-12
- Extends: [RFC-0012](0012-dormant-v16-ed25519-public-verifier.md)
- Proposed decision:
  [ADR-0017](../architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md)

## Problem

V17 packages a reviewed V16 Ed25519 public verifier, but leaves the authority
sequence in a raw-JDBC test harness. A production caller would still have to
manually coordinate the V16 stage function, map its result, call a signer,
verify the signature, invoke commit and preserve one transaction.

The next safe slice needs one typed authority boundary without wiring an App
route, shipping a signer or claiming that PostgreSQL performs cryptographic
verification.

## Proposed experiment

Add a narrow Core command, signer port, attestor port and typed receipt, plus
one PostgreSQL adapter method:

```text
complete(command, signer) -> typed V16 overlay receipt
```

The adapter must run the following sequence in one `REQUIRES_NEW`,
`READ_COMMITTED` Spring transaction and one bound connection:

1. recheck the frozen V16 role/function/relation/trigger authority;
2. call the existing V16 stage function;
3. map the 25 fields needed to reconstruct the reviewed challenge while
   treating the remaining 37 stage fields as database-minted TCB material;
4. after a successful stage, call the supplied signer at most once (exactly
   once on paths that reach signing);
5. call the V17 verifier before any commit SQL;
6. call the existing V16 commit function and validate its closed ten-field
   typed receipt;
7. return only after the outer transaction commits.

There are no public Java `stage` or `commit` methods. The packaged adapter may
be opened only by a future composition owner; this experiment leaves every App
consumer absent. It reuses `emergeos_provider_attestor_v16` and adds no
migration, database role, grant or durable shape.

## Authority and failure boundary

The adapter freezes and rechecks:

- database/server identity, V16 login role properties and membership;
- database/schema authority, owner roles and zero relation/column authority;
- exact V16 stage/commit/deferred-guard functions plus their transitive V14
  framed-hash helper: bodies, result shapes, owners, language and global
  non-owner execution-grant closure;
- an exact authority-relevant catalog projection for the four V16 write-set
  relations and the nine V8-V15 prerequisite relations read by stage:
  columns, constraints, indexes, relation owner/kind/partition/persistence/
  RLS/replica identity, zero inheritance edges and zero user rewrite rules;
  exact non-owner relation-grant closure plus the prefix-writer and graph-reader
  role topology;
- complete deferred-trigger topology for the four V16 write-set relations.

The upstream trigger/function mutation provenance remains inherited V8-V15 DB
TCB. V18 freezes only the audited catalog projection, owner and ACL semantics.
The exact V16 functions validate and hash-bind the row values they read, but
V18 neither locks every prerequisite row nor re-proves how every prerequisite
row was originally produced. Privileged DDL/admin and concurrent DDL after the
per-call audit remain in the TCB; this experiment does not claim a serializable
catalog snapshot.

Within the stage/commit SQL operations, SQLSTATE `55000`, serialization,
deadlock and duplicate-key fences become one fixed, cause-free conflict.
Authority-audit failures and local `RuntimeException` from signer, verifier,
mapper or test probe cannot forge SQL provenance and become one fixed,
cause-free integrity error. The adapter performs no automatic retry.

## Acceptance boundary

The local Acceptance must prove:

- wrong-key verification reaches stage once but invokes commit SQL zero times,
  then rolls back the full public-table JSON/`xmin` image;
- a post-receipt injected fault observes exactly one commit SQL call and all
  three internal probes, yet the outer transaction fully rolls back;
- signer-supplied conflict text/cause cannot escape or impersonate a database
  fence;
- an extra relation `SELECT` grant to the V16 role, an extra V16-table
  `INSERT` grant and an extra commit `EXECUTE` grant to another LOGIN role,
  an extra framed-hash-helper `EXECUTE` grant or body drift, plus a fifth
  non-internal V16 trigger, prerequisite-role membership, an upstream index
  drift and a V16 rewrite rule are each caught before signer or the
  stage-mapped probe; the helper-body canary additionally observes exact stage
  SQL=0. The production role audit recovers after each drift is removed;
- a valid call writes one V16 validation/attribution/event/head closure, leaves
  the legacy head at sequence 13 and binds command fields plus receipt hashes
  to the durable rows;
- typed replay is fenced before signer invocation and leaves the public-table
  JSON/`xmin` image unchanged;
- actual module/app JARs contain all production and nested failure-path
  classes byte-for-byte, exclude test access/Acceptance, and retain zero App
  route consumer and zero shipping signer implementation.

## Trusted-computing boundary

PostgreSQL still accepts a shape-valid signature from the raw V16 credential.
The V16 login credential, caller process, 37 database-minted stage fields and
the reviewed Java adapter remain in the TCB. The Java verifier is authoritative
only for callers that enter through this typed method; PostgreSQL-native
Ed25519 remains unimplemented.

The signer is a capability supplied by the caller. This slice ships its
interface but no implementation, private key, key custody or rotation path.
Raw provider request/response bytes remain outside durable storage and
receipts.

## Non-goals

- no App composition, configuration, runtime invocation or Live route;
- no PostgreSQL-native signature verification or raw-credential bypass
  closure;
- no production signer, KMS/HSM, key custody or rotation;
- no full 62-field provider-semantic mapper or provider provenance proof;
- no V16 overlay reader, TX-B/TX-C or legacy-head advancement;
- no process hard-kill after commit outcome uncertainty, retry/reconciliation,
  same-attempt race or restart proof for this typed method;
- no DeepSeek live result, pricing freshness, billing or pre-egress authority.

## Evidence required before acceptance

- Core record and surface tests;
- PostgreSQL/Testcontainers wrong-key, authority-drift, post-receipt fault,
  signer-provenance, valid and replay evidence;
- exact bytecode allowlist plus directory/synthetic/actual-JAR negatives;
- target/classes, module JAR and shaded app JAR parity for all production
  classes including nested failure-path classes;
- clean verification, documentation links, frozen hashes and three independent
  post-fix reviews;
- a Chinese Build Note that keeps App wiring, signer/key custody,
  PostgreSQL-native verification and Authority/Live explicitly Red.
