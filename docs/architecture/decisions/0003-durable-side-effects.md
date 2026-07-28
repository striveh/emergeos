# ADR-0003: External side effects require approval, idempotency and receipts

- Status: Accepted
- Date: 2026-07-28

## Context

模型、网络、进程和平台 API 都会超时或产生不明确结果。盲目重试可能重复发布、发送或删除。

## Decision

- ActionPlan 绑定主体、目标、Artifact Hash、策略版本、风险和幂等键。
- Approval 与 Capability 绑定同一版本和 Hash。
- Connector 必须支持幂等或实现对账。
- Receipt Ledger 保存实际结果；没有 Receipt 不得宣称完成。
- 粗粒度等待和恢复由 Durable Runtime 承载。

## Consequences

- 当前本地 Draft Stub 只在单进程测试中模拟“写入成功但响应丢失”，验证同一幂等键
  不会生成第二份草稿；这不是对进程重启、真实网络超时或第三方平台对账的验证。
- 在接入首个真实外部系统前，计划用持久化 Receipt Ledger、进程中断和网络故障注入
  验证跨进程恢复语义。
- Connector 开发成本高于直接调用 API，但可以控制重复副作用和错误账号。
- 语义评审不能替代确定性授权。

## Rollback

低风险、纯本地、无副作用操作可以走简化路径；对外行动不能绕过这些不变量。
