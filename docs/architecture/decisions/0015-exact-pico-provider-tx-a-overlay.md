# ADR-0015: exact-pico provider TX-A overlay

- Status: Proposed
- Date: 2026-08-12
- RFC:
  [RFC-0011](../../rfcs/0011-exact-pico-provider-tx-a-overlay.md)
- Extends:
  [ADR-0014](0014-exact-provider-tx-a-requirement-guard.md)

## Context

V15 makes exact-pico selection enforceable by fencing historical request-2
TX-A at legacy sequence 13. The old V8/V13 attribution stores integer nano-USD
rates, so it cannot honestly encode a fractional nano-USD rate. The legacy
`GraphAttemptSnapshot` also has no versioned representation for an exact-pico
head.

A positive experiment must therefore preserve the V15 marker and all legacy
truth while creating a separately named, separately hashed overlay. The
V16 freeze had no production verifier or Java API, and PostgreSQL does not
provide the Ed25519 verification boundary used by the local test JVM. The later
dormant verifier proposal is tracked separately in
[ADR-0016](0016-dormant-v16-ed25519-public-verifier.md).

## Decision

Adopt a forward-only V16 `RAW_JDBC_LOCAL_OVERLAY_TX_A` experiment:

- keep the V8 legacy head at sequence 13 and create an independent V16
  sequence-14 attribution/event/head closure;
- bind the overlay to the exact V13 validation policy, V15 requirement, V14
  provider profile, database/schema/role OIDs, request/response, model hashes,
  exact-pico rates/usage/cost and bounded decision material;
- mint the challenge and all semantic hashes in PostgreSQL, then require stage
  and commit on one explicit JDBC transaction;
- reject stage-only or incomplete closures through a deferred database guard;
- authenticate raw stage/commit through a dedicated
  `emergeos_provider_attestor_v16 LOGIN NOINHERIT` identity with zero relation
  ACL;
- treat the V16 login credential, attestor role and caller process as TCB;
- treat response/model/usage/decision provenance as trusted-caller input: the
  database binds those exact values but does not independently observe the
  provider;
- make no claim that PostgreSQL verifies Ed25519. Only the test JVM signs and
  verifies the ephemeral test signature;
- at the V16 freeze, add no production Java domain, port, mapper, reader,
  verifier or App route: `productionV16Verifier=0`, `productionJavaApi=0`.

## Consequences

- the overlay can represent exact integer pico-USD truth without rounding into
  or reinterpreting V8/V13 nano-USD fields;
- V8/V13/V15 JSON/`xmin` and the legacy sequence-13 head remain unchanged;
- V16 overlay head14 is not legacy head14 and is invisible to the old
  `GraphAttemptSnapshot` reader;
- the database proves role-bound transaction closure and tamper/replay fences,
  but not the cryptographic authenticity of signature bytes;
- the security claim depends on custody of the V16 login credential and the
  behavior of the attestor caller;
- the synthetic GPT precision profile is test arithmetic only, not provider
  pricing or billing evidence;
- TX-B/TX-C, process-fault, race, restart, live-provider, billing and
  pre-egress authority remain outside this decision.

## Alternatives rejected

- **Round pico rates into legacy nano-USD.** This creates false durable pricing
  truth.
- **Change the meaning or shape of V8/V13 rows.** This breaks historical
  canonical identity and JSON/`xmin` fidelity.
- **Advance the legacy head to 14.** Existing readers would observe a protocol
  they cannot decode and V13/V16 head14 would become ambiguous.
- **Call role authentication Ed25519 verification.** PostgreSQL stores the
  supplied signature but does not verify it cryptographically in this slice.
- **Add a production Java API before a production verifier/read model.** That
  would expose authority without a reviewed production consumer boundary.
- **Reuse V13 attestation rows.** Their canonical nano-USD and legacy head14
  semantics are not the V16 exact-pico overlay protocol.

## Evidence required before acceptance

- focused raw-JDBC stage/commit positive, stage-only rollback, tamper and replay
  evidence with exact durable receipts;
- full V1-V15 public-table JSON/`xmin` immutability outside the V16 allowlist
  for the positive commit delta from its post-fixture baseline and for each
  negative fence from its immediate baseline; intentional fixture/status
  mutations are recorded separately;
- proof that legacy `GraphAttemptSnapshot` remains sequence 13 and that no
  legacy sequence-14 row is created;
- V15-to-V16 populated migration and latest-schema inventory evidence;
- exact role, owner, function body, relation, trigger and ACL audit;
- first-party directory, synthetic JAR and actual shaded-JAR zero-consumer
  Gate for all V16 authority tokens;
- source/resource/class/JAR parity, clean repository verification, final
  ordered hashes and independent post-fix review;
- a Chinese Build Note that uses the exact scope
  `RAW_JDBC_LOCAL_OVERLAY_TX_A` and keeps every unproven boundary explicit.

The focused local Gate receipts are frozen Green in the V16 Build Note. This
ADR remains Proposed because production verification, typed consumption and
Live remain outside that result.

## Rollback

Revoke V16 stage/commit authority and supersede a flawed implementation with a
later forward migration. Never delete an existing V15 requirement, mutate
legacy V8/V13/V15 truth or project a V16 overlay head into the legacy head
relation.
