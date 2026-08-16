# S1 Append-only Logical Local Draft Undo Engineering Receipt

Date：2026-08-16

Stage：Stage 1 / 产品闭环增量

状态：`PostgreSQL adapter full + graph compatibility focused + post-pin UI focused Engineering Green；first DCO head ca2e1af pushed by ordinary fast-forward；exact-head CI run 31913800072 attempt 1 FAILED on test-only crash-marker race；independent diagnosis P0=0 / product P1=0 / Release Gate CI reliability P1；two-file test-only fix independently reviewed + focused 6/6 Green；single post-fix clean root 11/11 + 179 suites/916 tests/0F0E0S Engineering Green；terminal docs-only claims require links + independent review；second DCO commit/non-force push/new exact-head CI PENDING；7 product P2 unchanged；PR OPEN/Draft, not Ready/merge/release/deploy；overall Authority / Live Red`

## Outcome

在 exact approval 与第二手势创建 provider-free local Draft 之后，loopback-only 页面现在只在读取到
同一 creation attempt 的 committed `ACTIVE` Draft与`LOCAL_DRAFT_CREATED_V1` Receipt后，开放第三个
明确用户手势“逻辑撤销”。该手势向
`POST /api/v1/action-approvals/{attemptId}/undo`提交新生成的`undoNonce`以及浏览器独立重算的
`emergeos.local-draft-undo-scope.v1` hash。

成功不会更新或删除原始 Draft。PostgreSQL在一个事务内追加一条
`LOCAL_DRAFT_LOGICALLY_UNDONE_V1 / simulated=false` typed Receipt；原`local_drafts.state`继续是
不可变的`ACTIVE`，read projection只有在看到exact Undo Receipt后才返回
`localDraft.state=LOGICALLY_UNDONE`。首次成功为`201`，同scope/nonce replay与canonical GET为`200`。

这里的“Undo”只撤销当前产品对这条local Draft的**有效投影视图**。Capture正文、Artifact内容和版本、
creation attempt、transitions、creation Receipt与raw Draft都继续以明文保留。这不是删除、隔离、恢复、
crypto-shred或“被遗忘”；需要永久遗忘时必须另开带明确破坏性授权、保留策略与恢复边界的设计。

本Note封存当前已确认的Engineering evidence。PostgreSQL adapter full gate为`465/465` Green，三个
selected graph compatibility checks为`3/3` Green。早期API classpath与packaged `12/12`证据保留为
historical pre-pin/pre-late-P1 Receipt，不是current root。显式`maven-jar-plugin` `3.5.0` pin已落地
并完成独立只读review。late reset P1修复已落地并通过独立源码review；historical Undo
edit recovery P1已完成outside-in Red -> focused Green。最终UI focused gate为`7/7` Green
（Store 6 + UI 1）。唯一一次fresh root first cycle已执行，但因唯一stale current-schema
test fixture仍期待schema 18而实际为schema 19，以`exit 1`结束；它不是production故障。
该fixture的一行test-only修复已经独立review为`GO / P0=0 / P1=0 / P2=0`，并通过单次
focused verify。随后的唯一bounded root recovery已以`11/11` reactor、`179` fresh XML、`914`
tests全绿结束，因此fresh root Engineering Green。但它运行在本次terminal docs-only
evidence update之前，只覆盖当时code/test/POM与pre-update docs；本次claims仍需doc links、
final manifest封存与exact-head CI。final independent release review已给出
`GO / P0=0 / P1=0 / 7 P2 deferred`，允许Receipt、DCO commit与普通非force push；不允许
Draft转Ready、merge、release或deploy。首个DCO head随后已普通push，但它的exact-head CI
attempt 1失败；两文件test-only修复后的clean root仅执行一次并已覆盖current test与pre-update
docs bytes、取得Engineering Green。本次terminal docs-only claims仍需doc links与独立review；
第二DCO commit、non-force push与新exact-head CI仍PENDING。

没有provider/Connector调用、Reflection或Working Self mutation、public listener、production
authentication、merge、release或deploy；overall Authority / Live保持Red。

## Acceptance Red -> minimum implementation

outside-in HTTP Acceptance先创建exact approval并执行为真实local Draft，再要求第三个POST。实现前请求
已到达真实serving application并精确Red：

