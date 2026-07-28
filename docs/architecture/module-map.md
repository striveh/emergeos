# Module Map

## 当前物理模块

```text
modules/contracts
  跨进程与跨参与者稳定契约

modules/core
  domain       领域实体、值对象、状态机、不变量
  application  用例编排
  port         对外能力端口

adapters/inmemory
  内存 Ledger、确定性草稿生成、Policy、Action Stub

apps/api
  HTTP DTO、Controller、异常映射、依赖装配
```

## 依赖规则

```mermaid
flowchart RL
  API["apps/api"]
  AD["adapters/inmemory"]
  CORE["modules/core"]
  CT["modules/contracts"]

  API --> AD
  API --> CORE
  AD --> CORE
  CORE --> CT
```

- `core` 不依赖 Spring、数据库、Temporal、AgentScope 或模型 SDK。
- `contracts` 不依赖任何实现模块。
- Maven Enforcer 在 `core` 与 `contracts` 构建中阻止框架、数据库和模型 SDK 越界。
- Adapter 只能实现 Core Port，不能让 SDK 类型进入 Core。
- API 负责传输协议，不能包含领域状态转移。
- 跨模块只交换显式类型与引用，不共享可变聊天上下文。

## 领域包的演化目标

代码量增长后，Core 内部按领域拆包，但暂不立即拆 Maven 模块：

| 领域 | 所有权 |
|---|---|
| capture | 低摩擦输入、去重、来源 |
| evidence | 只追加事件、血缘、保留策略 |
| selfmodel | 已确认认识、候选、冲突、Working Self |
| artifact | 版本、内容 Hash、来源与编辑 |
| policy | 风险、审批、Capability 和撤销 |
| action | Connector、幂等、Receipt 和对账 |
| reflection | 用户修改、结果反馈、候选晋升 |
| verification | Readiness、Verifier、故障归因和回归 |

当一个领域拥有独立数据、不变量、维护者和发布节奏时，再将其提升为独立构建模块或服务。

## 后续 Adapter

```text
adapters/postgres
adapters/object-storage
adapters/agent-agentscope
adapters/agent-pi
adapters/temporal
adapters/connectors/*
```

这些是计划，不应在存在真实实现前创建空目录。
