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
| 协议与运行身份 | `schemaVersion`, `runId`, `taskId`, `experimentArm`, `repetition` |
| 模型与 Harness | `modelResolved`, `harnessVersion`, `componentVersions` |
| 环境与工具 | `environmentSnapshotRef`, `toolRegistryVersion` |
| 输入与 Working Self | `task`, `workingSelfRef` |
| 执行证据 | `traceRefs`, `handoffRefs`, `checkpointRefs`, `artifactRefs`, `receiptRefs` |
| 验证与结果 | `verificationRef`, `failureAttribution`, `outcome` |
| 资源与完整性 | `costUsd`, `tokenCount`, `latencyMs`, `integrityHash` |

早期设计中的 `agentSpec`、prompt、skill 和 verifier 版本目前记录在
`componentVersions`；principal、delegation、policy、state、context、Evidence、
fault plan、idempotency 和 recovery 只能通过 `task`、环境快照或各类引用所指向的
版本化记录承载。它们不是 v1 的独立顶层字段；若实验需要直接查询，必须先通过
契约变更加入，不能把设计意图当成已实现字段。
