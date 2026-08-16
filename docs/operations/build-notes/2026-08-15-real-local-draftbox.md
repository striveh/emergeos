# Real Local Draftbox Engineering Receipt

Date：2026-08-15

Stage：Stage 1 / 产品闭环增量

状态：`focused + PostgreSQL integration + crash/fresh-JVM + post-P1 fresh root + final independent review + exact code-head CI Engineering Green；Draft PR #7；docs-only head/release pending；overall Authority / Live Red`

## Outcome

当前 loopback-only 页面已经把一条 captured thought 变成可编辑的 current Artifact，并把“批准”与
“明确执行”拆成两个独立用户手势。第一个手势只持久化 exact approval；第二个手势才向
`POST /api/v1/action-approvals/{attemptId}/execute`提交同一 `scopeSchema/scopeHash`。成功后，
PostgreSQL在一个事务内写入一条 `ACTIVE` local Draft、一条
`LOCAL_DRAFT_CREATED_V1` typed Receipt，以及唯一的
`PLANNED → SUCCEEDED` terminal transition；首次返回`201`，replay与canonical GET返回`200`。

这是一个真实、provider-free 的**本地数据库 effect**，不是模拟 provider object：Receipt固定
`simulated=false`，local Draft只引用不可变 Artifact version/hash，不复制 Artifact正文。执行路径不会
访问 provider/Connector，也不会把 local Receipt写入旧`action_receipts` provider ledger。

本回执记录已通过的 Engineering evidence；late-review P1修复前的root verification cycle已Green，
production UI bytes随后变化后又完成了独立的post-P1 fresh root。两轮root必须按各自bytes解释，不能
沿用旧root作为post-P1 release evidence。Undo尚未实现；UI明确显示“撤销暂未开放”，不得把
`REVERSIBLE` risk label解释为已经可撤销。owner仍需在以下产品语义中选择：

- A（推荐）：logical undo，保留本地 Capture/Artifact明文与历史，追加不可变 Undo Receipt；
- B：isolate retention，bytes保留，但普通读取与Reflection拒绝；
- C：永久遗忘/crypto-shred，不可逆，必须另行明确授权。

ReflectionCandidate与founder dogfood均未执行；没有Teach-back、真实用户Seed、访谈、付费或商业
证据。shipping Live仍disabled，没有production verifier/key custody、真实provider/Connector、merge、
release或deploy，overall Authority / Live保持Red。

## Acceptance Red -> minimum implementation

outside-in packaged Acceptance先从 current Artifact创建 exact approval，再要求第二个HTTP手势写入
local Draft。实现前到达真实serving application并精确失败：

```text
REAL_LOCAL_DRAFTBOX_EXECUTE_MISSING expected=201 actual=404
```

minimum implementation只增加：

- `LOCAL_DRAFTBOX_V2` exact route及独立`LocalDraftboxAuthority`；
- provider-free `LocalDraftboxService`与thin execute/read HTTP surface；
- forward-only V18 `local_drafts`、`local_draft_creation_receipts`与数据库fences；
- approval card的第二手势、UNKNOWN后的显式GET恢复与typed local Receipt展示。

没有增加provider HTTP、public listener、自动重试、Undo、Reflection mutation或shipping Live wiring。
历史`LOCAL_DRAFTBOX_V1`只可读取既有`PLANNED`真相，V18后永久禁止执行。

## Exact authority and PostgreSQL truth

V2 authority仍复用`emergeos.action-approval-scope.v1` canonical 16-field hash，但固定为：

```text
principalBasis=CONFIGURED_LOCAL_PRINCIPAL
approvalOrigin=EXPLICIT_LOCAL_OWNER_INPUT
executionRoute=LOCAL_DRAFTBOX_V2
actionType=CREATE_LOCAL_DRAFT
targetRef=local://drafts
risk=REVERSIBLE
policyVersion=local-action-v2
connector=emergeos.local-draftbox
audience=emergeos:local-draftbox
accountRef=local-draftbox:{configuredPrincipalId}
maxCalls=1
```

execute必须再次匹配owner、attempt、`PLANNED/stateVersion=1/usedCalls=0`、scope schema/hash、plan、
Artifact id/version/hash、Capability binding及expiry。成功事务以PostgreSQL返回的同一时间形成：

