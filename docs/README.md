# EmergeOS 文档地图

文档不是宣传材料，而是参与者理解、验证和改变系统的共同入口。

## 真相层级

出现冲突时，以更靠前者为准：

```text
代码与自动化测试
→ 已接受的规格与不变量
→ 已接受 ADR
→ Roadmap
→ 产品叙事与探索记录
```

RFC 记录提议与讨论，并可进入 Accepted、Rejected 或 Withdrawn；ADR 记录已经采用的
决定。ADR 接受后不改写历史，只能由新 ADR 替代。

## 阅读路线

### 想理解产品

- [五结果项目宪章](./strategy/five-outcome-charter.md)
- [产品蓝图](../PRODUCT-BLUEPRINT.md)
- [第一条纵向闭环](./product/first-vertical-slice.md)
- [路线图](../ROADMAP.md)

### 想理解架构

- [系统总览](./architecture/system-overview.md)
- [模块地图](./architecture/module-map.md)
- [状态、凭证与持久执行](./architecture/runtime-and-state.md)
- [术语表](./glossary.md)

### 想参与研发

- [贡献指南](../CONTRIBUTING.md)
- [研发方法与分层测试](./engineering/development-method.md)
- [Codex 协作手册](./engineering/codex-playbook.md)
- [Task Brief 模板](./engineering/task-brief-template.md)
- [长任务 ExecPlan 规范](../PLANS.md)
- [Stage 1 Durable Correctness ExecPlan](./plans/2026-07-28-stage-1-durable-correctness.md)
- [Stage 2 AgentKernel ExecPlan](./plans/2026-07-29-stage-2-agent-kernel-evaluation.md)
- [模块化单体 ADR](./architecture/decisions/0001-modular-monolith.md)
- [真相与上下文 ADR](./architecture/decisions/0002-truth-and-projections.md)
- [外部副作用 ADR](./architecture/decisions/0003-durable-side-effects.md)
- [ActionAttempt 与对账决策](./architecture/decisions/0004-action-attempt-reconciliation.md)
- [五结果研发制度 ADR](./architecture/decisions/0005-five-outcome-development-system.md)
- [持久 AgentRun 真相 ADR](./architecture/decisions/0006-persistent-agent-run-truth.md)
- [RFC-0001：持久 AgentRun、Safe Trace 与 HarnessRunBundle](./rfcs/0001-persistent-agent-run-trace-and-bundle.md)
- [RFC-0002：真实模型 synthetic egress 与计量边界](./rfcs/0002-real-model-synthetic-egress-and-metering.md)
- [Eval Task Packs、Offline baseline 与 bounded provider runner](../evals/README.md)

### 想参与研究与共同进化

- [产品驱动学习路线](./learning/curriculum.md)
- [能力矩阵](./learning/capability-matrix.md)
- [Learning Note 模板](./learning/learning-note-template.md)
- [S1 Capture 学习草稿](./learning/notes/2026-07-28-s1-restart-safe-capture.md)
- [S2 Conflict-safe Revision 学习草稿](./learning/notes/2026-07-28-s2-conflict-safe-revision.md)
- [S3 Recoverable local Action 学习草稿](./learning/notes/2026-07-28-s3-recoverable-local-action.md)
- [S4 Operating evidence 学习草稿](./learning/notes/2026-07-29-s4-operating-evidence.md)
- [Harness 验证计划](./research/harness-validation.md)
- [公开运营模型](./operations/operating-model.md)
- [五结果周度/月度计分卡](./operations/five-outcome-scorecard.md)
- [2026-07 Foundation 基线](./operations/scorecards/2026-07-foundation-baseline.md)
- [Build Note 模板](./operations/build-note-template.md)
- [首个 Foundation Build Note](./operations/build-notes/2026-07-28-foundation.md)
- [五结果研发制度 Build Note](./operations/build-notes/2026-07-28-five-outcome-process.md)
- [S1 Restart-safe Capture Build Note](./operations/build-notes/2026-07-28-s1-restart-safe-capture.md)
- [S2 Conflict-safe Revision Build Note](./operations/build-notes/2026-07-28-s2-conflict-safe-revision.md)
- [S3 Recoverable local Action Build Note](./operations/build-notes/2026-07-28-s3-recoverable-local-action.md)
- [S4 Operating and Gate-Closure Build Note](./operations/build-notes/2026-07-29-s4-operating-gate-closure.md)
- [Stage 2 S1 Fake Agent Draft Build Note](./operations/build-notes/2026-07-30-s2-s1-fake-agent-draft-loop.md)
- [Stage 2 S2 持久 AgentRun Build Note](./operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)
- [Stage 2 S3 OpenAI Responses Adapter Build Note](./operations/build-notes/2026-07-30-s2-s3-openai-responses-adapter.md)
- [Stage 2 S3 Bounded Synthetic Eval Runner Build Note](./operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)
- [Stage 2 S3 Durable Eval Attempt Evidence Build Note](./operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)
- [Stage 2 S4 Offline Comparison Loader Build Note](./operations/build-notes/2026-07-30-s2-s4-offline-comparison-loader.md)
- [Stage 1 Operating Runbook](./operations/stage1-operating-runbook.md)
- [S4 Durable Operations Case Card](./interview/case-cards/2026-07-29-stage1-durable-operations.md)
- [商业验证路线](./business/validation-roadmap.md)
- [14 天 Founder Seed Log 模板](./business/founder-seed-log-template.md)
- [用户研究数据协议](./business/research-data-protocol.md)
- [用户问题访谈模板](./business/problem-interview-template.md)
- [面试证据包](./interview/evidence-pack.md)
- [开放源码准备清单](./community/open-source-readiness.md)

## 仍需逐步拆分的历史文档

[PRODUCT-BLUEPRINT.md](../PRODUCT-BLUEPRINT.md) 与 [AGENT-FABRIC.md](../AGENT-FABRIC.md) 暂时作为完整设计背景保留。稳定的契约和决策会逐步迁移到规格、ADR 和可执行测试，不在两份长文档中无限追加。
