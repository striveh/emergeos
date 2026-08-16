# RFC-0012: dormant V16 Ed25519 public verifier

- Status: Experiment
- Date: 2026-08-12
- Extends: [RFC-0011](0011-exact-pico-provider-tx-a-overlay.md)
- Proposed decision:
  [ADR-0016](../architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md)

## Problem

V16 can atomically close an exact-pico overlay, but its PostgreSQL commit
function only validates signature shape and durable binding. It does not verify
Ed25519. At the V16 freeze, only a test helper verified the signature, and no
production Java verifier existed.

The next safe slice must add a reusable public-key verification primitive
without exposing a production stage/commit authority path or pretending that
PostgreSQL now authenticates the signature.

## Proposed experiment

Add two production-source Core types:

1. `GraphExactPicoProviderValidationChallenge` reconstructs the V16 challenge
   identity and eight-field canonical transcript, rejects inconsistent hashes,
   and exposes only the full framed signature material.
2. `GraphExactPicoProviderSignatureVerifier.verifyOrThrow(...)` checks the
   SHA-256 fingerprint of an X.509 Ed25519 public key and verifies the signature
   over that framed material. All failures become a fixed, cause-free integrity
   error.

The primitive is dormant. This RFC adds no PostgreSQL migration, role, Java
stage/commit API, signer, private-key custody, reader, composer, App route or
shipping consumer.

## Acceptance boundary

The local Acceptance must prove:

- the unchanged V16 database accepts an arbitrary shape-valid signature when a
  caller holds the V16 attestor credential; the test observes that only inside
  an explicit transaction and rolls it back;
- invalid signature, wrong signer key and a signature replayed against a fresh
  challenge are rejected by the production-source verifier before the test
  calls commit, and the full public-table JSON/`xmin` image is unchanged;
- a valid signature passes the verifier before the test harness calls the V16
  commit function;
- expiry is still rejected by the PostgreSQL clock with SQLSTATE `55000`, not
  by the Java verifier;
- the actual shaded JAR contains the two production classes, excludes the test
  signer and Acceptance, adds no V17 production signer implementation or
  first-party private-key JCA reference, and has no reviewed private-key PEM
  headers/named resources or DeepSeek-style credential value markers under the
  explicit artifact scan rule. The historical V13 signer capability interface
  remains, but shipping first-party code still supplies no implementation or
  private key.

The V16 statement, attribution, event and head component hashes remain
database-minted TCB inputs to this small primitive. The Acceptance independently
checks their V16 canonical closure, but this RFC does not claim a production
62-field mapper or semantic attestor pipeline.

## Trusted-computing boundary

`emergeos_provider_attestor_v16`, its credential and the caller process remain
inside the TCB. A holder can bypass the dormant Java primitive and invoke the
raw commit function. PostgreSQL-native signature verification therefore remains
zero.

The public verifier uses no private key, signer, key generator or key-storage
API. Test signing keys are ephemeral and test-only. Raw provider request and
response bytes remain outside durable storage and receipts.

## Non-goals

- no production V16 stage/commit orchestration API;
- no DB-native Ed25519 verification or raw-credential bypass closure;
- no production signer, HSM/KMS integration, rotation or key custody;
- no production overlay reader, TX-B/TX-C, process-fault, race or restart proof;
- no provider provenance, DeepSeek live result, pricing freshness, billing or
  pre-egress authority;
- no claim that configured or packaged means running or Live-ready.

## Evidence required before acceptance

- canonical-invariant and ephemeral Ed25519 signature tests;
- local PostgreSQL/Testcontainers TCB, invalid, wrong-key, fresh-challenge,
  expiry and valid-path evidence;
- first-party directory, synthetic-JAR and actual shaded-JAR bytecode Gate;
- target/classes, Core JAR and app JAR class parity;
- clean verification, secret/material scans, documentation links, frozen
  hashes and three independent post-fix reviews;
- a Chinese Build Note that keeps PostgreSQL-native verification, stage/commit
  API, key custody and Live explicitly Red.