- ActionAttempt `SUCCEEDED/stateVersion=2/usedCalls=1`；
- 第二条`PLANNED → SUCCEEDED/useDelta=1` transition；
- 一条绑定同一attempt与Artifact version/hash的`ACTIVE` local Draft；
- 一条`SUCCEEDED / LOCAL_DRAFT_CREATED_V1 / simulated=false` Receipt；
- draft、Receipt与attempt的`createdAt/occurredAt/updatedAt` canonical time一致。

V18 deferred constraint triggers要求terminal attempt、两条transition、draft、typed Receipt在同一提交中
闭合；local truth update/delete被拒绝，V2插入旧provider Receipt亦被拒绝。exact replay只读取已提交
canonical truth，不消耗第二次Capability，也不接受调用方提出的新draft/receipt ID。

## DB-time claim guard

初版实现先查询`clock_timestamp()`，再以缓存时间执行guarded UPDATE。独立`SHARE` table lock证明该
窗口允许UPDATE在Capability过期后才真正claim effect。Acceptance首次Red为：

```text
REAL_LOCAL_DRAFTBOX_DB_TIME_EXPIRY_MISSING expected=STALE actual=Executed claimAfterExpiry=true
```

修复把取时与claim合并为一条statement：`WITH execution_clock AS MATERIALIZED`内调用
`pg_catalog.clock_timestamp()`，guarded UPDATE在同一statement比较
`capability_expires_at > execution_clock.occurred_at`，并`RETURNING updated_at`作为整个terminal闭包的
canonical time。零行更新返回typed `STALE`；不声称物理COMMIT必须发生在expiry之前。

focused store suite为`6/6` Green，覆盖direct/replay canonical shape、two-writer one-winner、
planHash/TTL mismatch零变更、legacy Receipt双账本fence，以及真实lock-upgrade跨expiry后
`STALE + digest unchanged`。PostgreSQL组合为`18/18` Green：上述6项、V18 fresh migration 1项和
既有ActionAttempt store 11项。

## UI second gesture and recovery

packaged UI Acceptance运行16个mode：happy create/replay/GET、double-click、response loss、hanging
response、planned edit、malformed response、typed stale、5个V2 preview authority drift
（policy/connector/audience/account-ref/max-calls）、timer spoof、executing-edit READY recovery、
executing-edit historical recovery，以及terminal result与new Artifact隔离。

共同边界为：

- approval POST完成后仍是`PLANNED / NOT_EXECUTED / Receipt:null`；执行POST数必须为0；
- 只有第二次click可发一个execute POST，double-click不会发第二个；timer不能继承用户手势；
- response loss/hang先显示UNKNOWN，不自动POST或GET；只有显式“查询执行结果”才GET同一attempt；
- edit会invalidate旧批准；historical UNKNOWN/recovery不会把旧scope或结果绑定到new Artifact；
- 只有strict same-origin/no-store typed terminal body才显示local Draft与Receipt；敏感execution refs隐藏；
- 页面明确`Undo`未开放，不显示虚假的撤销按钮或成功声明。

三项packaged UI/API组合共`570 tests / 0 failures / 0 errors / 0 skipped`：

- `RealLocalDraftboxUiHttpIT`：16 modes在一个packaged test中全绿；
- `ExactLocalApprovalUiHttpIT`：旧exact approval/no-execute边界回归绿；
- `RealLocalDraftboxUndoHttpIT`：first `201`、replay `200`、GET canonical、wrong scope `412`、
  missing/legacy surface `404`、存储完整性破坏`409`且零额外effect。

## Crash, restart and response-loss replay

独立packaged fault Acceptance使用PostgreSQL 18.4与多个新JVM：

1. test-only immediate constraint trigger在Receipt insert后、COMMIT前阻塞事务；hard-kill writer后，
   uncommitted attempt/draft/Receipt/transition全部回滚，fresh JVM仍GET同一`PLANNED` truth；
2. fresh writer成功执行为`201`；另一attempt通过raw TCP在commit后丢失HTTP response；停止该JVM后，
   fresh JVM以GET读到terminal truth，再以同一body POST得到`200` canonical replay，digest不变；
3. seeded retired `LOCAL_DRAFTBOX_V1`仍可GET `PLANNED`，execute返回typed `412`且mutation为0。

sealed marker：

