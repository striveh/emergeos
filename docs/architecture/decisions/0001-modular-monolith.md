# ADR-0001: Start as a modular monolith

- Status: Accepted
- Date: 2026-07-28

## Context

产品需要同时探索 Self Model、Agent Harness、持久行动和交互。当前只有一位主要开发者，领域边界仍在验证。

## Decision

使用单一 Monorepo 和模块化单体。Core 使用纯 Java；基础设施通过 Port/Adapter 接入。首阶段只有一个可运行 API 进程。

## Consequences

- 能以一次本地运行验证完整闭环。
- 事务、调试、重构和贡献者上手成本更低。
- 模块依赖必须通过构建和测试约束，不能因为同进程而任意调用。
- 只有出现独立扩缩、故障隔离、合规或团队所有权边界时才拆服务。

## Rejected alternatives

- 立即微服务化：增加分布式一致性和运维成本，领域尚不稳定。
- 单一无边界应用模块：初期快，但会让 Runtime SDK 渗透 Self Model 和行动领域。

## Rollback

若模块边界被证明错误，可在 Monorepo 内移动类型和端口，不需要跨服务迁移。

