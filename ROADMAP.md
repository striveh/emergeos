# Roadmap

路线图表达学习和验证顺序，不承诺发布日期。进入 `Now` 的事项必须有负责人、验收标准和可复现证据。

## Now

- 建立模块化单体、核心契约和本地纵向闭环。
- 固化 `EvidenceEvent`、`WorkingSelf`、`Artifact`、`ActionPlan`、`Receipt`、`ReflectionCandidate`。
- 建立 `HarnessRunBundle`、任务金标和故障注入格式。
- 以 PostgreSQL 验证主体隔离、Capture 去重、Revision CAS、ActionAttempt 与跨进程对账。
- 用同一模型完成 H0/H1 Harness 对照实验的 instrumentation smoke。
- 明确开源许可证、商标与公共/私有数据边界。

## Next

- Temporal 粗粒度 Workflow：等待审批、重试、幂等和恢复。
- `AgentKernel` SPI 与最小 AgentScope/Pi 对照适配器。
- macOS/Web 最小捕获入口和受控动态结果卡。
- 一个真实但可撤销的平台草稿 Connector。

## Later

- Self Model 候选、冲突、确认、遗忘和导出。
- 多设备同步、订阅源、主动建议预算。
- 多租户、配额、付费订阅和 Connector SDK。
- iOS/Android 原生入口和语音快回路。

## Exploring

- 长期上下文漂移检测与重建。
- 面向个人 Agent 的 Thought-to-Outcome Benchmark。
- 模型、Prompt、Skill 与 Harness 的自适应简化。
- 隐私保护的社区评测与联邦式经验共享。

## 接入真实 Connector 的硬门槛

在 PostgreSQL 故障测试证明以下能力前，不接入微博、X、小红书、公众号等真实写入：

- `(principal, manifestation)` 查询隔离与数据库级乐观锁；
- Capture 的 `clientNonce + requestHash` 去重；
- Revision 的 expected version/hash compare-and-swap；
- 写入前持久化 ActionAttempt；
- 跨进程、双实例下同一幂等键只产生一个外部对象；
- `UNKNOWN → RECONCILING → Receipt` 可解释恢复；
- Capability 精确绑定 ActionPlan、连接器 audience、账号与幂等键。