```text
REAL_LOCAL_DRAFTBOX_CRASH_RECEIPT
precommitKill=ROLLED_BACK freshPlannedGet=200 firstExecute=201
responseLoss=COMMITTED freshTerminalGet=200 replay=200
v1=412 v1Mutation=0 transitions=1>2 effects=0>1/1
```

这证明的是数据库提交真相与fresh-JVM replay，不是外部provider exactly-once，也不是Live readiness。

## Focused verification and exact hashes

以下hash绑定本切片封存的source、harness与focused XML；root Red的fresh reports另在root节独立
记录，不能用局部Green覆盖aggregate失败。

### Initial Product/UI evidence（late-review P1前）

| Evidence | SHA-256 |
|---|---|
| `apps/api/src/test/java/io/emergeos/api/RealLocalDraftboxUiHttpIT.java` | `e7811e1f5bc1d9541959a0003aeb9ed42348c19198483526fc8af79b055ac04b` |
| `apps/api/src/test/resources/io/emergeos/api/real-local-draftbox-ui-harness.mjs` | `1485cead465b7428d723b854d16de33b82046b2f2f27e08faf40f21a1f4a191e` |
| `apps/api/target/failsafe-reports/TEST-io.emergeos.api.RealLocalDraftboxUiHttpIT.xml` | `f850e4e7573ab9a26fd077e7107651732f45937b52a03df2a44dd1c00435b4a4` |
| `apps/api/src/test/java/io/emergeos/api/ExactLocalApprovalUiHttpIT.java` | `a32c11a56ebbe34ebff80b0c8df373d311378b76f4e68ca61c15c75d1ffca55e` |
| `apps/api/src/test/resources/io/emergeos/api/exact-local-approval-ui-harness.mjs` | `c1a6cf55e71e847000beaa3d7789d79e687f478fb7529040c6d4d412b06283fc` |
| `apps/api/target/failsafe-reports/TEST-io.emergeos.api.ExactLocalApprovalUiHttpIT.xml` | `31a76fd4703f6654ff1eef882da4c0b1f83da1fda3f534bdf5b49515d968d93f` |
| `apps/api/src/test/java/io/emergeos/api/RealLocalDraftboxUndoHttpIT.java` | `ae2f72222500e87b22389cda6541e6ee21ced6ff212a05ac1b77e3fbdeace820` |
| `apps/api/target/failsafe-reports/TEST-io.emergeos.api.RealLocalDraftboxUndoHttpIT.xml` | `71a008bd0e5a20d0ae87bbbbab5471015aad8f6eb2ede4f32c34823ca929953d` |
| `apps/api/src/main/resources/static/capture/approval-card.js` | `cf6b1b43e08929131fffa2d81d55b57ef63fe81c87653510f0d9bb4bc5bf4dce` |
| `apps/api/src/main/resources/static/capture/index.html` | `3089b86f21de9c0f7d3a3f01f05c0eef4176617e3bbe60bd0b792ac9d562604b` |
| `apps/api/src/main/resources/static/capture/quick-capture.css` | `73ebf45f46d82ab29cafbb0a52bad4ccda52f889cb2e1a15fa3fe1e2f10559eb` |

### PostgreSQL/fault evidence

| Evidence | SHA-256 |
|---|---|
| `adapters/postgres/src/main/java/io/emergeos/adapters/postgres/PostgresLocalDraftboxStore.java` | `ac166a7c688403461076f2eb253ffb560602035ccb331b42528411d9609e99fe` |
| `adapters/postgres/src/main/resources/db/migration/V18__real_local_draftbox_creation.sql` | `16eaaa095f93363bec6020e121c1dfec74071603a9b14ab579fe4aeca9e0cefe` |
| `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresLocalDraftboxStoreTest.java` | `19c5d66e25510ed96dc11548021e71e314dd1c2ecbc095eba117ad272e8937e4` |
| `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/V18LocalDraftboxMigrationTest.java` | `35c7fad8b461071e06c6e04703c2104cf9be8ae736d86e3bf4897ea4d8a96184` |
| `apps/api/src/test/java/io/emergeos/api/RealLocalDraftboxCrashHttpIT.java` | `40825c18403703c7de4607dd8bcdfb1b0e30b8ae2e1a62c9e87f503c565567b8` |
| focused `PostgresLocalDraftboxStoreTest` XML（6/6） | `061511ebc8833c005cfcfc1bf3b8382e87914b980d990bc23b171e3f0a59b3dd` |
| focused `V18LocalDraftboxMigrationTest` XML（1/1） | `6bcf365a9602f1396426b12a79e19b7ae4bce54741a6fea2d9b7265b217a090c` |
| focused `PostgresActionAttemptStoreTest` XML（11/11） | `f867078655590badc55e17690d888124060679d1cb136be7c2a6f0e5700f0f6e` |
| focused `RealLocalDraftboxCrashHttpIT` XML（1/1） | `31c09415c084456060b2c44389f0104cb1d97515108c989983cb8507367f1b6e` |