```text
REAL_LOCAL_DRAFTBOX_LOGICAL_UNDO_MISSING expected=201 actual=404
```

随后Acceptance冻结了：

- 20-field Undo scope在Java、PostgreSQL与browser使用同一versioned encoding与SHA-256；
- exact owner、Draft、creation attempt/Receipt、Artifact version/hash、expected`ACTIVE`、retention、
  nonce与`maxCalls=1`全绑定；
- first/replay/GET canonical shape，以及wrong hash`412`、foreign/missing`404`、nonce conflict`409`；
- raw creation truth及`xmin`/row digest不变，Undo Receipt不可UPDATE/DELETE；
- two-connection race、commit前hard-kill、commit后response loss与fresh-JVM recovery；
- 页面只有第三次click可POST Undo，double-click至多一次，UNKNOWN不自动POST或GET。

minimum implementation只增加Core typed scope/Receipt、PostgreSQL V19 append-only ledger与projection、
thin service/HTTP surface，以及approval card的第三手势和显式GET恢复。没有增加独立provider-style
ActionAttempt ledger：当前操作是owner显式POST、local-only、单事务append，exact Undo Receipt row本身
同时是操作身份与成功结果真相。若未来跨出单事务、进入异步workflow或provider effect，必须另行评估
attempt/lease/reconciliation，而不能外推本边界。

## Exact 20-field scope and typed Receipt

`emergeos.local-draft-undo-scope.v1`的canonical bytes为schema UTF-8、一个NUL，再依次写入20个
uint32 big-endian length-prefixed UTF-8值：

1. principal basis、configured principal与explicit owner origin；
2. `LOCAL_DRAFTBOX_LOGICAL_UNDO_V1` route、action type和`local://drafts/{draftId}` target；
3. Draft ID、creation attempt ID、creation Receipt ID；
4. Artifact ID、version与hash；
5. expected effective state `ACTIVE`与retention
   `CAPTURE_ARTIFACT_HISTORY_RETAINED`；
6. policy、connector、audience与principal-bound account ref；
7. `undoNonce`与`maxCalls=1`。

typed Receipt固定：

```text
receiptType=LOCAL_DRAFT_LOGICALLY_UNDONE_V1
effect=LOGICALLY_UNDONE
retention=CAPTURE_ARTIFACT_HISTORY_RETAINED
outcome=SUCCEEDED
simulated=false
```

它还绑定creation attempt、Draft、creation Receipt、Artifact version/hash、scope schema/hash、nonce与
PostgreSQL `clock_timestamp()`生成的`occurredAt`。creation attempt与Draft按principal唯一、receipt ID
全局唯一、nonce按principal唯一；exact replay只返回winner，不生成第二条Receipt。

## Append-only PostgreSQL truth and privacy boundary

V19增加`local_draft_undo_receipts`，并以deferred authority trigger在COMMIT时重新证明：

- creation ActionAttempt仍是exact`SUCCEEDED/stateVersion=2/usedCalls=1` local Draftbox V2 truth；
- raw Draft仍是同owner、attempt、Artifact version/hash与`ACTIVE`；
- creation Receipt仍是`LOCAL_DRAFT_CREATED_V1 / SUCCEEDED / simulated=false`；
- legacy provider `action_receipts`不存在；
- Undo scope hash由PostgreSQL从同一identity独立生成。

Undo Receipt使用DB time，UPDATE/DELETE被trigger拒绝；raw Draft、Capture、Artifact、version、creation
Receipt、ActionAttempt及transitions不因Undo改变。projection必须同时读creation truth与exact Undo
Receipt，不能把`local_drafts.state=ACTIVE`直接解释为仍然有效，也不能把投影`LOGICALLY_UNDONE`反写
到raw row。

这形成可审计历史，但也形成明确privacy代价：Capture/Artifact明文继续存在于数据库、备份与既有保留
边界内。UI与文档必须使用“逻辑撤销、内容与历史保留”，不能使用“删除”“清除”“忘记”或暗示恢复
能力的文案。

## Third gesture, UNKNOWN and prohibited surfaces

approval card在creation POST后仍要求第二手势execute；只有strict canonical terminal response或GET
确认`undoAvailable=true`后才显示第三手势。第三手势自己生成nonce并重算20-field hash；timer、第一次
批准、第二次execute、历史result或Artifact edit都不能继承它。

