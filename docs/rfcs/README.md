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
