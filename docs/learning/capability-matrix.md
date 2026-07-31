# 能力矩阵（Capability Matrix）

本矩阵追踪已经证明的能力，不追踪阅读进度。即使仓库已有工程证据，只要项目所有者尚未完成
teach-back、迁移实现与 debugging exercise，`未评估` 仍是正确的初始等级。

| 领域 | L3 所需证据 | 当前证据 | 人类掌握等级 |
|---|---|---|---|
| AI Coding | 能拆分切片、设计 Red、拒绝错误 Agent 变更并完成 debug/verify | S1–S4 Red → fault → process/restore Receipts；owner review/transfer 待完成 | 未评估 |
| Java domain design | 能重建并解释 state/invariant boundary | Core、ADR 与 domain tests | 未评估 |
| SQL/distributed correctness | 能解决 CAS、duplicate delivery 与 ambiguous external success | S1 uniqueness、S2 four-field CAS、S3 atomic budget/idempotency uniqueness、rollback injection 和 multi-process recovery；[首次 ambiguous-success Teach-back 已通过](notes/2026-07-29-s4-operating-evidence.md)，hands-on transfer/debugging 待完成 | L1 |
| Agent loop/harness | 能实现 Fake/real AgentKernel 并解释 runtime boundary | Framework-free Fake AgentKernel、bounded Tool loop、PostgreSQL AgentRun/Safe Trace/Bundle、Pack 004 repeated deterministic comparison，以及 Pack 007 single/depth=1 read-only Fake Worker；OpenAI adapter 只有 loopback protocol evidence，尚无 live model 或 stochastic Harness | L0 / 未评估 |
| Context/memory/Self | 能拒绝 unsupported inference，并按 provenance 纠正/删除 | 目前只有 architecture | L0 / 未评估 |
| Agent evaluation | 能运行固定 `model × harness` repetitions 并归因 failure | Pack 004 已完成 12 个 shared candidates / 24 次 H0/H1 deterministic VerifierEvaluation 与 independent replay；Pack 005–007 已形成 safety/fault regressions；真实模型、60-run stochastic comparison、人工盲评和成本结论仍未执行 | L0 / 未评估 |
| Capability/security | 能对 identity、approval、Tool、secret 与 injection 做 threat model | S1–S3 configured authority、owner-scoped access、exact Capability mutation matrix、unknown-field rejection 和 loopback tests；人类答辩待完成 | 未评估 |
| Durable workflow | 能在 kill/replay 后恢复 waiting/action state | S3 ActionAttempt 在 durable `UNKNOWN` 后经历强制 app kill 仍存在；new JVM 把一个 simulated object 对账为一张 Receipt；provider success 到 local outcome 之间崩溃仍可能停在 `DISPATCHING`，尚无 lease/fencing 或 Temporal/waiting 证据；[首次 recovery Teach-back 已通过](notes/2026-07-29-s4-operating-evidence.md)，hands-on transfer/debugging 待完成 | L1 |
| Voice/dynamic UI | 能测量 interruption/latency 并执行 safe UI Schema | 目前只有 product design | L0 / 未评估 |
| Production operations | 能从 alert 诊断到 Trace/Receipt，并恢复 backup | S4 owner-scoped readiness、V1/V2/V3→V6 populated upgrades、V6 fresh install、packaged checksum fail-fast、real new-database backup restore、runbook 和 sanitized trace；[readiness/liveness Teach-back 已通过](notes/2026-07-29-s4-operating-evidence.md)，migration/restore replay 待完成 | L1（仅 readiness） |
| Open source collaboration | 陌生贡献者能安全运行、验证并改进 | Docs/CI foundation | 未评估 |
| Product/business | 能识别 ICP，并证明 repeated outcome 与真实付款 | 目前只有 hypothesis | L0 / 未评估 |
| Interview communication | 能在约束变化与故障追问中解释并捍卫决策 | 目前只有 evidence template | 未评估 |

## 更新规则

每次等级变化都必须链接：

- 一次实现或真实任务；
- 一次故障或约束变化练习；
- 一次人类解释/答辩；
- 一份带日期的 Learning Note。

Codex 自评不能提升等级。仅阅读最多到 L1；在协助下成功实现最多到 L2；完成独立迁移实现与
debugging 才能达到 L3。
