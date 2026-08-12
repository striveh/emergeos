# Roadmap

路线图表达学习与验证顺序，不承诺发布日期。按阶段 Gate 推进，不因代码合并就宣布完成。
每个阶段必须留下 AI Coding、Agent Engineering、Product/Production、Career 和 Business
Receipt。

## Stage 0 · Foundation

状态：**Engineering baseline complete; human mastery and market value not yet proven.**

| Outcome | Evidence |
|---|---|
| AI Coding | 已有仓库规则和协作过程；个人掌握尚未考核 |
| Agent Engineering | 模块化单体、Ports/Adapters、Evidence vs Projection 的待复习材料 |
| Product/Production | 本地 Thought → Artifact → Approval → Receipt → ReflectionCandidate 闭环；领域不变量、主体查询、Hash 审批、幂等 Stub、Schema Fixture |
| Career | Architecture、ADR、Build Note、可运行 API；尚无录制讲解 |
| Business | 尚无外部用户或付费证据 |

Stage 0 只有在项目所有者能白板解释状态机、真相边界和失败窗口后，才计入个人能力证据。

## Stage 1 · Durable Correctness and Problem Discovery

状态：**Now — S1–S4 工程切片回执已完成；真实 Connector Gate、人类 Teach-back 与市场 Gate
仍未完成。**

执行计划：[Stage 1 Durable Correctness](./docs/plans/2026-07-28-stage-1-durable-correctness.md)

S1 回执：[Restart-safe Capture Build Note](./docs/operations/build-notes/2026-07-28-s1-restart-safe-capture.md)

S2 回执：[Conflict-safe Revision Build Note](./docs/operations/build-notes/2026-07-28-s2-conflict-safe-revision.md)

S3 回执：[Recoverable local Action Build Note](./docs/operations/build-notes/2026-07-28-s3-recoverable-local-action.md)

S4 回执：[Operating and Gate-Closure Build Note](./docs/operations/build-notes/2026-07-29-s4-operating-gate-closure.md)

产品/工程：

- 按 Capture、Revision、Action、Operations 四个纵向切片逐步引入 PostgreSQL；
- 一个同设备、loopback-only 的本地网页或可信系统快捷入口负责文字、链接或语音文件引用捕获；
- Capture `clientNonce + requestHash` 去重，Revision expected version/hash CAS；
- 写入前 ActionAttempt、唯一幂等约束、`UNKNOWN → RECONCILING`；
- Testcontainers 下验证双实例竞争、进程死亡、升级、失败停止与恢复；
- 暂缓通用 Outbox/Inbox、完整 Working Self 关系模型和通用 Metrics 平台。

学习：

- 两个 L3 主目标：事务/唯一约束/乐观锁，以及幂等外部行动/未知结果/对账；
- Outbox/Inbox 在本阶段只理解机制，不因“生产级”提前实现；
- Outside-in TDD、集成测试、故障注入和 Codex Diff Review；
- 无资料讲解、亲自定位一个未知故障，并能解释 Temporal 为什么不能替代数据库约束。

产品/商业并行：

- Day 1 启动 14 天 Founder dogfooding 和每周 2–3 次最近行为访谈；
- 至少 3 位目标用户提交真实 Seed，观察复用或明确不复用原因；
- 通过人 + Codex Concierge 交付母稿，并至少提出一次真实价格；
- 当前产品只验证捕获/修订/恢复；“有来源、像本人、值得付费”由 Concierge 独立验证；
- 明确 `continue / narrow / pivot` 首个用户群与输出类型。

Gate：

- 四个切片各有可重放的 Red、Fault、Review 和 Receipt；
- Capture 重启不丢失、Revision 双实例只有一个赢家；
- 独立进程且持久化状态的 Fake Provider 只有一个可观察模拟对象，真实终止/重启应用后可对账
  为一张 Receipt；
