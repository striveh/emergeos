# RFC-0008: bounded provider validation attestation

- Status: Experiment
- Date: 2026-08-10
- Extends:
  [RFC-0007](0007-attributed-terminal-graph-and-shared-candidate-harness.md)
- Proposed decision:
  [ADR-0012](../architecture/decisions/0012-db-authenticated-provider-validation-attestation.md)

## Problem

Pack010 V12 can durably bind request/response hashes, provider attribution,
failure provenance and terminal transitions. A hash does not independently
prove that the bounded response bytes passed the reviewed parser and the exact
Tool/structured-output schema. The generic prefix writer can also call the
historical attribution path without presenting a validation transcript.

Persisting raw provider responses would enlarge the privacy and credential
surface and would contradict RFC-0007. A Java-only boolean or local signature
check would not be a durable authority because a caller could still use the
legacy PostgreSQL writer path.

## Proposed experiment

1. The exact reviewed OpenAI adapter emits a bounded validation receipt only
   after transport-body hashing, strict single-object JSON parsing, attribution
   checks and decision parsing all complete. The receipt is privately minted
   for one `executionBindingHash`, which is the exact graph manifest hash
   supplied before the provider session is opened. It contains no raw request
   or response body, headers, credentials, reasoning content or exception text.
2. The receipt binds the request ordinal/hash, response hash, full provider
   attribution hash, execution binding, parser and schema profile hashes, a
   safe decision hash, and either a validated decision kind or one closed
   failure code. The decision hash also includes the execution binding. The
   OpenAI mapper verifies provider-observable attribution fields; the V13 Store
   and PostgreSQL separately verify the graph-owned actor and manifest truth.
3. A database-issued one-shot challenge binds the transcript to the database
   and schema identity, exact manifest, provider session intent, seq13/head13,
   request 2 and DB-clock validity window. Caller wall-clock time is not an
   authority.
4. Local acceptance uses an ephemeral Ed25519 test key. The private key exists
   only in test process memory and never enters PostgreSQL, the repository,
   command arguments, environment, files, logs or the shipping JAR. PostgreSQL
   stores only the public anchor, fingerprint, bounded transcript and
   signature.
5. PostgreSQL 18 and the currently installed standard extensions do not expose
   detached Ed25519 verification. Therefore a dedicated
   `emergeos_provider_attestor LOGIN NOINHERIT` JVM verifies the signature with
   the database-selected public anchor, then invokes the sole V13 semantic
   transaction. The role has exact function EXECUTE and zero relation DML.
6. The V13 semantic transaction consumes the challenge and atomically writes
   the validation receipt, request-2 attribution, event 14, head 14 and, for a
   closed attributed failure, the compatible durable failure outcome. A
   deferred database guard rejects a validation-required attempt that reaches
   request-2 attribution without the exact consumed receipt.
7. V13 validation is an explicit durable policy for a new attempt. Existing
   V1-V12 rows are not backfilled and remain readable as
   `LEGACY_UNATTESTED`; they can never be presented as V13-attested live truth.
8. The provider-attestor database credential and reviewed verifier JVM are in
   this experiment's TCB. Possession of that credential, a hostile DBA, OS/JVM
   compromise or cluster cloning is outside the claim. A future portable
   database-verified signature requires an independently approved and audited
   public-key verifier extension.

## Canonical transcript

The transcript uses a domain-separated, length-framed canonical encoding. Its
minimum safe fields are:

- protocol/domain version;
- database/schema binding and one-shot nonce;
- principal, attempt, manifest, revision and session-intent hashes;
- expected seq13/head13 and request-2 intent;
- exact execution binding, equal to the durable manifest hash;
- response and complete attribution hashes;
- transport, parser and output-schema profile hashes;
- decision kind and safe decision projection hash, or one closed failure code;
- database issue/expiry time and selected public-key id/fingerprint.

The compact typed constructors and PostgreSQL function both reject missing,
extra, malformed or inconsistent fields. `toString`, exception and subprocess
receipts must redact the signature and never include secret or raw-body data.

## Acceptance

- The first Red demonstrates that the historical bare request-2 attribution
  can advance seq13 to seq14 without a transcript. Green must turn the same
  raw call on a validation-required attempt into SQLSTATE `55000`, with a full
  public-table JSON/`xmin` snapshot unchanged.
- Valid signed structured-final and closed-failure receipts commit all V13/V8/
  V11 truth in one transaction. Missing, wrong or replayed challenge, signature,
  key, parser/schema profile, provenance, request/response/attribution/decision,
  expiry, manifest, attempt or database binding fails closed with no mutation.
- A kill after transcript staging and a kill at the real JDBC commit delegate
  both roll back to seq13; a commit-then-halt survives a fresh JVM and immediate
  PostgreSQL restart.
- Two fresh JVMs racing the same cursor produce one winner and one exact fenced
  loser. Cross-attempt and cloned cross-database replays are rejected. An old
  reviewed outcome cannot be attached to a new attempt and re-signed under a
  fresh challenge: the private receipt, safe decision hash, typed statement,
  signed transcript and PostgreSQL row all carry the same execution binding;
  a raw stage call with a different binding fails with SQLSTATE `55000`.
- Fresh and populated V12 to V13 migration preserves all historical rows,
  Flyway history and `xmin`; no historical receipt is invented.
- PUBLIC, prefix writer, graph executor, failure resumer and reader have no V13
  mutation authority. Function bodies, owners, search paths, ACLs, trigger
  topology and public anchors are frozen and fail closed on drift.
- Bytecode and shaded-JAR gates reject unreviewed direct, reflection,
  MethodHandles and invokedynamic access. Test signer/private key/harness code
  is absent from the shipping JAR and shipping execution remains disabled.

## Non-goals and claim boundary

This experiment does not prove a provider-origin signature, remote code
attestation, hostile-DBA resistance, provider exactly-once, billing correctness,
power-loss durability, production key custody, a real network/model call, or
owner approval for r1/r2/r3. It does not independently reparse raw bytes after
the reviewed adapter releases them. It proves only that the reviewed attestor
accepted a bounded signed statement and PostgreSQL atomically bound that
statement to graph truth. It also does not claim freshness for reusing the same
reviewed outcome after an independently authorized re-enrollment of the same
attempt; the attestor JVM and its credential remain inside the stated TCB.

## Rollback

V13 is forward-only. Before shipping enablement, a defect freezes new
validation-required attempts. Historical rows remain readable; consumed
receipts are never rewritten or backfilled. A replacement protocol uses a new
domain/version and migration rather than mutating accepted transcript bytes.
