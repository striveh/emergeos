# RFC

RFC 用于讨论影响 public contract、产品宪章、Self Model、权限、外部行动、
核心依赖或跨模块架构的变更。

流程：

```text
Draft → Discussion → Experiment → Accepted | Rejected | Withdrawn
```

RFC 被接受并落地后，由 ADR 记录最终采用的决定；RFC 历史不回写。

当前 RFC：

- [RFC-0001：持久 AgentRun、Safe Trace 与 HarnessRunBundle 完整性绑定](0001-persistent-agent-run-trace-and-bundle.md)
  — `Accepted`，实现决策见
  [ADR-0006](../architecture/decisions/0006-persistent-agent-run-truth.md)。
- [RFC-0002：真实模型只经 synthetic Eval egress，并绑定身份与计量](0002-real-model-synthetic-egress-and-metering.md)
  — `Accepted`，adapter、packaged preflight、bounded runner 与本地工程验证已完成；
  live-provider smoke 仍未批准或执行。
- [RFC-0003：post-dispatch read-only Tool deadline truth](0003-post-dispatch-read-only-tool-deadline-truth.md)
  — `Accepted`，由 Stage 2 S4/F2 Pack 006 冻结 cooperative late-result、
  exact-boundary、cancellation precedence 与 observed-latency contract；实现决策见
  [ADR-0007](../architecture/decisions/0007-observed-latency-and-post-dispatch-tool-deadline.md)。
- [RFC-0004：typed read-only Worker handoff 与 durable Worker Result](0004-typed-read-only-worker-handoff.md)
  — `Accepted`；Pack007 只冻结 single/synchronous/depth=1/read-only Worker、
  provider-neutral two-phase runtime、durable WorkerResult、parent→child hash chain、
  V6 PostgreSQL truth 与 context-policy drift fail-closed，不代表通用 multi-agent；
  实现决策见
  [ADR-0008](../architecture/decisions/0008-typed-read-only-worker-handoff.md)。
- [RFC-0005：child-only model-bound read-only Worker Eval baseline](0005-model-bound-read-only-worker-eval-baseline.md)
  — `Accepted`；Pack008 把 provider-neutral model profile 绑定到 exact child，
  parent 继续使用无 Tool authority 的 Fake Conductor；shipping surface 只增加
  zero-egress `--worker-preflight`，没有 Pack008 live execute route、durable graph
  attempt 或 product API wiring；实现决策见
  [ADR-0009](../architecture/decisions/0009-child-only-model-worker-eval-boundary.md)。
- [RFC-0006：PostgreSQL-canonical one-shot graph attempt 与 UNKNOWN replay boundary](0006-postgresql-canonical-one-shot-graph-attempt.md)
  — `Accepted`；Pack009 已用独立 graph Eval composition root、PostgreSQL V7
  create-only manifest、exact Run/profile binding 与 append-only journal证明：
  loopback provider accepted / attribution missing 时保持 `UNKNOWN`，两个 fresh
  verifier exact-equal，least-authority 与完整 writer replay都不能产生第二次
  request；实现决策见
  [ADR-0010](../architecture/decisions/0010-postgresql-canonical-graph-attempt.md)。
- [RFC-0007：Attributed terminal graph 与 shared-candidate Harness pilot](0007-attributed-terminal-graph-and-shared-candidate-harness.md)
  — `Proposed`；Pack010冻结 Candidate/Report contract、17-event terminal
  protocol、三个 PostgreSQL semantic transaction、真实 Store construction
  capability与逐次 owner-TTY Gate。实现和 live provider均未因 RFC 自动完成或授权；
  proposed decision见
  [ADR-0011](../architecture/decisions/0011-attributed-terminal-graph-and-live-harness-pilot.md)。
- [RFC-0008：bounded provider validation attestation](0008-bounded-provider-validation-attestation.md)
  — `Experiment`；以test-only Ed25519 transcript、独立provider-attestor JVM与
  PostgreSQL one-shot challenge验证request-2 semantic receipt的原子绑定；这不是
  PostgreSQL原生验签，shipping/live与真实r1/r2/r3继续Red。proposed decision见
  [ADR-0012](../architecture/decisions/0012-db-authenticated-provider-validation-attestation.md)。
- [RFC-0009：exact provider profile assertion foundation](0009-exact-provider-profile-assertion-foundation.md)
  — `Experiment`；V14仅冻结provider/profile与pico-USD精确计价的只读数据库断言，
  独立角色零relation ACL且shipping Java consumer为0；不定义graph-bound statement、
  transcript、attestation或TX-A。proposed decision见
  [ADR-0013](../architecture/decisions/0013-exact-provider-profile-assertion-foundation.md)。
- [RFC-0010：exact provider TX-A requirement guard](0010-exact-provider-tx-a-requirement-guard.md)
  — `Experiment`；V15仅在seq13登记不可逆exact-pico TX-A requirement，并由数据库
  deferred guard阻止该attempt继续走历史V8/V13 TX-A；不实现exact attribution、
  overlay head或TX-A。proposed decision见
  [ADR-0014](../architecture/decisions/0014-exact-provider-tx-a-requirement-guard.md)。
- [RFC-0011：exact-pico provider TX-A overlay](0011-exact-pico-provider-tx-a-overlay.md)
  — `Experiment`；V16仅以`RAW_JDBC_LOCAL_OVERLAY_TX_A`在独立relations中形成
  exact-pico attribution/event/head14 closure，legacy head继续停在seq13。
  PostgreSQL不验证Ed25519，只有test JVM完成本地验签；production verifier/API为0，
  focused local Gate已Green，production verifier/Live仍Red。proposed decision见
  [ADR-0015](../architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md)。
- [RFC-0012：dormant V16 Ed25519 public verifier](0012-dormant-v16-ed25519-public-verifier.md)
  — `Experiment`；只新增可打包但未接线的production public-key verifier primitive，
  保持V16 stage/commit Java API与shipping consumer为0。PostgreSQL-native验签仍为0，
  V16 credential/caller仍在TCB。proposed decision见
  [ADR-0016](../architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md)。
- [RFC-0013：dormant typed V16 stage-verify-commit attestor](0013-dormant-typed-v16-stage-verify-commit-attestor.md)
  — `Experiment`；在一个`REQUIRES_NEW` transaction内复用V16 stage/commit与V17 verifier，
  新增单一typed `complete` authority boundary。App consumer、shipping signer、key custody、
  PostgreSQL-native验签与Live仍为0/Red。proposed decision见
  [ADR-0017](../architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md)。
- [RFC-0014：fresh-JVM verified exact-pico overlay reader](0014-fresh-jvm-verified-exact-pico-overlay-reader.md)
  — `Experiment`；以专属只读角色和单一RR/RO snapshot区分`Missing / Required / Attributed / Invalid`，
  独立重算V13-V16 canonical与Ed25519，但保持legacy head13、App consumer与Live不变。proposed decision见
  [ADR-0018](../architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md)。