response loss或hanging Undo进入`UNDO_UNKNOWN`：

- 不自动重试POST；
- 不自动GET；
- 不显示Undo成功；
- 只有用户点击“查询撤销结果”才GET同一creation attempt；
- canonical GET可把已提交结果恢复为`LOGICALLY_UNDONE`，否则保持UNKNOWN/fail closed。

成功后Undo入口关闭，没有restore。整个路径禁止DELETE、legacy action/reconcile、provider surface、
Reflection或Working Self response/mutation。Capture/Artifact可以沿独立编辑流程产生新Artifact，但不会
把旧terminal context和新Artifact混合。

## Race, crash and no-leak evidence

isolation Acceptance的sealed marker为：

```text
REAL_LOCAL_DRAFTBOX_UNDO_ISOLATION_RECEIPT
sameScopeStatuses=[200,201] differentNonceStatuses=[201,409]
sameNonceOtherDraft=409 crossPrincipal=404 missing=404 privateRequestMutation=0
providerCalls=0 legacyActionReceipts=0 rawDraftState=ACTIVE
projectedState=LOGICALLY_UNDONE retention=CAPTURE_ARTIFACT_HISTORY_RETAINED
reflectionMutation=NOT_OBSERVABLE workingSelfMutation=NOT_OBSERVABLE
authority=ENGINEERING_ONLY live=DISABLED
```

crash Acceptance在winner事务的Receipt insert后、COMMIT前阻塞并hard-kill writer；backend drain后释放
test gate，fresh JVM读到原`ACTIVE` projection且未提交Undo Receipt。retry为`201`。另一条路径在
COMMIT后丢失HTTP response；停止writer后，fresh JVM GET同一canonical Undone truth并以同body replay
得到`200`，provider count窗口在所有test application JVM停止与backend drain后仍为0：

```text
REAL_LOCAL_DRAFTBOX_UNDO_CRASH_RECEIPT
precommitKill=ROLLED_BACK backendDrained=true retry=201
responseLoss=COMMITTED freshUndoneGet=200 replay=200 rowUnchanged=true
providerCalls=0 rawDraftState=ACTIVE projectedState=LOGICALLY_UNDONE
retention=CAPTURE_ARTIFACT_HISTORY_RETAINED authority=ENGINEERING_ONLY live=DISABLED
```

foreign、missing、wrong scope与nonce conflict的typed problem body会逐值拒绝已生成的Capture、Artifact、
plan、approval、Capability、attempt、Draft与Receipt identifiers；它们不输出raw content、scope material
或完整trace。

## Verification receipt

### Adapter/graph and historical pre-pin API evidence

- PostgreSQL adapter full gate：`465 tests / 0 failures / 0 errors / 0 skipped`；
- selected graph compatibility focused gate：`3/3` Green；
- API/Undo classpath integration：`12 tests / 0 failures / 0 errors / 0 skipped`，7/7 reactor modules
  `SUCCESS`，耗时`52.336s`。这是后续plugin pin与late P1发生前的historical evidence。

API gate的exact command：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am \
  -Dtest=PostgresLocalDraftboxStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='RealLocalDraftboxUndoHttpIT,RealLocalDraftboxUiHttpIT,RealLocalDraftboxUndoIsolationHttpIT,RealLocalDraftboxUndoCrashHttpIT,RealLocalDraftboxCrashHttpIT,ExactLocalApprovalUiHttpIT' \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

只计该次fresh Store与六份selected IT XML：

| Evidence | Tests | SHA-256 |
|---|---:|---|
| `PostgresLocalDraftboxStoreTest` | 6 | `510533f08b7729182848b12fdbca6c266e8b9d61f8e5792991043419117d660a` |
| `RealLocalDraftboxUndoHttpIT` | 1 | `075bb05ff732b8397d5bf96124c2a37bf74d10d02c209d6ba3fd8f87c60f20a6` |
| `RealLocalDraftboxUiHttpIT` | 1 | `3ef25fbcc82f4d7ac9a9b276b6305a1d9385e9e29252ac1a5a968b462cfadd22` |
| `RealLocalDraftboxUndoIsolationHttpIT` | 1 | `2a2a7bfcbf0bf597b89ccb8a2a8a959240037b345c0008287b212d98e827954b` |
| `RealLocalDraftboxUndoCrashHttpIT` | 1 | `f6c8c7768263abfb97591c91465dbf0467f1583746111ba5094ee729f4efa5a8` |
| `RealLocalDraftboxCrashHttpIT` | 1 | `ed9779e904d415a947841d67601720ed405b8f01700737e0f8e36b7ca99ab6f7` |
| `ExactLocalApprovalUiHttpIT` | 1 | `97f6f9b64aa7937c9abb6463f98d2c770b751cd45ac122d4b38439c1b72486bd` |

