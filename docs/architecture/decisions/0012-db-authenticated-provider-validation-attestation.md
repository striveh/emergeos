# ADR-0012: DB-authenticated provider validation attestation

- Status: Proposed
- Date: 2026-08-10
- RFC:
  [RFC-0008](../../rfcs/0008-bounded-provider-validation-attestation.md)
- Extends:
  [ADR-0011](0011-attributed-terminal-graph-and-live-harness-pilot.md)

## Context

V12 closes cross-JVM failure terminalization but still treats a response hash
and typed attribution as the durable provider boundary. The exact OpenAI
adapter parses bounded response bytes, yet the database cannot tell whether a
caller used that reviewed semantic path or the historical raw attribution
writer. Raw response persistence is intentionally forbidden.

PostgreSQL 18 plus the repository's standard dependency surface has no detached
Ed25519 verifier. HMAC would put an equivalent signing secret in the database
and create an unapproved custody/rotation/backup boundary. Treating either a
Java boolean or an HMAC test stub as a portable signature would overstate the
evidence.

## Decision

Adopt the RFC-0008 experiment:

- an ephemeral test-only Ed25519 signer creates a bounded transcript;
- a dedicated reviewed JVM verifies it with a database-selected public anchor;
- PostgreSQL authenticates that JVM as `emergeos_provider_attestor` and grants
  only the exact V13 semantic functions, with no relation DML;
- a DB-minted one-shot challenge provides database, attempt, cursor, session
  and DB-clock binding;
- the reviewed outcome is privately minted with an execution binding equal to
  the exact graph manifest hash; that binding is repeated in the decision hash,
  typed statement, transcript/signature material and durable V13 row;
- the V13 function consumes the challenge and atomically commits the receipt,
  request-2 attribution, event/head and optional durable failure outcome;
- deferred guards make a validation-required attempt fail closed when the
  historical writer tries to bypass the receipt;
- legacy V1-V12 data remains `LEGACY_UNATTESTED` and is never backfilled.

The PostgreSQL role and reviewed verifier JVM are explicitly part of the TCB.
This ADR does not claim PostgreSQL-native signature verification. Shipping
configuration contains no production public anchor, private key or enabled
execute route.

## Consequences

- semantic validation becomes durable, replay-fenced graph evidence without
  persisting raw response bytes;
- an old reviewed outcome cannot be rebound to a different graph attempt and
  newly signed: the mapper, Store and PostgreSQL each reject a mismatched
  execution binding, with PostgreSQL remaining the durable authority;
- an attacker holding only the prefix-writer credential cannot manufacture a
  V13-attested request-2 transition;
- an attacker holding the provider-attestor credential remains inside the TCB
  and can bypass the Java verifier through direct SQL; closing that boundary
  requires an audited database public-key verifier;
- migrations, ACL audits, bytecode gates and process/fault tests grow, but the
  trust claim remains inspectable and bounded;
- live provider, production key custody, shipping execute and real r1/r2/r3
  remain Red and require separate owner authorization.

## Evidence required before acceptance

- RFC-0008 Acceptance matrix Green on local PostgreSQL/Testcontainers;
- exact migration, role, ACL, trigger/function-body and public-anchor receipts;
- signed success/failure, tamper/replay/expiry/cross-DB negatives;
- full JSON/`xmin` rollback at transcript and commit fault cutpoints;
- fresh-JVM race/restart evidence and shipping bytecode/JAR closure;
- full repository, contracts, docs and independent P0/P1 review Green;
- Chinese Build Note that preserves every non-live and TCB boundary.

## Rollback

Do not enable shipping execute while this ADR is Proposed. V13 migration is
forward-only; on a defect, stop issuing new validation policy/challenges and
leave historical/consumed rows immutable. Replace a flawed transcript protocol
with a new version rather than rewriting durable evidence.