### Pack010 test-only timing Acceptance correction

root前置Gate曾因3秒wall-clock TTL在重载主机上连续两次超时Red；这不是production回归。test-only
correction把expired provider-session intent等待改为查询PostgreSQL
`clock_timestamp() >= durable expires_at`，并要求`PROVIDER_SESSION_DB_TIME_EXPIRED`先于typed reject；
focused Acceptance为`1/1` Green。该修复不改变Pack010 production authority语义。

| Evidence | SHA-256 |
|---|---|
| `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java` | `4b76fe5200a5ce4c47f29b0080f023d1bcecac1a3b0624676a0aaaaf4aca0d06` |
| focused `PostgresGraphAttemptStoreTest#ownerProviderSessionIntentIsExactConcurrentExpiryAndRestartFailClosed` XML（1/1） | `b769279cdad57db2acbab555396d26d3aa7aa11ac18133a8b574f44d0e62fd4a` |

### Late independent-review P1 and post-fix focused evidence

late independent review找到`P0=0 / P1=1 / P2=0`。唯一P1位于READY approval card：页面把
`REVERSIBLE`显示为“可撤销”，而本切片没有实现Undo，且terminal前Undo boundary仍隐藏。这会把
risk taxonomy误读为当前能力，与“撤销暂未开放”的产品边界冲突；该发现没有改写或抹去此前root
Red及其修复因果。

outside-in Acceptance先冻结READY卡片的诚实文案
`REVERSIBLE（风险分类；撤销暂未开放） · Policy local-action-v2`，并拒绝旧文案
`REVERSIBLE（可撤销）`。production修复前，packaged Exact UI精确Red：

```text
EXACT_LOCAL_APPROVAL_UI_TARGET_RISK_MISSING expected=1 actual=0
```

minimum implementation只修改approval-card的显示文案，没有增加Undo、改变risk、放宽16-mode或
terminal Undo unique boundary。随后focused Exact Green；包含PostgreSQL 6个store tests与三个指定
IT的combined Gate也全部Green。证据为：

| Evidence | SHA-256 |
|---|---|
| Acceptance Red `TEST-io.emergeos.api.ExactLocalApprovalUiHttpIT.xml` | `912335f314ca61b712bb57fce0f61ef260104764da587765591962e9710454ee` |
| fixed `apps/api/src/main/resources/static/capture/approval-card.js` | `a1cc455829d0015e873c26889af3eec6a72d104154cdf55448e4f02ff4231f9a` |
| fixed `apps/api/src/test/resources/io/emergeos/api/exact-local-approval-ui-harness.mjs` | `be588523917cc656e85ad6ffc48c7d4480f9b7207023dd7b6b5a7f025643eccc` |
| focused Green `TEST-io.emergeos.api.ExactLocalApprovalUiHttpIT.xml` | `e4888379576802bf9825b954a2e2a599f39b0fa0dd031cfed1bf40f23e4732a3` |
| combined Green 4-XML manifest（PostgreSQL 6 + 三个指定IT） | `8ba70bb4971216d5eee737ea78718bf7f91c77620edbd916df8555d911d18a7b` |

因为minimum fix改变了production UI bytes，下面记录的`904/904`root只能作为late P1之前的历史
Engineering evidence；当前bytes另由随后独立完成的post-P1 fresh root封存。

## Root/release Gate（late-review P1前的历史）

唯一release runner串行执行了：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

首次终态：**Red / exit 1**。fresh root aggregate为`115 XML / 627 tests / 2 failures / 0 errors /
0 skipped`；三个下游runner modules因API failure被跳过，所以不能宣称root或Pack010 overall Green。
已实际运行的PostgreSQL为`188/188` Green，其中DB-time store `6/6`、V18 migration `1/1`、既有
ActionAttempt store `11/11`与完整PostgresGraphAttempt store `61/61`均Green；Real backend、Real/Exact
UI与Crash也各`1/1` Green。

