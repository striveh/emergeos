# RFC-0009: exact provider profile assertion foundation

- Status: Experiment
- Date: 2026-08-11
- Extends:
  [RFC-0008](0008-bounded-provider-validation-attestation.md)
- Proposed decision:
  [ADR-0013](../architecture/decisions/0013-exact-provider-profile-assertion-foundation.md)

## Problem

V13 binds one OpenAI-oriented validation transcript to graph truth, but its
durable attribution uses integer nano-USD per token. The reviewed DeepSeek
V4 Flash public-list cache-hit rate used by the local probe is 2.8 nano-USD
per token, which cannot be represented exactly in that domain. Rounding the
rate, silently changing the V13 unit, or hiding a provider/pricing identity in
a parser hash would corrupt the canonical contract.

A versioned multi-provider TX-A also needs an exact provider protocol,
transport/parser/schema/model-resolution profile and pricing source/window.
Those authorities must be inspectable before a new transcript or graph
mutation surface is designed.

## Proposed experiment

V14 adds only an assertion foundation:

1. `agent_graph_provider_profiles_v14` stores an owner-provisioned, versioned
   provider profile. The identity includes provider/protocol, transport,
   parser, schema, model-resolution and observed pricing material.
2. Pricing rates use integer pico-USD per token. The synthetic DeepSeek fixture
   therefore represents uncached input `140000`, cached input `2800` and output
   `280000` pico-USD per token without rounding.
3. `agent_graph_assert_provider_statement_v14(jsonb)` accepts one closed,
   bounded JSON object, verifies exact profile identity, active pricing window,
   token arithmetic and claimed pico-USD cost, and returns only profile hash,
   statement hash and exact pico-USD cost. It performs no DML.
4. A dedicated `emergeos_provider_attestor_v14 LOGIN NOINHERIT` role receives
   only `EXECUTE` on that assertion function and zero relation privileges.
   The SECURITY DEFINER owner has profile `SELECT` and canonical-helper
   `EXECUTE`, not profile mutation authority.
5. The profile table is not seeded with a production DeepSeek profile by the
   migration. Local Acceptance inserts a synthetic, bounded admin fixture.
   Runtime profile provisioning, revocation and price-source review remain a
   separate owner-controlled lifecycle.
6. The payload's execution-binding, request, response and decision hashes are
   opaque bounded inputs to the returned statement hash. V14 does not verify
   that they came from a graph attempt, provider response or reviewed mapper.

## Acceptance

- Wrong provider, protocol/profile, pricing provider/id/fingerprint/source,
  pico rate or claimed cost returns SQLSTATE `55000` with a fixed reason.
- Missing, extra, malformed or oversized input returns SQLSTATE `22023`;
  a non-V14 session returns `42501`. Failure text cannot contain a credential,
  raw response sentinel or statement body.
- The exact cost for the synthetic usage `100 input / 20 cached / 10 output`
  is `14,056,000` pico-USD. Java independently recomputes both length-framed
  profile and statement SHA-256 values.
- Every assertion call leaves all public-table JSON/`xmin` images unchanged.
  The graph remains at seq13/head13, and historical V13 raw TX-A is still
  fenced before and after a valid V14 assertion.
- Fresh V14 provisioning freezes the two functions, profile relation shape,
  owner/ACL and independent role. V13 rows, function catalog images, triggers,
  canonical sample and `xmin` remain byte-for-byte unchanged across V13→V14.
- First-party bytecode and the actual shaded JAR contain no V14 function,
  relation or role consumer.

## Non-goals and claim boundary

V14 does not define a graph-bound V14 statement, challenge, signature,
transcript, attestation, durable receipt, provider attribution or TX-A. It does
not prove that the synthetic profile equals current provider behavior, pricing
or billing, and it does not authorize a live request. The V14 database role and
profile assertion are not connected to the shipping application.

The profile identity is owner-provisioned truth. A privileged database owner,
hostile DBA, compromised profile-review process or stale external price source
is outside this experiment. A future version must separately bind an exact
pico-priced attribution to one durable graph attempt before it can advance
seq13 to seq14.

## Rollback

V14 is forward-only. On a defect, revoke the V14 assertion role's CONNECT or
EXECUTE privilege and stop provisioning profiles. Do not rewrite V13 canonical
bytes, reinterpret nano-USD fields or backfill historical graph rows.
