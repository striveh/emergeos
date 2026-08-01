# EmergeOS

[![CI](https://github.com/striveh/emergeos/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/striveh/emergeos/actions/workflows/ci.yml)

[中文 README](./README.md) · English

> A user-owned Personal AI OS for turning what you see, think, and intend into
> evidence-backed, editable, and reviewable outcomes.

> **Status: pre-alpha research prototype.**
>
> EmergeOS is not production-ready. It has no production authentication,
> encrypted user storage, live platform connectors, autonomous Self Model
> updates, or supported cloud deployment. Use synthetic, non-sensitive data
> only, and do not expose the local API to a LAN or the public internet.

## Why EmergeOS

The long-term goal is to help a person move through this loop:

```text
what I see
→ what I think
→ what I intend
→ an artifact or action
→ a verifiable receipt
→ reflection and evaluated evolution
```

EmergeOS is being built as an inspectable AI system rather than a chat wrapper.
Evidence, authority, durable state, evaluation, and recovery are treated as
product primitives.

The current repository proves selected engineering boundaries behind that
vision. It does not yet deliver the complete Personal AI OS described above.

## What works today

The Pack009 public-preview baseline includes:

- restart-safe, owner-scoped Capture storage in PostgreSQL;
- conflict-safe Artifact revision using compare-and-swap;
- durable `AgentRun`, safe hashed Trace, Result, immutable resource bindings,
  and `HarnessRunBundle`;
- a framework-neutral, bounded Fake Agent loop with typed tool authority;
- one serial, read-only Worker handoff with a single parent Artifact writer;
- an isolated OpenAI Responses adapter tested against local loopback fixtures;
- frozen synthetic Task Packs and deterministic offline Harness evaluation;
- append-only local attempt evidence and create-only terminal records;
- a PostgreSQL-backed canonical graph-attempt prefix with exact parent/child
  Run and execution-profile bindings.

All normal product API generation remains deterministic and Fake. No real
model result or platform action is claimed.

## Pack009 evidence boundary

Pack009 tests one deliberately difficult failure window:

```text
fixed PUBLIC synthetic graph
→ PostgreSQL one-shot execution claim
→ durable provider intent
→ independent loopback provider accepts one request
→ writer JVM is forcibly terminated before attribution is committed
→ two fresh verifier JVMs independently return
  VALID / INCOMPLETE / billing UNKNOWN
→ replay attempts are rejected before a second provider request
```

This demonstrates durable uncertainty and no automatic replay after a
provider-accepted crash.

It does **not** demonstrate:

- a real OpenAI request, API key, model result, token count, cost, or invoice;
- network-call exactly-once semantics;
- a completed terminal graph or successful product Artifact;
- provider-side idempotency or reconciliation;
- power-loss, NFS, cross-host, or hostile-database durability;
- production authorization, checkpoint/resume, Temporal, or a real Connector.

The shipping graph application exposes only safe preflight/help behavior. Its
database verification route is disabled, and it has no execute route.

See the [Pack009 Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)
for exact tests, receipts, and non-claims.

## Architecture

EmergeOS currently uses a modular monolith with inward-pointing dependencies:

```text
API and runner applications
        ↓
in-memory / PostgreSQL / OpenAI adapters
        ↓
pure Java Core
        ↓
versioned Contracts
```

```text
apps/api/                    local HTTP API and composition
apps/eval-runner/            isolated synthetic model evaluation
apps/graph-eval-runner/      Pack009 graph preflight; execute disabled
apps/offline-harness-runner/ deterministic comparison and replay
modules/contracts/           stable cross-boundary contracts
modules/core/                domain rules, use cases, AgentKernel ports
adapters/agent-loop/         provider-neutral bounded model/tool loop
adapters/inmemory/           deterministic Fake implementations
adapters/postgres/           durable product and graph truth
adapters/openai/             isolated Responses protocol adapter
contracts/                   JSON Schema and cross-language fixtures
evals/                       frozen synthetic Task Packs
docs/                        architecture, RFCs, ADRs, plans, and receipts
```

`modules/core` is pure Java and does not depend on Spring, model SDKs,
database drivers, Temporal, or an Agent framework. Vendor and infrastructure
types stop at adapter boundaries.

## Safety model

The current prototype follows these rules:

- no Evidence, no Artifact;
- no Receipt, no completed external action;
- models do not write directly to PostgreSQL;
- tool registration, Task authority, and owner-scoped data access are separate
  checks;
- Trace records typed execution facts, not hidden chain-of-thought;
- external publishing, deletion, messaging, and other irreversible actions
  require explicit human authority;
- evaluation fixtures contain synthetic data only.

The API listens on `127.0.0.1` by default and rejects non-loopback binding
while authentication is absent. The configured `local-user` principal is not
an authentication system.

Do not use production credentials, private prompts, real browsing history,
raw personal conversations, or sensitive user data with this release.

See [SECURITY.md](./SECURITY.md) for reporting and support boundaries.

## Requirements

- Java 21
- Node.js 22 and npm
- Docker
- PostgreSQL for the local API

The repository includes Maven Wrapper scripts.

## Verify the repository

```bash
npm ci

./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
./mvnw --batch-mode --no-transfer-progress verify
```

These commands use synthetic fixtures. They do not intentionally call a live
model or external platform.

## Run the zero-egress preflights

After the Maven build:

```bash
# Synthetic Eval Runner preflight.
# Does not read OPENAI_API_KEY or construct a provider request.
java -jar \
  apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar

# Pack009 graph preflight.
# Shipping database verification and execution remain disabled.
java -jar \
  apps/graph-eval-runner/target/emerge-graph-eval-runner-0.1.0-SNAPSHOT-app.jar \
  --preflight
```

A live-provider command is intentionally not part of this quick start.

## Run the local deterministic API

Start a local PostgreSQL container:

```bash
docker run --detach --rm \
  --name emerge-postgres \
  -e POSTGRES_DB=emerge \
  -e POSTGRES_USER=emerge \
  -e POSTGRES_PASSWORD=local-prototype-only \
  -p 127.0.0.1:5432:5432 \
  postgres:18.4-alpine
```

Start the API:

```bash
EMERGE_DB_URL='jdbc:postgresql://127.0.0.1:5432/emerge' \
EMERGE_DB_USER='emerge' \
EMERGE_DB_PASSWORD='local-prototype-only' \
java -jar apps/api/target/emerge-api-0.1.0-SNAPSHOT.jar
```

Check local health:

```bash
curl -s http://127.0.0.1:8080/actuator/health/liveness
curl -s http://127.0.0.1:8080/actuator/health/readiness
```

The normal API uses deterministic Fake generation. It does not publish to an
external platform.

For the complete local Capture → Agent draft → Artifact → Run/Trace/Bundle
walkthrough, see the [Chinese README](./README.md) and the
[Stage 1 Operating Runbook](./docs/operations/stage1-operating-runbook.md).

## Project maturity and roadmap

This release is an engineering baseline, not a supported product release.

Not yet implemented as production capabilities:

- authentication and multi-tenant isolation;
- encrypted personal data and a Secret Broker;
- live social-platform Connectors;
- safe stale-run takeover with lease/fencing;
- durable workflow checkpoint/resume;
- production Self Model learning and promotion;
- voice-first capture, mobile clients, and generative UI;
- live-model quality, cost, and stochastic Harness baselines;
- real-user retention, payment, or product-market evidence.

Start with:

- [System Overview](./docs/architecture/system-overview.md)
- [Roadmap](./ROADMAP.md)
- [Public Contracts](./contracts/README.md)
- [RFC Index](./docs/rfcs/README.md)
- [Documentation Map](./docs/README.md)

## Contributing

Contributions may include code, synthetic evaluations, fault cases, product
design, research, documentation, and translation.

Please read:

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [GOVERNANCE.md](./GOVERNANCE.md)
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)
- [DCO](./DCO)

Never submit credentials, real user data, private prompts, complete real
traces, or identifiable personal history in an Issue, PR, fixture, or
benchmark.

## Security

Do not report exploitable vulnerabilities in a public Issue. Follow
[SECURITY.md](./SECURITY.md) and use the repository's private vulnerability
reporting channel.

EmergeOS currently provides no production support or security SLA.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](./LICENSE).

EmergeOS is an independent project. References to third-party products and
trademarks do not imply affiliation or endorsement.
