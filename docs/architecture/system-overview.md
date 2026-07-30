# System Overview

## 架构目标

EmergeOS 的架构首先保护五件事：

1. 用户拥有并能修正系统对自己的理解；
2. 模型上下文与持久真相分离；
3. 外部行动最小授权、可恢复且不重复；
4. 每个结论和结果可以追溯到证据；
5. 模型、Runtime 和平台 Connector 可以替换而不改写产品领域。

## 系统上下文

```mermaid
flowchart LR
  U["用户\n手机·桌面·浏览器"]
  C["Capture\n语音·文字·分享"]
  OS["EmergeOS Control Plane"]
  M["Model / Agent Runtime"]
  D["Durable Runtime"]
  X["Connectors\n平台 API·MCP·浏览器"]
  P["External Platforms"]

  U --> C --> OS
  OS <--> M
  OS <--> D
  D --> X --> P
  P --> X --> OS
  OS --> U
```

模型和 Agent Runtime 是被调用的能力，不是系统的所有者。外部平台的真实状态必须通过 Connector Receipt 回流。

## 领域闭环

```mermaid
flowchart TB
  S["Thought Seed"]
  E["Evidence Ledger"]
  W["Working Self Projector"]
  A["Artifact Compiler"]
  G["Policy Gate"]
  H["Human Approval"]
  R["Durable Action Runtime"]
  C["Connector"]
  O["Receipt Ledger"]
  F["Reflection Candidate"]

  S --> E --> W --> A --> G --> H --> R --> C --> O --> F
  O -. "verified outcome" .-> W
```

## 三个控制面

### Product Control Plane

拥有 Self Model、证据、任务、策略、Artifact、Receipt 和演进规则。这是产品核心。

### Agent Control Plane

负责模型选择、工具循环、Subagent、上下文压缩和流式事件。必须被 `AgentKernel` 端口隔离。

### Durable Action Plane

负责等待审批、定时、重试、补偿、幂等和崩溃恢复。模型调用和 Connector 调用作为不可靠 Activity 处理。

## 首阶段部署形态

采用模块化单体：

- 一个 Spring Boot API 进程；
- 纯 Java Core；
- 内存适配器保留 Stage 0 确定性演示；
- Stage 2 S1 已有 framework-free、固定步数/工具预算的 Fake Agent 循环；它只注册
  `capture.read`，结果仍由 Core 校验和提交；
- Stage 2 S2 已把 AgentRun、safe hashed Trace、immutable resource binding、
  Result 与 HarnessRunBundle 持久化；成功 Artifact 与 terminal Run 原子提交；
- frozen synthetic Task Pack 可通过 fresh、无网络的 Offline runner 得到 exact
  Trace/Artifact/Bundle golden hashes；
- PostgreSQL 已持有 Capture、Artifact lineage、local ActionAttempt/Receipt 与
  AgentRun truth；
- S3 仅通过 loopback HTTP 调用独立、文件持久化的 Fake Provider；
- 真实模型、Temporal、AgentScope 和真实 Connector 仍是后续外层适配器，不是当前实现。

此时拆微服务只会增加一致性、部署和调试成本，不能增加用户价值。未来只有出现独立扩缩、故障隔离、团队所有权或合规边界时才拆服务。

## ETCLOVG 映射

| 层 | EmergeOS 所有权 |
|---|---|
| Execution | 沙箱与可替换执行后端 |
| Tool | Connector Registry、Schema、版本、健康和权限 |
| Context | Evidence、Self Model、Working Self 与漂移检测 |
| Lifecycle | 领域状态机、AgentKernel 与 Durable Runtime |
| Observability | Trace、成本、版本、事件和 HarnessRunBundle |
| Verification | Readiness、结果/轨迹/评测器检查和回归 |
| Governance | 身份、委托、Capability、审批、审计和删除 |
