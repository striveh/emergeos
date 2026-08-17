# RFC-0014: fresh-JVM verified exact-pico overlay reader

- Status: Experiment
- Date: 2026-08-12
- Extends: [RFC-0013](0013-dormant-typed-v16-stage-verify-commit-attestor.md)
- Proposed decision:
  [ADR-0018](../architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md)

## Problem

V18 can create a typed V16 exact-pico overlay, but the durable result has no
production read capability. The legacy graph reader deliberately remains at
sequence 13, so treating it as proof of overlay sequence 14 would conflate two
protocols. A reader also must not trust stored component hashes or current key
status as a substitute for historical verification.

## Proposed experiment

Add a separate four-state Core read model and a PostgreSQL adapter:

```text
findVerified(expectedManifest) -> Missing | Required | Attributed | Invalid
```

- `Missing`: no V15 marker and no V16 overlay rows;
- `Required`: a valid V15 marker exists and all four V16 tables are empty;
  the compatible V15-only case may have no V13 validation row;
- `Attributed`: V15, V13, V14 and all four V16 rows form one independently
  recomputed canonical and Ed25519-valid historical closure;
- `Invalid`: manifest mismatch, invalid requirement, partial overlay, or
  complete but semantically/hash/signature-invalid overlay.

The adapter runs one `REQUIRES_NEW`, `REPEATABLE_READ`, enforced read-only
transaction. It does not call the legacy `REQUIRES_NEW` reader and therefore
does not split the base and overlay across snapshots. It returns hashes and
bounded metadata only; public-key DER, signature, nonce, database OIDs and raw
provider request/response bytes remain private implementation material.

## Reader authority

Provision `emergeos_exact_overlay_reader_v19` as a dedicated LOGIN,
NOINHERIT, non-privileged, membership-free role. It receives only database
CONNECT, public-schema USAGE and table-level SELECT on the exact thirteen
relations read by the adapter. It receives no DML, sequence, function,
column-level or grant-option authority. The existing graph reader is not
expanded.

Construction double-reads and every call rechecks server/database/schema/role
identity, the exact thirteen-relation SELECT set, global ACL closure, the
audited column/constraint/index/relation projection, zero inheritance edges,
zero user rewrite rules and absence of extra readable public table/view/
materialized-view/foreign-table authority. V13/V15/V16 prerequisite roles are
also required to retain their non-privileged topology.

Privileged DDL between the catalog audit and subsequent SELECT statements,
catalog framing delimiter ambiguity, and the inherited V8-V16 writers,
triggers and functions remain in the database TCB. This experiment does not
claim a serializable catalog snapshot or independently re-prove the historical
ACTIVE status at commit.

## Independent verification

Within the one database snapshot, Java independently verifies:

- expected/stored manifest, legacy events 1..13, head chain, request-1
  attribution and absence of legacy request-2/sequence-14 completion;
- optional V13 policy, V14 profile and V15 requirement identities and hashes;
- V16 statement, attribution, event, head, challenge, transcript and receipt
  canonical domains, row cardinality and cross-row bindings;
- public-key fingerprint, signature hash and Ed25519 signature;
- historical key/profile/session/challenge windows and exact pico-USD cost
  using `BigInteger`.

Read-time `REVOKED` status and a challenge whose expiry is now in the past do
not invalidate an already consumed historical receipt. `Attributed` therefore
means `VALID_AT_COMMIT` under the inherited write-time TCB, not current
authorization, current readiness or pricing freshness.

## Acceptance boundary

The local Acceptance must prove with production classes loaded from the
shaded app JAR and credentials supplied only by framed standard input:

- an unmarked sequence-13 attempt returns `Missing`;
- a valid V15 marker without V13 returns `Required`;
- a V18-completed attempt remains `Attributed` after key/profile revocation
  and after the V16 challenge expiry;
- a three-row partial overlay returns `EXACT_OVERLAY_PARTIAL` rather than
  falling back to the legacy sequence-13 snapshot;
- cross-row-consistent canonical input drift with stored hashes unchanged,
  and an invalid Ed25519 signature with signature/receipt hashes recomputed,
  both return `EXACT_OVERLAY_INVALID`;
- each restoration again returns `Attributed`, excluding test-order
  pollution;
- direct reader-role defaults remain read-write while the adapter transaction
  is read-only; legacy-role privilege drift and an extra readable public view
  fail closed;
- every read leaves the public-table row/JSON/`xmin` image and legacy
  sequence-13 snapshot unchanged.

## Non-goals

- no App route, configuration, runtime invocation or Live provider call;
- no PostgreSQL restart, hard-kill, connection-loss, same-attempt race or
  process reconciliation evidence;
- no current-authorization/readiness assertion from historical receipts;
- no production signer, key custody, PostgreSQL-native signature verification
  or raw V16 credential-bypass closure;
- no legacy head advancement, TX-B/TX-C, provider network, billing,
  DeepSeek r1/r2/r3 or Commercial evidence.
