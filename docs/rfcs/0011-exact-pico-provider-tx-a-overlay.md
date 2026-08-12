# RFC-0011: exact-pico provider TX-A overlay

- Status: Experiment
- Date: 2026-08-12
- Extends:
  [RFC-0010](0010-exact-provider-tx-a-requirement-guard.md)
- Proposed decision:
  [ADR-0015](../architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md)

## Problem

V15 can mark an exact sequence-13 attempt as requiring `PICO_OVERLAY_V1` and
can prevent that attempt from falling back to the historical V8/V13 request-2
TX-A. It deliberately does not provide a positive exact-pico attribution,
event or head.

The legacy graph attribution encodes integer nano-USD rates. Reinterpreting or
rounding those columns would make the durable graph claim something that was
not priced exactly. Updating the V8 head to a historical sequence 14 would also
make old `GraphAttemptSnapshot` readers observe a protocol they cannot decode.

The next experiment therefore needs a versioned exact-pico overlay that can
complete the V15-marked request-2 boundary without modifying or pretending to
extend the legacy read model.

## Proposed experiment

V16 adds a local-only raw JDBC overlay TX-A:

1. `agent_graph_stage_exact_tx_a_v16(jsonb)` locks the durable legacy
   sequence-13 head and derives a database-, schema-, role-, attempt-, V13
   policy-, V15 requirement- and V14 profile-bound challenge. It computes the
   exact-pico statement, attribution, event, overlay-head and transcript hashes
   inside PostgreSQL.
2. A test JVM recomputes the canonical material, signs it with an ephemeral
   Ed25519 test key, verifies that signature locally, and calls
   `agent_graph_commit_exact_tx_a_v16(jsonb)` on the same JDBC connection and
   transaction.
3. Commit consumes the staged validation and atomically creates exactly one
   V16 attribution, V16 event and V16 head. A deferred constraint guard rejects
   stage-only commit and incomplete closure.
4. The new V16 relations are
   `agent_graph_exact_provider_validations_v16`,
   `agent_graph_exact_provider_attributions_v16`,
   `agent_graph_exact_attempt_events_v16` and
   `agent_graph_exact_attempt_heads_v16`.
5. The independent `emergeos_provider_attestor_v16 LOGIN NOINHERIT` role has
   only the exact stage/commit function authority and no relation privileges.
   The role credential, role identity and caller process are part of the
   trusted computing base.
6. At the V16 freeze, no production Java domain, port, mapper, verifier,
   reader or App route was introduced. The historical V16 scope label is
   `RAW_JDBC_LOCAL_OVERLAY_TX_A`, with `productionV16Verifier=0` and
   `productionJavaApi=0`. The later dormant verifier experiment is tracked
   independently in [RFC-0012](0012-dormant-v16-ed25519-public-verifier.md).

## Canonical truth boundary

- exact pricing uses `PICO_USD_PER_TOKEN`, exact integer pico-USD rates, bounded
  token counts and `NUMERIC(38,0)` observed cost;
- the statement, attribution, event, overlay head, challenge, transcript and
  receipt each have a separate V16 domain and ordered framed-hash material;
- the overlay starts from the immutable V8 sequence-13 head but does not update
  it. V16 overlay sequence/head 14 is a different protocol truth and must not
  be described as legacy head14;
- the historical V8 attribution/event/head, V13 validation policy and V15
  requirement remain byte- and row-version-stable across each V16 authority
  call. The positive commit compares a post-fixture baseline, and each negative
  fence compares its immediate baseline. Intentional fixture creation and
  profile/key status mutations are not attributed to the V16 call;
- the existing `GraphAttemptSnapshot` remains an honest legacy sequence-13
  snapshot. V16 currently has no production Java overlay read model.

## Cryptographic and authority boundary

PostgreSQL does **not** verify Ed25519 in V16. It validates the scoped database
identity, frozen key metadata, transcript hash, signature shape and durable
closure, then records the signature supplied by the V16 attestor caller. Only
the test JVM performs Ed25519 sign/verify in this experiment.
Response/model/usage/decision values are also supplied by that trusted caller:
the database binds them into one exact closure, but does not independently
observe their provider provenance.

Consequently, V16 is not a portable cryptographic provider attestation. A
caller that possesses the V16 login credential and can assume the exact
attestor identity is inside the TCB. Hostile DBA, stolen credential, production
key custody, trust-anchor rotation and a production public-key verifier are not
closed by this RFC.

## Focused Acceptance candidate

The current focused experiment is expected to demonstrate, without claiming a
final repository Gate:

- stage-only auto-commit is rejected with the fixed V16 `55000` reason and
  leaves the full database image unchanged;
- wrong base head, request hash, key, requirement or commit transcript is
  fenced before durable overlay completion;
- a profile or key revoked after stage, and an expired challenge, are fenced
  at commit with the full staged overlay rolled back;
- one in-transaction event-insert failure rolls the full stage/commit attempt
  back; this is a local SQL rollback canary, not process-fault durability;
- a valid local test signature creates one `CONSUMED` validation plus one exact
  attribution/event/overlay-head closure, while legacy head13 and legacy
  sequence-14 count remain unchanged;
- replay is rejected without mutation;
- the positive commit delta from its post-canary baseline, and every negative
  fence delta from its immediate baseline, leave all V1-V15 public-table
  JSON/`xmin` outside the explicit V16 allowlist unchanged.

Final clean verification, resource/JAR parity, first-party zero-consumer Gate,
ordered hashes and independent review are frozen Green in the V16 Build Note.
The RFC remains an Experiment because production verification and Live remain
outside this result.

## Non-goals and claim boundary

- TX-B and TX-C over the V16 overlay are `NOT_IMPLEMENTED`.
- Process hard-kill, commit-before-delegate crash, multi-JVM race and restart
  durability are `NOT_PROVEN` for V16.
- Live provider behavior, provider-native metadata, invoice reconciliation and
  billing are `NOT_PROVEN`; no V16 live PASS exists.
- Pre-egress budget/effect authority is `NOT_IMPLEMENTED`. V15 enrollment and
  V16 completion occur after the historical provider-intent prefix.
- The synthetic `gpt-5.6-terra` exact-pico profile is a numeric precision
  canary. Its rates and cost are not OpenAI, DeepSeek or any provider's pricing
  and are not Commercial evidence.
- V16 does not turn test-only signature verification into a production
  verifier, does not expose a production Java API and does not enable a
  shipping live consumer.
- Historical V13 success/failure head14 and V12 terminal resume evidence apply
  to the legacy protocol, not to V16 overlay head14.

## Rollback

V16 is forward-only. On a defect, revoke the V16 role's CONNECT or stage/commit
EXECUTE privileges and stop creating new overlay rows. Do not delete V15
requirements, rewrite V8/V13/V15 rows, reinterpret nano-USD truth or copy an
overlay head into the legacy head relation. Any repair must be a later
versioned migration.
