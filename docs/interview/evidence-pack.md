# Interview Evidence Pack

项目的面试价值来自可以被追问的真实决策与失败，不来自技术名词数量。

## 每个可用于 Career 证据的 Stage Gate / Release Case Card

```text
Problem
Constraints
Options considered
Decision and why
Failure injected or encountered
Evidence
User/production result
What changed my mind
Remaining risk
```

准备四种表达长度：

- 30 秒：项目定位与最难问题；
- 2 分钟：一个完整 Case；
- 10 分钟：架构白板与关键状态机；
- 30 分钟：替代方案、事故、成本、安全和演进。

所有数字都必须指向测试、Trace、Build Note、用户记录或真实收入；不能用模型生成估算冒充结果。

## 重点案例及可使用条件

| Case | 可以开始对外主张的 Gate |
|---|---|
| 为什么采用模块化单体和 framework-neutral Core | Stage 0 teach-back 通过 |
| 为什么 Capture 同时需要数据库唯一约束与 server-owned request hash | S1 工程回执完成且 owner Teach-back 通过 |
| 为什么 Artifact CAS 同时匹配主体、版本和 Hash，并把 head/lineage 放在一个事务 | S2 工程回执完成且 owner Teach-back 通过 |
| ActionAttempt、Receipt 与 reconciliation 如何处理模拟 Provider 成功/响应丢失 | S3 工程回执完成且 owner Teach-back 通过 |
| 为什么 readiness 不能等于 liveness，以及迁移/恢复如何验证数据真相 | S4 工程回执完成且 owner Teach-back/独立故障诊断通过 |
| 固定模型下 H0/H1 Harness 如何排除“换模型”干扰 | Stage 2 重复实验完成 |
| Evidence-backed Self Model 如何避免把推断当人格事实 | Self Eval 与纠正/撤销流程完成 |
| Codex 多 Agent 如何降低 Context noise 和写入冲突 | 至少两个切片有周期、返工和缺陷记录 |
| 从用户工作流到首个付费试点删掉了哪些宏大功能 | 真实付款或拒付实验完成 |

## 核心问题库

### Product

- 为什么它不是 ChatGPT 壳、笔记 App 或自动化工具箱？
- 首个目标用户是谁，替代品是什么，为什么愿意付费？
- 北极星指标为什么不是生成量或聊天时长？

### Architecture

- 为什么当前不拆微服务？什么信号出现后才拆？
- Evidence、Working Self、Self Model、Artifact 和 Receipt 哪些是真相、哪些是投影？
- Agent、Agent Runtime、Harness 和 Durable Workflow 分别负责什么？
- 为什么不用一个框架包办所有层？

### Correctness and production

- Exactly-once 为什么通常不可得？
- 外部写入成功但本地超时如何处理？
- Temporal 为什么不能替代数据库约束和对账？
- 如何证明多租户隔离、备份恢复和模型降级？

### Agent engineering

- 多 Agent 什么时候比单 Agent 更差？
- 如何评测非确定性输出并避免只挑最好结果？
- Critic 为什么不是实验真值？
- Memory、Context、RAG 和 Self Model 有什么区别？
- 模型升级如何灰度、评测和回滚？

### AI Coding

- 哪个 Codex 方案被你否决了，为什么？
- 如何证明 Subagent 提高质量而不是制造更多代码？
- 怎样防止“项目能跑，但自己不会”？
- 哪个故障是你亲自复现、定位并解释的？

## 当前可说的 90 秒版本

> 我做的不是聊天壳，而是一套把个人的所见、所思、所愿变成可验证成果的
> Personal AI OS。当前是一个研究原型：模块化单体和纯 Java 领域核心已经跑通本地
> Thought → Artifact → Approval → Receipt 闭环；第一个 PostgreSQL Capture 切片已用数据库
> 唯一约束、两个配置主体和真实 JVM 重启证明持久化与隔离；第二个 Artifact 切片用两个独立
> 应用进程竞争同一 base，证明四条件 CAS 只生成一个 v2，且 lineage 在新 JVM 中保持不变；
> 第三个 local Action 切片先写 ActionAttempt，再让独立、持久化状态的 Fake Provider 创建
> 一个模拟对象并丢失响应，强杀旧应用后由新 JVM 对账成一张 Receipt；第四个 operating
> 切片用 owner-scoped readiness、populated V1/V2/V3 升级、packaged migration fail-fast 和
> PostgreSQL new-database restore 验证了可操作边界。复核也推翻了一个过强结论：现有 crash
> 发生在数据库已记录 `UNKNOWN` 之后，尚未证明 provider success 后、local commit 前遗留
> `DISPATCHING` 的安全接管，所以 ADR 仍为 Proposed，真实 Connector Gate 保持关闭。
> Manifestation 其余状态
> 仍在内存，系统也没有生产认证、真实模型、Temporal 或真实平台 Connector。研发中我把 Codex
> 当受监督的工程团队，主线程整合需求与验证证据，Subagent 做边界清楚的探索、测试设计和独立
> 审查，但风险接受和里程碑 go/no-go 由我负责。真实访谈、复用与价格实验仍需项目所有者开始
> 记录。

这段是仓库提供的表达草稿；项目所有者完成无资料 Teach-back 前，不得把它当作个人能力已验证。

## 仅在 Stage 1 Gate 通过后可说的增量

> 我进一步用数据库唯一约束、乐观并发和写入前 ActionAttempt 处理跨进程重复与响应丢失，
> 通过真实 PostgreSQL、两个应用实例、进程重启和独立 Fake Provider 状态证明恢复路径。它仍然
> 只证明模拟外部动作，不代表任何真实平台 Connector 已上线。

实际面试时必须删除未完成段落，并让每个数字和能力指向当时已有的测试、Trace、Build Note、
用户记录或收入回执。
