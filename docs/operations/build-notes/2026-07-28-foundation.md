# Build Note · 2026-07-28 · Foundation

## Outcome

Established a runnable EmergeOS research foundation and one safe
`thought → artifact → approval → local action → receipt → reflection candidate` slice.

## Decisions

- Start as a modular monolith with a framework-neutral Core and Contracts layer.
- Keep Evidence and user-approved outcomes as truth; keep Working Self and reflection as projections.
- Require current artifact and ActionPlan hashes for approval.
- Treat action completion and reflection as independent state axes.
- Keep the prototype loopback-only, single-user and free of external side effects.

## Evidence

- Maven reactor builds all four modules; 17 automated tests pass.
- Tests cover stale approval, approval replay, in-process ambiguous-response recovery, reflection
  failure, sensitive-data rejection, owner-scoped reads and domain provenance invariants.
- AJV compiles 4 JSON Schema 2020-12 contracts, resolves their references and validates 8
  positive/negative fixtures; 1 synthetic task pack also passes structural checks.
- The documentation checker resolves local links across 32 Markdown files.
- A packaged API run returned health `UP`, revised Artifact v1 to v2, rejected the stale hash with
  HTTP 409, completed with a Receipt, and returned the same Receipt on approval replay.

## Limits

- State is in memory and disappears on restart.
- There is no login, encrypted storage, real model, Temporal workflow, dynamic UI or platform connector.
- In-process retry evidence does not prove crash recovery or multi-instance safety.
- Capture retry deduplication and concurrent revision compare-and-swap are not implemented.
- The open-source license and real maintainer/security contacts remain decisions for the operator.

## Next falsifiable hypothesis

A PostgreSQL adapter with owner-scoped queries, optimistic concurrency, persisted ActionAttempt and
unique idempotency constraints can survive process loss after a simulated provider write without
creating a duplicate. This must pass before any real connector is enabled.
