# Learning Note · Readiness, migration and restore evidence

- Date: 2026-07-29
- Related task / commit: Stage 1 S4, this focused commit
- Target mastery level: `L3`
- Current result: `L1` — checkpoints 1–3 passed and checkpoint 4 is in progress;
  hands-on transfer and independent diagnosis have not run

This is a rehearsal draft and evidence index. It does not claim that the project
owner completed a hands-on no-notes replay, migration/restore exercise or
independent diagnosis.

## Owner Teach-back · 2026-07-29

### Checkpoint 1 · ambiguous external outcome

- Prompt: 平台返回超时或没有帖子 ID 时，为什么不能直接再发一次？
- Owner's exact answer: `可能已经发出了，只是没有结果而已`
- Assessment: pass. The answer correctly recognizes that the external action may
  already have succeeded even though the local system did not receive its
  result, so retrying immediately could duplicate the action.
- Still unassessed: the holding state and the safe next recovery action. No
  mastery-level change yet.

### Checkpoint 2 · honest holding state and recovery

- Prompt: 既然不能直接重发，系统应该先把它记成什么状态，接下来做什么？
- Owner's exact answer: `待确认状态，接下来去尝试获取状态`
- Assessment: pass. `待确认` is the plain-language form of `UNKNOWN`; the next
  step is reconciliation—querying the platform with the original Action's
  identity and evidence instead of creating a new Action.
- Safety boundary: if the platform cannot provide an authoritative answer, keep
  the Action unresolved, block duplicate execution and request human review.
- Still unassessed: readiness versus liveness under this changed operating
  condition. No mastery-level change yet.

### Checkpoint 3 · readiness versus liveness

- Prompt: 系统重启后能正常运行，但还有一条“待确认”的发布：是否允许系统
  启动、是否继续接收新发布任务，为什么？
- Owner's exact answer:
  `允许，不应该继续接收新的发布任务，系统启动才能确定问题，问题处理前不该再处理新发布任务`
- Assessment: pass. The process must remain live so it can inspect and repair
  the unresolved Action, while readiness must fail so no new work increases the
  ambiguity or causes duplicate effects.

### Checkpoint 4 · human-confirmed evidence

- Prompt: 平台接口仍查不到结果，但个人主页出现标题和封面匹配的帖子。
  系统能否只凭截图自动判定成功；如果不能，应由谁确认并保存什么证据？
- Owner's exact answer: `可以凭人工确认只凭截图判定`
- Assessment: partial pass. An authorized human may use a screenshot to confirm
  the outcome when policy permits, but the system must label the result
  `human-confirmed`, not `platform-verified`.
- Evidence boundary: bind the screenshot to the original Action, expected
  account, observed time and content fingerprint; preserve the confirmer,
  confirmation time, evidence reference and reason. Never invent a platform
  object ID or URL that the evidence does not contain.
- Follow-up prompt: 如果存在同标题、同封面的旧帖，至少还应记录什么来证明
  截图对应本次发布？
- Owner's exact follow-up answer: `时间`
- Follow-up assessment: correct but incomplete. Time is necessary evidence, but
  it does not by itself bind the screenshot to the expected account, content
  fingerprint and original Action; timezone, delayed display and duplicate
  content can still make the observation ambiguous.
- Still pending: identify the account and content binding needed alongside time.

### Level decision

- `L1` is supported for the ambiguous-success portion of SQL/distributed
  correctness, durable workflow, and the readiness portion of production
  operations.
- `L2` is not claimed: the owner has not yet performed the recovery against a
  running system, changed the implementation or interpreted the resulting
  Trace/Receipt.
- `L3` is not claimed: no independent changed-constraint debugging or transfer
  has been demonstrated.

## Pre-test

Recorded engineering prediction, not a completed human pre-test: process
liveness is not durable readiness; an `UNKNOWN` Action must block new work
without preventing the recovery JVM from starting; table existence alone cannot
prove a safe migration; a backup file is not recovery evidence until it restores
into a clean destination and the durable truth matches; and a time-based reset
of `DISPATCHING` can falsely take work from a still-live owner.

## Mechanism

Draft explanation for owner validation:

