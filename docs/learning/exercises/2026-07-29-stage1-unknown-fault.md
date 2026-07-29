# Learning Exercise · Stage 1 undisclosed recovery fault

- Date: 2026-07-29
- Status: assigned; owner diagnosis pending
- Target: `L3` diagnosis for idempotent external action and reconciliation
- Isolated worktree:
  `/Users/huangqingxi/workspace/personal-ai-os-stage1-diagnosis`
- Branch: `learning/stage1-unknown-fault-20260729`
- Seed commit: `4c1bea9`
- Main worktree impact: none

## Scenario

An external action reached `UNKNOWN`. Before reconciliation, the user revised
the source Artifact. The external object may already exist, so the system must
reconcile the original Action rather than dispatch it again.

The focused test now fails reproducibly:

```text
expected: ClaimResult.Claimed
actual:   ClaimResult.Rejected
location: PostgresActionAttemptStoreTest.java:121
```

Run:

```bash
cd /Users/huangqingxi/workspace/personal-ai-os-stage1-diagnosis

./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/postgres -am \
  -Dtest=PostgresActionAttemptStoreTest#persistsEveryStateBudgetUseAndReceiptAcrossAdapterInstances \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

For the first diagnosis attempt, do not use `git diff`, `git show` or compare the
learning branch with `main`. Diagnose from the scenario, failing test, state and
code path.

## Owner mission

Return these four answers in your own words:

1. What user-visible problem does this rejection cause?
2. Which transition was expected, and what relevant event happened immediately
   before it?
3. Which invariant should differ between first dispatch and reconciliation?
4. Where would you make the smallest fix, and what regression assertion proves
   it?

Then make the smallest fix in the isolated worktree and rerun the focused test.
Do not weaken or delete the assertion.

## Progressive hints

Use only when needed:

1. Read test lines 93–145 and write the state sequence plus the operation between
   `UNKNOWN` and `RECONCILING`.
2. Compare `claimDispatch` with `claimReconciliation`; ask which one is allowed
   to depend on the current Artifact head.
3. Follow the boolean passed into the shared claim SQL and inspect the predicate
   it enables.

## Completion evidence

- owner diagnosis in their own words;
- exact fix and focused Green output;
- one explanation of why the tempting alternative is unsafe;
- main remains unchanged;
- only after the owner diagnosis, compare against the seeded Diff and record the
  result in the Stage 1 Learning Note.

Codex may clarify vocabulary and run commands. It must not supply the root cause
before the owner's first diagnosis attempt.