两个root失败同属一个test-only stale expectation class，production实际返回V2，但旧Acceptance仍
硬编码V1：

```text
ExactLocalApprovalHttpIT.java:600
expected: <LOCAL_DRAFTBOX_V1> but was: <LOCAL_DRAFTBOX_V2>

ExplicitLocalApprovalScopeHttpIT.java:260
expected: <LOCAL_DRAFTBOX_V1> but was: <LOCAL_DRAFTBOX_V2>
```

fresh root Red evidence：

| Evidence | SHA-256 |
|---|---|
| `apps/api/src/test/java/io/emergeos/api/ExactLocalApprovalHttpIT.java` | `9547951612ebd8d8d89106bce4db2ee5c5e09a8def1931f0783da5135c737a51` |
| root Red `TEST-io.emergeos.api.ExactLocalApprovalHttpIT.xml` | `90e9b7a3fc7867331715ed7d5ba4c68086df6efe1c062b2ca9105bf0baaf8de3` |
| `apps/api/src/test/java/io/emergeos/api/ExplicitLocalApprovalScopeHttpIT.java` | `7141ff8da360420a998a85416beacc401cc7fa69cd34167b9b2618ee62c60265` |
| root Red `TEST-io.emergeos.api.ExplicitLocalApprovalScopeHttpIT.xml` | `db2cbff2e0bbc38f54760f5166323b740d2edd289731f98607c1fcc28e7b1f75` |
| root Green `TEST-io.emergeos.adapters.postgres.PostgresLocalDraftboxStoreTest.xml` | `763f660dee157b98b8c8da7f118fa524c07a3530db3642dc69d744647634bb31` |
| root Green `TEST-io.emergeos.adapters.postgres.V18LocalDraftboxMigrationTest.xml` | `00e32e3be5eb02c8cf157e78181cc53810e1749ddb3e3a3f26bf9b0369b70cb9` |
| root Green `TEST-io.emergeos.adapters.postgres.PostgresActionAttemptStoreTest.xml` | `d3494359c9d6913f33d3a5cdcea0ae85dbdf4d08e3c250a58d50f5899918deb1` |
| root Green `TEST-io.emergeos.adapters.postgres.PostgresGraphAttemptStoreTest.xml` | `858c5224f69a138d7e93e8fbac14de38c5420d52f58b7dcdd15f480cd1e08927` |
| root Green `TEST-io.emergeos.api.RealLocalDraftboxCrashHttpIT.xml` | `0e75c13346061feecdb5b3c1e8df4a0661fde077325be85457c1c143378fba86` |
| root Green `TEST-io.emergeos.api.RealLocalDraftboxUiHttpIT.xml` | `94a281902171fe6147c6071932891167dd6a8eb214db51af2e6057363746c47b` |
| root Green `TEST-io.emergeos.api.ExactLocalApprovalUiHttpIT.xml` | `7d5f52b4bd92aee162cd559f8eee00a24fd191b6ffb2abaa7f05db13eabdd2a3` |
| root Green `TEST-io.emergeos.api.RealLocalDraftboxUndoHttpIT.xml` | `6211ccefce889c37bb48744069f0dab64cb6cb858a1f3b9474cb7ccc4134268a` |

这两条V1 expectation随后已做最小test-only修正，并在bounded recovery中分别`1/1` Green；修正后
source与XML hash为：

| Evidence | SHA-256 |
|---|---|
| fixed `apps/api/src/test/java/io/emergeos/api/ExactLocalApprovalHttpIT.java` | `4b0755b3533b9834546a130a509ca7a4e643c77829f684be2f75b93770d1448b` |
| recovery Green `TEST-io.emergeos.api.ExactLocalApprovalHttpIT.xml` | `381e9b66b01e1a62ae59a9d2a63ae008e62fcf8794beb09de82f1a89d0ab2232` |
| fixed `apps/api/src/test/java/io/emergeos/api/ExplicitLocalApprovalScopeHttpIT.java` | `99ed1a670ea49b05bfcbdd8ea31189bb3f66498b9ca00a962057bf789e1e5ff2` |
| recovery Green `TEST-io.emergeos.api.ExplicitLocalApprovalScopeHttpIT.xml` | `b2d7119c7f310f3b674115246592a229ed12a0e39e36a59081c16fe0cd4a19a1` |