- input: Flyway's current/validation result, PostgreSQL reachability and the six
  Action state counts for the server-configured principal;
- transition: a read-only adapter performs one owner-scoped aggregate query; the
  API health adapter combines it with database/migration health;
- output: `UP` for current migration plus no unresolved Action,
  `OUT_OF_SERVICE` for `PLANNED`/`DISPATCHING`/`UNKNOWN`/`RECONCILING`, and
  `DOWN` for database or migration failure;
- recovery authority: only `UNKNOWN` has the implemented reconcile HTTP entry;
  `DISPATCHING` and `RECONCILING` explicitly say no safe stale recovery;
- invariant: operational inspection does not change Action state, spend budget,
  create a Receipt or reveal another configured principal;
- migration/restore proof: compare stable business values before/after populated
  upgrades and after restoring a real PostgreSQL archive into a new database.

## Build and break

- 最小实现：one aggregate PostgreSQL probe, one API health contributor, one
  packaged fail-fast test, one upgrade/restore game-day test, one replay script,
  one runbook and one sanitized trace. No V4 schema or Core change.
- 先失败的测试：the packaged endpoint returned Boot's generic readiness without
  a durability component; the focused adapter test failed compilation because
  the probe did not exist.
- 注入的故障：owner and foreign unresolved rows; response loss and no-object
  timeout; global-health startup deadlock; populated V1/V2/V3 upgrades;
  destructive cascade truncate followed by new-database restore; corrupted
  applied V3 checksum.
- 实际观察：readiness counts stayed principal-scoped and read-only; liveness
  let a new JVM start while readiness remained `503`; all stable data snapshots
  matched; the incompatible packaged process never became ready; one local
  restore observation was 229 ms, explicitly not an RTO/SLA.

## Alternatives and trade-offs

- Reporting only database ping would be simpler but would mark an unresolved
  Action as ready and hide the operator's recovery entry.
- Returning process `DOWN` for `UNKNOWN` would prevent recovery automation from
  recognizing the new JVM. Separating liveness from readiness preserves both
  honesty and repairability.
- Adding a general metrics platform would exceed the fault matrix. Bounded
  readiness details are sufficient for this slice and avoid new telemetry
  storage, labels and retention policy.
- A time threshold on `updated_at` does not prove the old owner is dead. Safe
  takeover requires PostgreSQL-canonical lease ownership and fencing on every
  terminal commit; S4 keeps that future work explicit.
- Generic down migrations are not promised. A forward fix or verified restore
  is selected case by case after fail-fast validation.

## Codex collaboration receipt

- Codex/Subagent 做了什么：two read-only reviewers independently audited the
  ADR evidence gap; one read-only reviewer designed bounded operating tests; the
  main thread was the only writer, implemented the slice and reviewed every
  conclusion and Diff. A separate post-implementation production review is
  recorded in the ExecPlan.
- 我检查或否决了什么：not yet performed by the project owner. The repository
  decision rejected a fragile stale timestamp reset and rejected expanding S4
  into a lease/fencing runtime.
- 哪部分我能够独立复现：not yet assessed.
- 哪部分仍需要补课：explain readiness vs liveness without notes, restore the
  archive manually, design a fenced lease predicate and diagnose a deliberately
  altered migration or count scope.

## Interview drill

These are prompts, not a completed Teach-back:

- 30 秒解释：为什么 `UNKNOWN` 应让 readiness 失败，却不应让 liveness 失败？
- 5 分钟深挖：画出 provider success、旧 JVM 死亡、数据库仍为
  `DISPATCHING` 的窗口，再画出 owner/lease/fencing 的安全候选。
- 最可能被追问的三个问题：为什么恢复到新库？为什么 checksum mismatch
  必须启动失败？为什么 229 ms 不能叫 RTO？
- 可引用的真实证据：S4 Build Note 的 Red/Green、六表快照、packaged
  fail-fast Receipt、独立审查和脱敏 trace。

## Spaced review

- One-week review date: 2026-08-05
- New variant or failure to solve: with no implementation notes, add a synthetic
  pending migration or wrong-principal row, predict readiness exactly, then
  repair a deliberately removed owner predicate.
- Updated mastery level: pending; do not change before owner defense and
  independent diagnosis.
