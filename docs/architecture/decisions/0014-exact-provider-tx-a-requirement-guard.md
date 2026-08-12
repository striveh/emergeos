# ADR-0014: exact provider TX-A requirement guard

- Status: Proposed
- Date: 2026-08-12
- RFC:
  [RFC-0010](../../rfcs/0010-exact-provider-tx-a-requirement-guard.md)
- Extends:
  [ADR-0013](0013-exact-provider-profile-assertion-foundation.md)

## Context

V14 proves exact provider-profile and pico-USD statement arithmetic without
graph mutation. The frozen V8/V13 graph attribution uses integer nano-USD per
token and therefore cannot honestly represent DeepSeek's reviewed 2.8
nano-USD cache-hit rate. A versioned exact-pico TX-A is not yet implemented.

Allowing the historical TX-A to run after an attempt is selected for the
future exact protocol would make that selection unenforceable. Projecting a
rounded row into the old schema would be worse: it would create false durable
pricing truth.

## Decision

Adopt a forward-only V15 requirement guard:

- enroll an immutable `PICO_OVERLAY_V1` marker only against an exact durable
  seq13/head13 and ACTIVE V14 provider profile;
- derive the live head and profile hashes inside PostgreSQL while holding the
  head lock;
- use four deferred database triggers to reject historical V8/V13 request-2
  attribution/event/head mutation after enrollment;
- authenticate enrollment through an independent V15 role with one function
  EXECUTE grant and zero relation ACL;
- preserve V8-V14 relation and canonical identities and leave unmarked V13
  attempts behaviorally unchanged;
- add no production Java V15 API or shipping consumer.

## Consequences

- an enrolled attempt cannot silently fall back to rounded nano-USD TX-A;
- a valid V13 signer may run before the deferred V15 guard rolls the entire
  transaction back, so the claim is database commit authority, not zero signer
  invocation;
- the marker and legacy head are serialized on the same PostgreSQL head row;
- the V15 attestor selects a V14 profile ID; PostgreSQL freezes the matching
  ACTIVE/effective row and hash but does not re-run the V14 statement assertion
  or bind that profile to request/model/response/usage/cost truth;
- `requirement_hash` is a marker identity, not an attestation or completion
  receipt;
- marked attempts remain at legacy head13 until a later overlay protocol is
  implemented;
- existing V12 resume cannot operate on a marked attempt because no legacy
  head14 failure outcome exists;
- shipping/live, real-provider compatibility, pricing freshness and billing
  remain Red.

The executable race contract is scoped to PostgreSQL `READ COMMITTED`. Higher
isolation levels may fail closed with serialization SQLSTATE rather than the
fixed `55000`; this ADR does not normalize those diagnostics.

## Evidence required before acceptance

- exact SQLSTATE/message and full public-table JSON/`xmin` rollback for both
  legacy typed TX-A and valid signed V13 completion;
- two-backend `pg_blocking_pids` evidence for marker-first and V13-first lock
  order, with exactly one durable winner in each direction;
- non-marked V13 positive control;
- populated V14-to-V15 migration/history/function/trigger fidelity;
- exact role, owner, body, relation and trigger-topology audit, including an
  ACL drift canary;
- first-party directory, synthetic JAR and actual shaded-JAR zero-consumer
  Gate;
- packaged crash/restart/race/resume regression evidence;
- the packaged regressions above cover unmarked historical paths, not a marked
  attempt's restart, reclaim or positive commit;
- a Chinese Build Note that says `REQUIREMENT_GUARD_ONLY`,
  `exactPicoAttribution=NOT_IMPLEMENTED` and `TX-A=NOT_IMPLEMENTED`.

## Rollback

Revoke V15 enrollment authority and replace a flawed guard with a later
forward migration. Never mutate existing requirement rows, reinterpret the
V8/V13 nano-USD domain or remove the fence to force a marked attempt through
legacy TX-A.
