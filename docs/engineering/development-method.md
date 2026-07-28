# Development Method

EmergeOS 使用结果驱动的 TDD，而不是把“先写测试”当作宗教。每项工作先确定可观察行为和
主要失败边界，再选择 Unit Test、Contract Test、Integration Test、Agent Eval 或用户实验。

## Change class

| Class | Meaning | Required process |
|---|---|---|
| S · Small | 文档、机械重构、无行为变化 | 受影响检查、简短 Receipt |
| V · Vertical | 新用户能力或可观察行为 | Task Brief；达到门槛则由 ExecPlan 替代；验收、测试、独立 Review |
| R · Risky | 契约、身份、Self、迁移、外部行动、Runtime | Task Brief 或 ExecPlan、RFC/ADR 判断、故障矩阵、恢复、两类审查 |
| X · Spike | 一个未知的限时研究 | 简短 Spike 记录：假设、2–4 小时时间盒、复现、结论与限制 |

Spike 代码不直接进入生产路径。V/R 任务原则上按 0.5–2 天的纵向结果切分；超过时建立
[ExecPlan](../../PLANS.md)，不要按“先建完所有表、再建完所有 Repository”横向铺开。

## 标准循环

```text
Problem evidence
→ Task Brief 或 ExecPlan slice delta
→ Outside-in failing acceptance
→ Focused failing test
→ Minimal implementation
→ Refactor
→ Integration/fault evidence
→ Independent review
→ Human teach-back
→ Build Note / Release
```

### 1. Frame

短 `V/R` 使用 [Task Brief](./task-brief-template.md) 写明目标、非目标、风险、学习主题和完成
条件；达到 [ExecPlan](../../PLANS.md) 门槛时直接更新计划中的 slice delta，不再复制 Task Brief。
一次切片只解决一个用户结果和一个主要未知；替换外层 Adapter 时一次只替换一个变量。

### 2. Red

行为变化在实现前先获得失败证据：

- 新领域规则：一个最小失败 Unit/State-machine Test；
- Bug：能够稳定复现原错误的 Regression Test；
- API/Schema：消费者可观察的 Contract Test；
- 数据库与 Runtime：真实依赖或 Testcontainer 下的 Integration/Fault Test；
- Agent 行为：固定任务、模型、预算、重复次数和评分器的 Baseline；
- 用户价值：预先定义任务、样本和成功/停止条件。

失败必须因为缺少目标行为，而不是测试写错、环境坏了或断言无意义。PR 记录 Red 命令和
预期失败摘要，不提交大段日志。

### 3. Green

只实现让当前行为通过的最小设计，不顺便搭建未来框架，不放宽测试，不把 Fake 的能力描述成
生产能力。

### 4. Refactor

测试通过后再改善命名、结构、重复和边界。重构不能改变对外行为；旧系统缺少测试时先写
Characterization Test。

### 5. Broaden evidence

针对风险补充一层更真实的验证：

- 并发、进程死亡、超时、重试和乱序；
- 主体隔离、过期授权、Prompt Injection 和秘密泄露；
- 模型非确定性、成本、延迟和 Context Drift；
- 可访问性、视觉状态和真实用户完成任务。

### 6. Review and explain

生成代码的 Agent 不能独自证明自己正确。至少执行：

- 主 Agent 审阅完整 Diff；
- 独立 Reviewer 检查 correctness/security/recovery/test gaps；
- 人能够解释关键状态机、事务边界、失败窗口和替代方案；
- 全仓验证和端到端验收。

## 分层验证策略

| 层 | 方法 | 适用对象 | 不应做什么 |
|---|---|---|---|
| Domain | Example、Property、State-machine TDD | 不变量、Hash、审批、状态转移 | Mock 自己的领域对象 |
| Contract | Schema/consumer tests | Agent、API、Connector、跨语言协议 | 只检查 JSON 能解析 |
| Integration | Testcontainers、真实序列化与迁移 | PostgreSQL、Temporal、Object Storage | 用内存实现证明生产事务 |
| Fault | Kill、timeout、duplicate、race、poison | Durable action、队列、Connector | 只测 happy path |
| Agent Eval | Golden task、重复运行、独立 grader | Prompt、model、skill、harness | 对自然语言逐字断言或挑最好一次 |
| Product | E2E、可用性、cohort、付费 | Thought-to-Outcome 与商业假设 | 用生成量、聊天时长代替价值 |

少量真实模型和真实平台 Smoke Test 可以在受控环境运行，但不能替代确定性测试，也不能因成本、
凭据或偶发失败让普通 CI 不稳定。

## TDD 例外

以下工作可先探索后测试：

- 一次性研究 Spike；
- 视觉方向探索；
- 对未知第三方 API 的最小探针；
- 用于验证可行性的抛弃式原型。

例外必须写明时间盒和“不得直接合入生产路径”。一旦方案被采用，先补验收与回归测试，再迁移
实现；不能把 Spike 改名为 MVP 后上线。

## Definition of Ready

- 有问题证据，而不是只有技术偏好；
- 用户结果、非目标和主要风险清楚；
- 已知相关架构、不变量和版本；
- 知道第一条 Red 证据来自哪里；
- 已写学习目标与商业影响；
- 任务小到一个连贯 Codex 会话可以验证，或已经建立 ExecPlan。

## Definition of Done

- 验收行为与失败边界通过；
- 新增行为有合适层级的测试或 Eval；
- Subagent 结论经过主 Agent 复核；
- 权限、数据、幂等、恢复、成本与回滚已考虑；
- 文档、契约和运行方式同步；
- V/R Slice 留下相关证据并允许 `N/A`；每个 Roadmap Stage/Release Gate 留下 AI Coding、
  Agent Engineering、Product/Production、Career 和 Business Receipt；
- 没有把未验证能力写成已完成。
