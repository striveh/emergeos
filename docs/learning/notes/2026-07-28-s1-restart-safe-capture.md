# Learning Note · PostgreSQL uniqueness and restart-safe Capture

- Date: 2026-07-28
- Related task / commit: Stage 1 S1, this focused commit
- Target mastery level: `L3`
- Current result: `L0 / unassessed` — automated engineering evidence exists; human Teach-back has not run

This is a rehearsal draft and evidence index. It does not claim that the project owner has completed the
pre-test, explanation, transfer or debugging exercise.

## Pre-test

Recorded engineering prediction, not a completed human pre-test: an application-level
“find nonce, then insert” sequence can race; accepting principal or request hash from the client breaks the
trust boundary; rebuilding objects in one JVM does not prove restart durability.

## Mechanism

Draft explanation for owner validation:

- input: server-configured principal plus client nonce and validated Capture semantic fields;
- transition: Core computes a versioned SHA-256 request hash, then PostgreSQL attempts one insert under
  unique `(principal_id, client_nonce)`;
- output: the insert winner returns `201`; the same request reads and returns the original with `200`;
  a different hash returns `409`;
- invariant: a principal/nonce names at most one committed Capture, and reads always include principal;
- failure mode: without database uniqueness, two processes can both observe absence and create different
  Captures; after `DO NOTHING`, failing to select the committed row leaves no original to compare or return,
  while returning the proposed Java object can diverge from the database-canonical value.

## Build and break

- 最小实现：one `captures` table, one Flyway migration, one Core port and one `JdbcClient` adapter.
- 先失败的测试：packaged HTTP request returned `404`; focused Core test did not compile because the
  command/hash behavior was absent.
- 注入的故障：same/different-hash concurrency, forced application termination, different configured
  principal and wildcard bind.
- 实际观察：one database winner; original returned after a different JVM started; foreign and missing
  reads had the same `404` shape; wildcard binding failed before serving; PostgreSQL rounded a nanosecond
  timestamp to microseconds, so the first write also had to return the database-canonical row.

## Alternatives and trade-offs

- A process-local map is simpler but cannot satisfy restart or multi-process evidence.
- A distributed lock adds machinery without replacing the database invariant needed here.
- `SERIALIZABLE` could detect more anomalies but is unnecessary for one uniqueness decision and would add
  retry behavior. PostgreSQL `READ COMMITTED`, `INSERT ... ON CONFLICT DO NOTHING`, then a second select
  is sufficient for this slice.
- Persisting all Manifestation repositories would increase schema and review surface without strengthening
  the S1 Capture invariant.

## Codex collaboration receipt

- Codex/Subagent 做了什么：two read-only subagents explored architecture and acceptance/fault cases; the
  main thread implemented and verified the slice.
- 我检查或否决了什么：not yet performed by the project owner. The repository decision rejected a
  horizontal Manifestation persistence expansion.
- 哪部分我能够独立复现：not yet assessed.
- 哪部分仍需要补课：explain PostgreSQL visibility after conflict, rebuild the CAS variant without notes,
  and diagnose an injected isolation/transaction bug.

## Interview drill

These are prompts, not a completed Teach-back:

- 30 秒解释：为什么唯一约束和请求 Hash 分别解决两个不同问题？
- 5 分钟深挖：画出两个进程并发使用同一 nonce 时，赢家提交、输家读取和 Core 判定的顺序。
- 最可能被追问的三个问题：为何不是 exactly-once？为何 hash 不含 principal/nonce？为何不用
  `SERIALIZABLE` 或分布式锁？
- 可引用的真实证据：S1 Build Note 中的 Red、六个 PostgreSQL 测试与三个真实应用 PID。

## Spaced review

- One-week review date: 2026-08-04
- New variant or failure to solve: change the isolation level and predict the contention behavior, then
  implement S2 compare-and-swap.
- Updated mastery level: pending; do not change before the review and human defense.
