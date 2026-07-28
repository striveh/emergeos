# ADR-0002: Persistent truth is separate from model context

- Status: Accepted
- Date: 2026-07-28

## Context

长期 Agent 会压缩、检索和重组上下文。模型看到的是不完整投影，不能把聊天历史、向量检索结果或摘要当作人格和任务真相。

## Decision

- Evidence、已确认 Self Model、Artifact、Approval、Receipt 和版本化策略构成持久真相。
- Working Self 是针对任务的不可变、最小、可重建投影。
- 向量索引只负责发现，不拥有事实。
- Worker 只能提交 ReflectionCandidate，不能直接修改 Self Model。

## Consequences

- 每次输出能解释“用了哪些证据和哪一版认识”。
- 需要维护来源、Hash、冲突、有效期和版本。
- 上下文漂移可以通过真相对账检测，而不是相信模型自述。

## Rollback

这是产品安全不变量，不提供退回“聊天记录即人格”的路径。实现可替换，但语义不能弱化。