该historical Real UI在同一process/classpath test中执行4个Undo场景（created、double-click、response loss、hang）和既有
16个approval/execute/edit/recovery场景。Undo HTTP、Real UI与Exact UI没有输出独立success marker；
它们的证据是上述fresh testcase、冻结source bytes与全部assertions，不能伪造不存在的stdout marker。

### Initial packaging P1 and historical pre-pin recovery

上述API命令的classpath tests全部Green，但`maven-jar-plugin:jar`没有重建thin API JAR；
Spring Boot repackage虽打印`Replacing main artifact`，fat JAR仍内嵌旧adapter：

```text
current reactor adapter JAR  9ee9af19e8d1d271ee4c5fa7df27cb54752d584e261f6c56e97c16630603fc45
fat-JAR embedded adapter     7103960264409353ca08ef2a9e56ec9eced1be21746c78a8696182e10d3fe417
current V19 migration        740bea023cf8ca597329a072a66e8a0f0a05ccab020fceb66a29167e066c24cf
embedded V19 migration       cdd8392f7eaf32270442eb65952c1b28e9fb7993f24a061918239b94d6e13f70
```

V19唯一bytes差异是旧embedded migration缺少：

```sql
REVOKE ALL ON TYPE public.local_draft_undo_receipts FROM PUBLIC;
```

上述是初始P1的因果证据，不是当前终态。同一原命令随后做bounded packaged
recovery，在`51.502s`内完成`12/12`与`7/7` reactor `SUCCESS`，且封存：

```text
fat JAR                         639ed678fa1e8d2ba39863f2bd619de96044e12d5d9831d0b4f57cb02288e73b
reactor/embedded adapter JAR    9ee9af19e8d1d271ee4c5fa7df27cb54752d584e261f6c56e97c16630603fc45
source/target/embedded V19      740bea023cf8ca597329a072a66e8a0f0a05ccab020fceb66a29167e066c24cf
```

该fat JAR的mtime为fresh；nested adapter与reactor adapter逐字一致，nested/source/target V19
逐字一致且包含`REVOKE ALL ON TYPE public.local_draft_undo_receipts FROM PUBLIC;`；UI、
resources与classes parity也已确认。该次fresh XML SHA-256前缀为：

| Evidence | Tests | SHA-256 prefix |
|---|---:|---|
| `PostgresLocalDraftboxStoreTest` | 6 | `6435964f` |
| `RealLocalDraftboxUndoHttpIT` | 1 | `c2d21072` |
| `RealLocalDraftboxUiHttpIT` | 1 | `a926f3e6` |
| `RealLocalDraftboxUndoIsolationHttpIT` | 1 | `aace72da` |
| `RealLocalDraftboxUndoCrashHttpIT` | 1 | `676c4b0e` |
| `RealLocalDraftboxCrashHttpIT` | 1 | `da320e01` |
| `ExactLocalApprovalUiHttpIT` | 1 | `982c835a` |

因此initial fat-JAR parity P1已对该当时bytes关闭；但这份fat JAR
`639ed678…`是plugin pin与late P1之前的historical artifact，不得当作current root或最终
release-candidate Receipt。

### Plugin pin and late P1 status

显式`maven-jar-plugin` `3.5.0` pin与`forceCreation=true`已落地；`apps/api/pom.xml` SHA-256为
`5b8c82cba8b8178b83c766085a2edeecbeb739e65e6f2b2bce8331ff1f14b2b9`。独立只读review确认该
pin与packaging lifecycle边界，后续focused运行中先前的plugin-version warning未再出现。这不替代
terminal docs-only claims的doc links/final release review。

late review记录两个P1：

