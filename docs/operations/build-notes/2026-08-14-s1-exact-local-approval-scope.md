# S1 Exact Local Action Approval Scope Build Note

Date：2026-08-14

Stage：Stage 1 / 产品闭环增量

状态：`focused + root clean + exact code-head CI Engineering Green；Draft PR #6；overall Authority / Live Red`

## Outcome

从当前、owned Artifact 出发，loopback-only 页面现在会先读取一个只读 approval-scope preview，
在浏览器中独立重算 canonical SHA-256，向用户展示精确行动、目标、风险、Artifact 版本/hash 与
授权边界，然后才开放“批准”按钮。POST 必须同时提交 current Artifact version/hash 与 exact
`scopeSchema/scopeHash`；成功只持久化一条 `PLANNED` ActionAttempt，返回
`executionState=NOT_EXECUTED`，不会调用 provider、不会产生 effect 或 Receipt。

本切片把“批准了哪个当前成果、由什么输入来源、将走哪条执行路线”冻结为可审计 bytes；它没有实现
Local Draftbox execute/undo，也没有证明 authenticated end-user identity、production authority、
真实 Connector/provider、Live 或商业价值。代码与当时文档已形成 DCO commit
`27da843dcb9deb9ae4008f1812dafd537a748cf3`，并通过非 force push推送至 feature branch，形成
[Draft PR #6](https://github.com/striveh/emergeos/pull/6)；下文 CI结论只绑定该 exact code head。
后续 docs-only head不改变这份 code evidence；PR当前 check状态以GitHub为准，本Note不预判或追写
每个docs-only head。

## Acceptance Red -> minimum implementation

首个 packaged Acceptance 使用真实 PostgreSQL 18.4 与 fat API JAR，从 Capture 创建 current Artifact，
随后请求：

```text
GET /api/v1/artifacts/{artifactId}/action-approval-scope
    ?artifactVersion={currentVersion}&artifactHash={currentHash}
```

实现前测试到达 serving application 后精确失败为：

```text
EXPLICIT_LOCAL_APPROVAL_SCOPE_MISSING expected=200 actual=404
```

Red 命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am \
  -Dit.test=ExplicitLocalApprovalScopeHttpIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

minimum implementation只增加 scope domain、preview/approval binding、V17 authority seal 与现有
approval card 的可信显示；没有增加 execute endpoint、provider route、reconcile entry、Receipt writer
或外部 network effect。

## Canonical 16-field authority seal

`emergeos.action-approval-scope.v1` 使用同一 Java/PostgreSQL/browser 编码：schema UTF-8 bytes、一个
NUL 分隔符，然后按固定顺序写入 16 个值；每个值前置 uint32 big-endian UTF-8 byte length，最终取
lowercase SHA-256。固定顺序为：

1. principal basis 与 configured principal ID；
2. approval origin 与 execution route；
3. action type 与 target ref；
4. Artifact ID、version 与 hash；
5. risk 与 policy version；
6. connector、audience 与 account ref；
7. capability TTL micros 与 max calls。

当前显式用户输入只能形成
`EXPLICIT_LOCAL_OWNER_INPUT + LOCAL_DRAFTBOX_V1`；旧 HTTP action 路线只能形成
`LEGACY_SERVER_IMPLICIT + SIMULATED_PROVIDER_V1`。schema/hash 缺失为 `400`；wrong schema、wrong
hash 或 Artifact 在 preview 后变化均为 typed `412 approval-stale`，并在持久化前失败。preview 自身
为只读：它不创建 attempt、transition 或 Receipt。

## V17 migration, history and dual effect fence

forward-only V17为 `action_attempts` 增加 principal basis、configured principal、approval origin、
execution route、scope schema/hash 与 scope TTL。历史行统一标记为
`PRE_V17_UNPROVEN + SIMULATED_PROVIDER_V1`，绝不伪装成 explicit owner approval；TTL 从既有
`plan_expires_at - approved_at` 回填，只有 `PRE_V17_UNPROVEN` 允许 `TTL=0`，从而保留“批准时已经
到期”的历史 bytes 与原 plan hash。Java 与 PostgreSQL 对同一 16-field material 得到相同 scope hash。

数据库 CHECK 固定 provenance/route 组合、schema、hash 与 TTL；trigger 冻结 ActionAttempt authority
identity并把 transition history 变为 append-only。scope/schema/TTL/authority field 的 raw UPDATE、
attempt DELETE、transition UPDATE/DELETE 均被 PostgreSQL 拒绝。

provider effect 另有两道独立 fence：

- service 在 dispatch/reconcile 前只接受
  `LEGACY_SERVER_IMPLICIT + SIMULATED_PROVIDER_V1`；
- PostgreSQL atomic claim predicate再次要求同一 origin/route，且继续绑定 plan、Artifact、Capability、
  budget、expiry 与 state version。

因此 `PRE_V17_UNPROVEN` 的 `UNKNOWN` history，以及显式
`EXPLICIT_LOCAL_OWNER_INPUT + LOCAL_DRAFTBOX_V1` 的 `PLANNED/UNKNOWN` fixture，都不能被 dispatch
或 reconcile；拒绝前后 state version、transitions、used calls、Receipt 与 provider observations 不变。
这是真实 execute 到位前的 fail-closed fence，不是 Local Draftbox 已实现的证据。

## Approval card and privacy boundary

页面只有在以下条件全部成立后进入 `READY`：same-origin preview 为 strict JSON/no-store、shape 与固定
domain value精确、Artifact仍是 trusted current head、WebCrypto 重算 hash 与服务端 scope hash一致。
卡片显示 action、`Local Draftbox · local://drafts`、`REVERSIBLE（可撤销）`、policy、Artifact
version/hash 与 scope hash；configured principal ID不显示，只说明“绑定本机配置主体”，避免把本机
配置值冒充 authenticated identity。任何 timeout、丢响应、wrong MIME/cache/type/shape/hash、缺失
WebCrypto/AbortController 都进入 `UNKNOWN`，不发送 approval POST。

批准成功或 exact replay只显示 `PLANNED / NOT_EXECUTED / Receipt：无`。Artifact继续编辑会立即
invalidate旧 card；typed stale显示 `STALE`；transport UNKNOWN只允许用户以冻结的同 nonce/body显式
重试，无自动重试。UI 没有调用 action execute/reconcile surface，也不渲染完成或 Receipt 声明。

## Verification receipt

最终 focused reactor 命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am \
  -Dit.test=ExplicitLocalApprovalScopeHttpIT,ExactLocalApprovalHttpIT,ExactLocalApprovalUiHttpIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

结果：reactor `BUILD SUCCESS`，结束于 `2026-08-14T11:31:28+08:00`；Surefire为`560/0`，
三份 selected packaged Failsafe IT各为`1/0`，因此 aggregate为
`563 tests / 0 failures / 0 errors / 0 skipped / 0 flakes`：

- `apps/api/target/failsafe-reports/TEST-io.emergeos.api.ExactLocalApprovalHttpIT.xml`；
- `apps/api/target/failsafe-reports/TEST-io.emergeos.api.ExplicitLocalApprovalScopeHttpIT.xml`；
- `apps/api/target/failsafe-reports/TEST-io.emergeos.api.ExactLocalApprovalUiHttpIT.xml`。

关键 focused XML还包括：

- `modules/core/target/surefire-reports/TEST-io.emergeos.core.domain.ActionApprovalScopeTest.xml`
  为 `3/0`；
- `modules/core/target/surefire-reports/TEST-io.emergeos.core.application.RecoverableActionServiceTest.xml`
  为 `5/0`；
- `adapters/postgres/target/surefire-reports/TEST-io.emergeos.adapters.postgres.V17ActionApprovalScopeMigrationTest.xml`
  为 `1/0`；
- `adapters/postgres/target/surefire-reports/TEST-io.emergeos.adapters.postgres.PostgresActionAttemptStoreTest.xml`
  为 `11/0`。

Exact HTTP Receipt：

```text
first=201 replay=200 conflict=409 stale=412 foreign=404 race=200/201
responseLossRecovered=true restartGet=200 attempts=3 plans=3 approvals=3
transitions=PLANNED usedCalls=0 receipts=0 providerBase=unreachable
```

scope IT另验证 preview/POST/GET/DB hash逐字段一致、missing scope `400`、wrong/stale scope `412`、
explicit route只有一条 `PLANNED` transition且 used calls为0。UI IT在 packaged served HTML/JS上覆盖
create、replay、double-click、response loss、continuous edit、stale、malformed/hanging response、缺失
client capability、nonce conflict与 problem-response boundary，始终没有 execute/reconcile call。

fat JAR为 `apps/api/target/emerge-api-0.1.0-SNAPSHOT.jar`。source 与 JAR entry逐字一致：

```text
f1ac455bd79b6dc0e42a98c370878e738e15581f44dc046782a64a29da619085  capture/approval-card.js
074201797486a5a4804ecee54f11e1575d001b94071ed85e5a6f324020bc4470  capture/index.html
```

最终 root Gate 由唯一 release runner 串行执行：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

11/11 reactor `BUILD SUCCESS`，结束于 `2026-08-14T12:41:04+08:00`。最终 target 内为
`170 XML / 893 tests / 0 failures / 0 errors / 0 skipped / 0 flakes`；按 test-bearing module
分解为 contracts `59`、Core `210`、agent-loop `41`、OpenAI adapter `39`、in-memory `37`、
PostgreSQL `180`、API `50`、eval-runner `88`、graph-eval-runner `105`、offline-eval `84`。

关键 root reports：

- `ExactLocalApprovalHttpIT`、`ExplicitLocalApprovalScopeHttpIT`、
  `ExactLocalApprovalUiHttpIT` 与 `RecoverableLocalActionHttpIT` 各为 `1/0`；
- `Pack010ProviderCapabilityBytecodeGateTest` 为 `15/0`；
- `Pack010V18TypedAttestorShippingJarIT` 为 `1/0`；
- `Pack009DurableGraphCrashProcessIT` 为 `2/0`。

root integration 经过两个有界 failure round：首轮暴露 `RecoverableLocalActionHttpIT` 的 schema-16
expectation与 V18 raw-role path 两个独立兼容性 Red，各只做一次 bounded correction；下一轮才暴露
Pack009 的 schema-16 expectation，并对该新 failure class做一次 bounded correction。最终上述 fresh
`clean verify` 全绿；没有对同一失败循环试错。shipping Live route仍 disabled，root Green不把
configured/dormant/loopback证据上调为 Authority / Live Green。code-bearing commit/push/CI见下节；
后续docs-only head不改变该证据截止点。

## Code-head commit, PR and CI receipt

DCO commit `27da843dcb9deb9ae4008f1812dafd537a748cf3`已 push到 feature branch。
[GitHub Actions run 31771125144](https://github.com/striveh/emergeos/actions/runs/31771125144)
绑定该 exact head，终态为 `SUCCESS / BUILD SUCCESS`，耗时`24m42s`。CI从 fresh checkout执行
repository verify；日志确认以下 packaged/fault evidence均实际运行并 Green，而非只沿用本地 XML：

- `ExplicitLocalApprovalScopeHttpIT`、`ExactLocalApprovalUiHttpIT`、
  `ExactLocalApprovalHttpIT`与`RecoverableLocalActionHttpIT`；
- `Pack009DurableGraphCrashProcessIT`；
- `Pack010V18TypedAttestorShippingJarIT`。

该 CI保持 shipping Live route disabled；它不含真实 provider/Connector/effect或 Receipt evidence，
所以 overall Authority / Live仍为 Red。PR在本回执截止点仍是`Draft / Open / CLEAN`，没有 merge、
release或deploy。本节明确绑定 exact code commit与对应CI run；后续docs-only head不改变该code
evidence，PR当前check状态以GitHub为准，本Note不预判或追写每个docs-only head。

## Independent review and remaining risk

最终独立 review为 `P0=0 / P1=0`。保留一项 pre-existing P2：configured principal 是本机单用户
prototype的隔离/绑定依据，不是生产 authentication 或可信现实身份。该卡片已明确显示此边界，
但本切片没有关闭它。

结论分层：

- Engineering：本 exact approval-scope focused、fresh root clean与 exact code-head CI均 Green；
  回执截止时Draft PR #6为Open/CLEAN且未merge/release；当前状态以GitHub为准；
- Product：用户可看见并精确批准一个 current Artifact 的本地草稿计划，但 execute/undo/Receipt尚不存在；
- Human learning：没有 founder dogfood、Teach-back、真实用户 Seed 或访谈证据；
- Commercial：没有付费、复用、转化、billing 或市场证据；
- Authority / Live：Red；没有生产 verifier/key custody、真实 provider/Connector、外部 effect或
  live reconciliation。

下一条产品 Acceptance是 Real Local Draftbox + Undo Receipt；它必须先从独立 Red开始，不能复用
本 Note 的 `PLANNED` 结论冒充 execute 成功。

## References

- [Stage 1 living ExecPlan](../../plans/2026-07-28-stage-1-durable-correctness.md)
- [Stage 1 foundation](2026-07-28-foundation.md)
- [Recoverable local Action](2026-07-28-s3-recoverable-local-action.md)
