# Harness Validation Plan

## 研究问题

在固定模型、任务、工具、Evidence、Self Model 和预算时，版本化 Harness 是否提高了“可验证成果率”，并减少人工修改、未授权行动、重复副作用和不可解释恢复？

## 实验臂

### H0

同一模型、单 Agent、相同工具、基础 Schema 检查和外部行动人工确认；没有独立 Verifier、typed handoff、Context Drift Check 或完整 Trace。

### H1

同一模型，增加：

- Task/Result Envelope；
- Working Self 来源与版本；
- 角色和上下文隔离；
- Capability、Temporal、幂等与 Receipt；
- 独立 Verification Plane；
- HarnessRunBundle。

Critic 即使使用隔离上下文，也不是实验真值。最终评分来自确定性检查、证据审查和盲化用户评审。

## 当前实现状态

Stage 2 S2 已完成 deterministic Fake Agent 的 synthetic success baseline：同一
Task Pack 可以在 Offline runner 中得到一致的 Artifact、safe Trace 与
HarnessRunBundle hashes。S4 另完成一个更小、隔离的 Pack 004 单变量对照：
4 cases × 3 repetitions 形成 12 个 shared candidates / 24 次 H0/H1
VerifierEvaluation，independent replay 得到 `VERIFIED_PASSED`；canonical report
也已通过 packaged process-kill 与 fresh-JVM read-only verification。

Pack 005 与 Pack 006 分别把 schema-invalid Tool arguments 的 pre-dispatch rejection、
read-only Tool 的 post-dispatch deadline truth 固定为 deterministic safety regression。
Pack 007 又加入一个 single/synchronous/`depth=1`、one Worker、read-only Fake
vertical：control 形成 terminal parent/child、durable WorkerResult、一个 parent
Artifact 与一个 exact `HANDOFF`；只改变 registered Worker
`contextPolicyVersion` 的 fault 会在 child Run/Model/Tool/delegated read 前
fail-closed。它还覆盖 V6 graph constraints 与 packaged crash-gap read-back，但没有
比较 H0/H1 的 stochastic model quality。

这些小实验只证明 frozen synthetic reference-grounding discrimination、replay
equivalence 和被测 safety invariant，不是下面规划的完整 Harness 结论。10 个真实任务
× 2 arms × 3 repetitions 的 60 次运行、真实模型、人工盲评、完整 fault injection、
成本/延迟和用户修改时间仍未执行，因此本文继续是一份可证伪的评测计划。

## 最小任务集

10 个真实但可安全回放的 Thought-to-Outcome Task Pack：

- 3 个事实/研究密集型；
- 3 个碎片整合和表达风格型；
- 2 个含过期或冲突记忆；
- 2 个审批与外部草稿型。

每个任务、每个实验臂重复三次：`10 × 2 × 3 = 60` 次正常运行。

## 故障注入

1. 模型限流或超时；
2. 工具返回错误结构；
3. 压缩丢约束或注入过期记忆；
4. 等待批准时进程退出；
5. 平台已写入但响应超时；
6. 来源中包含 Prompt Injection。

## 指标

- Verified Outcome Rate；
- 用户盲评、像我程度、可发布程度、修改时间；
- Claim/Evidence 覆盖和 Context Drift；
- 未授权动作、重复草稿和 Receipt 完整率；
- 故障后的可解释终态；
- Trace 到失败归因和回归用例的可复现性；
- p50/p95、Token、成本和用户打断次数。

## 项目决策门槛

这些是工程门槛，不是论文结论：

- 0 未授权动作、0 重复副作用、100% 会话隔离；
- H1 故障运行全部进入可解释终态；
- H1 验证成果率提高至少 15 个百分点；或成果率非劣且人工修改时间降低至少 30%；
- 有用初稿中位数小于 5 分钟；
- 验证成果成本目标不超过 H0 的 1.5 倍，硬上限 2 倍。

样本只支持下一阶段架构决策，不能证明普遍安全或市场需求。

## HarnessRunBundle 最小字段

当前规范以 `contracts/schemas/v1/harness-run-bundle.schema.json` 为准，Java
映射位于 `modules/contracts`。v1 顶层字段是：

| 维度 | v1 字段 |
| --- | --- |
| 协议与运行身份 | `schemaVersion`, `runId`, `taskId`, `experiment`（内含 `arm`, `repetition`） |
| 模型与 Harness | `modelResolved`, `harnessVersion`, `componentVersions` |
| 环境与工具 | `environmentSnapshotRef`, `toolRegistryVersion` |
| 输入、结果与 Working Self | `task`, `result`, `workingSelfRef` |
| 执行证据 | `traceRef`, `traceRootHash`, `handoffRefs`, `checkpointRefs`, `resourceBindings` |
| 验证与结果 | `verificationRef`, `failureAttribution`, `outcome` |
| 用量与完整性 | `costUsd`, `tokenCount`, `latencyMs`, `integrityProfile`, `integrityHash` |

当前 `componentVersions` 明确记录 `agent`、`verifier` 和 `trace-integrity`；
`modelResolved`、`harnessVersion` 与 `toolRegistryVersion` 分别是独立字段。prompt、
skill 和独立 `agentSpec` 版本尚未显式记录，不能从现有 Bundle 反推。
principal、delegation、policy、state、context、fault plan、idempotency 和 recovery
由内嵌 `task` 承载；Evidence、Artifact、Receipt、handoff、checkpoint 与 verification
通过 Result 和 typed `resourceBindings` 绑定。若实验需要新的独立字段，必须先做契约
变更，不能把设计意图当成已实现字段。