1. `PostgresApiTest.truncateBusinessTruth`在V19三表进入API test reset后需按child-first列出
   `local_draft_undo_receipts`、`local_draft_creation_receipts`、`local_drafts`，明确保持V19 dependency
   order并封住跨类残留。当前test harness SHA-256为
   `e42994e059d9581e36d438d310c4b9943349fbacffabeb5c98aed7cf6609ed64`。修复已落地并通过
   独立源码review。后续fresh root first cycle已运行该reset harness，且API Surefire `33/33`
   Green；该cycle的唯一失败是无关production行为的stale current-schema Failsafe fixture，
   不是reset P1回归。
2. 当Undo response尚在flight时立即编辑Artifact，新Artifact context不能丢失旧creation attempt的
   historical `UNDO_UNKNOWN`，也不能把旧Undo POST继承到新Artifact。outside-in Acceptance先精确Red：

   ```text
   REAL_LOCAL_DRAFTBOX_UI_UNDO_INFLIGHT_EDIT_RECOVERY_LOST expected=1 actual=0
   ```

   Red XML SHA-256前缀为`446e90c`。minimum fix保留独立historical operation，只允许新的
   显式GET手势恢复旧Receipt；新Artifact继续自己的edit/revision context。封存bytes为：

   ```text
   approval-card.js                 d364688c680ff90e80f438769760dc491fce1f0decb9b8c234432b6ecfdfa787
   RealLocalDraftboxUiHttpIT.java   787ce4c22f840ae9323cc71071225761e72b245540b5582183b50c512b43bd0f
   exact UI harness                 fc8102ee8a651abf758487e9c876a0e90544c7704fffff40ab3ebc4f174641c6
   ```

最终post-pin/post-P1 UI focused gate为`7 tests / 0 failures / 0 errors / 0 skipped`：

| Evidence | Tests | SHA-256 |
|---|---:|---|
| `PostgresLocalDraftboxStoreTest` | 6 | `acd77b950510e3d244de60edffe8987a1ff042e60d8699576b937917c02e6cc1` |
| `RealLocalDraftboxUiHttpIT` | 1 | `bff291bd2702547691a2a450f33da36bdf55300862846232cd2801a0e70b9593` |

Real UI在这一个testcase中实际执行5个Undo场景与16个legacy场景。新fat JAR SHA-256为
`a355feb46a22b98005f4012c121d386c03d419d25eaef966ce71f1ba9017b245`，mtime fresh；
`approval-card.js`的source、`target/classes`与fat-JAR resource均为`d364688c…`，所以resource parity
Green。这是current focused evidence本身，不替代下文的root Receipt。

### Fresh root first-cycle Red and test-only focused recovery

唯一一次fresh root first cycle使用exact command：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

该cycle在`4m25s`后以`exit 1`结束，所以root明确不是Green。Contracts `59`、Core `210`、
Agent Loop `41`、OpenAI `39`、InMemory `37`、PostgreSQL `196`以及API Surefire `33`全部Green。
API Failsafe的`22`个testcase中只有`RecoverableLocalActionHttpIT`失败；readiness断言
`expected schemaVersion=18, actual=19`；reactor后三个module因前置失败而`SKIPPED`。这是全仓
唯一个stale current-schema fixture，不是production readiness、migration或Undo故障。失败XML
SHA-256为`628335f891e35746b62930a67c7e499a72ecd8744b3fd96738ea3307f621252b`；该轮fresh fat JAR
SHA-256前缀为`7ba5b19b…`。

minimum correction只把该test fixture的`CURRENT_SCHEMA_VERSION` 从`18`改为`19`，没有修改
production、migration或其他test oracle。修复后source SHA-256为
`474528d20f858bd92a60ec9c4aeef70f9e2e75fe78e8a04e9da9e45270c3042a`；全仓静态扫描确认没有
第二个current-head schema oracle。独立test-only fix review结论为
`GO / P0=0 / P1=0 / P2=0`。这不是final independent release review。

修复后只执行一次focused verify，exact command为：

