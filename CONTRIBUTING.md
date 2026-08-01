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
2. 阅读 [研发方法](./docs/engineering/development-method.md) 与
   [Codex 协作手册](./docs/engineering/codex-playbook.md)。
3. 将工作分类为 `S` 小改、`V` 纵向能力、`R` 高风险边界或 `X` 限时 Spike。
4. `S` 只需 PR/简短 Receipt；未达到长计划门槛的 `V/R` 使用
   [Task Brief](./docs/engineering/task-brief-template.md)；跨模块、基础设施、公开契约/安全边界、
   超过一天或含重大未知的 `V/R` 按 [PLANS.md](./PLANS.md) 使用 ExecPlan **替代** Task Brief；
   `X` 只写限时 Spike 记录。
5. 小修复可直接提交；涉及核心协议、权限、Self Model、公开或跨模块数据契约、主要依赖或
   公开行动的变化，先提交 RFC。
6. 不确定是否需要 RFC 时，先开 Discussion 或 Issue 描述问题、证据和最小验证。

## 本地验证

需要 Java 21、Node.js 22 与 npm。首次检出或 `package-lock.json` 变化后，先从仓库根目录安装锁定的契约验证依赖：

```bash
npm ci
```

开发时先运行受影响的快速检查；提交 V/R 变化前运行完整确定性验证：

```bash
./mvnw verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
```

提交前至少证明：

- 行为变化先出现了预期失败的测试、Eval 或用户实验基线；
- 新行为有测试或可复现验收步骤；
- 没有跨越模块依赖方向；
- 外部副作用具有授权、幂等键和 Receipt；
- 不记录或展示隐藏思维链；
- 文档与行为同步更新。

探索性 Spike 可以先实现最小探针，但必须有时间盒、结论与限制，且不能直接作为生产能力合入。
模型/Prompt/Harness 变化使用固定 Task Pack 重复评测，不对自然语言逐字断言。

## 数据安全

所有测试数据必须是合成数据。提交 Issue 或 PR 即表示你确认没有包含：

- 真实语音、聊天、浏览记录或 Self Model；
- Token、Cookie、账号 ID、草稿 ID或真实 Receipt；
- 可还原个人上下文的完整 Prompt、Trace 或 Embedding；
- 任何其他个人信息或秘密。

## 决策路径

以下路径只适用于需要 RFC 的变化；其余工作直接按 `S/V/R/X` 路由：

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

## DCO sign-off

项目采用 [Developer Certificate of Origin 1.1](./DCO)，不要求 CLA。每个贡献 commit
必须包含与提交者身份一致的 `Signed-off-by`：

```text
Signed-off-by: Your Name <your-public-email@example.com>
```

创建 commit 时推荐使用：

```bash
git commit -s
```

如果已有 commit 缺少 sign-off，请在确认自己有权作出 DCO 声明后补签；rebase、squash
或修改 commit 后，应重新确认 sign-off 仍与最终内容和作者身份一致。DCO 会长期保留你
主动提交的姓名、邮箱与 sign-off 记录，请使用你愿意公开的身份。