### Bounded root recovery

唯一一次bounded recovery仍为**Red / exit 1**。reactor 1–9均`SUCCESS`，第10个
`graph-eval-runner`失败，第11个`offline-eval-runner`被跳过；fresh aggregate为
`164 XML / 820 tests / 1 failure / 3 errors / 0 skipped`，报告manifest SHA-256为
`9c3924728abf5222d338f48c4646ed2277ede637dc0dc3fcae6f2d9f72d92d2d`。

本地Draftbox相关Gate仍实际运行并Green：PostgreSQL`188/188`（含DB-time`6/6`）、API
Surefire/Failsafe `33 + 20`、Crash `1/1`、Real UI `1/1`；eval-runner为`88/88`。但两个V18 schema
fixture compatibility缺口使root aggregate保持Red：

- `Pack010DurableGraphTerminalProcessIT`为`4 tests / 0 failures / 3 errors`：reset fixture的
  `TRUNCATE`表集未包含引用`action_attempts`的`local_drafts`，三项在fault body之前失败；
- `Pack009DurableGraphCrashProcessIT`为`2 tests / 1 failure / 0 errors`：exact catalog fixture未列入
  V18新增的`local_drafts`与`local_draft_creation_receipts`。

recovery exact evidence：

| Evidence | SHA-256 |
|---|---|
| `apps/api/target/failsafe-reports/TEST-io.emergeos.api.RealLocalDraftboxCrashHttpIT.xml` | `76df0eaecec9b82e1813d195534c3e0c407d02a15076bc9dbcfaa3f651487ed3` |
| `apps/api/target/failsafe-reports/TEST-io.emergeos.api.RealLocalDraftboxUiHttpIT.xml` | `a22cd53f767fbcb93b7915509540fb3d7694a4df8323be634c4fc95822d27405` |
| `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java` | `b5519c225ef066cf79e9d1eb4e4649c67f5e5596276f2d39cbaa4da588a6e922` |
| recovery Red `TEST-io.emergeos.grapheval.Pack010DurableGraphTerminalProcessIT.xml` | `cfddbd239d31afb676c2cfbe71750247c26634f8a91ace662a19648c73bfe905` |
| `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java` | `371405acb3d6aceb46cbbe5b081ca94faf487da94293334c757d1efea58afec5` |
| recovery Red `TEST-io.emergeos.grapheval.Pack009DurableGraphCrashProcessIT.xml` | `f00476823a0d859ea6fce715fa9217612cedf2f3b4589974f177b51650dc1a47` |

该轮bounded recovery耗尽后没有继续重跑，也没有宣称root Green；先静态修正上述两份fixture并确认
只扩充V18 schema compatibility、未放宽fault assertions，随后才开启**新的**root cycle。这不是前一轮
的第二次recovery。

### New root verification cycle

两份fixture做最小静态兼容修正后，开启了一个新的root verification cycle；它不是前一轮的继续重试。
唯一runner从fresh `clean`开始执行同一命令，一次终态为：

```text
11/11 reactor SUCCESS
BUILD SUCCESS
32m02s
175 XML / 904 tests / 0 failures / 0 errors / 0 skipped / 0 flakes
```

XML report manifest SHA-256为
`b43cc1d0e7694a995922e72f52c3fb6cc87c4f9aed839f23fb526d0cc6dd5c8`；12个shipping JAR
manifest SHA-256为`fb20fef8e21c9a926095ee9eb4379ad94515e9ddc762d61d5acd0d35b25ab3e2`。
test-bearing module totals为PostgreSQL`188`、API`53`、Synthetic Eval Runner`88`、Graph Eval Runner
`105`、Offline Harness Runner`84`；所有下游module均实际运行，没有因上游失败而跳过。

fixture因果已闭合：Pack010 terminal reset包含V18 local Draftbox依赖表，Pack009 exact catalog包含
`local_drafts`与`local_draft_creation_receipts`；原fault/crash assertions未被删除或降级。关键fresh
source/XML hashes为：

