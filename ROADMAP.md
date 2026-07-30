# Roadmap

路线图表达学习与验证顺序，不承诺发布日期。按阶段 Gate 推进，不因代码合并就宣布完成。
每个阶段必须留下 AI Coding、Agent Engineering、Product/Production、Career 和 Business
Receipt。

## Stage 0 · Foundation

状态：**Engineering baseline complete; human mastery and market value not yet proven.**

| Outcome | Evidence |
|---|---|
| AI Coding | 已有仓库规则和协作过程；个人掌握尚未考核 |
| Agent Engineering | 模块化单体、Ports/Adapters、Evidence vs Projection 的待复习材料 |
| Product/Production | 本地 Thought → Artifact → Approval → Receipt → ReflectionCandidate 闭环；领域不变量、主体查询、Hash 审批、幂等 Stub、Schema Fixture |
| Career | Architecture、ADR、Build Note、可运行 API；尚无录制讲解 |
| Business | 尚无外部用户或付费证据 |

Stage 0 只有在项目所有者能白板解释状态机、真相边界和失败窗口后，才计入个人能力证据。

## Stage 1 · Durable Correctness and Problem Discovery

状态：**Now — S1–S4 工程切片回执已完成；真实 Connector Gate、人类 Teach-back 与市场 Gate
仍未完成。**

执行计划：[Stage 1 Durable Correctness](./docs/plans/2026-07-28-stage-1-durable-correctness.md)

S1 回执：[Restart-safe Capture Build Note](./docs/operations/build-notes/2026-07-28-s1-restart-safe-capture.md)

S2 回执：[Conflict-safe Revision Build Note](./docs/operations/build-notes/2026-07-28-s2-conflict-safe-revision.md)

S3 回执：[Recoverable local Action Build Note](./docs/operations/build-notes/2026-07-28-s3-recoverable-local-action.md)

S4 回执：[Operating and Gate-Closure Build Note](./docs/operations/build-notes/2026-07-29-s4-operating-gate-closure.md)

产品/工程：

- 按 Capture、Revision、Action、Operations 四个纵向切片逐步引入 PostgreSQL；
- 一个同设备、loopback-only 的本地网页或可信系统快捷入口负责文字、链接或语音文件引用捕获；
- Capture `clientNonce + requestHash` 去重，Revision expected version/hash CAS；
- 写入前 ActionAttempt、唯一幂等约束、`UNKNOWN → RECONCILING`；
- Testcontainers 下验证双实例竞争、进程死亡、升级、失败停止与恢复；
- 暂缓通用 Outbox/Inbox、完整 Working Self 关系模型和通用 Metrics 平台。

学习：

- 两个 L3 主目标：事务/唯一约束/乐观锁，以及幂等外部行动/未知结果/对账；
- Outbox/Inbox 在本阶段只理解机制，不因“生产级”提前实现；
- Outside-in TDD、集成测试、故障注入和 Codex Diff Review；
- 无资料讲解、亲自定位一个未知故障，并能解释 Temporal 为什么不能替代数据库约束。

产品/商业并行：

- Day 1 启动 14 天 Founder dogfooding 和每周 2–3 次最近行为访谈；
- 至少 3 位目标用户提交真实 Seed，观察复用或明确不复用原因；
- 通过人 + Codex Concierge 交付母稿，并至少提出一次真实价格；
- 当前产品只验证捕获/修订/恢复；“有来源、像本人、值得付费”由 Concierge 独立验证；
- 明确 `continue / narrow / pivot` 首个用户群与输出类型。

Gate：

- 四个切片各有可重放的 Red、Fault、Review 和 Receipt；
- Capture 重启不丢失、Revision 双实例只有一个赢家；
- 独立进程且持久化状态的 Fake Provider 只有一个可观察模拟对象，真实终止/重启应用后可对账
  为一张 Receipt；
- fresh install、上一版升级、失败停止及备份恢复/forward-fix 有证据；
- provider success 后、local outcome/Receipt commit 前进程死亡不会造成无权接管或双活提交；
- 完成 14 天记录、10 次访谈、3 位真实 Seed、一次价格请求和明确商业决策；
- 完成故障恢复 Demo、Case Card、无资料 Teach-back 和延迟变体题。