- fresh install、上一版升级、失败停止及备份恢复/forward-fix 有证据；
- provider success 后、local outcome/Receipt commit 前进程死亡不会造成无权接管或双活提交；
- 完成 14 天记录、10 次访谈、3 位真实 Seed、一次价格请求和明确商业决策；
- 完成故障恢复 Demo、Case Card、无资料 Teach-back 和延迟变体题。

## Stage 2 · AgentKernel and Eval-Driven Development

执行计划：
[Stage 2 AgentKernel and Eval-Driven Development](./docs/plans/2026-07-29-stage-2-agent-kernel-evaluation.md)。

状态：**S1、S2 工程完成；S3 real model protocol adapter、isolated synthetic
Eval Runner、本地 attempt durability 与 terminal record create-only repair
工程切片已通过。曾执行一次 bounded intermediate DeepSeek request，但在
`RESPONSE_METADATA_MISMATCH` 处 fail closed；当时 artifact hash 未冻结，current final bytes
仍没有 live PASS，provider compatibility、retention 与 billing 均未知。S4 已完成 deterministic
Verifier comparison、canonical durable report，以及 Pack 005 Tool arguments、
Pack 006 post-dispatch deadline 和 Pack 007 typed read-only Worker 三个有限故障/
runtime 工程切片。Pack009已把 one-shot graph与 provider-accepted crash truth落到
PostgreSQL V7；Pack010 offline slice已把 attributed terminal graph、sequence-17 seal、
三份 fresh repetition与 complete-only Harness Report落到 PostgreSQL V8；本地
owner-TTY facade、local permit object one-winner与 r1→r2→r3 predecessor atomic claim
子切片也已完成；forward-only V9 runtime role/ACL + exact TX-B/TX-C semantic function与
fat-JAR-first/test-shell successor hard-kill/two-JVM也已 focused Green。
shipping artifact内的 dormant exact credential broker/session composer，以及
intent persistence failure→HTTP 0、attribution fail-closed→无 replay增量的本机 loopback
sentinel也已 focused Green；owner-approved同进程线性 capability handoff与独立、默认拒绝的
role/ACL provisioning bootstrap也已取得 focused evidence；dormant V9 fixed-role writer
composition又绑定 exact owner terminal capability。sequence-7 durable provider-session intent
已在 credential/client/model/session/HTTP effect之前原子落库，并覆盖 exact binding、并发
one-shot、expiry/replay及 commit前/后 hard-kill + restart；complete attribution仍必须先于
terminal transaction。runtime拒绝 cross-attempt/two-database splice、alternating identity、
transitive helper/trigger `tgattr` drift与 prefix TEMP/ACL drift，expiry与 semantic call合并为
单条 SQL；TX-B/TX-C各一次及 PostgreSQL process restart reconciliation；dedicated DB pure audit
也已扩为全 grantee allowlist、exact helper topology与
真实 audit-failure atomic rollback。最新 Acceptance又把 credential lease的 durable
intent/owner/expiry带到 key/client/model/session每个 effect boundary、把 Coordinator固定到
authority-bound Store，并要求 OwnerTty与 exact prefix direct-login identity相同；数据库返回的
session cursor受 canonical event复合外键约束，额外非 internal session-intent trigger fail closed。
后续 actual review又补上 compose后 `next`/exact pre-HTTP双重 expiry复核，以及全部35个 public
non-internal trigger的 shipping SHA-256 topology；provisioning与 terminal runtime同时固定相同的
16-helper signature/properties/search_path/body SHA-256 closure。production provider response
attribution又完成exact content-decoded bytes hash、完整token split的durable-before-semantic
ordering、PostgreSQL restart与fresh packaged JVM no-replay matrix。actual success structured-final
也已成为绑定 exact lease/Coordinator/egress/manifest/attribution/expiry的process-local opaque
outcome，并只能 one-shot派生 typed TX-B command；wrong/malformed/forged/expired/replayed/
concurrent/hard-kill均 fail closed。V9 exact row keys与 Candidate/WorkerResult nested integrity、
完整child AgentRun/binding/event 15/sequence-15 snapshot及Trace/resource/Run relation在
claim前关闭，extra/duplicate/trailing/tampered payload不烧毁 outcome；actual PUBLIC loopback
outcome已沿同一 owner/Coordinator/egress capability进入 PostgreSQL TX-B并通过 restart
reconciliation。successful sequence 15又只能经 strict parent aggregate review派生 process-local
opaque typed TX-C command；Owner facade自行read-back durable seq15，forged/no-burn、wrong runtime、
完整 parent AgentRun/ArtifactLineage/sequence-17 snapshot及全部 relation mirror value必须通过 Core
aggregate invariant，event audit timestamp由PostgreSQL在TX-C内生成；两个 commands并发、
PostgreSQL restart→seq17 reconciliation均已 focused Green。production App/public writer又已移除raw
terminal payload ABI，PostgreSQL adapter以typed terminal truth与verified seq14/15 snapshot mint
private-constructor one-shot TX-B/TX-C transition；wrong typed truth no-burn、child/parent并发一个winner
与PostgreSQL restart reconciliation均已Green。forward-only V10又把 active executor surface固定为
exact child/parent semantic pair并撤销V9 executor EXECUTE；fresh migration PUBLIC revoke、独立
provisioning SHA-256/ACL/trigger/helper read-back、V8→V9→V10 fidelity，以及两个独立数据库session
的 TX-B/TX-C one-winner + seq14→15→17 restart reconciliation均已Green。failure protocol现在只接受
Core closed allowlist和exact parent mapping；forward-only V11又把typed failure verdict与request-2
attribution原子落为durable provenance，并通过dedicated resumer、state version、DB-clock lease与
exact head完成two-JVM one-winner、hard-kill、PostgreSQL restart和lease-expiry reclaim。forward-only
V12已把该claim与failure TX-B/TX-C原子绑定，并以fresh JVM completion、commit前hard-kill回滚、
PostgreSQL restart、child/parent two-JVM race及raw V10 bypass fence关闭跨JVM failure terminal resume
缺口。V13 local-only bounded semantic attestation已把真实loopback reviewed outcome、exact manifest
execution binding、DB challenge、typed signature receipt与TX-A原子提交串成同一Acceptance，并以
raw/tamper/replay/expiry/cross-attempt/cross-DB/fault/race fail-closed证明不依赖Java-only precheck；
V14 `PROFILE_ASSERTION_ONLY`以pico-USD精确费率冻结只读provider profile assertion，但不提交graph truth；
V15 `REQUIREMENT_GUARD_ONLY`再于seq13登记one-way marker并由deferred PostgreSQL guard阻止已登记attempt
回退到历史nano-USD TX-A，`exactPicoAttribution=NOT_IMPLEMENTED`、`TX-A=NOT_IMPLEMENTED`；
PostgreSQL-native验签、production key custody、shipping execute/live r1/r2/r3、完整 stochastic Harness、
通用multi-agent 与真实 Seed Gate仍未完成。**
这条技术主线来自项目所有者 2026-07-30 的 Roadmap
顺序例外；它不代表 Stage 1 的学习、市场或真实 Connector Gate 已完成。

