# ADR-0011：采用 attributed terminal graph 与 shared-candidate Harness pilot

> Extended by
> [ADR-0012](0012-db-authenticated-provider-validation-attestation.md). The
> extension does not enable the shipping live route or rewrite V1-V12 truth.

- Status: Proposed
- Date: 2026-07-31
- RFC:
  [RFC-0007](../../rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)
- Extends:
  [ADR-0010](0010-postgresql-canonical-graph-attempt.md)

## Context

Pack009 的 durable truth只到 first `PROVIDER_INTENT`。它证明了 accepted-but-
unattributed crash必须保持 `UNKNOWN`，但没有 terminal Run、Candidate、attribution、
Artifact或 seal。与此同时，现有 public PostgreSQL Store constructor与 injected
console/clock虽然尚不能从 shipping CLI触发真实 egress，但在 Pack010 live route中会
成为 authority leak。

真实 Harness比较还有一个独立问题：H0/H1必须评测同一个 stochastic output。若分别
生成，变量不再只有 verifier；若只保存 successful WorkerResult，H1拒绝的 Candidate
会从证据中消失。

## Decision

1. 新增 `HarnessCandidateEnvelope 1.0`，把 structured final作为独立、hash-bound、
   content-redacted-on-log 的模型 observation，并绑定 exact request 2 provider
   response hash、child structured-final proposal hash 与 durable obtained Evidence。
   Candidate不是WorkerResult；
   H1失败时仍保留 Candidate，但没有成功WorkerResult/Artifact；structured final
   形成前失败则明确不造 Candidate，该失败 graph 不能进入 Harness Report。
2. 新增 `HarnessEvaluationReport 1.0`，固定三次 repetition、两个 evaluator arms、
   六次 deterministic evaluation与 complete-only semantics。报告不输出统计显著性
   或模型优劣结论。
3. Pack010固定 `gpt-5.6-terra`、PUBLIC synthetic Capture、serial depth-one graph、
   每次 exact two Responses requests。变更这些参数需要新 protocol/pack。
4. journal在 sequence 11后固定：
   `ATTRIBUTED-1 → INTENT-2 → ATTRIBUTED-2 → CHILD_TERMINAL →
   PARENT_TERMINAL → TERMINAL_SEALED`。sequence 15可以 durable，sequence 16
   不可 commit，sequence 17为 complete terminal。
5. PostgreSQL V8使用三个 semantic transaction：attribution、child terminal、
   parent terminal + seal。现有 generic AgentRun transaction不能与 graph append
   按调用顺序拼接。
6. seal绑定 exact provider attributions、Candidate ref/hash、child/parent terminal
   binding、outcome、billing与 pre-seal head；final event再绑定 seal hash，避免
   circular preimage。
7. provider/child/parent cost与token必须exact一致；parent Handoff binding与Trace
   必须指向 exact child Run/Bundle，并与child status一致。
8. 引入 public `GraphAttemptReader`；真实 PostgreSQL Store construction由 private、
   single-use permit封闭，public safe Access只返回 reader或高层owner-TTY facade。
   Pack010 App不得取得 raw Store/Coordinator/Console/Clock/approval/key/client/model。
9. `pack010-r1/r2/r3`分别需要owner real-TTY批准；predecessor terminal verification
   与next slot claim同事务。没有自动 retry、replacement repetition或batch approval。
10. shipping execute继续disabled，直到 contract、PostgreSQL fault/concurrency、
    authority、loopback、full repository与独立P0/P1 review全部Green。工程Green本身
    不授权真实OpenAI请求。

## Consequences

- rejected Candidate首次成为可审计truth，不再被伪装成WorkerResult或静默丢弃；
- pre-Candidate failure可被seal为可解释终态，但不会伪造成完整 repetition；
- H0/H1可以共享同一个真实模型输出，evaluator本身保持0 network/credential/effect；
- V8 implementation与fault matrix显著增加，但避免terminal Run、journal和seal之间
  的crash gap；
- sequence 16只能在transaction内部出现，数据库constraint与verified reader都必须
  支持这一点；
- provider response与数据库仍不是同一transaction；attribution前crash仍是UNKNOWN；
- public reader/closed construction缩小authority面，但unkeyed hash仍不防hostile DBA；
- 三次pilot可形成协议完整报告，不能外推模型质量或商业价值。

## Evidence required before acceptance

- RFC-0007每项Acceptance有可重放命令与receipt；
- Candidate/Report schema、Java/Node golden parity与semantic negative fixtures Green；
- Core terminal/candidate invariants Green；
- PostgreSQL V8 fresh/populated migration、kill/concurrency/tamper matrix Green；
- Reader/Store construction、TTY、credential/client与shaded bytecode Gates Green；
- loopback三repetition与fresh report reconstruction Green；
- full `./mvnw verify`、contract/doc link checks Green；
- 独立review `P0=0、P1=0`；
- 中文Build Note明确live provider未因ADR自动授权。

## Rollback

在ADR仍为 Proposed期间不开放shipping execute。V8一旦应用不做down migration；
已写入Candidate/attribution/terminal truth不得删除或改写。发现协议缺陷时冻结新slot、
保持现有prefix可读，并通过forward-only migration与新pack修复。