## Stage 2 · AgentKernel and Eval-Driven Development

执行计划：
[Stage 2 AgentKernel and Eval-Driven Development](./docs/plans/2026-07-29-stage-2-agent-kernel-evaluation.md)。

状态：**S1、S2 工程完成；S3 real model protocol adapter、isolated synthetic
Eval Runner 与本地 attempt durability 工程切片已通过。Live-provider smoke 尚未执行；
S4 已冻结首个 Verifier comparison foundation 并完成 strict Pack loader，但
12 个 shared candidates / 24 次 verifier evaluation 尚未执行。**
这条技术主线来自项目所有者 2026-07-30 的 Roadmap
顺序例外；它不代表 Stage 1 的学习、市场或真实 Connector Gate 已完成。

S1 回执：
[Fake Agent Draft Loop Build Note](./docs/operations/build-notes/2026-07-30-s2-s1-fake-agent-draft-loop.md)。

S2 回执：
[持久 AgentRun、Safe Trace 与 HarnessRunBundle Build Note](./docs/operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)。

S3 adapter 回执：
[OpenAI Responses Adapter Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-openai-responses-adapter.md)。

S3 bounded runner 回执：
[Bounded Synthetic Eval Runner Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)。

S3 durability 回执：
[Durable Eval Attempt Evidence Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)。

S4 loader 回执：
[Offline Comparison Loader Build Note](./docs/operations/build-notes/2026-07-30-s2-s4-offline-comparison-loader.md)。

产品/工程：

- Provider-neutral `AgentKernel` SPI，先 Fake 后 real adapter；
- Task/Result Envelope、工具循环、预算、取消、结构化输出和 Trace；
- PostgreSQL durable AgentRun、Safe Trace hash chain、typed resource binding、
  verified read 与 deterministic Offline golden runner；
- 普通 API 之外的 isolated synthetic Eval Runner：默认 zero-egress preflight，
  real TTY + exact Task-bound one-shot permit，POSIX attempt marker/journal 与本地
  atomic terminal run record；
- production read-only journal verifier 与 7-point fat-JAR process-kill/restart matrix；
- 固定模型与 Task Pack 的 H0/H1 Harness 对照；
- 错误工具结果、限流、Context Drift 和 Prompt Injection 故障集。

当前已经完成 synthetic Fake success 的 deterministic baseline、OpenAI Responses
protocol 的 loopback evidence、bounded runner engineering Gate，以及 7 个选定 durable
boundary 的 fat-JAR 强制终止/新 JVM 只读核验；仍没有读取 real key、执行
live-provider smoke、取得 real model result 或 billing receipt。Task Pack 004 已冻结
reference-grounding Verifier comparison 的输入、arms、cases 与预期矩阵；独立
offline module 已完成 fixed-path、hash-bound、semantic-bound strict loader，但
12 个 shared candidates / 24 次 verifier evaluation 尚未执行；H0/H1 正式重复实验、
真实任务、人工盲评和 stochastic quality Eval 也尚未执行。
`billingStatus=UNKNOWN` 表示 provider 费用未知，不能解释成免费；reservation 是调用前的
authorization ceiling，provider 已返回的 observed usage 即使超过 reservation 也必须如实保留。

学习：

- Model、Agent、Runtime、Harness、Workflow 和 Verifier 的边界；
- Tool calling、handoff、context policy 与非确定性评测；
- 重复实验、失败归因、成本/延迟与模型灰度。

产品/商业：

- Concierge 方式为少量 Design Partner 交付一个母稿成果；
- 固定模型比较有/无 Working Self 是否减少修改时间；
- 在自动化前获得重复使用和真实付费/拒付证据。

Gate：

- 他人可用一条流程重放 Harness 实验；
- 结论来自重复运行和独立证据，不挑最好一次；
- AgentKernel SDK 类型不进入 Core；
- 商业楔子比“通用模型 + 旧工具”表现出可测差异。

## Stage 3 · Rich Capture and Controlled UI

产品/工程：

