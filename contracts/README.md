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

Versioning rules:

- Because v1 objects use `additionalProperties: false`, adding an optional property is not
  automatically wire-compatible: output from a new producer can be rejected by an older strict
  validator. While v1 is unpublished, such changes require coordinated schema, Java type and
  fixture updates plus compatibility tests. After publication, a new property requires a
  versioned contract (or an explicitly designed extension point); it must not be described as
  compatible merely because existing payloads still validate against the newer schema.
- Removing fields, changing meaning, narrowing enums or changing identity/idempotency semantics is breaking.
- Breaking changes require a new schema major version and migration notes.
