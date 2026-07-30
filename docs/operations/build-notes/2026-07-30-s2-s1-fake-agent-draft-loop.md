# Build Note · 2026-07-30 · Stage 2 S1 Fake Agent Draft Loop

- Change class: `V`
- Task / commit: Stage 2 S1, this focused commit

## Outcome

Added the first executable Agent product path without selecting a Runtime
framework or real model:

```text
POST /api/v1/agent-drafts
  → server-owned TaskEnvelope
  → framework-free Fake AgentKernel
  → scripted model requests capture.read
  → registered + Task-declared + input-scoped + owner-scoped tool execution
  → structured draft proposal
  → deterministic evidence/content verification
  → PostgreSQL Artifact v1
  → ResultEnvelope + response-only safe Trace
```

The client supplies only `captureId` and `intent`. Principal, model, tool
allowlist, limits, budget and policy versions remain server-owned. The model
does not write PostgreSQL and its claimed Evidence is not trusted as proof that
a tool actually ran.

## Why this matters

The previous Template Generator demonstrated the product state machine but not
the central Agent mechanism. This slice establishes a small inspectable
baseline before evaluating Pi, AgentScope or another Harness:

- the model first receives references, not an unrestricted database row;
- a tool request cannot expand registration, Task authority or input scope;
- successful tool results create explicit obtained-Evidence provenance;
- a structured final is a proposal until Core verifies it;
- only the deterministic product service commits Artifact truth;
- typed Trace events expose control flow without raw Capture content or hidden
  chain-of-thought.

## TDD and process evidence

- Baseline: clean `main@1ff640e`; no `/api/v1/agent-drafts`, AgentKernel, model
  loop, tool registry or Agent Trace existed.
- Acceptance Red: the packaged jar reached healthy state against PostgreSQL,
  created the synthetic Capture, then failed at the absent endpoint with
  expected `201`, actual `404`.
- Focused Red: compilation failed because the provider-neutral `AgentKernel`,
  typed outcome and `AgentDraftService` did not exist.
- Minimum Green added only Core-owned neutral types/use case, the existing
  in-memory outer adapter and thin API wiring. It added no dependency, public
  JSON Schema or Flyway migration.
- First independent review found `P0=0`, `P1=4`: model-claimed Evidence could
  bypass the tool, untrusted tool fields could leak into Trace, a final returned
  after cancellation/deadline could still succeed, and invalid model content
  could be misreported as client `400`.
- The fixes separate `inputRefs` from `obtainedEvidenceRefs`, require exact
  verified Evidence claims, authorize and canonicalize tool calls before
  public Trace, recheck cancellation/deadline after every model call, and apply
  the persistent Artifact content invariant before commit.
- Focused Green now passes 5 Core verifier tests and 13 loop tests. It covers
  direct-final Evidence forgery, overclaimed refs, malformed output, ref-only
  initial context, exact event order, registered-but-undeclared and
  declared-but-unregistered tools, malicious references, malformed tool
  results, prompt injection, cancellation, deadline, model-step limit and
  tool-call limit.
- The PostgreSQL-backed API security tests pass 2 cases: headers/JSON cannot
  supply authority; foreign and missing Capture produce the same selected
  non-leaking failure shape. A successful Agent draft creates one Artifact and
  zero ActionAttempt/Receipt rows.
- Packaged Green uses the same Acceptance command, kills the first JVM after
  creation, starts a second packaged JVM against the same PostgreSQL container
  and retrieves the exact Artifact v1 through the original `Location`.

Focused command:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl modules/core,adapters/inmemory -am \
  -Dtest=AgentDraftServiceTest,FrameworkFreeAgentKernelTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Packaged command:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am verify \
  -Dit.test=AgentDraftHttpIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Independent review closed at `P0=0`, `P1=0`, `P2=0`. One reviewer first found
that cross-JVM persistence behavior was proven but process-death evidence did
not explicitly assert first-process liveness, its death after the forced stop
and distinct restart PIDs. Those assertions were added, and the focused
packaged verification passed again.

Full verification:

- `./mvnw --batch-mode --no-transfer-progress verify`: 98 tests passed
  (Core 28, in-memory 19, PostgreSQL 22, API unit 22, packaged integration 7);
- `./scripts/verify-contracts.sh`: 4 Schemas, 8 fixtures and 1 Task Pack passed;
- `./scripts/verify-doc-links.sh`: all 63 Markdown files passed;
- `git diff --check`: passed.

## Architecture and security boundary

- `modules/core` owns the narrow `AgentKernel` port, neutral outcomes,
  deterministic verifier and Artifact commit use case. It still has no Spring,
  database, framework or model-SDK dependency.
- `adapters/inmemory/agent` owns the model decision shape, finite loop, scripted
  Fake Model, tool registry and `capture.read` adapter.
- Tool authorization is defense in depth: canonical field bounds, registry
  presence, Task declaration, Task input membership and owner-scoped
  `CaptureStore.findOwned`.
- Rejected model-controlled tool values are not echoed. Trace uses a fixed
  untrusted marker until authority is proven; successful events only use
  server-owned canonical references.
- `AgentRunOutcome.obtainedEvidenceRefs` comes only from validated
  `TOOL_RESULT` events. Core rejects immediate finals, omitted Evidence and
  extra model-claimed refs.
- Failed, blocked or cancelled results return no Artifact or Evidence refs.
  `ARTIFACT_COMMITTED` is appended only after PostgreSQL returns the created
  lineage.

## Version-sensitive check

The repository uses Java 21, Spring Boot `4.1.0` and Spring Framework `7.0.8`.
The installed `ResponseEntity` API was inspected locally; S1 uses
`unprocessableContent()` rather than the deprecated
`unprocessableEntity()`. No other version-sensitive external API was added.

## Limits and non-claims

- The scripted Fake Model is deterministic and costs zero. It does not prove
  real-model quality, personalization or useful writing quality.
- Deadline and cancellation are cooperative checks between synchronous steps;
  S1 cannot forcibly interrupt a hung model call and exposes no asynchronous
  cancellation endpoint.
- Trace is returned inline and is not persisted or integrity-bound to a run.
  `traceRef` therefore remains null.
- Repeating the same successful request intentionally creates another Artifact.
  Retry-safe draft creation needs a durable idempotency contract and is deferred;
  an in-memory lock would not solve it.
- There is no real authentication, durable Agent checkpoint, stochastic Eval,
  Temporal, external Connector, credential or platform side effect.
- Stage 1 human learning, market validation and the fenced stale-`DISPATCHING`
  Connector Gate remain incomplete. The owner's Roadmap exception changes
  sequencing only.

## AI Coding and Agent Engineering receipts

- Practiced: outside-in packaged Red, focused Red, minimum Green, adversarial
  review, targeted regression tests and process-restart acceptance.
- Implemented: references-first context, typed model/tool decisions, layered
  tool authority, explicit obtained Evidence, structured-final verification,
  bounded loop control and safe Trace projection.
- Human mastery is not claimed. The learning plan is deliberately paused while
  product implementation is primary.

## Career and business receipts

The repository now contains a runnable first Agent loop and a concrete review
story about why model-claimed Evidence is not evidence. No interview rehearsal,
real Seed, user reuse, price request or payment was created by this slice.

## Next falsifiable hypothesis

S2 should persist an integrity-bound run/Trace and bind it to
`HarnessRunBundle` so another developer can replay and attribute a failure. It
must do so without making SDK types public or letting Trace become hidden
reasoning storage.

## Public derivatives

- Short post: deferred.
- Visual/demo: deferred.
- Weekly long-form: deferred.