```bash
./mvnw --batch-mode --no-transfer-progress -pl apps/api -am -Dtest=PostgresLocalDraftboxStoreTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RecoverableLocalActionHttpIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

该命令在`21.993s`内`BUILD SUCCESS`；`PostgresLocalDraftboxStoreTest` `6/6`与
`RecoverableLocalActionHttpIT` `1/1`全部Green。fresh XML证据为：

| Evidence | Tests | SHA-256 |
|---|---:|---|
| `PostgresLocalDraftboxStoreTest` | 6 | `a9afe0890ddb77b3f9254894afe499e50587b45ea46537523578a731fc11b03c` |
| `RecoverableLocalActionHttpIT` | 1 | `113bd4e2d3840f97dd3499e7d85972dc2bd3e9df1a347fd6e95b670e5644ac2a` |

IT中6次readiness检查均观测`schemaVersion=19 / pendingMigrations=0 / schemaValid=true`。fresh fat JAR
SHA-256前缀为`bdd5d664…`，其nested PostgreSQL adapter SHA-256前缀为`76844d5b…`，与当次
reactor adapter逐字节parity。source/target/nested V19均为
`740bea023cf8ca597329a072a66e8a0f0a05ccab020fceb66a29167e066c24cf`，且包含
`REVOKE ALL ON TYPE public.local_draft_undo_receipts FROM PUBLIC;`。这只关闭唯一stale
fixture的focused Engineering证据，不能把first-cycle root Red改写为Green。

### Historical bounded root recovery Receipt

唯一bounded root recovery重新执行exact command：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

该cycle以`exit 0 / BUILD SUCCESS / 09:28 / 11 of 11`结束。共有`179` fresh Surefire/Failsafe XML，
aggregate为`914 tests / 0 failures / 0 errors / 0 skipped`；XML manifest SHA-256为
`31d68d6d728a8f6cc8921d19dd153e5c7ae88e9d8df70be9b5efffe9695caca8`。关键XML SHA-256前缀为：

| Evidence | Tests | SHA-256 prefix |
|---|---:|---|
| V9 PostgreSQL compatibility | - | `5387d045…` |
| V19 migration/ACL | - | `0b5564d9…` |
| Undo Store | - | `25b5690f…` |
| Graph aggregate | 61 | `a12ecd6d…` |
| Durable Graph | - | `b6ff161a…` |
| Undo API | - | `4f608710…` |
| Real UI | - | `e7190259…` |
| Undo Crash | - | `d09a7f81…` |
| Undo Isolation | - | `956f8d21…` |
| Recoverable Local Action | - | `7e4abcf2…` |
| Pack009 | - | `70b6041c…` |

API fat JAR SHA-256前缀为`1a0278d8…`。其nested PostgreSQL/Core JAR分别与当次reactor
artifact逐字节一致；V19在source、target/classes、reactor adapter与nested adapter等全部
surfaces均为`740bea023cf8ca597329a072a66e8a0f0a05ccab020fceb66a29167e066c24cf`，且
`REVOKE ALL ON TYPE public.local_draft_undo_receipts FROM PUBLIC;`在每个surface exact一次。UI
`4/4` surfaces parity Green；adapter `130`与graph `61` payload comparison均为`0 mismatch`。
`maven-jar-plugin 3.5.0`生效且未再出现plugin-version warning；postflight确认root后
code/test/POM无drift。

这使fresh root对当时code/test/POM与pre-update docs成为Engineering Green，但不能回写首轮root
Red的历史。本节与其他terminal evidence claims是root之后的docs-only变更，因此本次
exact docs bytes不在该root manifest内。本writer在stop-write前运行doc links；final independent
release review已给出下文GO，current docs manifests待stop-write后外部封存，exact-head CI仍待覆盖。

### Known nonblocking P2 follow-up

下列是已知、非阻断当前focused Green的follow-up，不得悄悄改写为已覆盖：

1. 补充Undo/creation truth的严格causal-time ordering Acceptance；
2. 补充hostile named role与default type ACL矩阵；
3. 在generic Undone read/projection路径独立重算并比对hash；
4. 冻结historical recovery完成后card的exact文案与state；
5. 增加forced handler fault Acceptance，封住错误映射、零变更与no false success；
6. 让`EmergeDatabaseSnapshot`覆盖`local_drafts`、`local_draft_creation_receipts`与
   `local_draft_undo_receipts`三表。
7. 补two-tab different-nonce UI Acceptance。当两个tab对同一Draft并发Undo且nonce不同时，
   backend正确返回`201 + 409`且只有一条Receipt；loser UI目前把deterministic `409`视为
   `UNDO_UNKNOWN`，随后显式GET虽取得winner canonical Undone，仍因loser的nonce/hash不匹配
   而保持UNKNOWN。已有backend证据保证无重复mutation、no leak和no false success，所以这是
   非阻断UI P2。未来最窄修复是识别exact `409`，并在显式GET读到winner truth后显示
   “由另一操作逻辑撤销”，但不得声称loser POST成功。

### Final independent review and manifest boundary

final independent release review结论为`GO / P0=0 / P1=0 / 7 P2 deferred`。上述7项均为
明确接受的非阻断follow-up，不改变fresh root Engineering Green；其中新增P2只是
two-tab different-nonce loser UI的truthful recovery表达缺口，backend的`201 + 409`、one Receipt、
no duplicate mutation、no leak和no false success保持不变。

root-covered code-bearing `41-path`内容manifest已冻结为
`bacb5b377e9cfa4c8d14f7a771d7c0742b4c6734290fb9804b9b9f80903ae98f`，且在docs-only
更新中应保持不变。终审中任何pre-update `45-path content`、`docs5`或`terminal-docs4`
manifest都只是historical snapshot，不应继续称为final。current 45-path content manifest、
docs5 manifest与terminal-docs4 manifest必须在本writer stop-write后由独立reviewer外部重算并封存。
文档自身的manifest无法可靠地内嵌到自身而不改变bytes，因此本Note只内嵌已冻结的
41-path code-bearing manifest，不伪造自指的final docs manifest。

该GO允许后续封存Receipt、创建DCO commit并普通非force push；exact-head CI仍必须
在push后独立通过。Draft不得转Ready，也不得merge、release或deploy；Authority / Live仍Red。

### First DCO head, exact-head CI failure, and test-only recovery

首个DCO commit为`ca2e1af5fb904a3da17c34910f97e08e4c0ee783`，parent为`0e34f88`，
tree为`1f740982`，包含`45 files`。它已以ordinary fast-forward push到Draft PR #7的
remote branch；PR仍为`OPEN / Draft`，没有转Ready、merge、release或deploy。

绑定该exact head的CI run `31913800072` attempt `1`已terminal `failure`，job
`95082643336`中唯一失败step为`Build and test`。`SyntheticEvalCrashRestartProcessIT`在line `113`
失败：`expected CREDENTIAL_READ_STARTED, actual empty`；后续graph/offline module因前置失败而
`SKIPPED`。full job log SHA-256前缀为`1dd55b…`，failed-step log SHA-256前缀为
`812f…`。因此`ca2e1af…`的CI明确不是Green。

独立诊断确认：旧test-only child使用`CREATE_NEW`后，zero-byte marker会在写入前对parent
可见；parent同时以`isRegularFile`判定，形成可见空文件窗口。durable journal phase已经
持久化，production blobs未变，`ca2e1af…`也未触发Eval实现变化，所以这不是产品故障。
诊断结论为`Product P0=0 / Product P1=0 / Release Gate CI reliability P1`；Offline harness
存在同构latent race，一并修复。这个CI reliability P1不是第8个product P2，现有7项
product P2保持不变。

minimum fix只修改两个test-only source：Synthetic Eval源码SHA-256为
`6413fb1b73716420d6605e62562af873a0c06167fac1060d7546b127e264ae1f`，Offline源码SHA-256为
`b60b587999052fa73ee25d1ed6a15f2eae37fecdd628935a870f8f2816061325`。新protocol只接受
exact phase + LF，拒绝zero-byte、partial、wrong phase与CRLF；parent在child exit后做final read，
deadline path保持cleanup。独立fix review结论为`GO`。

唯一一次focused recovery在`18.563s`内`BUILD SUCCESS`，`7/7` reactor module `SUCCESS`；
Synthetic Eval `3/3`的fresh XML SHA-256为`18ebadf5…b76`，Offline `3/3`的fresh XML
SHA-256为`8f1e76f9…395`。该run为6 tests全绿，但它是non-clean focused run；所有JAR
mtime早于两个test source，证明测试加载的是unchanged packaged main JAR + fresh test classes，
不能把该运行用作final artifact freshness证据。

上一轮`179 XML / 914 tests / 0`是`ca2e1af…` pre-commit same-tree的historical Green，不覆盖
随后两个test-only source bytes。它不冒充下述post-fix terminal root，也不改变ca2 exact-head CI Red历史。

### Post-fix clean root terminal Receipt

针对两个test-only fix与当时pre-update docs bytes，新的clean root只执行一次exact命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

该cycle从`2026-08-16T00:05:48Z`运行到`2026-08-16T00:15:14Z`，总耗时`09:26`，以
`exit 0 / BUILD SUCCESS / 11 of 11`结束。`179`份fresh Surefire/Failsafe XML suites汇总为
`916 tests / 0 failures / 0 errors / 0 skipped`；XML manifest SHA-256为
`7437e57ee9b60cff2934f7144836740349c2302a37b2ba13495d0df87ac7ba19`。

Synthetic Eval与Offline crash/restart各`3/3` Green，XML SHA-256分别为
`4b15a0cd…5b48`与`5cc39ffd…ec2`；两份suite都同时覆盖deterministic ready marker和original
crash path。fresh Eval fat JAR SHA-256为`575f2b83…`；Offline normal/app JAR SHA-256分别为
`07d02216…` / `9ad318d9…`，API fat JAR SHA-256为`91556318…`。关键fresh XML包括Real UI
`0476ece6…`、V19 `63b38547…`、Undo Store `36147912…`与Recoverable Local Action
`354bd067…`，全部Green；Recoverable的6次readiness状态保持
`schemaVersion=19 / pendingMigrations=0 / schemaValid=true`。

clean lifecycle确认post-fix source、compiled test class、fresh report与fresh JAR的生成顺序和
mtime一致，关闭focused non-clean run无法证明artifact freshness的边界；`maven-jar-plugin 3.5.0`
生效且没有plugin-version warning。该root因此只对current test + pre-update docs bytes给出
fresh-root Engineering Green；本节是root之后的terminal docs-only claim，不在root XML manifest内，
也不内嵌自指docs manifest。它仍需本writer的doc links与独立review封存。

第二DCO commit、ordinary non-force push与新exact-head CI仍为`PENDING`；PR仍`OPEN / Draft`，
Authority / Live仍Red，7项product P2保持不变。

### Remaining release gates

1. writer完成doc links并stop-write后，由独立reviewer复核terminal docs-only claims，外部重算并
   封存current manifests；
2. 创建第二DCO commit并普通非force push；
3. 新exact-head CI通过；
4. PR继续保持`OPEN / Draft`；不Ready/merge/release/deploy。

## Evidence layers and remaining risk

- Engineering：adapter full、graph focused与post-pin UI focused gate Green；plugin pin已落地并独立
  review；reset P1 runtime已在first-cycle root/API Surefire中执行，UI P1已outside-in Red -> focused
  Green；首轮root只因唯一stale current-schema fixture而Red，一行test-only修复已经独立
  `GO / P0=0 / P1=0 / P2=0`与focused `7/7` Green；旧packaged `12/12`仅为historical，
  bounded root recovery已以`11/11 / 179 XML / 914 tests / 0`成为Engineering Green；该root在本次
  terminal docs-only update前运行；final review已`GO / P0=0 / P1=0 / 7 P2 deferred`，41-path
  code-bearing manifest已冻结；首个DCO head `ca2e1af…`已push，但exact-head CI attempt 1因
  test-only marker race失败；两文件fix已review/focused Green；single post-fix clean root已以
  `11/11 / 179 suites / 916 tests / 0`覆盖current test与pre-update docs bytes并成为Engineering
  Green，terminal docs-only claims仍需links/review，第二commit/push/new exact-head CI仍待；
- Product：第三手势logical Undo已在local-only acceptance中工作，且诚实显示内容/历史保留；
- Human learning：没有owner Teach-back、真实用户Seed、Reflection评审或founder dogfood证据；
- Commercial：没有访谈、重复使用、付费、billing或市场证据；
- Authority / Live：Red；configured local principal不是production identity，release candidate尚未完成，
  production verifier/key custody、真实provider/Connector、merge/release/deploy均不存在。

## References

- [ADR-0020: Append-only logical local Draft Undo](../../architecture/decisions/0020-append-only-logical-local-draft-undo.md)
- [Stage 1 living ExecPlan](../../plans/2026-07-28-stage-1-durable-correctness.md)
- [Real Local Draftbox Engineering Receipt](2026-08-15-real-local-draftbox.md)
