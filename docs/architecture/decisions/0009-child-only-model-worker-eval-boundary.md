# ADR-0009：model route 只绑定 exact child Worker，Pack008 保持 Eval-only

- Status: Accepted
- Date: 2026-07-31
- RFC:
  [RFC-0005](../../rfcs/0005-model-bound-read-only-worker-eval-baseline.md)
- Extends:
  [ADR-0008](0008-typed-read-only-worker-handoff.md)

## Context

EmergeOS 已分别拥有 typed read-only Worker vertical 与隔离的真实 model protocol
adapter，但还没有证明两者能在 least-authority graph 中组合。直接复用 root
execution profile、普通 API 或 Pack003 execute route，会让 parent 获得不需要的
provider authority，或把 single-Agent attempt evidence 冒充成 Worker graph evidence。

同时，V6 中已经存在 historical Pack007 truth。一个新 Worker generation 不能让
PostgreSQL reader 按“最新注册项”重新解释旧 Run。

## Decision

1. Core 使用 provider-neutral `ModelExecutionProfile` 作为 model adapter boundary。
   root `AgentExecutionProfile` 与 child
   `ModelBoundReadOnlyWorkerExecutionProfile` 都可以实现它，但二者不是同一种
   runtime role。
2. Pack008 parent 固定为 non-model-bound deterministic Fake Conductor，且
   `requiredTools=[]`；只有 exact Task 1.1 child 拥有 OpenAI model/pricing/environment
   identity 与 `capture.read` authority。parent 是唯一 Artifact writer。
3. parent Task 1.0 的非零 budget 只表示 fixed `depth=1` subtree reservation。
   parent 自身没有 model/pricing/token route；只有 child 产生 usage/cost，parent
   Result 必须与唯一 verified child 的 cost/token exact-equal，不能新增或抹掉
   计量；parent Bundle 不得出现 `model-adapter`。Pack007 仍允许 Conductor 自身
   usage，所以历史 subtree aggregate `>= child` 语义不变。
4. Conductor decision surface 使用 content-addressed fingerprint 进入 parent profile、
   Bundle component map 与 attempt identity。Pack007 保持历史 fixed-intent behavior；
   Pack008 独立使用 intent-inheriting behavior。
5. `ReadOnlyWorkerProfileRegistry` 对 `RUNNING` graph 要求 unique full-Task match，
   对 terminal graph 要求 exact registry/fingerprint/experiment/Harness/component
   identity；绝不按 registration order 或“current profile”fallback。
6. Pack008 shipping surface 只有显式 `--worker-preflight`。它只做 bounded、
   hash-frozen、zero-egress verification。普通 API、Pack003 default/execute behavior
   与 Pack007 product route不变。
7. Pack008 graph permit 是专用、process-local、child-only one-shot authority；
   `arm` 只接受 exact compiled attempt ID，test graph Spec 也必须 exact 冻结。
   它只用于 offline/loopback integration tests。在 preflight receipt object、
   durable graph marker、journal、terminal record 与 process-kill evidence完成前，
   不增加 Pack008 execute route。
8. PostgreSQL 继续使用 V6；fresh Store 可验证和完成 Pack008 crash gap，但本 ADR
   不把 same-JVM fresh Store 描述成 fresh JVM/process-kill。

## Consequences

- model/provider authority、usage 与 pricing 可归属到 child，而不是被 parent 或
  generic Worker label 吞掉；
- Pack007 与 Pack008 可以共存，terminal verified read 由 durable profile identity
  决定；
- OpenAI adapter 更可复用，但 SDK 类型和 live-client construction 仍停在 adapter/
  Eval boundary；
- Pack008 已具备严格 preflight、exact graph 与 loopback baseline，却仍不能安全执行
  live smoke；
- shipping CLI 多一个 read-only mode，文档必须明确 no-arg 仍是 Pack003，不能把
  Pack008 写成默认；
- 若未来 Task-compatible profile 产生 ambiguity，必须新增 durable selector/migration，
  不能放宽 registry；
- 若未来引入 parallel/write-capable Worker、product model routing、workflow resume
  或 distributed runtime，必须另开 RFC/ADR 与 fault evidence。

## Evidence

- Pack008 raw bytes、environment、Capture、双 Task、双 profile、pricing、
  prompt/Conductor surface 与 attempt ID 均由 strict preflight/frozen hash 绑定；
- packaged process tests覆盖 `--worker-preflight`、`--help`、invalid combinations、
  hostile environment、owner home/temp directory empty 与 zero-request loopback
  sentinel；
- process-local permit覆盖 exact ordering、CAS one-winner、expiry 与 fail-before-delegate；
- loopback OpenAI protocol test经过 production graph classes，但没有外部网络；
- PostgreSQL mixed-generation tests覆盖 reverse registration、fresh-store crash gap、
  pre-insert fail-closed、cross-profile terminal tamper、exact metering transaction
  rollback、coherent SQL tamper 后三种 fresh Store fail-closed reads 与 read-only
  snapshots；
- Pack007 strict replay保持原 raw SHA 与 Worker profile fingerprint。

完整命令、hash、计数、review 与 nonclaims 见
[Pack008 Build Note](../../operations/build-notes/2026-07-31-s2-s4-model-bound-read-only-worker-baseline.md)。

## Rollback

可移除 Pack008 catalog、preflight CLI route、model-bound Worker profile 与 test-only
graph fixture，同时保留 `ModelExecutionProfile` 和 multi-profile reader compatibility，
因为它们不改变已有 Pack007 durable truth。不得回滚成 registration-order selection，
也不得让旧 binary 覆盖无法识别的 terminal profile。
