# ADR-0018: fresh-JVM verified exact-pico overlay reader

- Status: Proposed
- Date: 2026-08-12
- RFC: [RFC-0014](../../rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md)

## Context

V18 writes a separate V16 overlay while the legacy graph cursor remains at
sequence 13. A future caller needs a bounded, typed way to distinguish no
marker, requirement-only, verified attribution and durable corruption without
advancing or weakening the legacy reader.

## Decision

- Add a sealed four-state Core result and one production PostgreSQL reader
  opened through `open(DataSource)` and queried through
  `findVerified(GraphAttemptManifest)`.
- Use a dedicated V19 read role with exactly thirteen direct SELECT grants;
  do not expand the legacy graph-reader credential and expose no SECDEF read
  function.
- Read and re-verify legacy and overlay rows in one enforced read-only,
  repeatable-read transaction and one Spring-bound connection.
- Independently recompute the V13/V14/V15 identities and all V16 canonical
  component hashes, fingerprint/signature hashes and Ed25519 signature.
- Keep public-key DER, signature and nonce private to the adapter; return only
  bounded hashes, semantic metadata and exact pico cost.
- Keep every App consumer absent. The capability is packaged, not configured
  or running.

## Consequences

- A fresh JVM can verify a historical V16 receipt without changing legacy
  sequence 13 or trusting stored component hashes alone.
- `Required` remains compatible with V15 markers that predate or omit V13.
- A later `REVOKED` status or elapsed V16 challenge does not erase historical
  validity; the result does not assert current authorization.
- The dedicated role and reader process can see the bounded thirteen-relation
  durable evidence, including public verification material, and therefore
  remain in the confidentiality TCB.
- Privileged/concurrent DDL and inherited V8-V16 write provenance remain in
  the TCB. No database restart or process-fault durability claim follows from
  a fresh child JVM alone.
- Overall Authority/Live remains Red because App wiring, provider provenance,
  raw-credential bypass closure and key custody are still absent.

## Alternatives rejected

- **Reuse the legacy graph reader.** It would either split snapshots through a
  nested transaction or expand a previously frozen role and read model.
- **Return only an optional receipt.** It cannot distinguish `Required`,
  partial corruption and true absence.
- **Trust a database-projected receipt.** It would not independently recompute
  the durable canonical closure.
- **Treat current `REVOKED` as historical invalidity.** Current authorization
  state cannot rewrite what was valid at commit.

## Rollback

Keep App consumers at zero, revoke the dedicated role's CONNECT/USAGE/SELECT
grants and stop packaging the reader before superseding this Proposed
decision. Schema remains V16; there is no Flyway migration or durable-row
rollback in this slice.