S1 回执：
[Fake Agent Draft Loop Build Note](./docs/operations/build-notes/2026-07-30-s2-s1-fake-agent-draft-loop.md)。

S2 回执：
[持久 AgentRun、Safe Trace 与 HarnessRunBundle Build Note](./docs/operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)。

S3 adapter 回执：
[OpenAI Responses Adapter Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-openai-responses-adapter.md)。

S3 bounded runner 回执：
[Bounded Synthetic Eval Runner Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)。

S3 durability 回执：
[Durable Eval Attempt Evidence Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)。

S3 create-only record 回执：
[Eval Run Record Create-only Build Note](./docs/operations/build-notes/2026-07-31-s2-s3-eval-run-record-create-only.md)。

S4 loader 回执：
[Offline Comparison Loader Build Note](./docs/operations/build-notes/2026-07-30-s2-s4-offline-comparison-loader.md)。

S4 comparison 回执：
[Verified Offline Comparison Build Note](./docs/operations/build-notes/2026-07-30-s2-s4-verified-offline-comparison.md)。

S4 durable report 回执：
[Durable Offline Comparison Build Note](./docs/operations/build-notes/2026-07-30-s2-s4-durable-offline-comparison-report.md)。

S4 Tool arguments fault 回执：
[Tool Arguments Fault Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-tool-argument-fault.md)。

