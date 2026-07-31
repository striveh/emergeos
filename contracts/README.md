# Public Contracts

This directory is the language-neutral boundary for clients, agents, tools and connectors.

- `schemas/v1/`: JSON Schema 2020-12 contracts.
- `fixtures/v1/`: at least one accepted and one rejected example for every v1 contract.
- Java records currently live in `modules/contracts`.
- TypeScript and Swift types will be generated after compatibility tests are in place.

`npm ci && ./scripts/verify-contracts.sh` compiles all schemas together with AJV's
JSON Schema 2020-12 implementation, resolves cross-schema references, enables
standard format validation, and checks every fixture. During the prototype,
schema and Java types are reviewed together. Before a public SDK release,
generation and backward-compatibility checking must become automated.

Schema validation is necessary but not sufficient. The Node semantic verifier
and Java constructors additionally enforce cross-field invariants, the shared
SafeText/numeric domains, typed resource bindings, Trace protocol, and
integrity-hash recomputation. A payload is not accepted merely because AJV
accepts its shape.

`WorkerResultEnvelope` 是 child durable output 的 language-neutral contract；
`WORKER_RESULT` binding 把 child Bundle 绑定到它的 integrity hash，`HANDOFF`
binding 再把 parent Bundle 绑定到 exact child Run/Bundle。JSON Schema 只能验证单个
payload 的 shape 与局部字段关系，不能独自证明 same-owner、terminal child、
parent/child Task lineage、single-consume 或 cross-run hash relation。这些关系还必须由
Core pair verifier 与 PostgreSQL verified read/constraints 证明。

Versioning rules:

- Because v1 objects use `additionalProperties: false`, adding an optional property is not
  automatically wire-compatible: output from a new producer can be rejected by an older strict
  validator. While v1 is unpublished, such changes require coordinated schema, Java type and
  fixture updates plus compatibility tests. After publication, a new property requires a
  versioned contract (or an explicitly designed extension point); it must not be described as
  compatible merely because existing payloads still validate against the newer schema.
- Removing fields, changing meaning, narrowing enums or changing identity/idempotency semantics is breaking.
- Breaking changes require a new schema major version and migration notes.

## 当前 unpublished model binding 规则

- `TaskEnvelope 1.0` 与 `HarnessRunBundle 1.0` 是已有 Fake/历史运行格式；三个
  model binding 字段必须缺失或为 `null`，且不进入旧 hash preimage。
- `TaskEnvelope 1.1` 必须完整绑定 `modelProvider`、`modelRequested`、
  `pricingProfile`、server-owned `idempotencyKey` 与 content-addressed
  `environmentSnapshotRef`。
- `HarnessRunBundle.schemaVersion` 必须与内嵌 Task 一致；1.1 Bundle 还必须完整性绑定
  `componentVersions["model-adapter"]`。
- 1.0 的保证是 storage/hash backward-read compatible：旧 JSON 与旧 Bundle hash
  可以被新代码验证读取。它不承诺新 producer 的 JSON 一定能被旧
  `additionalProperties: false` validator 接受。
- 对 1.1 而言，`budgetUsd` 是 requested ceiling。成功结果仍不得超预算；若 provider
  已产生可归因 usage，非成功结果允许如实记录实际 cost 超过 ceiling，不能把真实消费
  改写成零。
