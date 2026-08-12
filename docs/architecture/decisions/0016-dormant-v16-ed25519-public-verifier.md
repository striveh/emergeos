# ADR-0016: dormant V16 Ed25519 public verifier

- Status: Proposed
- Date: 2026-08-12
- RFC: [RFC-0012](../../rfcs/0012-dormant-v16-ed25519-public-verifier.md)

## Context

V16 records a caller-supplied signature only after database identity, transcript
hash and overlay closure checks. It deliberately has no PostgreSQL Ed25519
verifier. A production-quality public-key primitive is useful, but wiring a
partial authority API before key custody and raw-credential closure would widen
the trusted surface.

## Decision

Add a dormant, packaged Core verifier primitive and challenge value type.

- Recompute the V16 challenge and transcript canonical hashes.
- Verify `sha256(publicKeyDer) == keyFingerprint`.
- Verify Ed25519 over the complete framed transcript bytes.
- Convert malformed anchors, malformed signatures and invalid signatures to one
  fixed, cause-free integrity error.
- Add no V17 production signer implementation or first-party private-key JCA
  reference; the historical V13 signer capability interface remains, while
  ephemeral signing implementations stay test-only.
- Keep production V16 stage/commit Java API, consumer, route and configuration
  absent.
- Keep the V16 login credential, role and caller process in the TCB; raw SQL can
  still bypass Java verification.

The database-minted statement/attribution/event/head hashes are trusted inputs
to this primitive. Full production semantic mapping is a later decision.

## Consequences

- The shipping artifact can contain a reviewed public verifier without making
  any live path reachable.
- The local test harness can demonstrate verify-before-commit ordering.
- PostgreSQL-native Ed25519 remains unimplemented, so the durable database does
  not globally enforce signature authenticity.
- V16 historical receipts remain true at their freeze point; V17 is recorded as
  a new additive slice rather than rewriting V16 evidence.
- Key custody, provider provenance, TX-B/TX-C, live, billing, process-fault,
  race and restart remain Red.

## Alternatives rejected

- **Call V16 role authentication cryptographic verification.** It is not.
- **Expose stage/commit as public Java methods now.** That creates authority
  wiring before custody and bypass closure.
- **Put a test signer in production.** That would ship private-signing
  capability and collapse the trust boundary.
- **Claim whole-JAR private-key types or all credential formats are absent.**
  Third-party dependencies may contain such types; the enforceable claims are
  zero first-party private-key JCA references and zero V17 production signer
  implementation, plus zero reviewed PEM-header/named-key resource and
  DeepSeek-style credential value markers.

## Rollback

Remove all production consumers (already zero), stop packaging the two additive
Core classes in a later build, and supersede this Proposed decision. No database
rollback or durable-row mutation is involved.