| Evidence | SHA-256 |
|---|---|
| fixed `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java` | `f81a0dae5026f9ceda95916c163b83b634a8975b59c85e164f540f7efa58240c` |
| root Green `TEST-io.emergeos.grapheval.Pack010DurableGraphTerminalProcessIT.xml`（4/4） | `023bbb571dd5a96d88ffc50bd0cbe1180db6ef4efc476394b87f3ca476c9cf90` |
| fixed `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack009DurableGraphCrashProcessIT.java` | `784bf9b9b2b69bb505b42a20a1ec49c75795a88324403ab1e3c5a56651f3c8ae` |
| root Green `TEST-io.emergeos.grapheval.Pack009DurableGraphCrashProcessIT.xml`（2/2） | `227f4fc316143d1190c35c4e3168c12a485f90847becd1d39d0ab9b672d799e4` |
| root Green `TEST-io.emergeos.adapters.postgres.PostgresLocalDraftboxStoreTest.xml`（6/6） | `1febb575efa1c69323c0bd7da9333a45b2755784dd2ba3a5fc7e91562f54ba7c` |
| root Green `TEST-io.emergeos.api.RealLocalDraftboxCrashHttpIT.xml`（1/1） | `5df5dec4c6d6865f14236ce9283cbdc1ba59a1609ae78c25b8e142565fbc6fec` |
| root Green `TEST-io.emergeos.api.RealLocalDraftboxUiHttpIT.xml`（1/1，16 modes） | `298ccf113baf78e33a3d8dbbbac778b0019189857ff3f85699cc536c60bcb194` |
| root Green `TEST-io.emergeos.api.ExactLocalApprovalUiHttpIT.xml`（1/1） | `f6563e911edf0553682eedb85d36a5eae0ad7b0853038bc9ae85af2a8af71cc1` |
| root Green `TEST-io.emergeos.api.RealLocalDraftboxUndoHttpIT.xml`（1/1） | `0f837f4996912bf3b65a1e5cd80248ea7f8846bcd11af04a2c2b0fb80a5f01ec` |
| root Green `TEST-io.emergeos.api.ExactLocalApprovalHttpIT.xml`（1/1） | `8d2a1f1ac89fd019d05cd757cf810fc0028fc138e5cf89ffe60bd72bdf0ff6a8` |
| root Green `TEST-io.emergeos.grapheval.Pack010DurableFailureTerminalResumeProcessIT.xml`（3/3） | `ce45164cdecaa2cf7404c76c5f3fb85ef242429f477ea12160fce1fe99d93952` |
| root Green `TEST-io.emergeos.evalrunner.SyntheticEvalCrashRestartProcessIT.xml`（2/2） | `396c46d9f103c655e64df1a13c97fdf93c7e82ee0bab17a77294b7ebc33f0c32` |
| root Green `TEST-io.emergeos.offlineharness.OfflineComparisonCrashRestartProcessIT.xml`（2/2） | `047c6b3b714bfa2c8c3238c6e82066ad4257e0ec76e37ed5fe57f8e139ddc48f` |

这次root Green在当时bytes上关闭了repository verification Gate，但不把Engineering证据上调为
Authority/Live成功。late independent review随后发现上述P1并改变了production UI bytes，因此该
`904/904`结果不是post-P1 release evidence。

## Post-P1 fresh root verification cycle

minimum copy修复及focused/combined Gate完成后，唯一release runner从fresh `clean`开启了新的root
cycle；不是对pre-P1 reports的复用。命令与一次终态为：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

```text
finished=2026-08-15 15:29:41+08
duration=10m16s
11/11 reactor SUCCESS
BUILD SUCCESS
175 fresh XML / 904 tests / 0 failures / 0 errors / 0 skipped
```

post-P1 XML report manifest SHA-256为
`7d0d98716ec45efc85aa5ffb635f12cc0f5500db3218ae226862cd0e6bd4ba95`；12个shipping JAR
manifest SHA-256为`bd4ac24ef8a8dcfdae51e6ce62eb39f6b166a76a02421afac40bd34aaaf4ad20`。
DB Store、Graph Store、Undo、Crash、Real UI、Exact UI、Pack009、Pack010 terminal/failure、Synthetic
crash与Offline crash均包含在该fresh XML manifest内并Green；没有把局部或pre-P1 report混入本轮
aggregate。

resource parity再次确认当前文案确实进入packaged artifact，而不是仅有source Green：

| Evidence | SHA-256 |
|---|---|
| `approval-card.js` source = target/classes = packaged JAR resource | `a1cc455829d0015e873c26889af3eec6a72d104154cdf55448e4f02ff4231f9a` |
| Exact UI harness source = target/test-classes | `be588523917cc656e85ad6ffc48c7d4480f9b7207023dd7b6b5a7f025643eccc` |

