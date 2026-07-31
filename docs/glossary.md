# Glossary

| 术语 | 含义 |
|---|---|
| Thought Seed | 用户刚看到、想到或想要的原始输入 |
| EvidenceEvent | 只追加的原始经历或来源事件，带时间、来源、权限和 Hash |
| Self Model | 对用户事实、偏好、价值、状态和技能的可纠正、版本化模型 |
| Working Self | 针对当前任务，从已确认 Self Model 与 Evidence 投影出的不可变最小视图 |
| Artifact | 文章、计划、图片、代码等可检查交付物；修改产生新版本 |
| ActionPlan | 对外行动的确定性描述，绑定 Artifact Hash、风险、目标和幂等键 |
| Capability | 对特定主体、动作、对象、次数和时间窗的临时授权 |
| Receipt | 证明动作实际发生或失败的外部凭证 |
| ReflectionCandidate | 从用户修改和真实结果中提出的记忆、偏好或技能候选；默认不生效 |
| Harness | 把模型调用变成受约束、可恢复、可观察、可验证执行的系统 |
| AgentKernel | 可替换的 Model/Tool/Worker decision loop 接口，不拥有产品真相 |
| Conductor | 当前 parent Agent 角色；Conductor Model 决定是否提出允许的 WorkerCall，parent application service 验证结果并负责持久提交；Model 没有直接数据库能力 |
| Worker | 短命、最小权限的 child Agent；Pack007 仅实现一个 serial、depth=1、read-only Worker |
| WorkerCall | Model 提出的 typed 委派请求；只能由 server-owned registry/profile 验证并执行 |
| AgentWorkerRuntime | Core 中 provider-neutral 的 Worker preparation/execution port；当前是 synchronous seam，不是通用 workflow runtime |
| WorkerResultEnvelope | 在 owner-scoped store 中保存、可完整性核验的 child durable output；Envelope 本身不携带 principal，也不是用户 Artifact |
| HANDOFF binding | parent Bundle 对 exact child Run 与 child Bundle hash 的 typed 绑定 |
| WORKER_RESULT binding | child Bundle 对 exact WorkerResultEnvelope integrity hash 的 typed 绑定 |
| Delegation chain | Task 层的显式 parent Task lineage；Pack007 固定为 root → one child |
| Context-policy drift | registered Worker profile 与 parent contextPolicyVersion 不一致；必须在 child dispatch 前 fail closed |
| AgentRun | 一次 server-owned Agent 执行的持久产品真相，绑定 Task、terminal Result、Safe Trace 与实际资源 |
| Safe Trace | 只含 allowlisted 结构事件、状态、用量和 resource ref 的 hash-chain 执行证据；不是 transcript 或 chain-of-thought |
| Durable Runtime | 跨崩溃、等待和重试继续任务的粗粒度执行系统 |
| HarnessRunBundle | 一次运行的任务、版本、状态、权限、Trace 与零个或多个 typed resource binding 的完整性证据包 |
