# Codex Development Playbook

Codex 是研发加速器和可编排工程团队，不是项目真相或验收者。主线程维护目标、约束、决策和最终
结果；Subagent 承担边界清楚、可并行、噪声较大的工作。

## 信息放在哪里

| 信息 | Codex surface |
|---|---|
| 本次目标、上下文、约束、完成条件 | 当前任务 / Task Brief |
| 长期仓库规则、命令、Definition of Done | `AGENTS.md` |
| 多阶段、跨模块执行进度和决策 | `PLANS.md` / ExecPlan |
| 重复且已经稳定的工作方法 | `.agents/skills/<name>/SKILL.md` |
| 实时外部事实与受控行动 | MCP / Connector |
| 权限、Subagent、Hook 等项目配置 | 受信任仓库中的 `.codex/config.toml` |
| 定期运行的成熟流程 | Automation / dedicated worktree |

同一规则不要同时复制到多层。工作流至少手工稳定三次，或相同错误重复出现两次，才考虑制作
Skill；需要大量临场指导的任务不得自动定时执行。

## 单任务协议

1. **用户/主 Agent 建立 Context Pack**：Goal、Context、Constraints、Done when。
2. **先预测**：写出方案草图、三个失败方式和第一条 Red 测试。
3. **并行探索**：按需要启动 Explorer、Docs Researcher、Test Designer。
4. **收敛决策**：主 Agent 比较证据，更新 Task Brief 或 ExecPlan slice delta；Subagent 不自行扩大范围。
5. **Red → Green → Refactor**：实现者不得通过删除断言或降低安全要求获得绿色。
6. **攻击性复核**：Reviewer 独立查 correctness、security、concurrency、recovery 和 test gaps。
7. **验证汇总**：主 Agent 查看 Diff、运行目标测试与全仓检查、执行真实验收步骤并汇总证据。
8. **人类验收与 Teach-back**：项目所有者接受风险、作里程碑 go/no-go，并解释关键机制、取舍和未知。
9. **沉淀**：Build Note；新决策才写 ADR；稳定重复流程才做 Skill。

## Subagent 何时值得使用

适合：

- 互不依赖的仓库探索、官方文档核验、威胁建模和测试矩阵；
- 大量日志、Trace、论文或竞品材料的分片分析；
- 对同一 Diff 的安全、正确性、性能、文档等独立审查；
- 已有清晰接口、文件不重叠的实现切片；
- 慢测试与主线程设计工作并行。

不适合：

- 需求和架构尚未收敛；
- 多个 Agent 要编辑同一个状态机、Schema 或迁移；
- 小任务的协调成本高于实现；
- 需要同一份隐式上下文才能正确判断；
- 只是为了显示“用了多 Agent”。

默认先并行 read-heavy 工作。Write-heavy Agent 只能写明确文件范围；并行 writer 必须使用
各自独立的 Git worktree 和不重叠的所有权。单独创建 branch 不能隔离同一工作目录、Git index
或构建产物。

## 推荐角色

| 角色 | 职责 | 默认权限 |
|---|---|---|
| Main Integrator | 需求、架构、计划、整合、验证汇总和用户沟通 | 按当前任务 |
| `architecture_researcher` | 路径、依赖、源码与官方文档证据 | 默认 read-only |
| `test_designer` | Red case、状态表、fault matrix、eval design | 默认 read-only |
| 内置 `worker` | 一个已经定义的切片 | 独立 worktree + 限定写范围 |
| `production_reviewer` | 正确性、安全、恢复、可运维和测试缺口 | 默认 read-only |

每个委派 Prompt 必须包含：具体问题、相关上下文、允许/禁止范围、验收方式、输出格式，以及主线程
是否等待全部结果。Subagent 自报“完成”不构成验收。

`sandbox_mode = "read-only"` 是角色默认值，不是不可覆盖的安全边界：父任务的实时权限可在
spawn 时覆盖它，文件只读也不限制 MCP、app 或 Connector 的远程写入。Read-oriented 角色的
Prompt 必须禁止所有变更型外部工具；需要强约束时，父任务先选择 read-only 权限并限制工具面。

项目 `.codex/config.toml` 只应在明确受信任的仓库中生效。不要把 `/`、用户主目录或宽泛父目录
设为通用 trusted root；只信任经过审查的具体仓库。

## Context hygiene

- 一个 Chat 对应一个连贯结果；真正分叉时才 Fork；
- 主线程保留需求、决策与结果，原始日志和广泛扫描交给 Subagent；
- 需要时 Compact，但持久决策必须已进入代码、ADR 或 ExecPlan；
- 引用具体文件、错误和版本，不把整个仓库复制进 Prompt；
- 当前库、API 和云服务行为先查锁文件/源码，再查官方文档；
- 不在上下文中放 Token、真实 Self Model 或完整私人 Trace。

## Model与成本

仓库不长期钉死某个模型名称。复杂架构、安全与综合审查使用较高推理预算；机械扫描、文档索引和
独立轻量检查使用更快、更低成本的 Agent。任何模型替换都通过固定 Task Pack 与
HarnessRunBundle 评估，不以“新模型应该更强”直接升级。

## 学习保护

AI Coding 的目标不是增加手敲代码，而是提升问题定义、委派、验证和系统判断。以下责任不外包：

- 决定目标、非目标和成功标准；
- 实现前预测主要失败方式；
- 承担架构与安全取舍；
- 审阅关键状态机、权限、迁移和外部行动 Diff；
- 亲自复现并定位至少一个重要故障；
- 用自己的话完成白板与面试式追问。

每个重要任务在 Learning Note 中记录 Codex 做了什么、哪些建议被否决、自己能独立复现什么。

## Codex workflow audit

每月检查：

- Subagent 是否真正缩短周期或提高缺陷发现率；
- 是否因并行写入增加冲突和返工；
- `AGENTS.md` 是否来自重复真实摩擦，而非预想规则；
- 是否有流程已稳定到值得做 Skill；
- 自动化是否连续三次人工执行都无需临场修正；
- 用户是否仍能解释关键实现。

## Current Codex source basis

Checked on 2026-07-28 against the official Codex manual:

- [Best practices](https://learn.chatgpt.com/guides/best-practices)
- [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Git worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)
- [Configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference)
- [Build skills](https://learn.chatgpt.com/docs/build-skills)
- [MCP](https://learn.chatgpt.com/docs/extend/mcp)

Codex configuration and product surfaces can change; recheck the manual before changing project config,
models, permissions, hooks or automation behavior.
