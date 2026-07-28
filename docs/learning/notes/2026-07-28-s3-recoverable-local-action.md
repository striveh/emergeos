# Learning Note · Durable ActionAttempt and reconciliation

- Date: 2026-07-28
- Related task / commit: Stage 1 S3, this focused commit
- Target mastery level: `L3`
- Current result: `L0 / unassessed` — automated engineering evidence exists; human Teach-back has not run

This is a rehearsal draft and evidence index. It does not claim that the project owner completed the
pre-test, explanation, transfer or debugging exercise.

## Pre-test

Recorded engineering prediction, not a completed human pre-test: dispatching before the attempt commit loses
the recovery anchor; a JVM lock cannot arbitrate two application processes; retrying execute after a lost
response can create a second object; timeout without a provider identifier must stay unresolved; and
terminal state plus Receipt must be one transaction.

## Mechanism

Draft explanation for owner validation:

- input: server-configured principal/connector/audience/account, the current owned Artifact hash, an explicit
  approval and an idempotency key;
- transition: PostgreSQL first stores the exact ActionPlan, approval, Capability, `PLANNED` attempt and
  transition. A guarded database update atomically consumes one call and enters `DISPATCHING` before the
  provider HTTP call;
- ambiguous output: response loss or timeout writes `UNKNOWN` without a Receipt;
- recovery: a different JVM atomically consumes the reconciliation call, enters `RECONCILING`, and invokes
  `RECONCILE_ONLY` with the same provider idempotency identity;
- terminal output: only a definite provider object or failure writes `SUCCEEDED`/`FAILED`, its transition and
  one matching Receipt in the same transaction;
- invariant: no identifier means no success Receipt; one `(connector, accountRef, idempotencyKey)` names at
  most one attempt and simulated provider object.

## Build and break

- 最小实现：three S3 tables, one forward-only migration, pure Java state/ports/service, one thin
  `JdbcClient` store, one loopback HTTP provider adapter and one test-only external provider process.
- 先失败的测试：the packaged HTTP test reached PostgreSQL, provider, two healthy application JVMs and the
  S1/S2 setup, then received `404` for the missing Action route; the focused Core test failed compilation
  for the missing state and Capability types.
- 注入的故障：two packaged JVMs race one key; provider blocks before persisting, then persists one object
  and drops the response; both applications are forcibly killed; wrong audience/account applications
  attempt reconciliation; a new JVM reconciles; a separate mode times out without creating an object; a
  PostgreSQL trigger rejects Receipt insert.
- 实际观察：one attempt, one initial budget use and no Receipt existed before provider release; after loss,
  one provider file row and `UNKNOWN` survived. The new JVM produced
  `PLANNED → DISPATCHING → UNKNOWN → RECONCILING → SUCCEEDED`, two total calls, one object and one Receipt.

## Alternatives and trade-offs

- Reusing the Stage 0 in-memory Manifestation/Receipt would preserve neither the exact S2 Artifact provenance
  nor cross-process recovery, so S3 uses a separate durable Action boundary and leaves Stage 0 unchanged.
- A database transaction cannot include the external side effect. The selected boundary commits the claim,
  calls the provider outside the transaction, then commits `UNKNOWN` or the terminal result.
- `UNKNOWN` is less convenient than a guessed success/failure, but it is the only honest state when the
  provider gives no definite result.
- The budget deliberately covers both provider calls in this slice. It prevents unbounded reconciliation,
  but an exhausted unresolved attempt needs an S4 operating/human path rather than hidden retries.
- Temporal could later coordinate waiting and retries, but it cannot replace the database uniqueness,
  persisted authority, provider idempotency or Receipt transaction.

## Codex collaboration receipt

- Codex/Subagent 做了什么：two read-only subagents inspected reusable types, exact local versions, official
  APIs and adversarial process tests; a third independently reviewed the completed production Diff. The main
  thread was the only writer.
- 我检查或否决了什么：not yet performed by the project owner. Repository decisions rejected an in-JVM Fake
  Provider, blind execute retry, a success Receipt without provider identity and horizontal persistence of
  unrelated Stage 0 aggregates.
- 哪部分我能够独立复现：not yet assessed.
- 哪部分仍需要补课：draw the transaction/provider failure windows without notes, change one Capability
  binding safely, and diagnose a deliberately split Receipt transaction.

## Interview drill

These are prompts, not a completed Teach-back:

- 30 秒解释：为什么 timeout 既不是成功也不是失败，而必须持久化成 `UNKNOWN`？
- 5 分钟深挖：画出两 JVM 竞争 claim、provider 创建对象后丢响应、旧 JVM 被杀、新 JVM
  reconcile 和 Receipt commit 的时序。
- 最可能被追问的三个问题：为什么唯一键不包含 principal？为什么不能把 HTTP 放进数据库事务？
  为什么两次 provider call 仍能只有一个模拟对象？
- 可引用的真实证据：S3 Build Note 中的 Red、provider/app PID、状态历史、数据库行数、provider
  state file 和 Receipt rollback injection。

## Spaced review

- One-week review date: 2026-08-04
- New variant or failure to solve: privately remove one exact Capability predicate or split terminal state
  from Receipt, predict the first failing adversarial test, then repair it without the implementation notes.
- Updated mastery level: pending; do not change before the review and human defense.
