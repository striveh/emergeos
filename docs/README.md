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

RFC 表示“正在提议”；ADR 表示“已经决定”。ADR 接受后不改写历史，只能由新 ADR 替代。

## 阅读路线

### 想理解产品

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
- [模块化单体 ADR](./architecture/decisions/0001-modular-monolith.md)
- [真相与上下文 ADR](./architecture/decisions/0002-truth-and-projections.md)
- [外部副作用 ADR](./architecture/decisions/0003-durable-side-effects.md)
- [ActionAttempt 与对账提案](./architecture/decisions/0004-action-attempt-reconciliation.md)

### 想参与研究与共同进化

- [Harness 验证计划](./research/harness-validation.md)
- [公开运营模型](./operations/operating-model.md)
- [Build Note 模板](./operations/build-note-template.md)
- [首个 Foundation Build Note](./operations/build-notes/2026-07-28-foundation.md)
- [开放源码准备清单](./community/open-source-readiness.md)

## 仍需逐步拆分的历史文档

[PRODUCT-BLUEPRINT.md](../PRODUCT-BLUEPRINT.md) 与 [AGENT-FABRIC.md](../AGENT-FABRIC.md) 暂时作为完整设计背景保留。稳定的契约和决策会逐步迁移到规格、ADR 和可执行测试，不在两份长文档中无限追加。
