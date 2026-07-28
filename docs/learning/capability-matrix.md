# Capability Matrix

This matrix tracks demonstrated capability, not reading progress. `Unassessed` is the correct initial value
when repository evidence exists but the project owner has not completed a teach-back, transfer and debugging
exercise.

| Domain | L3 evidence required | Current evidence | Human level |
|---|---|---|---|
| AI Coding | Slice a task, design Red evidence, reject a bad Agent change, debug and verify | S1/S2 Red → fault → process Receipts; owner review/transfer pending | Unassessed |
| Java domain design | Rebuild and defend state/invariant boundaries | Core, ADRs, domain tests | Unassessed |
| SQL/distributed correctness | Resolve CAS, duplicate delivery and ambiguous external success | S1 uniqueness plus S2 four-field PostgreSQL CAS, rollback injection, two-process race and restart evidence; Teach-back pending | L0/unassessed |
| Agent loop/harness | Build Fake/real AgentKernel and explain runtime boundaries | Contracts/design only | L0/unassessed |
| Context/memory/Self | Reject unsupported inference; correct/delete with provenance | Architecture only | L0/unassessed |
| Agent evaluation | Run fixed `model × harness` repetitions and attribute failures | Plan/task pack only | L0/unassessed |
| Capability/security | Threat-model identity, approval, tools, secrets and injection | S1/S2 configured principal, owner-scoped GET/PUT, unknown-field rejection and loopback failure tests; human defense pending | Unassessed |
| Durable workflow | Recover waiting/action state after kill/replay | Proposed ADR only | L0/unassessed |
| Voice/dynamic UI | Measure interruption/latency and enforce safe UI Schema | Product design only | L0/unassessed |
| Production operations | Diagnose from alert to Trace/Receipt; restore backup | S1/S2 packaged kill/restart only; alerting and backup/restore not implemented | L0 |
| Open source collaboration | An unfamiliar contributor can run and improve safely | Docs/CI foundation | Unassessed |
| Product/business | Identify ICP, prove repeated outcome and real payment | Hypothesis only | L0/unassessed |
| Interview communication | Defend decisions through changed constraints and failure questions | Evidence template only | Unassessed |

## Update rule

For each level change, link:

- one implementation or real task;
- one failure or changed-constraint exercise;
- one human explanation/defense;
- one dated Learning Note.

Codex self-assessment cannot raise a level. Reading raises at most to L1; successful implementation with help
raises at most to L2; transfer plus debugging is required for L3.
