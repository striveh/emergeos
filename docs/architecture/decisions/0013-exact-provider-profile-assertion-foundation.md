# ADR-0013: exact provider profile assertion foundation

- Status: Proposed
- Date: 2026-08-11
- RFC:
  [RFC-0009](../../rfcs/0009-exact-provider-profile-assertion-foundation.md)
- Extends:
  [ADR-0012](0012-db-authenticated-provider-validation-attestation.md)

## Context

The dormant DeepSeek Responses probe produced a bounded local compatibility
surface, but DeepSeek cache-hit pricing cannot be represented exactly by the
V13 integer nano-USD domain. Extending the frozen V13 transcript or rounding
2.8 nano-USD per token would turn a provider-specific experiment into false
durable graph truth.

## Decision

Adopt a forward-only V14 assertion foundation:

- preserve every V13 relation, function, trigger, hash domain and nano-USD
  meaning;
- add a separate exact provider profile with integer pico-USD rates, pricing
  source hash and effective window;
- validate a closed statement through a read-only SECURITY DEFINER function;
- authenticate that caller with an independent, zero-relation-ACL V14 role;
- keep the migration free of a production provider profile;
- keep all production Java API, graph Store, attribution, transcript and TX-A
  surfaces unchanged;
- reject any first-party shipping bytecode reference to the V14 function,
  relation or role.

The returned statement hash binds four opaque hashes, token arithmetic and the
exact profile. It is not graph authority. Only a later version with an exact
pico-priced attribution, durable attempt binding and a new atomic protocol may
use this foundation to authorize graph mutation.

## Consequences

- exact fractional nano-USD rates can be represented without changing V13;
- provider/pricing splices fail closed in PostgreSQL and require no Java
  boolean precheck;
- the V14 role cannot read or mutate the profile relation;
- profile provisioning/revocation remains inside the database-owner TCB;
- there is deliberately no V14 Java mapper, signer, attestor or shipping route;
- live compatibility, model provenance, retention and billing remain Red.

## Evidence required before acceptance

- V14 SQLSTATE `42501`/`22023`/`55000` matrix and valid exact-cost control;
- independent framed-hash recomputation and full public-table JSON/`xmin`
  non-mutation evidence;
- V13 raw TX-A fenced both before and after V14 assertion;
- populated V13→V14 row/function/trigger/canonical fidelity;
- exact role, function, relation and zero-consumer bytecode/JAR audits;
- full repository Gate and independent P0/P1 review Green;
- a Chinese Build Note that says `PROFILE_ASSERTION_ONLY` and
  `TX-A=NOT_IMPLEMENTED`.

## Rollback

Do not connect V14 to shipping execution while this ADR is Proposed. Revoke the
V14 role on a defect and replace a flawed profile/assertion domain with a later
version; never rewrite historical V13 or V14 canonical identity.