- 在 Stage 1 极薄入口上增加完整 Web/macOS 体验、语音流、打断和部分结果；
- Schema-driven 动态结果卡与安全组件注册表；
- Evidence 来源、执行进度、审批和回执可视化；
- 离线/断线状态与端到端延迟测量。

学习：

- Realtime audio、VAD、streaming、event state 与 latency budget；
- 前端状态机、Human-in-the-loop、可访问性与 Generative UI 边界；
- Self Model 来源、冲突、纠正、遗忘和导出。

产品/商业：

- 观察真实用户无需培训完成任务；
- 测 Seed → 可用成果时间、“不像我”反馈和修改路径；
- 只保留激活、留存和付费最强的目标用户群。

Gate：

- 用户知道系统看到了什么、准备做什么和完成了什么；
- 模型不能生成任意可执行 UI；
- 关键人格推断可解释、纠正和撤销；
- 核心任务形成周重复使用。

## Stage 4 · Durable Workflow and One Reversible Connector

产品/工程：

- Temporal 粗粒度 Workflow：等待审批、重试、取消、恢复和对账；
- Secret Broker、OAuth、Capability Use Ledger 和审计；
- 只接一个“进入草稿箱”的真实 Connector；
- SLO、告警、Runbook、Game Day 与脱敏 Postmortem。

学习：

- Workflow/Activity、deterministic replay、signal、timeout 和 compensation；
- OAuth、最小权限、凭据生命周期、Connector contract 和威胁建模；
- 真实平台 `UNKNOWN` 结果的人工/自动对账。

产品/商业：

- 邀请制付费 Alpha；
- 测第四周成果留存、支持成本、外部动作成功与单位成本；
- 没有留存与付款前不扩第二个平台。

Gate：

- Worker kill/restart 后继续等待或对账；
- 0 未授权动作、0 重复外部对象；
- 模型从未获得长期密钥；
- 至少一个真实付费 cohort 与明确的支持成本。

## Stage 5 · Production Service and Open Ecosystem

产品/工程：

- OIDC、多租户隔离、加密、配额、订阅、导出/删除和保留策略；
- CI/CD、灰度、回滚、备份恢复、容量、成本熔断与供应链安全；
- Open Source 许可证、商标、Connector SDK、兼容性和安全响应；
- iOS/Android 与更多 Connector 只按留存需求扩展。

学习/职业：

- 完成 Production Readiness Review、恢复演练和模拟事故；
- 陌生贡献者可以按文档运行、验证并提交有效变化；
- 形成生产架构、Benchmark、Incident、用户结果和商业取舍的完整面试证据。

商业：

- 从 Design Partner → Paid Alpha → Production Beta；
- 只在留存、毛利和可重复获客成立后规模化；
- 用户始终拥有原始数据、Self Model、导出与删除权。

## 接入真实 Connector 的硬门槛

在 PostgreSQL 故障测试证明以下能力前，不接入微博、X、小红书、公众号等真实写入：

- `(principal, manifestation)` 查询隔离与数据库级乐观锁；
- Capture 的 `clientNonce + requestHash` 去重；
- Revision 的 expected version/hash compare-and-swap；
- 写入前持久化 ActionAttempt；
- 跨进程、双实例下同一幂等键只产生一个外部对象；
- `UNKNOWN → RECONCILING → Receipt` 可解释恢复；
- Capability 精确绑定 ActionPlan、连接器 audience、账号与幂等键。
- provider success 后、local outcome/Receipt commit 前进程死亡有 PostgreSQL-canonical
  owner/lease/fencing 与误接管证据；当前没有这条证据，ADR-0004 为 `Proposed`，Gate 保持关闭。

## 防止架构黑洞

- 一次只替换一个主要变量，保留 Fake/旧实现作对照；
- Day 1 启动用户接触；连续 7 天无用户接触或两个基础设施切片无市场证据时冻结新基础设施；
- 多 Agent、微服务、Kubernetes、知识图谱和任意动态 UI 必须由实验或生产约束触发；
- 每周 Pulse、每月用 [Five-Outcome Scorecard](./docs/operations/five-outcome-scorecard.md) 调整最弱轨道。
