# Product-Driven Learning Curriculum

学习不独立于产品推进。每个主题必须依次经历：

```text
知道术语
→ 能画出机制
→ 能用最小代码实现
→ 能在产品中替换一个变量
→ 能诊断故障
→ 能比较取舍并讲给别人
```

## 掌握等级

| 等级 | 判断标准 |
|---|---|
| L0 听过 | 知道名称，不计入已掌握 |
| L1 解释 | 不看资料能说明输入、状态、输出和失败方式 |
| L2 实现 | 能借助资料实现最小版本并写测试 |
| L3 调试 | 能从 Trace、状态和测试定位陌生故障 |
| L4 取舍 | 能比较替代方案，说明为何适合当前约束 |
| L5 迁移/教学 | 能在新场景复用并让他人理解 |

关键主题至少达到 L3 才能写入简历“掌握”；达到 L4 才能在面试中作为主导设计陈述。

## 阶段课程

### Stage 1 · Durable Personal Loop

产品交付：

- PostgreSQL 按纵向切片逐步持久化 Capture、Artifact、ActionAttempt 与 Receipt；
- Capture 幂等、Revision CAS、主体隔离和进程崩溃恢复；
- 一个同设备、loopback-only 的极薄捕获入口和可重复运行的本地用户闭环。

学习主题：

- Hexagonal Architecture、领域不变量与模块化单体；
- SQL Schema、事务、唯一约束、乐观锁和迁移；
- Outside-in TDD、Testcontainers、故障注入；
- 幂等、至少一次执行、未知结果与对账；
- Codex 任务切片、Diff 审查和 Subagent 协作。

本阶段只有两个 L3 主目标：

- 事务边界、唯一约束与乐观并发；
- 幂等外部行动、未知结果与对账。

其他主题先达到 L1/L2，不因 Codex 生成了代码而自动升级掌握等级。

必须亲手完成：

- 画出“平台写入成功但本地 Receipt 未提交”的时间线；
- 先写失败测试，再实现一次跨进程恢复；
- 人工解释为何 Temporal 不能替代数据库唯一约束；
- 从一个未预告根因的失败日志亲自定位并修复问题；
- 一周后无资料回答一个变体题，并记录至少一项被自己否决或修改的 Codex 建议。

职业证据：

- 架构图、ADR、Red/Green PR、并发/崩溃测试和五分钟故障恢复 Demo。

### Stage 2 · Agent Kernel and Evaluation

产品交付：

- Provider-neutral `AgentKernel`；
- Task/Result Envelope、工具循环、预算、取消和结构化输出；
- 固定模型下 H0/H1 Harness 对照实验。

学习主题：

- Agent loop、tool calling、handoff、context policy 与 harness；
- 模型非确定性、结构化输出、重试和错误归因；
- Eval-driven development、golden task、grader 与重复运行；
- Trace、OpenTelemetry、成本和延迟；
- 单 Agent、多 Agent 和确定性 Workflow 的边界。

必须亲手完成：

- 不依赖框架写一个最小 tool loop；
- 固定模型和任务，仅替换 Harness 做重复实验；
- 注入错误工具结果、上下文漂移和超时；
- 解释为什么 Critic 不是实验真值。

职业证据：

- HarnessRunBundle、实验报告、Trace、成本/质量图和一次失败归因复盘。

### Stage 3 · Low-Friction Capture and Generative UI

产品交付：

- Web/macOS 最小入口、流式语音捕获和可编辑结果卡；
- 动态 UI Schema 与受控组件注册表；
- 从开口到可用草稿的时间测量。

学习主题：

- Realtime 音频、VAD、打断、流式协议和端到端延迟；
- 前端状态机、乐观 UI、离线与同步；
- A2UI/AG-UI 类协议思想与 Schema-driven UI；
- 可访问性、隐私指示和人机交接；
- 体验指标与用户研究。

必须亲手完成：

- 分解一次语音交互的延迟预算；
- 处理打断、网络抖动和部分转写；
- 证明模型不能生成任意可执行 UI 代码；
- 观察至少五次真实使用并记录修订路径。

职业证据：

- 可操作 Demo、交互状态图、延迟分析和一次真实用户体验改进。

### Stage 4 · Controlled External Action

产品交付：

- Temporal 粗粒度 Workflow；
- Secret Broker、OAuth、审批与一个“仅存草稿”的真实 Connector；
- UNKNOWN、对账、撤销与审计。

学习主题：

- Durable execution、Activity、重试、补偿和 Human-in-the-loop；
- OAuth、Token 生命周期、最小权限和审计；
- Connector contract、幂等和 provider reconciliation；
- 威胁建模、数据分级和事故响应；
- SLO、告警和演练。

必须亲手完成：

- 杀死 Worker 并恢复等待中的 Workflow；
- 模拟平台成功但响应丢失；
- 轮换/撤销凭据并证明模型从未看到密钥；
- 完成一次 Game Day 和脱敏 Postmortem。

职业证据：

- 时序图、威胁模型、恢复录像、SLO 面板和事故复盘。

### Stage 5 · Product, Multi-tenancy and Monetization

产品交付：

- 多租户、配额、订阅、数据导出/删除和最小生产部署；
- 私有 Alpha 到付费 Beta；
- 开源边界、Connector SDK 与贡献流程。

学习主题：

- 租户隔离、加密、备份、容量和成本；
- CI/CD、灰度、回滚、供应链安全和运维；
- 定价、激活、留存、单位经济与分发；
- 开源治理、兼容性、支持与社区运营；
- 技术叙事、系统设计面试和项目复盘。

必须亲手完成：

- 恢复备份并验证 RPO/RTO；
- 运行租户隔离和权限渗透测试；
- 完成真实付费试点和取消访谈；
- 对外演示时明确已验证能力与限制。

职业与商业证据：

- 生产架构、Runbook、发布历史、用户结果、收入回执和开源贡献记录。

## 每个主题的学习回路

1. **Pre-test**：先写下自己目前的解释和未知。
2. **Source**：读一手文档、论文或源码，记录版本与日期。
3. **Build**：实现最小机制，不先依赖上层框架隐藏原理。
4. **Break**：主动注入至少一个失败。
5. **Explain**：用自己的话画图并回答“为什么不用另一种方案”。
6. **Apply**：进入一个真实纵向切片。
7. **Review**：间隔一周重新解释或修复一个变体。

使用 [Learning Note Template](./learning-note-template.md) 留证。笔记记录可验证理解，
不保存模型隐藏思维链，也不把 Codex 的回答冒充为人的掌握。
