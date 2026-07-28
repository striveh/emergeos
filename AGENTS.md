# EmergeOS Agent Guide

This file is for AI coding agents and human contributors using agentic tools.

## Read first

Every task reads `README.md` and the files it will change. For `V`, `R` or `X` work, also read:

1. `docs/strategy/five-outcome-charter.md`
2. `docs/engineering/development-method.md`
3. `docs/engineering/codex-playbook.md`
4. the relevant architecture documents, ADRs and active ExecPlan

Do not load every architecture document for an `S` change unless it affects that boundary.

## Mission

Every Roadmap Stage/Release Gate advances five durable outcomes: AI Coding capability, Agent Engineering
capability, a production-grade product, evidence-backed career value, and commercial value. Product and
production truth outrank framework novelty. Use the Five-Outcome Scorecard weekly and monthly. Freeze
new infrastructure after seven days without user contact or two infrastructure slices without market
evidence.

## Current scope

The repository is a research prototype. The active slice is:

```text
Thought Seed → Evidence → Working Self → Artifact
→ Approval → Local Action Stub → Receipt → Reflection Candidate
```

Do not add real publishing, production credentials, autonomous Self Model mutation, or irreversible actions unless the task explicitly includes the required policy, test environment, approval and receipt work.

## Architecture rules

- Dependencies point inward: `api → adapters → core → contracts`.
- `modules/core` must remain pure Java and must not depend on Spring, Temporal, AgentScope, model SDKs or database drivers.
- SDK and framework types stop at adapters.
- Do not create empty modules for future ideas.
- Do not turn Agent roles into long-lived services without evidence.
- Context is a projection; Evidence, approved state, Artifact and Receipt are truth.
- Do not record or expose hidden chain-of-thought.

## Safety invariants

1. No Evidence, no Artifact.
2. Artifact edits create a new version and hash.
3. Approval and Capability bind the current Artifact hash.
4. No Receipt, no completed status.
5. One idempotency key cannot create two external effects.
6. Reflection candidates do not modify the Self Model until explicitly promoted.
7. Tests and issues use synthetic data only.

## Commands

```bash
npm ci
./mvnw verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
java -jar apps/api/target/emerge-api-0.1.0-SNAPSHOT.jar
```

## Change process

- Classify work as `S` small, `V` vertical behavior, `R` risky boundary, or `X` time-boxed spike.
- `S` uses a PR/short Receipt; short `V/R` uses `docs/engineering/task-brief-template.md`; `V/R`
  crossing the `PLANS.md` threshold uses an ExecPlan **instead of** a Task Brief; `X` uses a short,
  time-boxed spike record.
- For behavior changes, establish the appropriate failing test/eval before implementation. Bug fixes
  require a reproduction first. A Spike may explore first but cannot enter production without tests.
- Small compatible fixes: code, tests and docs in one change.
- Public schemas, Self Model, permissions, external actions, core dependencies and constitution changes require an RFC before implementation.
- Accepted decisions are recorded as ADRs and are superseded, never silently rewritten.
- New model, tool or harness behavior requires a reproducible evaluation or clearly marked spike.

## Codex and subagents

- The main agent owns requirements synthesis, architecture proposals, file ownership, integration,
  verification execution and evidence summaries. The human owner accepts risk and makes milestone
  go/no-go decisions.
- Delegate bounded, independent, read-heavy work first: exploration, official-doc verification, test
  design, logs and adversarial review.
- Use one writer per working tree. Parallel writers require separate Git worktrees and non-overlapping
  ownership; a branch alone is not workspace isolation.
- Subagent completion is a proposal. Inspect its evidence and diff, then run verification yourself.
- Keep one chat per coherent outcome. Put durable truth in code, tests, ADRs and ExecPlans, not chat.
- Turn a workflow into a Skill only after it succeeds manually at least three times or fixes repeated
  friction. Schedule it only after it is predictable.

Project-scoped read-oriented roles live under `.codex/agents/`. Their `read-only` sandbox is a default,
not a security boundary: parent permission overrides may replace it and external tools can mutate remote
state. Prompts must prohibit mutating MCP/app/Connector calls; strict review runs start with read-only parent
permissions and a restricted tool surface.

## Definition of done

- The changed behavior has an automated test or repeatable acceptance check.
- Module boundaries remain intact.
- Failure, retry, idempotency and user authority have been considered.
- Documentation and examples reflect the real behavior.
- `./mvnw verify`, contract verification and documentation link checks pass.
- No credentials, personal data, private prompts or complete real traces are present.
- V/R slices record relevant evidence and may use `N/A`. Every Roadmap Stage/Release Gate has all five:
  AI Coding, Agent Engineering, Product/Production, Career and Business receipts.
- The owner can explain the key mechanism, failure boundary, evidence and rejected alternative.