S4 post-dispatch deadline 回执：
[Post-dispatch Deadline Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-post-dispatch-deadline.md)。

S4 typed read-only Worker 回执：
[Typed Read-only Worker Handoff Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-typed-read-only-worker-handoff.md)。

S4 Pack009 durable graph crash 回执：
[Pack009 Durable Graph Crash Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)。

S4 Pack010 offline terminal graph / Harness 回执：
[Pack010 Terminal Graph / Harness Report Build Note](./docs/operations/build-notes/2026-08-01-s2-s4-pack010-terminal-graph-harness-report.md)。

S4 Pack010 owner TTY / predecessor authority子切片回执：
[Pack010 Owner TTY / Predecessor Authority Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-owner-tty-predecessor-authority.md)。

S4 Pack010 V9 terminal authority / packaged successor子切片回执：
[Pack010 V9 Terminal Authority Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-v9-terminal-authority.md)。

S4 Pack010 dormant provider capability / loopback ordering子切片回执：
[Pack010 Dormant Provider Capability Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-dormant-provider-capabilities.md)。

S4 Pack010 production capability handoff / role provisioning / runtime composition子切片回执：
[Pack010 Capability Handoff / Provisioning Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-capability-handoff-provisioning.md)。

S4 Pack010 exact provider response attribution子切片回执：
[Pack010 Exact Provider Attribution Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-exact-provider-attribution.md)。

S4 Pack010 terminal outcome binding子切片回执：
[Pack010 Terminal Outcome Binding Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-terminal-outcome-binding.md)。

S4 Pack010 adapter-owned canonical terminal transition子切片回执：
[Pack010 Canonical Terminal Transitions Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-canonical-terminal-transitions.md)。

S4 Pack010 V10 attributed failure authority子切片回执：
[Pack010 V10 Attributed Failure Authority Build Note](./docs/operations/build-notes/2026-08-09-s2-s4-pack010-v10-attributed-failure-authority.md)。

S4 Pack010 durable attributed failure outcome / resume fencing子切片回执：
[Pack010 Durable Attributed Failure Resume Build Note](./docs/operations/build-notes/2026-08-09-s2-s4-pack010-durable-attributed-failure-resume.md)。

产品/工程：

- Provider-neutral `AgentKernel` SPI，先 Fake 后 real adapter；
- Task/Result Envelope、工具循环、预算、取消、结构化输出和 Trace；
- PostgreSQL durable AgentRun、Safe Trace hash chain、typed resource binding、
  verified read 与 deterministic Offline golden runner；
- typed `WorkerCall`、`AgentWorkerRuntime`、durable `WorkerResultEnvelope`、
  V6 parent/child relation，以及 parent-only Artifact commit；
- 普通 API 之外的 isolated synthetic Eval Runner：默认 zero-egress preflight，
  real TTY + exact Task-bound one-shot permit，POSIX attempt marker/journal 与本地
  hard-link create-only terminal run record；
- production read-only journal verifier 与 8-point fat-JAR process-kill/restart matrix；
- 固定模型与 Task Pack 的 H0/H1 Harness 对照；
- 已完成 schema-invalid Tool arguments 的 pre-dispatch fault，以及 trusted
  read-only Tool 的 post-dispatch cooperative deadline fault；Pack 007 另固定
  registered Worker `contextPolicyVersion` 单变量 drift 在 child dispatch 前
  fail-closed。Tool 执行失败、限流、Working Self/context compaction drift、
  Prompt Injection 与 write-side timeout/reconciliation 故障集仍开放。

当前已经完成 synthetic Fake success 的 deterministic baseline、OpenAI Responses
protocol 的 loopback evidence、bounded runner engineering Gate，以及 8 个选定
boundary 的 fat-JAR 强制终止/新 JVM 只读核验。新增 link-commit 窗口固定
directory `fsync` 后、pending cleanup 前的同 inode residue；precheck 后的竞争 target
不会被覆盖。仍没有读取 real key、执行 live-provider smoke、取得 real model result
或 billing receipt。Task Pack 004 已冻结
reference-grounding Verifier comparison 的输入、arms、cases 与预期矩阵；独立
offline module 已实际生成 12 个 shared candidates，执行 24 次 H0/H1
VerifierEvaluation，并由 independent verifier replay 得到 `VERIFIED_PASSED`。
相同 28,343-byte canonical report 已通过 packaged hard-link create-only commit、
双 writer、7-point process-kill 与 fresh-JVM read-only verification。这个小型
deterministic synthetic comparison 不是正式 60-run stochastic quality 结论；
Pack 005 另以完全相同的 control/fault Task 证明 schema-invalid raw arguments 在
Tool execute 前被拒绝：fault 的 Tool execute、Tool-backed read、Artifact 均为 0，
并保留 typed failure 与 safe Trace。它是 deterministic Fake safety regression，
不是 live model 或系统级“什么都没发生”。Pack 006 进一步证明 read-only Tool
已 dispatch 后越过 deadline 时，actual read/latency 保留，但 late result 不进入
Evidence/Artifact；exact boundary 与 cancellation precedence 也被固定。它仍不是 hard
timeout、write-side exactly-once 或 live model evidence。Pack 007 再加入一个
server-owned、serial、synchronous、`depth=1` read-only Fake Worker：child
Task/Run/Trace/Bundle、durable WorkerResult、parent `HANDOFF` 和 V6 graph constraints
已验证，context-policy drift 在 child Run/Model/Tool/delegated read 前 fail-closed；
真实 process-kill 后可观察 child terminal + WorkerResult 已 durable、parent 仍
`RUNNING` 的 crash gap。它不会自动 resume，也不是 parallel/general multi-agent。
真实任务、人工盲评、其余 fault injection 与用户价值证据仍未执行。
`billingStatus=UNKNOWN` 表示 provider 费用未知，不能解释成免费；reservation 是调用前的
authorization ceiling，provider 已返回的 observed usage 即使超过 reservation 也必须如实保留。

学习：

- Model、Agent、Runtime、Harness、Workflow 和 Verifier 的边界；
- Tool calling、handoff、context policy 与非确定性评测；
- 重复实验、失败归因、成本/延迟与模型灰度。

产品/商业：

- Concierge 方式为少量 Design Partner 交付一个母稿成果；
- 固定模型比较有/无 Working Self 是否减少修改时间；
- 在自动化前获得重复使用和真实付费/拒付证据。

Gate：

- 他人可用一条流程重放 Harness 实验；
- 结论来自重复运行和独立证据，不挑最好一次；
- AgentKernel SDK 类型不进入 Core；
- 商业楔子比“通用模型 + 旧工具”表现出可测差异。

## Stage 3 · Rich Capture and Controlled UI

产品/工程：

- 在 Stage 1 极薄入口上增加完整 Web/macOS 体验、语音流、打断和部分结果；
- Schema-driven 动态结果卡与安全组件注册表；
- Evidence 来源、执行进度、审批和回执可视化；
- 离线/断线状态与端到端延迟测量。

