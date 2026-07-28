# EmergeOS Agent Guide

This file is for AI coding agents and human contributors using agentic tools.

## Read first

1. `README.md`
2. `docs/architecture/system-overview.md`
3. `docs/architecture/module-map.md`
4. `docs/architecture/runtime-and-state.md`
5. The ADRs relevant to the change

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

- Small compatible fixes: code, tests and docs in one change.
- Public schemas, Self Model, permissions, external actions, core dependencies and constitution changes require an RFC before implementation.
- Accepted decisions are recorded as ADRs and are superseded, never silently rewritten.
- New model, tool or harness behavior requires a reproducible evaluation or clearly marked spike.

## Definition of done

- The changed behavior has an automated test or repeatable acceptance check.
- Module boundaries remain intact.
- Failure, retry, idempotency and user authority have been considered.
- Documentation and examples reflect the real behavior.
- `./mvnw verify`, contract verification and documentation link checks pass.
- No credentials, personal data, private prompts or complete real traces are present.
