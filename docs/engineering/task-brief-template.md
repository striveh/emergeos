# Task Brief: outcome

- Change class: `V | R`
- Expected duration:

Use this template only when the work is below the [ExecPlan](../../PLANS.md) threshold. `S` uses a short
Receipt, an ExecPlan replaces this template for larger `V/R` work, and `X` uses a short spike record.

## Five-outcome alignment

- AI Coding capability:
- Agent Engineering mechanism and target mastery:
- User/Product outcome and production quality:
- Career/interview evidence:
- Business hypothesis or `N/A`:

## Problem evidence

What is observed? Link a synthetic reproduction, user note, metric, issue or prior experiment.

## Scope

- In:
- Out:
- Allowed modules/files:
- One main unknown:

## Architecture context

- Relevant invariants/ADRs:
- Current dependency and version truth:
- Alternatives considered:

## Acceptance

Use observable examples:

```text
Given
When
Then
```

Include success, rejection, retry and recovery states where relevant.

## Test/Eval plan

- First Red test and expected failure:
- Deterministic tests:
- Integration/fault tests:
- Agent eval or user experiment:
- Full verification command:

## Risk

- Identity/data:
- External side effects:
- Concurrency/recovery:
- Cost/latency:
- Rollback:

## Codex delegation

| Role | Bounded task | Write scope | Expected return |
|---|---|---|---|
| Main | Decision synthesis, integration and verification | Entire task | Evidence summary |
| Explorer | | Read-oriented | Paths, evidence, unknowns |
| Test designer | | Read-oriented or tests-only worktree | Red cases and fault matrix |
| Reviewer | | Read-oriented | Prioritized findings |

Do not assign multiple writing agents overlapping files. Parallel writers require separate worktrees.

## Human ownership check

The human owner accepts risk and makes the go/no-go decision. Before closing, explain without copying the
generated summary:

1. Why this design?
2. What can fail?
3. What evidence says it works?
4. What would make us replace it?

## Receipts

- AI Coding:
- Agent Engineering:
- Product/Production:
- Career:
- Business:
