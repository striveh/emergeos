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

前一产品闭环增量：**Exact Local Action Approval Scope focused + root clean + exact code-head CI
Engineering Green；overall Authority / Live Red。** DCO commit
`27da843dcb9deb9ae4008f1812dafd537a748cf3`的
[Draft PR #6](https://github.com/striveh/emergeos/pull/6)在回执截止时为`Draft / Open / CLEAN`；
[CI run 31771125144](https://github.com/striveh/emergeos/actions/runs/31771125144)为
`SUCCESS / BUILD SUCCESS`（`24m42s`）。Fresh local root为11/11 reactor、170 XML、893 tests全绿；
shipping Live仍 disabled，PR未merge/release。该证据绑定上述code-bearing commit/run；后续docs-only
head不改变它，PR当前check状态以GitHub为准。
回执：[Exact Local Action Approval Scope Build Note](./docs/operations/build-notes/2026-08-14-s1-exact-local-approval-scope.md)

前一产品闭环增量：**Real Local Draftbox focused、PostgreSQL integration、crash/fresh-JVM、post-P1
fresh root、final independent review及exact code-head CI Engineering Green；Draft PR #7；overall
Authority / Live Red。** 用户现在可在
exact approval后以第二个明确手势写入provider-free本地Draft，并取得
`LOCAL_DRAFT_CREATED_V1 / simulated=false` typed Receipt；response loss只允许显式GET恢复，fresh JVM
返回同一canonical truth，历史`LOCAL_DRAFTBOX_V1`仍fail closed。Undo尚未实现，owner需选择
A logical undo（推荐）、B isolate retention或C permanent forget；ReflectionCandidate与founder
dogfood尚未执行。首轮两条stale V1 Acceptance已修复并Green；
recovery在graph-eval-runner暴露两个V18 test fixture兼容性缺口，以
`164 XML / 820 tests / 1F + 3E`终止。两个fixture经最小静态修正后，新的root cycle一次完成
`11/11 reactor / 175 XML / 904 tests / 0`；该结果只绑定late P1修复前的bytes。随后独立终审发现唯一
P1：READY卡片把`REVERSIBLE`误译为“可撤销”，但Undo尚未实现；outside-in Acceptance以
`EXACT_LOCAL_APPROVAL_UI_TARGET_RISK_MISSING`精确Red，minimum copy修复后focused Exact与
PostgreSQL 6 + 三个指定IT的combined Gate均Green。production UI bytes已变化，因此新的post-P1
`clean verify`另起一次fresh cycle并一次完成`11/11 reactor / 175 XML / 904 tests / 0`；XML/JAR
manifests分别为`7d0d98716ec45efc85aa5ffb635f12cc0f5500db3218ae226862cd0e6bd4ba95`与
`bd4ac24ef8a8dcfdae51e6ce62eb39f6b166a76a02421afac40bd34aaaf4ad20`。旧`904/904`仍只作为
pre-P1历史，不能替代这轮post-P1 evidence。final release-candidate independent review为
`GO / P0=0 / P1=0 / P2=0`；reviewer独立复现root、manifests及54-file scope，并确认无secret、
`CLAUDE.md`、test hook或out-of-scope文件。

DCO code-bearing commit
[`195c15683f8ddadcf17ec313771b5e66c4c7ec37`](https://github.com/striveh/emergeos/commit/195c15683f8ddadcf17ec313771b5e66c4c7ec37)
已普通push到`origin/agent/real-local-draftbox-undo`；54-path manifest为
`8ed8b20d19f0abdb7a531737300baa5ecb5946c23e2fd433b674a7f4c14d874d`。
[Draft PR #7](https://github.com/striveh/emergeos/pull/7)在本回执截止时为
`OPEN / Draft / CLEAN`，base `main`、head exact `195c156…`；绑定该code head的
[CI run 31872883333](https://github.com/striveh/emergeos/actions/runs/31872883333)已
`completed / success`，job `94984100618`耗时`18m43s`，Maven于`17:59`报`BUILD SUCCESS`。
CI只有非阻断的Actions v4 Node20/setup-java deprecation warning，不是功能失败。当前这笔Receipt
docs-only更新会产生后续新head，仍须等待该head自己的CI；现有Green只证明code-bearing `195c156…`。
PR不得擅自转Ready，且没有merge/release/deploy；任何Engineering Green都不改变Authority/Live Red。
回执：[Real Local Draftbox Engineering Receipt](./docs/operations/build-notes/2026-08-15-real-local-draftbox.md)

当前产品闭环增量：**Append-only Logical Local Draft Undo已取得PostgreSQL adapter full、selected graph
compatibility与post-pin UI focused Engineering Green；plugin pin已落地并独立review，两个late P1已关闭；
fresh root first cycle因唯一stale current-schema test fixture而Red，一行test-only修复已review/focused Green；
唯一bounded root recovery已`11/11 / 179 XML / 914 tests / 0` Engineering Green；terminal docs-only
claims的final review已`GO / P0=0 / P1=0 / 7 P2 deferred`，Receipt/DCO/non-force push允许；
overall Authority / Live Red。** owner已选择logical Undo option A。用户只有在批准与execute之后，才可
通过第三个明确手势追加一条`LOCAL_DRAFT_LOGICALLY_UNDONE_V1 / simulated=false` Receipt；raw Draft
继续是不可变`ACTIVE`，exact read projection变为`LOGICALLY_UNDONE`。first/replay/GET为
`201/200/200`，response loss或hang进入`UNDO_UNKNOWN`且不自动POST/GET，只能由显式查询手势恢复。

这是保留明文与历史的逻辑撤销：Capture/Artifact、creation attempt/transitions/Receipt与raw Draft都不
删除或改写；没有restore、isolate retention、permanent forget、provider/Connector、legacy reconcile、
Reflection或Working Self mutation。已确认PostgreSQL adapter full `465/465`、selected graph
compatibility `3/3`、API classpath selected `12/12` Green，覆盖4个Undo UI场景、16个既有UI场景、
race/no-leak、commit前rollback与commit后response-loss fresh-JVM replay。

初始fat JAR parity P1与随后`12/12`、`7/7` reactor、`51.502s` packaged recovery仍保留为
historical pre-pin/pre-late-P1 evidence；当时fat JAR `639ed678…`不是current root。显式
`maven-jar-plugin` `3.5.0` pin已落地并独立review。late review关闭的两个P1为：
`PostgresApiTest` reset将V19三表按child-first顺序纳入（SHA `e42994e…`）；以及
historical Undo在inflight response后立即edit时保留独立UNKNOWN/GET recovery context（Red marker
`REAL_LOCAL_DRAFTBOX_UI_UNDO_INFLIGHT_EDIT_RECOVERY_LOST expected=1 actual=0`）。

最终UI focused gate为`7/7` Green（Store 6 + UI 1），Real UI实际执行5个Undo与16个
legacy场景；Store/UI XML SHA前缀为`acd77b95`/`bff291bd`。new fat JAR为`a355feb4…`，
UI resource source/target/fat parity Green，plugin-version warning absent。known nonblocking P2包括
causal-time、hostile named/default type ACL、generic Undone hash重算、historical recovery后card文案/
state、forced handler Acceptance、`EmergeDatabaseSnapshot`三表coverage，以及新增的two-tab
different-nonce loser UI recovery缺口，共7项。新增P2中backend正确返回`201 + 409`、
one Receipt，且无duplicate mutation、leak或false success；loser UI目前把exact `409`视为
`UNDO_UNKNOWN`，显式GET取得winner canonical Undone后仍因nonce/hash不匹配而UNKNOWN。
最窄future是补two-tab UI Acceptance，识别exact `409`并在显式GET后显示
“由另一操作逻辑撤销”，但不得声称loser POST成功。

唯一一次fresh root first cycle使用exact `./mvnw --batch-mode --no-transfer-progress clean verify`，
于`4m25s / exit 1`结束，因此root不是Green。Contracts `59`、Core `210`、Agent Loop `41`、
OpenAI `39`、InMemory `37`、PostgreSQL `196`与API Surefire `33`全绿；API Failsafe `22`中
唯一失败为`RecoverableLocalActionHttpIT` readiness期待schema 18而实际为19，后三个
reactor module `SKIPPED`。failure XML SHA-256为`628335f891e35746b62930a67c7e499a72ecd8744b3fd96738ea3307f621252b`，
fresh fat JAR为`7ba5b19b…`。这是唯一stale current-schema fixture，不是production故障。

minimum test-only fix只将`CURRENT_SCHEMA_VERSION` `18 → 19`，source SHA-256为
`474528d20f858bd92a60ec9c4aeef70f9e2e75fe78e8a04e9da9e45270c3042a`；全仓无第二
current-head oracle，独立review为`GO / P0=0 / P1=0 / P2=0`。修复后单次focused verify
于`21.993s`完成`BUILD SUCCESS`，Store `6/6` + IT `1/1` Green；XML SHA-256为
`a9afe0890ddb77b3f9254894afe499e50587b45ea46537523578a731fc11b03c` /
`113bd4e2d3840f97dd3499e7d85972dc2bd3e9df1a347fd6e95b670e5644ac2a`，6次readiness均为
`schemaVersion=19 / pendingMigrations=0 / schemaValid=true`。focused fat JAR `bdd5d664…`与
nested adapter `76844d5b…` parity，source/target/nested V19均为`740bea…`且包含type
`REVOKE`。这只是Engineering focused closure，不回写首轮root Red。

随后唯一bounded root recovery使用同exact `clean verify`命令，以
`exit 0 / BUILD SUCCESS / 09:28 / 11 of 11`结束。`179` fresh XML共`914 tests / 0 failures /
0 errors / 0 skipped`，manifest SHA-256为
`31d68d6d728a8f6cc8921d19dd153e5c7ae88e9d8df70be9b5efffe9695caca8`。关键XML SHA前缀为
V9 `5387d045…`、V19 `0b5564d9…`、UndoStore `25b5690f…`、Graph61 `a12ecd6d…`、
DurableGraph `b6ff161a…`、UndoAPI `4f608710…`、UI `e7190259…`、Crash `d09a7f81…`、
Isolation `956f8d21…`、Recoverable `7e4abcf2…`和Pack009 `70b6041c…`。API fat JAR为
`1a0278d8…`；nested PostgreSQL/Core与reactor parity，V19 all surfaces为`740bea…` + type
`REVOKE` exact一次，UI `4/4` parity，adapter `130` / graph `61` payload `0 mismatch`，
`maven-jar-plugin 3.5.0`无warning，postflight无code/test/POM drift。fresh root Engineering Green。

该root运行在本次terminal docs-only update之前，只覆盖当时code/test/POM与pre-update
docs。final independent review为`GO / P0=0 / P1=0 / 7 P2 deferred`；root-covered 41-path
code-bearing manifest已冻结为
`bacb5b377e9cfa4c8d14f7a771d7c0742b4c6734290fb9804b9b9f80903ae98f`。终审中的
pre-update 45-path content/docs5/terminal-docs4 manifests只是historical，不再称final；current值
必须在writer stop-write后由独立reviewer外部重算并封存，避免文档manifest自引用。
Receipt、DCO commit与普通非force push已允许，exact-head CI仍待通过。Draft不得
转Ready，也不得merge、release或deploy；任何local Green都不改变Authority / Live Red。
回执：[S1 Append-only Logical Local Draft Undo Engineering Receipt](./docs/operations/build-notes/2026-08-16-s1-logical-local-draft-undo.md)；
决策：[ADR-0020](./docs/architecture/decisions/0020-append-only-logical-local-draft-undo.md)。

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
- 当前产品只验证捕获/修订、provider-free local Draft与append-only logical Undo的本地闭包；
  “有来源、像本人、值得付费”由 Concierge 独立验证；
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
