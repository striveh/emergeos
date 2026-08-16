# RFC-0010: exact provider TX-A requirement guard

- Status: Experiment
- Date: 2026-08-12
- Extends:
  [RFC-0009](0009-exact-provider-profile-assertion-foundation.md)
- Proposed decision:
  [ADR-0014](../architecture/decisions/0014-exact-provider-tx-a-requirement-guard.md)

## Problem

V14 can assert a provider profile and exact pico-USD arithmetic, but it does
not bind that material to one graph attempt or authorize TX-A. The historical
V8 attribution row and V13 validation commit still represent pricing with
integer nano-USD per token. A DeepSeek cache-hit rate of 2.8 nano-USD per token
cannot be written there exactly.

Rounding that rate, changing the meaning of the frozen V8/V13 columns or
writing a compatibility attribution would create false durable truth. Before
an exact-pico overlay TX-A exists, an attempt selected for that future protocol
must therefore stop using the historical request-2 TX-A.

## Proposed experiment

V15 adds a one-way requirement guard only:

1. `agent_graph_exact_tx_a_requirements_v15` records that one exact seq13 graph
   attempt requires the future `PICO_OVERLAY_V1` TX-A. The row binds database,
   schema and V15 attestor-role OIDs, principal, attempt, manifest, immutable
   event/head13, request ordinal 2 and one ACTIVE V14 provider-profile hash.
2. `agent_graph_require_exact_tx_a_v15(varchar,char,char,varchar)` authenticates
   `emergeos_provider_attestor_v15`, locks the durable V8 head, derives the
   head/profile identities from PostgreSQL and inserts the immutable marker.
   The caller cannot supply a head hash or provider-profile hash.
3. Four deferred constraint triggers protect the marker and the historical V8
   attribution, event and head relations. Once the marker exists, any legacy
   request-2 13-to-14 transition fails with SQLSTATE `55000` and the fixed
   reason `V15 exact provider attribution is required`.
4. The independent V15 role has `LOGIN NOINHERIT`, one exact `EXECUTE` grant
   on the require function and zero relation privileges. No production Java
   V15 domain, port, mapper, signer, attestor or public API is added.
5. Attempts without a V15 marker retain the existing V13 signed TX-A path.

## Acceptance

- With no V13 validation row, a marker at seq13 makes the otherwise-valid
  production legacy TX-A reach PostgreSQL and fail with the exact V15 `55000`;
  all public-table JSON/`xmin`, marker and verified head13 remain unchanged.
- With a valid V13 REQUIRED policy and Ed25519 signer, stage and signature
  verification complete, but the legacy commit remains zero and the complete
  transaction rolls back to REQUIRED/head13 with the same V15 `55000`.
- A non-marked control still reaches V13 `CONSUMED` and head14.
- Under explicit PostgreSQL `READ COMMITTED`, two independent backends prove
  both lock orders with `pg_blocking_pids`: marker-first fences and rolls back
  V13 to REQUIRED/head13, while V13-first reaches CONSUMED/head14 and fences
  enrollment without creating a marker.
- V14-to-V15 migration preserves historical V1-V14 rows/`xmin`, V13/V14
  functions, canonical samples, existing trigger images and Flyway history;
  V15 adds one empty marker relation, two functions and four triggers.
- Provisioning and pure audit freeze exact owners, bodies, topology and ACL;
  relation-grant drift fails closed. First-party bytecode and the actual shaded
  JAR have no V15 authority consumer.
- Packaged crash, restart, resume and existing race evidence remain Green on
  the V15 schema for unmarked historical flows.

## Non-goals and claim boundary

V15 does not implement an exact-pico attribution, provider challenge,
signature, transcript, attestation, overlay event/head, TX-A, TX-B/TX-C resume
or pre-egress budget/effect authority. The requirement is enrolled at seq13,
not before provider egress. The V14 profile used by local Acceptance is a
synthetic admin fixture, not provider-live or commercial truth.

The V15 attestor chooses `provider_profile_id`. PostgreSQL requires that exact
V14 row to be ACTIVE and effective and derives its hash, but V15 does not call
the V14 statement assertion or prove that the profile matches request-2 model,
provider response, usage or cost. `requirement_hash` identifies the immutable
fence marker; it is not a validation, attestation or completion receipt.

Packaged crash/restart/race/resume checks in this slice are regressions for
existing unmarked paths. They are not marked-attempt process, restart, reclaim
or positive exact-pico commit evidence. The marked-attempt evidence is limited
to the in-process two-backend `READ COMMITTED` enrollment-versus-V13 race;
other isolation levels are fail-closed but are not promised to use SQLSTATE
`55000` rather than a serialization failure.

The legacy V8 head deliberately remains at seq13 for a marked attempt. A later
version must define a versioned exact-pico attribution and overlay read model;
it must not round 2.8 nano-USD into the V8/V13 domain.

## Rollback

V15 is forward-only. On a defect, revoke the V15 role's CONNECT or require
function EXECUTE privilege and stop enrolling new markers. Existing markers
are intentionally immutable; do not delete them, rewrite V8-V14 canonical
bytes or re-enable legacy TX-A for an enrolled attempt.