学习：

- Realtime audio、VAD、streaming、event state 与 latency budget；
- 前端状态机、Human-in-the-loop、可访问性与 Generative UI 边界；
- Self Model 来源、冲突、纠正、遗忘和导出。

产品/商业：

- 观察真实用户无需培训完成任务；
- 测 Seed → 可用成果时间、“不像我”反馈和修改路径；
- 只保留激活、留存和付费最强的目标用户群。

Gate：

- 用户知道系统看到了什么、准备做什么和完成了什么；
- 模型不能生成任意可执行 UI；
- 关键人格推断可解释、纠正和撤销；
- 核心任务形成周重复使用。

## Stage 4 · Durable Workflow and One Reversible Connector

产品/工程：

- Temporal 粗粒度 Workflow：等待审批、重试、取消、恢复和对账；
- Secret Broker、OAuth、Capability Use Ledger 和审计；
- 只接一个“进入草稿箱”的真实 Connector；
- SLO、告警、Runbook、Game Day 与脱敏 Postmortem。

学习：

- Workflow/Activity、deterministic replay、signal、timeout 和 compensation；
- OAuth、最小权限、凭据生命周期、Connector contract 和威胁建模；
- 真实平台 `UNKNOWN` 结果的人工/自动对账。

产品/商业：

- 邀请制付费 Alpha；
- 测第四周成果留存、支持成本、外部动作成功与单位成本；
- 没有留存与付款前不扩第二个平台。

Gate：

- Worker kill/restart 后继续等待或对账；
- 0 未授权动作、0 重复外部对象；
- 模型从未获得长期密钥；
- 至少一个真实付费 cohort 与明确的支持成本。

## Stage 5 · Production Service and Open Ecosystem

产品/工程：

- OIDC、多租户隔离、加密、配额、订阅、导出/删除和保留策略；
- CI/CD、灰度、回滚、备份恢复、容量、成本熔断与供应链安全；
- Open Source 许可证、商标、Connector SDK、兼容性和安全响应；
- iOS/Android 与更多 Connector 只按留存需求扩展。

学习/职业：

- 完成 Production Readiness Review、恢复演练和模拟事故；
- 陌生贡献者可以按文档运行、验证并提交有效变化；
- 形成生产架构、Benchmark、Incident、用户结果和商业取舍的完整面试证据。

商业：

- 从 Design Partner → Paid Alpha → Production Beta；
- 只在留存、毛利和可重复获客成立后规模化；
- 用户始终拥有原始数据、Self Model、导出与删除权。

## 接入真实 Connector 的硬门槛

在 PostgreSQL 故障测试证明以下能力前，不接入微博、X、小红书、公众号等真实写入：

- `(principal, manifestation)` 查询隔离与数据库级乐观锁；
- Capture 的 `clientNonce + requestHash` 去重；
- Revision 的 expected version/hash compare-and-swap；
- 写入前持久化 ActionAttempt；
- 跨进程、双实例下同一幂等键只产生一个外部对象；
- `UNKNOWN → RECONCILING → Receipt` 可解释恢复；
- Capability 精确绑定 ActionPlan、连接器 audience、账号与幂等键。
- provider success 后、local outcome/Receipt commit 前进程死亡有 PostgreSQL-canonical
  owner/lease/fencing 与误接管证据；当前没有这条证据，ADR-0004 为 `Proposed`，Gate 保持关闭。

## 防止架构黑洞

- 一次只替换一个主要变量，保留 Fake/旧实现作对照；
- Day 1 启动用户接触；连续 7 天无用户接触或两个基础设施切片无市场证据时冻结新基础设施；
- 多 Agent、微服务、Kubernetes、知识图谱和任意动态 UI 必须由实验或生产约束触发；
- 每周 Pulse、每月用 [Five-Outcome Scorecard](./docs/operations/five-outcome-scorecard.md) 调整最弱轨道。
