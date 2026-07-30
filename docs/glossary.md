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
| AgentKernel | 可替换的模型—工具循环接口，不拥有产品真相 |
| AgentRun | 一次 server-owned Agent 执行的持久产品真相，绑定 Task、terminal Result、Safe Trace 与实际资源 |
| Safe Trace | 只含 allowlisted 结构事件、状态、用量和 resource ref 的 hash-chain 执行证据；不是 transcript 或 chain-of-thought |
| Durable Runtime | 跨崩溃、等待和重试继续任务的粗粒度执行系统 |
| HarnessRunBundle | 一次运行的任务、版本、状态、权限、Trace、Artifact、Receipt 与验证证据包 |