本轮关闭当前bytes的repository verification Gate，但仍只是Engineering evidence；Authority / Live
仍Red，shipping仍disabled。

## Independent review and release Receipt

post-P1 final release-candidate independent review结论为`GO / P0=0 / P1=0 / P2=0`。reviewer独立复现
`175 fresh XML / 904 tests / 0`、XML/JAR manifests与54-file scope，并确认没有secret、`CLAUDE.md`、
production test hook或out-of-scope文件；其结论只授权exact stage、DCO commit与普通push，不上调CI、
Authority或Live。

唯一release runner随后对exact scope创建DCO code-bearing commit并普通push：

| Receipt | Value |
|---|---|
| Commit | [`195c15683f8ddadcf17ec313771b5e66c4c7ec37`](https://github.com/striveh/emergeos/commit/195c15683f8ddadcf17ec313771b5e66c4c7ec37) |
| Tree | `c79110f483434bab2d8983bb850bfecce96c3ab2` |
| Parent | `50169fb25cfbfd397050036adae4d2fb6689b30e` |
| Subject | `feat: add durable local draftbox execution` |
| DCO | `Signed-off-by` trailer present |
| Exact 54-path manifest | `8ed8b20d19f0abdb7a531737300baa5ecb5946c23e2fd433b674a7f4c14d874d` |
| Remote branch | `origin/agent/real-local-draftbox-undo`（ordinary/non-force push） |

[Draft PR #7](https://github.com/striveh/emergeos/pull/7)在回执截止时为
`OPEN / Draft / CLEAN`，base `main`、head exact `195c15683f8ddadcf17ec313771b5e66c4c7ec37`。
绑定该code-bearing head的
[CI run 31872883333](https://github.com/striveh/emergeos/actions/runs/31872883333)为
`completed / success`；job `94984100618`耗时`18m43s`，Maven于`17:59`报告`BUILD SUCCESS`。
Actions v4 Node20/setup-java deprecation warning为非阻断toolchain-maintenance信号，不是本切片功能
失败，也不能被当作功能证据。

本节的Receipt文案在`195c156…`之后形成；后续docs-only DCO commit/push会使PR产生新head，因此仍须
等待该docs-only head自己的CI终态。run `31872883333`只证明code-bearing `195c156…`，不能提前证明
未来docs-only head。PR不得擅自转Ready；没有merge/release/deploy。

## Evidence classification and next decision

- Engineering：focused UI/API、PostgreSQL 18/18与crash/fresh-JVM Green；pre-P1 root为`904/904`
  historical Green；late-review P1已有精确Red、minimum fix、focused Exact Green与combined 4-XML
  Green；post-P1 fresh root为`175 XML / 904 tests / 0` Green；final independent review为
  `GO / P0=0 / P1=0 / P2=0`，code-bearing commit与exact-head CI均Green；此前root Red及其修复因果
  完整保留；
- Release：code-bearing `195c156…`已DCO ordinary push，Draft PR #7为`OPEN / CLEAN`且CI
  run `31872883333`成功；当前Receipt docs-only bytes尚需自己的commit/push与新head CI；PR未Ready，
  无merge/release/deploy；
- Product：可完成provider-free local Draft与typed Receipt；Undo尚不存在；
- Human learning：ReflectionCandidate与founder dogfood未执行；
- Commercial：没有真实用户Seed、复用、价格请求、付费、billing或市场证据；
- Authority / Live：Red；shipping disabled，没有真实provider/Connector、production verifier或key custody。

下一步由唯一release runner封存本Receipt docs-only diff、创建DCO commit并普通push，再等待新head
CI终态；不得据code-bearing run提前宣称docs-only head Green，也不得把PR转Ready。之后才由owner选择
Undo A/B/C语义。在Undo明确前不实现或宣称
“可撤销”。founder dogfood D0若先行，也只能用非敏感真实thought、`undoAvailable=false`与明文/无删除
警告，并把Engineering/Human-learning evidence分开。

## References

- [Stage 1 living ExecPlan](../../plans/2026-07-28-stage-1-durable-correctness.md)
- [Exact Local Action Approval Scope](2026-08-14-s1-exact-local-approval-scope.md)
- [Roadmap](../../../ROADMAP.md)
