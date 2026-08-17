# EmergeOS · 显现

[![CI](https://github.com/striveh/emergeos/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/striveh/emergeos/actions/workflows/ci.yml)

[English README](./README_EN.md) · 中文

> A user-owned Personal AI OS that turns what you see, think, and intend into verified outcomes.

EmergeOS 不是聊天机器人、笔记 App 或自动化工具箱。它要解决的是：

> **把一个人刚刚看到、想到、想要的东西，在几分钟内变成有证据、可编辑、可执行、可复盘的作品或行动。**

当前阶段是 `0.1 / research prototype`。仓库已经建立第一条无外部副作用的纵向闭环，并完成
四个 Stage 1 PostgreSQL 工程切片：Capture 可在真实应用进程重启后取回；两个独立应用实例并发
修订同一 Artifact base 时，数据库 compare-and-swap 只允许一个新版本；独立、持久化状态的
Fake Provider 在响应丢失与真实应用 JVM 重启后，可把一个模拟对象对账成一张 Receipt。
readiness、populated schema 升级、incompatible migration fail-fast 与 PostgreSQL
backup/restore 已有可重放证据。但 provider success 后、local outcome 持久化前崩溃可能遗留
`DISPATCHING`，当前没有安全 lease/fencing 接管；ADR-0004 仍为 Proposed，真实 Connector
Gate 保持关闭。
Stage 2 S1 另增加一条无框架 Fake Agent 草稿闭环：脚本 Fake Model 必须通过声明的
`capture.read` 工具读取 owner-scoped Capture，结构化结果经确定性校验后才能写入 Artifact；
Stage 2 S2 又把 AgentRun、safe hashed Trace、Result、immutable resource binding 与
HarnessRunBundle 写入 PostgreSQL；成功 Artifact 与 terminal Run 在同一个 transaction
提交，JVM 重启后仍能 verified read。冻结的 synthetic Task Pack 可以在无网络 Offline
runner 中重复得到 exact golden hashes。它们是 Agent Runtime/Harness 的确定性基线，
不是真实模型质量证明，也不是可对真实用户数据执行的 product replay API。
完整工程回执见
[Stage 2 S2 Build Note](./docs/operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)。
Stage 2 S3 已把隔离的 OpenAI Responses adapter 装配到独立 `apps/eval-runner`。默认
packaged command 只核验 hash-frozen Task Pack、environment、Task/profile/pricing
identity 与预算，保持零 key read、零 client、零 marker 和零网络；显式 `--execute`
还必须经过真实 TTY challenge、本机 POSIX one-shot marker、30 秒 Task-bound permit、
append-only attempt journal 与 create-only terminal run record。production client 固定官方
base URL、`Proxy.NO_PROXY`、`maxRetries(0)`、日志关闭和 30 秒 deadline。完整执行目前
只在本机 loopback `HttpServer` 验证；**尚未读取真实 key、尚未访问 OpenAI、尚无 live
模型结果或费用回执**。普通 `apps/api` 仍只装配 deterministic Fake，目前具体是
Fake Conductor + 一个 Fake read-only Worker。adapter 历史回执见
[Stage 2 S3 Adapter Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-openai-responses-adapter.md)，
runner 工程回执见
[Stage 2 S3 Eval Runner Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)。
在此基础上，production read-only journal verifier 已能把本地尝试证据区分为
`VERIFIED / UNKNOWN / INVALID`，并把 billing evidence 与 run record state 分栏解释。
commit `2b50c66` 又把 terminal record 的 logical commit 从 provider-specific
`ATOMIC_MOVE` 改为 hard-link create-only：precheck 后出现的竞争 target 只能令 writer
拒绝，不能被覆盖。一个只存在于 `target/test-classes` 的 crash harness 已在 8 个
selected boundary 启动真实 fat JAR production classes、强制终止进程，再由新 JVM
只读核验 journal/record 并证明 one-shot replay 继续被 marker 拒绝；新增窗口固定
directory `fsync` 后、pending cleanup 前的同 inode committed residue。shipping CLI
不接受 crash injection 参数。该证据只覆盖 tested local POSIX filesystem、cooperative
writer 与 loopback provider，不是断电、NFS、真实 provider 或真实账单证明。S4 的
Task Pack 004 已冻结第一组 reference-grounding Verifier 对照设计；
独立 `apps/offline-harness-runner` 现在可以从固定路径严格加载该 Pack，并以 raw hash、
完整语义、依赖 allowlist 与 production bytecode gate 拒绝漂移或网络/进程逃逸路径。
固定 Runner 已在 4 cases × 3 repetitions 上生成 12 个 immutable shared candidates，
由 isolated H0 与 production H1 各验证一次，共完成 24 次 VerifierEvaluation；独立
verifier 不调用 Runner/generator，而是重建 candidate、重跑两臂并重算 matrix、IDs、
hash 与 owned counters，得到 `VERIFIED_PASSED`。这个结果只证明 deterministic
reference-grounding discrimination 与 replay equivalence；它不是 24 个 product
`AgentRun`，也不证明真实模型、product `capture.read`、历史进程 provenance 或系统级
零副作用。commit `a2cb02b` 又把相同结果编码为 28,343-byte canonical report：
packaged `--execute` 通过 claim、pending fsync/read-back 与 hard-link create-only
commit 写入 owner-local state，两个 fresh JVM 的只读 `--verify` 均从相同 bytes
independent replay 得到 `VERIFIED_PASSED`。双 packaged writer 只有一个物理
publisher；7-point process-kill matrix 中，pre-link incomplete evidence 保持
`UNKNOWN`，同 inode committed residue 和 clean final 保持 `FINAL`。pending 无
claim、不同 inode、unsafe path 或不可信 authoritative target 才是 `INVALID`。
这仍只是 tested local POSIX filesystem 上的 cooperative synthetic evidence，不是
断电/NFS durability、hostile-local-user authorization、signature、producer
attestation 或产品 Receipt。完整回执见
[Stage 2 S3 Durable Attempt Build Note](./docs/operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)
、
[Stage 2 S3 Eval Run Record Create-only Build Note](./docs/operations/build-notes/2026-07-31-s2-s3-eval-run-record-create-only.md)
与
[Stage 2 S4 Verified Offline Comparison Build Note](./docs/operations/build-notes/2026-07-30-s2-s4-verified-offline-comparison.md)，
durable 增量见
[Stage 2 S4 Durable Offline Comparison Build Note](./docs/operations/build-notes/2026-07-30-s2-s4-durable-offline-comparison-report.md)。
commit `33d1b9f` 又完成 S4 的第一个 Tool fault：OpenAI/其他 Model adapter 只传递有界、
不可变且 redacted 的 raw arguments；`capture.read` 必须在 Tool-owned strict
validation 中得到 typed arguments，registry 与 Task authority 都通过后才可 dispatch。
Pack 005 证明 extra-property fault 保留一次可归因 Model step 与
`RUNNING → FAILED` truth，但 Tool execute、Tool-backed Capture read 和 Artifact 均为
0。完整回执见
[Stage 2 S4 Tool Arguments Fault Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-tool-argument-fault.md)。
Pack 006 又固定 read-only Tool 的 post-dispatch deadline truth：5ms deadline 下，
4ms control 正常完成；7ms fault 虽已 dispatch/read，却不接受 late result、不形成
Evidence/Artifact，并以
`FAILED / TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH` 和
`MODEL_STEP → TOOL_REQUEST → TOOL_REJECTED(DEADLINE_EXCEEDED)` 结束。actual latency、
Trace 与 terminal truth 可以跨 PostgreSQL fresh-store read 保持一致；Model
attribution/usage 则由 product aggregate 与 Eval Runner regression 保真。exact boundary
与 cancellation precedence 也有 adversarial regression。完整回执见
[Stage 2 S4 Post-dispatch Deadline Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-post-dispatch-deadline.md)。
Pack 007 在普通 `apps/api` 的 deterministic Fake 路径加入一个 server-owned、
serial、synchronous、`depth=1` 的 typed read-only Worker：parent Conductor 发出
`WorkerCall`，registered Worker 只读 parent 已授权的同一 owner-scoped Capture，
proposal 先作为 `WorkerResultEnvelope` 独立持久化，parent verified consume 后仍是唯一
Artifact writer。V6 保存 parent/child relation、`WORKER_RESULT` 与 `HANDOFF` binding；
PUBLIC synthetic control、context-policy drift、PostgreSQL constraint、真实 JVM
process-kill 和两个 fresh JVM read-back 已形成可重放证据。这不是通用 multi-agent、
parallel Worker、checkpoint/resume、lease/fencing、live model、write-capable Worker
或用户价值证明。
Pack008 继续把 OpenAI model route 收缩到 exact child Worker：parent 仍是
non-model-bound Fake Conductor、没有 Tool authority；child Task 1.1 单独绑定
model/pricing/environment/profile identity。因为 parent 不产生 provider usage，
其 Result 的 cost/token 必须与唯一 child exact-equal，不能同步重算出额外计量。
独立 Eval CLI 新增显式
`--worker-preflight`，只核验 hash-frozen PUBLIC synthetic graph，保持零 key read、
无 credential/client/model/provider invocation construction path、零 Run/marker；
指定 local HTTP sentinel 收到 0 request，但这不是 system-wide socket
instrumentation。test-only integration 才用 process-local graph permit 与 loopback
`HttpServer`。V6 PostgreSQL reader 可同时解释 Pack007 与
Pack008 terminal truth，并拒绝 registration-order fallback。Pack008 目前没有
live execute route、durable graph marker/journal、fresh-JVM/process-kill 或普通 API
wiring，不能把这项 baseline 描述成真实模型已经运行。设计与证据边界见
[RFC-0005](./docs/rfcs/0005-model-bound-read-only-worker-eval-baseline.md) 与
[Pack008 Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-model-bound-read-only-worker-baseline.md)。
Pack009 新增独立 `apps/graph-eval-runner` 与 PostgreSQL V7 canonical graph attempt：
fixed PUBLIC synthetic graph 的 manifest、exact parent/child Run/profile binding、
append-only hash-chain journal、CAS head与 one-shot execution slot都以 PostgreSQL为
唯一 truth。独立 loopback provider durable 接收一次 request后，父测试真实强杀
writer；两个 fresh verifier exact-equal返回
`VALID / INCOMPLETE / billing UNKNOWN`，least-authority replay与完整 writer replay
都不能产生第二次 request。shipping App仍只有 zero-effect preflight/help；
`--verify` 是明确 disabled的保留参数，`--execute` 不存在。本证据使用 synthetic
console/key/provider，不是 real TTY、external provider、真实模型结果或账单；V7
terminal-seal table当前也是 disabled skeleton，不能描述成 terminal graph capability。
shipping class Gate对全部 App编译输出使用 package-independent exact allowlist，
并覆盖 multi-release JAR与全部 test-class resources。设计与回执见
[RFC-0006](./docs/rfcs/0006-postgresql-canonical-one-shot-graph-attempt.md)、
[ADR-0010](./docs/architecture/decisions/0010-postgresql-canonical-graph-attempt.md) 与
[Pack009 Build Note](./docs/operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)。
Pack010 以 additive PostgreSQL V8把每次 graph扩展到 exact 两次 provider
request attribution、child/parent terminal truth、Artifact lineage与 sequence-17 seal，
并用 restricted repeatable-read reader从三个独立 offline synthetic repetition投影
complete-only `HarnessEvaluationReport`。37 个 kill boundary、两个并发 JVM的 CAS
one-winner、tamper/generic Store negative与 fresh reader均已有可执行证据；shipping App
仍只有 zero-effect preflight，`--execute`继续 disabled。新增 production owner-TTY facade、
private local permit object one-winner、本地真实 PTY negative matrix、前驱 repetition原子
claim；forward-only V9又把 generic writer与 `graph_executor`隔离在 raw GUC/DML、
ACL/membership/owner/trigger drift之外，只有两个 exact SECDEF semantic transaction可完成
TX-B/TX-C。successor另有 fat-JAR-first production bytecode + test-only shell的
`AFTER_HEAD_UPDATE` hard-kill rollback/two-JVM one-winner evidence；这些
synthetic harness证据不等同于 owner逐次批准，也不包含真实 key/provider/model/token/cost。
V8 custom GUC仍只是 legacy compatibility guard，V9 independent guard不信任它。production
artifact现已包含 Main不可达的 exact credential broker/session composer；descriptor-exact
directory/shaded-JAR Gate冻结其唯一 effect consumer、transport policy与 reviewed bootstrap，
本机 loopback又证明 intent persistence failure时 HTTP为0、attribution fail-closed后 replay
不会增加 request/effect。owner-approved cursor现在可通过同进程、不可重建的线性 capability
派生 exact Coordinator/egress/terminal typestate，独立 role/ACL provisioning artifact也已具备
同 transaction bootstrap + 全 grantee pure audit、幂等 read-back与 drift fail-fast；两者都不表示
跨 JVM resume。sequence-7 egress又新增 exact durable provider-session intent；packaged TTY/JVM
已证明 owner/attempt/revision/coordinator/egress/first-request/expiry绑定、claim/consume并发唯一胜者、
expiry/replay，以及 commit前/后 hard-kill + restart fail-closed。broker bytecode要求该 intent先于
credential/client/model/session/HTTP effect，shipping Main仍不可达。dormant V9 runtime writer
composition不再暴露 generic writer，并已用 exact owner terminal claim + fixed prefix/executor
roles证明 attribution-before-terminal、cross-attempt/two-database owner/runtime splice、
claim-to-semantic expiry与 alternating-identity fail closed，并拒绝 startup前 semantic/trigger及
transitive helper closure drift、`tgattr`列级 trigger drift和 startup后的 prefix TEMP/ACL drift；
expiry与 semantic call在同一 SQL statement复核；TX-B/TX-C各一次及 PostgreSQL process restart后
session-intent/cursor/attribution/terminal reconciliation；
credential lease现在保留 durable intent/owner/expiry，并在 key/client/model/session每个 effect
boundary前复核本地 Clock与 DB time；OwnerTty Coordinator只能使用 authority-bound Store，
且 runtime要求 OwnerTty identity与 exact prefix direct-login identity一致，admin/migrator不能
凭同库 identity进入。provider-session cursor从数据库 row重建并受 canonical event复合外键约束，
session-intent relation出现额外非 internal trigger会在 provisioning/runtime audit中 fail closed；
session compose后仍由同一 lease在 `next`与 exact pre-HTTP observer双重复核 expiry；全部35个
public non-internal trigger由 shipping SHA-256 topology固定，preflight/pure audit与 terminal
runtime startup/每 TX还共享16-helper signature/properties/search_path/body SHA-256 closure；
它在 bytecode中仍对 Main不可达，
	也不声称 provider exactly-once。production provider完整 response attribution现已在exact
   content-decoded bytes hash、完整token split、durable-before-semantic ordering、PostgreSQL restart
	与fresh packaged JVM no-replay matrix上focused Green；actual success structured-final又已绑定为
	带 exact lease/Coordinator/egress/manifest/attribution/expiry的process-local opaque outcome，并只能
	one-shot派生 typed TX-B command。runtime在claim前要求全部 V9 row exact keys，并以 contract
	constructor重算 Candidate/WorkerResult integrity，再重建完整child AgentRun/binding/event 15/
	sequence-15 snapshot并exact绑定Trace/resource mirror/Run relation；event audit timestamp由PostgreSQL
	生成。forged nested evidence、extra key、duplicate/
	trailing JSON均不烧毁 outcome。actual PUBLIC loopback outcome已在同一 capability链进入 exact
	PostgreSQL TX-B并通过 restart reconciliation；wrong model、usage超限、malformed final、expiry、
	replay/concurrency与 outcome-mint hard-kill均 fail closed。successful sequence 15现在又只能先经
	strict parent aggregate review派生 private-constructor、one-shot typed TX-C command；Owner facade
	自行read-back并绑定 exact durable seq15，forged/missing/nested/seal/cross-run/duplicate/trailing均在
	claim前拒绝；完整 parent AgentRun/ArtifactLineage/sequence-17 snapshot与全部 relation mirror value
	必须通过 Core aggregate invariant，event audit timestamp由PostgreSQL在TX-C内生成。两个 commands并发只有一个seq17 winner，PostgreSQL restart后 reconciliation保持
	一致。production App与public writer现已移除raw terminal payload ABI；PostgreSQL adapter从typed
	AgentRun/Candidate/WorkerResult/ArtifactLineage与verified seq14/15 snapshot生成private-constructor
	one-shot canonical TX-B/TX-C transition，wrong typed truth在owner claim前fail closed，并已覆盖child/
	parent并发一个winner与PostgreSQL restart reconciliation；forward-only V10进一步把 active
	`graph_executor` surface收敛为 exact child/parent semantic pair：V9只保留历史定义且 executor
	`EXECUTE=0`，fresh migration自行撤销 PUBLIC EXECUTE，独立 provisioning以 SHA-256、全 grantee
	ACL与trigger/helper closure fail-fast。failure child只接受 Core closed allowlist，failed parent只接受
	exact `FAILED/HANDOFF_CHILD_FAILED/HANDOFF_REJECTED/CHILD_FAILED`映射；两个独立 PostgreSQL
	session在 TX-B/TX-C各只有一个 winner，restart后保持 seq14→15→17且无 partial truth。该证据仍是
	PUBLIC synthetic、dormant composition，不是live authority。forward-only V11又把request-2 typed
	failure verdict与sequence-14 attribution在同一个PostgreSQL transaction内落为durable provenance，
	并以dedicated NOINHERIT resumer、state version、DB-clock lease、session expiry和exact head实现
	two-JVM one-winner、hard-kill、PostgreSQL restart与lease-expiry reclaim。forward-only V12再把
	exact provenance/version/claimant/fence/lease/head与failure TX-B/TX-C terminal truth分别放进同一个
	PostgreSQL transaction，fresh JVM completion、commit前hard-kill全库回滚、PostgreSQL restart与
	child/parent two-JVM race均focused Green，并以durable receipt拒绝raw V10 failure bypass。该证据仍是
	PUBLIC synthetic Engineering slice。forward-only V13进一步以DB-minted one-shot challenge、
	test-only Ed25519、dedicated attestor role/JVM与bounded transcript，把真实loopback reviewed outcome、
	exact manifest execution binding、request-2 attribution/event/head及closed failure outcome原子绑定；
	raw bypass、tamper/replay/expiry/cross-attempt/cross-DB、commit前hard-kill、restart与two-JVM race均
	fail closed，且不保存raw provider bytes或private key。该local-only semantic attestation slice已
	focused Green；PostgreSQL-native验签、production key custody、shipping live route与真实r1/r2/r3
	仍未完成。V14随后只建立exact-pico profile assertion，V15只在legacy seq13登记requirement并
	fence历史TX-A。当前V16 focused local Gate以`RAW_JDBC_LOCAL_OVERLAY_TX_A`在独立relations中形成
	exact-pico attribution/event/overlay head14；从每次authority call的immediate baseline起，
	V8/V13/V15 JSON/`xmin`与legacy head13不变；
	overlay head14不是legacy head14。PostgreSQL不验证Ed25519，只有test JVM使用ephemeral key完成本地
	签名/验签；这是V16冻结时的历史边界。V17随后只增加可打包但未接线的production
	Ed25519 public-key verifier primitive；V16 login credential、attestor role与caller process仍在
	TCB，V18再增加一个packaged但App未接线的typed stage→verify→commit adapter；public Java raw
	stage/commit method、shipping signer、App consumer与PostgreSQL-native验签仍为0。
	synthetic GPT profile只验证pico-USD精度，
	不是provider定价。V16、V17、V18与V19 focused local Engineering Gate均已Green；V19新增专属13表SELECT
	只读角色与fresh-JVM四态reader，并在单一RR/RO snapshot独立重算V13-V16 canonical与Ed25519。
	V20另以test-only独立keepalive Testcontainer证明同container/system identifier/PGDATA上的PostgreSQL
	immediate restart后，另一fresh packaged JVM仍重验同一`Attributed` receipt；production delta为0。
	legacy head13与App consumer保持不变，overall Authority/Live仍Red；
	TX-B/TX-C与pre-egress
	未实现，host/power/storage/HA、connection-loss/reconcile/race、
	live与billing均未证明。
设计和边界见
[RFC-0007](./docs/rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)、
[ADR-0011](./docs/architecture/decisions/0011-attributed-terminal-graph-and-live-harness-pilot.md) 与
[RFC-0008](./docs/rfcs/0008-bounded-provider-validation-attestation.md)、
[ADR-0012](./docs/architecture/decisions/0012-db-authenticated-provider-validation-attestation.md)、
[RFC-0009](./docs/rfcs/0009-exact-provider-profile-assertion-foundation.md)、
[ADR-0013](./docs/architecture/decisions/0013-exact-provider-profile-assertion-foundation.md)、
[RFC-0010](./docs/rfcs/0010-exact-provider-tx-a-requirement-guard.md)、
[ADR-0014](./docs/architecture/decisions/0014-exact-provider-tx-a-requirement-guard.md)、
[RFC-0011](./docs/rfcs/0011-exact-pico-provider-tx-a-overlay.md)、
[ADR-0015](./docs/architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md)、
[RFC-0012](./docs/rfcs/0012-dormant-v16-ed25519-public-verifier.md)、
[ADR-0016](./docs/architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md)、
[RFC-0013](./docs/rfcs/0013-dormant-typed-v16-stage-verify-commit-attestor.md)、
[ADR-0017](./docs/architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md)、
[RFC-0014](./docs/rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md)、
[ADR-0018](./docs/architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md) 与
[Pack010 offline Build Note](./docs/operations/build-notes/2026-08-01-s2-s4-pack010-terminal-graph-harness-report.md)、
[Pack010 Authority Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-owner-tty-predecessor-authority.md)、
[Pack010 V9 Authority Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-v9-terminal-authority.md)、
[Pack010 Dormant Provider Capability Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-dormant-provider-capabilities.md)、
[Pack010 Capability Handoff / Provisioning Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-capability-handoff-provisioning.md)、
[Pack010 Exact Provider Attribution Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-exact-provider-attribution.md)、
[Pack010 typed TX-C Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-typed-tx-c.md)、
[Pack010 Terminal Outcome Binding Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-terminal-outcome-binding.md)、
[Pack010 V13 Provider Validation Build Note](./docs/operations/build-notes/2026-08-11-s2-s4-pack010-bounded-provider-validation-attestation.md)、
[Pack010 V14 Provider Profile Assertion Build Note](./docs/operations/build-notes/2026-08-11-s2-s4-pack010-v14-exact-provider-profile-assertion.md)、
[Pack010 V15 Exact TX-A Requirement Guard Build Note](./docs/operations/build-notes/2026-08-12-s2-s4-pack010-v15-exact-tx-a-requirement-guard.md)、
[Pack010 V16 Exact-pico Provider TX-A Overlay Build Note](./docs/operations/build-notes/2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md)、
[Pack010 V17 Dormant V16 Ed25519 Verifier Build Note](./docs/operations/build-notes/2026-08-12-s2-s4-pack010-v17-dormant-v16-ed25519-verifier.md)、
[Pack010 V18 Dormant Typed V16 Attestor Build Note](./docs/operations/build-notes/2026-08-12-s2-s4-pack010-v18-dormant-typed-v16-attestor.md)、
[Pack010 V19 Fresh-JVM Exact-pico Overlay Reader Build Note](./docs/operations/build-notes/2026-08-12-s2-s4-pack010-v19-fresh-jvm-exact-pico-overlay-reader.md)、
[Pack010 V20 PostgreSQL Immediate-restart Overlay Readback Build Note](./docs/operations/build-notes/2026-08-12-s2-s4-pack010-v20-postgres-immediate-restart-overlay-readback.md)、
[Pack010 Canonical Terminal Transitions Build Note](./docs/operations/build-notes/2026-08-02-s2-s4-pack010-canonical-terminal-transitions.md)。
[Pack010 V10 Attributed Failure Authority Build Note](./docs/operations/build-notes/2026-08-09-s2-s4-pack010-v10-attributed-failure-authority.md)。
[Pack010 Durable Attributed Failure Resume Build Note](./docs/operations/build-notes/2026-08-09-s2-s4-pack010-durable-attributed-failure-resume.md)。
Temporal、生产认证、加密存储和平台连接器仍未接入，不能把这条工程路径理解为生产自治能力。

API 默认只监听 `127.0.0.1`，并在未认证阶段拒绝非 loopback 绑定。所有请求都被当作服务端配置
中的单用户 `local-user`，没有实现登录或多租户身份验证。主体不接受 Header 或请求体覆盖，
读取也按该主体查询，但这仍不是认证。不要把它暴露到局域网或公网；在加密持久化与
Secret Broker 落地前，持久化 Capture 会拒绝 `SENSITIVE` 和 `SECRET` 输入。Artifact 原型
没有独立的数据分类或加密边界，只能使用合成、非敏感内容。

## 系统原则

- **One Self, Many Workers**：一个可审阅、可纠正的 Self Model；多个短命、最小权限 Worker。
- **Context is a projection**：Working Self 是任务视图，不是人格真相。
- **Evidence before inference**：经历与凭证是事实，人格是可撤销推断。
- **No receipt, no completion**：没有外部回执，就不能声称行动已经完成。
- **Evolution is evaluated**：Prompt、Skill、模型和策略只能经评测、灰度与回滚演进。
- **Human authority remains explicit**：公开发布、消息、删除等动作必须经过明确授权。

## 当前可运行闭环

```text
思想种子
  → EvidenceEvent
  → WorkingSelf 投影
  → Artifact 草稿
  → 等待批准
  → 本地 Action Stub
  → Receipt
  → ReflectionCandidate
```

Stage 0 Manifestation 闭环仍刻意使用内存存储和确定性 Stub。Stage 1 已把独立的 Capture、
Artifact lineage 和 local ActionAttempt/Receipt 边界替换为 PostgreSQL；S3 Action 只连接
loopback-only 模拟 Provider；S4 只增加薄 operations/readiness 边界和迁移/恢复证据，没有
增加通用运维平台。Stage 2 S2 的 additive V4 新增三张 AgentRun/Trace/binding 表，并通过
V1/V2/V3 → V4 升级与恢复演练保持旧 truth；V4 同时为 Capture identity/request hash
增加 composite unique constraint。Stage 2 S3 的 additive V5 不新增业务表，只加入
`model_provider`、`model_requested`、`pricing_profile` 三个 typed columns；真实模型
Task 1.1 的 routing/pricing identity 必须与 Task JSON 双向一致，而历史 Task 1.0 JSON
与 Bundle hash 不被改写。Pack 007 的 additive V6 新增 `agent_worker_results`，并在
AgentRun/Trace/binding truth 上冻结 exact-one-child、same-owner、terminal、hash-chain
与 single-consume 约束；V1–V5 历史 JSON 与 hash 不被改写。
Pack009 的 additive V7再增加五张 graph attempt truth tables，并为
`agent_runs` 增加 all-or-none graph selector；V1–V6 historical rows保持 selector
全 NULL，不猜测或回填。Pack010 的 additive V8再增加 request attribution与 terminal
binding truth，并以 forward-only migration保持 V1–V7 historical bytes不被猜测或改写。
forward-only V9新增 durable provider-session intent，并把 caller-minted custom GUC permit
替换为 exact schema-qualified SECURITY DEFINER semantic authority；production role/ACL bootstrap
仍独立于 Flyway。forward-only V10收窄 failure terminal semantic protocol并撤销 executor对V9
function的执行权；V9只作为历史 migration保留。forward-only V11新增durable typed failure
provenance与dedicated cross-process claim fence；forward-only V12再把该 fence 与 failure
TX-B/TX-C terminal truth 原子绑定并拒绝 raw V10 failure bypass；forward-only V13新增
DB-authenticated bounded provider-validation attestation与一次性challenge/receipt，仍明确把
attestor JVM/DB identity纳入TCB且不启用shipping live route。V14仅新增
exact provider/pico-USD profile assertion authority，不提供attestation、TX-A或live route。
V15再增加seq13 exact-pico TX-A requirement marker与deferred database guard：已登记attempt不能继续
写入历史V8/V13 request-2 TX-A，而未登记attempt保持V13行为不变。V15仍不提供exact-pico
attribution、overlay head、TX-A或shipping/live route。当前 schema version 是 V16；它只以独立
V16 relations增加raw-JDBC local exact-pico attribution/event/head overlay，legacy V8 head继续停在
seq13，旧`GraphAttemptSnapshot`语义不变。PostgreSQL不验证Ed25519；V17冻结时只新增dormant
public-key verifier primitive，production stage/commit Java API、shipping consumer与live route均为0。
	V18另增packaged typed `complete` adapter，但App consumer与signer implementation为0，external
	configuration/runtime invocation未证明。V19另增独立四态overlay reader与专属只读role；V20只新增
	test-only same-container PostgreSQL immediate-restart readback；V16-V20 focused local Engineering evidence均已Green；
	overall Authority/Live仍Red。

当前普通 API 仍只装配 deterministic Fake；Pack 007 Agent 路径是：

```text
owner-scoped PostgreSQL Capture
  → server-owned parent Task + Fake Conductor
  → typed WorkerCall
  → registered child Task/Run（serial，depth=1）
  → capture.read
  → durable WorkerResult + terminal child Trace/Bundle
  → verified parent HANDOFF
  → deterministic verification
  → parent-only Artifact + terminal parent Run
```

## 快速开始

要求：Java 21、Node.js 22、可用的 Docker 和 PostgreSQL。仓库自带 Maven Wrapper；Node
用于校验公共 JSON Schema，Docker 用于 Testcontainers 验收。

```bash
npm ci
./scripts/verify-contracts.sh
./mvnw --batch-mode --no-transfer-progress verify

# 默认只做 zero-egress preflight；不会读取 OPENAI_API_KEY
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar

# Pack008 child-only model Worker 也只做 zero-egress preflight；没有 execute route
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar \
  --worker-preflight

# Pack009 durable graph App 只做 zero-effect preflight；verify/execute均未开放
java -jar \
  apps/graph-eval-runner/target/emerge-graph-eval-runner-0.1.0-SNAPSHOT-app.jar \
  --preflight

# 在临时 owner home 执行 fixed Pack 004 并由 fresh invocation 只读重放
comparison_home=$(mktemp -d /tmp/emerge-comparison.XXXXXX)
chmod 700 "$comparison_home"
java -Duser.home="$comparison_home" \
  -jar apps/offline-harness-runner/target/emerge-offline-harness-runner-0.1.0-SNAPSHOT-app.jar \
  --execute
java -Duser.home="$comparison_home" \
  -jar apps/offline-harness-runner/target/emerge-offline-harness-runner-0.1.0-SNAPSHOT-app.jar \
  --verify

# clean-checkout、packaged JVM、独立 Fake Provider 的 Stage 1 operating demo
./scripts/run-stage1-operating-demo.sh

docker run --detach --rm --name emerge-postgres \
  -e POSTGRES_DB=emerge \
  -e POSTGRES_USER=emerge \
  -e POSTGRES_PASSWORD=local-prototype-only \
  -p 127.0.0.1:5432:5432 \
  postgres:18.4-alpine

EMERGE_DB_URL='jdbc:postgresql://127.0.0.1:5432/emerge' \
EMERGE_DB_USER='emerge' \
EMERGE_DB_PASSWORD='local-prototype-only' \
java -jar apps/api/target/emerge-api-0.1.0-SNAPSHOT.jar
```

`apps/eval-runner` 是隔离的 synthetic verification 入口，不是产品 API，也不是 CI
中的 live 命令。只有项目所有者明确批准唯一一次 bounded smoke 后，才可在真实 TTY
手工运行：

```bash
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar --execute
```

它会先显示 frozen hashes、最大两次 provider request 与 `$0.417000` requested
reservation（调用前 authorization ceiling，并非 provider invoice 的 hard cap），
随后要求输入完整 `EXECUTE {attemptId}`。marker 在 challenge 前以 `CREATE_NEW` 取得，
所以输错 challenge 也会烧掉该 attempt；不要删除 marker 来伪造“重试”。本机状态位于
`~/.emergeos/eval-attempts/`：目录为 `0700`，marker、journal 和 terminal record 为
`0600`。`billingStatus=UNKNOWN` 时，即使 `observedCostUsd=0`，也只表示费用未被可靠
观测，绝不表示 provider 没有计费。当前仓库没有 live report。

S3 Action API 还要求另行启动测试用的 loopback Fake Provider。默认地址故意指向不可用的
`127.0.0.1:1`，因此普通快速启动不会产生模拟对象；若批准 Action，只会得到可解释的
`UNKNOWN`，不会伪造成功 Receipt。完整外部进程与故障恢复证据由 packaged-process 验收测试
提供，不把 test fixture 包装成可用 Connector。

另开终端。liveness 表示进程可服务；readiness 还会检查 PostgreSQL、Flyway 与当前
server-configured principal 的 unresolved Action：

```bash
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8080/actuator/health/readiness

curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "clientNonce": "local-demo-001",
    "content": "把我关于长期个人 Agent 的想法留到重启后继续",
    "sourceType": "TEXT",
    "sourceRef": "local-demo",
    "dataClass": "PERSONAL"
  }' \
  http://localhost:8080/api/v1/captures

# 用上一步的 captureId 创建 Artifact v1
curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "captureId": "{captureId from the latest response}",
    "intent": "把这个想法整理成一篇有证据的中文文章"
  }' \
  http://localhost:8080/api/v1/agent-drafts

# 使用 POST response 的 runId 读取持久执行真相
curl -s http://localhost:8080/api/v1/agent-runs/{runId}
curl -s http://localhost:8080/api/v1/agent-runs/{runId}/trace
curl -s http://localhost:8080/api/v1/agent-runs/{runId}/bundle

# 从 parent Bundle 的 handoffRefs 取得 agent-run://{childRunId} 后，只读 child truth
curl -s http://localhost:8080/api/v1/agent-runs/{childRunId}
curl -s http://localhost:8080/api/v1/agent-runs/{childRunId}/trace
curl -s http://localhost:8080/api/v1/agent-runs/{childRunId}/bundle

# 也可以绕过 Fake Agent，直接用确定性内容创建 Artifact v1
curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "captureId": "{captureId from the latest response}",
    "content": "把这个想法整理成一篇有证据的文章"
  }' \
  http://localhost:8080/api/v1/artifacts

# 修订必须绑定当前版本和服务端返回的 currentHash
curl -s \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Artifact v2",
    "expectedBaseVersion": 1,
    "expectedBaseHash": "{currentHash from the latest Artifact response}"
  }' \
  http://localhost:8080/api/v1/artifacts/{artifactId}

curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "把我关于长期个人 Agent 的想法整理成一篇有证据的文章",
    "sourceType": "VOICE",
    "sourceRef": "local-demo",
    "dataClass": "PERSONAL"
  }' \
  http://localhost:8080/api/v1/manifestations
```

响应会返回 `manifestationId`、草稿和待批准动作。再调用：

```bash
curl -s \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
    "artifactHash": "{artifact.contentHash from the latest response}"
  }' \
  http://localhost:8080/api/v1/manifestations/{manifestationId}/approve
```

当前没有公开 WorkerResult content 的 HTTP endpoint；它只存在于 owner-scoped
PostgreSQL truth，并通过 child `WORKER_RESULT` binding、parent `HANDOFF` 与 hash chain
间接核验。不要把 Run/Bundle read API 描述成 WorkerResult content API。

Capture、Agent 生成的 Artifact、AgentRun、safe Trace、HarnessRunBundle、
WorkerResult、parent/child relation 与独立 Artifact lineage 写入本机 PostgreSQL。
Manifestation 仍只写入应用进程内存中的草稿回执。独立 local ActionAttempt/Receipt
也写入 PostgreSQL，但只在测试中访问 loopback Fake Provider 并产生模拟对象。它们都不会
访问或发布到任何真实外部平台。

状态含义、`UNKNOWN` 恢复入口、迁移/备份演练和明确限制见
[Stage 1 Operating Runbook](./docs/operations/stage1-operating-runbook.md)。

## 仓库地图

```text
apps/api/                 HTTP 入口与依赖装配
apps/eval-runner/         隔离 synthetic model Eval；Pack003 default/execute 与 Pack008 preflight 分离
apps/graph-eval-runner/   Pack009 PostgreSQL graph preflight；shipping verify/execute禁用
apps/offline-harness-runner/ 固定 Pack 004 comparison、canonical durable report、packaged CLI 与独立 replay verifier
modules/contracts/        跨 Agent、工具、人类边界的稳定契约
modules/core/             纯 Java 领域、用例、AgentKernel 与端口
adapters/inmemory/        Fake Conductor/Worker、有限 Agent loop 与 Offline Pack replay
adapters/postgres/        Capture、Artifact、Action、AgentRun/Trace/WorkerResult 的 PostgreSQL 适配器
adapters/openai/          隔离 Responses adapter；已接 Eval Runner、未接产品 API，当前无 live receipt
contracts/                跨语言 JSON Schema
evals/                    Pack 001–009 合成任务、Harness 对照与故障回归证据
docs/                     产品、架构、研究、运营和共同治理
```

依赖只允许由外向内：

```text
api → fake/postgres adapters → core → contracts
eval-runner → openai/agent-loop adapters → core → contracts
graph-eval-runner → postgres/openai/agent-loop adapters → core → contracts
offline-harness-runner → core → contracts
```

核心层不能依赖 Spring、Temporal、AgentScope、数据库或模型 SDK。

## 从哪里读起

1. [文档地图](./docs/README.md)
2. [系统架构](./docs/architecture/system-overview.md)
3. [第一条纵向闭环](./docs/product/first-vertical-slice.md)
4. [公共契约](./contracts/README.md)
5. [Harness 验证计划](./docs/research/harness-validation.md)
6. [产品蓝图](./PRODUCT-BLUEPRINT.md)
7. [Agent Fabric 历史候选设计](./AGENT-FABRIC.md)
8. [路线图](./ROADMAP.md)

## 参与共同进化

贡献不只包括代码，也包括评测任务、故障案例、交互设计、研究、文档和翻译。先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [GOVERNANCE.md](./GOVERNANCE.md)。

共同进化的对象是代码、协议、评测和方法，绝不是任何用户的真实人格与生活数据。禁止在 Issue、PR、测试样例或 Trace 中提交真实用户数据、凭据或可识别个人的信息。

## 为什么做，以及怎样不偏航

项目同时追求 AI Coding 能力、Agent Engineering 能力、生产级产品、可信职业作品与商业结果。
每个任务留下相关证据，每个 Roadmap Stage/Release Gate 同时检查这五项结果。具体见：

- [Five-Outcome Charter](./docs/strategy/five-outcome-charter.md)
- [Product-driven Curriculum](./docs/learning/curriculum.md)
- [Development Method](./docs/engineering/development-method.md)
- [Codex Playbook](./docs/engineering/codex-playbook.md)
- [Business Validation Roadmap](./docs/business/validation-roadmap.md)

## 许可证与贡献

EmergeOS 使用 [Apache License 2.0](./LICENSE)。外部贡献采用
[Developer Certificate of Origin 1.1](./DCO)，每个贡献 commit 需要
`Signed-off-by`。决策与边界见
[ADR-0019](./docs/architecture/decisions/0019-open-source-license-and-contributions.md)；
项目名称与品牌使用见 [TRADEMARKS.md](./TRADEMARKS.md)。
