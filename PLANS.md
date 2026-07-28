# Execution Plans

Use an ExecPlan when work crosses more than one major module, is expected to last longer than one focused
day, changes a public contract or security boundary, adds infrastructure, or contains material unknowns.

Routing is exclusive:

- `S`: PR description or short Receipt only; no Task Brief or ExecPlan.
- `V/R` below the threshold above: use one Task Brief.
- `V/R` at or above the threshold above: use one ExecPlan; it replaces the Task Brief.
- `X`: use a short spike record with hypothesis, 2–4 hour time box, reproduction, conclusion and limits.

Inside an active ExecPlan, each engineering slice adds only a short delta (observable result, first Red,
owned files, new risk and Receipt) to the plan or Issue. Do not copy the full Task Brief.

Create plans under `docs/plans/YYYY-MM-DD-short-name.md`. An ExecPlan is a living handoff artifact:
another contributor or Codex session should be able to continue from it without relying on chat history.

## Required structure

```markdown
# ExecPlan: outcome

## Five-outcome alignment
- AI Coding:
- Agent Engineering:
- Product/Production:
- Career:
- Business:

## Context and user result
Current behavior, evidence, desired observable result and non-goals.

## Architecture and constraints
Relevant code paths, ADRs, versions, invariants, privacy and authority.

## Slices and stage gate
Each slice must be independently runnable or able to change a decision. The Roadmap Stage/Release Gate is
the five-outcome milestone; ordinary slices record only relevant receipts and may use `N/A`.

## Test and evaluation plan
First Red evidence, contract/integration/fault tests, agent eval, user experiment.

## Delegation and ownership
Agent/thread, bounded task, allowed files, dependencies and expected return.

## Progress
- [ ] Timestamped, concrete step.

## Decisions
Decision, alternatives, evidence and date. Link an ADR when the decision is durable.

## Surprises and failures
Observed behavior, reproduction and plan change.

## Verification receipts
Commands, artifacts, demos, unverified boundaries.

## AI Coding receipt
Task framing, delegation, verification, rejected suggestion or `N/A`.

## Agent Engineering receipt
Mechanism practiced, human teach-back, failure diagnosed or `N/A`.

## Career receipt
Case Card, demo, interview question or `N/A`.

## Business receipt
User/retention/payment evidence or why it is not applicable.

## Outcome and next hypothesis
What is now true, what remains false, rollback and smallest next test.
```

## Plan discipline

- Update progress and decisions while working, not retrospectively.
- Record observable facts and file references; do not paste hidden chain-of-thought.
- Mark changed assumptions instead of silently rewriting history.
- A plan does not authorize external publication, credentials or expanded data access.
- Close the plan only when verification receipts support the stated outcome.
