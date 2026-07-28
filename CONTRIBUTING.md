# Contributing

感谢你参与 EmergeOS。项目采用 **founder-led、RFC-governed、evidence-gated** 的早期治理方式：方向保持一致，重要变化公开讨论，工程结论用可复现实验证明。

## 贡献入口

- Code：核心运行时、客户端、Connector 与开发工具。
- Eval：合成任务、故障注入、Verifier 和回归集。
- Research：Self Model、长期状态、人机交接与 Agent Harness。
- Product / Design：用户旅程、动态 UI、可解释性与可访问性。
- Docs / Translation：教程、原理、中文、英文和术语。

## 开始前

1. 阅读 [README.md](./README.md) 和 [docs/README.md](./docs/README.md)。
2. 小修复可直接提交；涉及核心协议、权限、Self Model、数据结构、主要依赖或公开行动的变化，先提交 RFC。
3. 不确定是否需要 RFC 时，先开 Discussion 或 Issue 描述问题、证据和最小验证。

## 本地验证

需要 Java 21、Node.js 22 与 npm。首次检出或 `package-lock.json` 变化后，先从仓库根目录安装锁定的契约验证依赖：

```bash
npm ci
```

然后运行完整验证：

```bash
./mvnw verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
```

提交前至少证明：

- 新行为有测试或可复现验收步骤；
- 没有跨越模块依赖方向；
- 外部副作用具有授权、幂等键和 Receipt；
- 不记录或展示隐藏思维链；
- 文档与行为同步更新。

## 数据安全

所有测试数据必须是合成数据。提交 Issue 或 PR 即表示你确认没有包含：

- 真实语音、聊天、浏览记录或 Self Model；
- Token、Cookie、账号 ID、草稿 ID或真实 Receipt；
- 可还原个人上下文的完整 Prompt、Trace 或 Embedding；
- 任何其他个人信息或秘密。

## 决策路径

```text
Problem / Evidence
→ Discussion
→ RFC
→ Spike / Evaluation
→ ADR
→ Implementation
→ Release Note
→ Regression Case
```

RFC 是提议；ADR 是已经接受的决定。已接受 ADR 不改写历史，只能被新 ADR 替代。

## 提交风格

- 一次提交解决一个可解释问题。
- Commit message 建议使用 `type(scope): summary`，例如 `feat(core): add receipt idempotency`。
- PR 描述应包含：问题、决策、验证、风险、回滚方式和文档变化。
