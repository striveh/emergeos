# Learning Note · PostgreSQL compare-and-swap and Artifact lineage

- Date: 2026-07-28
- Related task / commit: Stage 1 S2, this focused commit
- Target mastery level: `L3`
- Current result: `L0 / unassessed` — automated engineering evidence exists; human Teach-back has not run

This is a rehearsal draft and evidence index. It does not claim that the project owner completed the
pre-test, explanation, transfer or debugging exercise.

## Pre-test

Recorded engineering prediction, not a completed human pre-test: a read-current-then-insert sequence can
let two processes both propose v2; checking only a version misses the expected content; omitting principal
from the database predicate can turn an authorization bug into an overwrite; updating a head separately
from lineage can leave split state.

## Mechanism

Draft explanation for owner validation:

- input: server-configured principal, Artifact ID, new content and expected base version/hash;
- transition: Core validates the command and computes the new content hash; PostgreSQL atomically updates
  the owner-scoped head only when Artifact ID, version and hash all match, then appends the next immutable
  row in the same transaction;
- output: one writer receives the database-canonical v2 lineage; a stale owner receives a dedicated
  `409 currentVersion`; foreign and missing receive the same `404`;
- invariant: the head names the last ordered version, and every later version names the immediately
  preceding version and exact content hash;
- failure mode: if the version insert fails, the head update must roll back; if CAS updates zero rows,
  classification must remain owner-scoped and must not reveal content or a content-derived hash.

## Build and break

- 最小实现：two S2 tables, one forward-only migration, one Core port/service and one `JdbcClient`
  adapter; the Stage 0 Manifestation graph remains unchanged.
- 先失败的测试：the packaged HTTP test reached two healthy JVMs and S1 Capture, then received `404` for
  the missing Artifact route; the focused Core test failed compilation for missing lineage/CAS types.
- 注入的故障：two PostgreSQL adapters and two packaged JVMs race one base; wrong version/hash matrix;
  exact-base foreign PUT; a trigger rejects v2 insert after the head update; v2 NULL base insertion.
- 实际观察：one `200`, one `409`, one head, two versions, no losing content row; the trigger rolled the
  head back to v1; PostgreSQL rounded Java nanoseconds to microseconds; a third JVM returned byte-for-byte
  identical lineage.

## Alternatives and trade-offs

- Reusing Stage 0 `ArtifactVersion` would either fabricate Working Self/evidence/generator values or make
  existing invariants nullable, so S2 uses a separate durable Capture-to-Artifact boundary.
- A single append-only table can arbitrate v2 with a unique key, but the selected head + versions model
  makes current-base CAS explicit and lets database foreign keys prove both the exact current row and
  exact parent row. The cost is one extra table and the requirement that both writes share one transaction.
- A process lock or Java `synchronized` cannot protect two JVMs and would not prove database restart
  behavior.
- `SERIALIZABLE` is not required for one row CAS. PostgreSQL `READ COMMITTED` waits on the competing row
  update and rechecks the WHERE predicate after the winner commits.

## Codex collaboration receipt

- Codex/Subagent 做了什么：two read-only subagents examined existing domain coupling, PostgreSQL/Spring
  behavior and adversarial process tests; a third independently reviewed the production Diff. The main
  thread wrote and verified all changes.
- 我检查或否决了什么：not yet performed by the project owner. Repository decisions rejected horizontal
  Manifestation persistence, a leaking conflict hash and a stale foreign authorization probe.
- 哪部分我能够独立复现：not yet assessed.
- 哪部分仍需要补课：explain READ COMMITTED row recheck without notes, implement a changed CAS predicate,
  and diagnose a deliberately split transaction.

## Interview drill

These are prompts, not a completed Teach-back:

- 30 秒解释：为什么 CAS 同时需要 principal、Artifact ID、expected version 和 expected hash？
- 5 分钟深挖：画出两个 JVM 对 v1/hash1 的 UPDATE、行锁等待、赢家提交、输家重检和 reload。
- 最可能被追问的三个问题：为什么不只用版本？为什么 conflict 不返回当前 hash？为什么
  deferred head FK 和事务回滚缺一不可？
- 可引用的真实证据：S2 Build Note 中的 Red、trigger rollback、四个独立 PID 和数据库行数。

## Spaced review

- One-week review date: 2026-08-04
- New variant or failure to solve: remove one CAS predicate in a private exercise, predict which
  adversarial test fails, then repair it without the implementation notes.
- Updated mastery level: pending; do not change before the review and human defense.
