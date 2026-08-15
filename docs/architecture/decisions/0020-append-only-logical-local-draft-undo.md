# ADR-0020: Append-only logical local Draft Undo

- Status: Accepted
- Date: 2026-08-16
- Supersedes: none

## Context

Real Local Draftbox已经以第二个明确用户手势在PostgreSQL中形成不可变的`ACTIVE` Draft与
`LOCAL_DRAFT_CREATED_V1` Receipt。`REVERSIBLE`只是risk taxonomy；它本身不提供删除、恢复或遗忘
能力。下一步需要让owner停止把一条Draft视为当前有效，又不能改写creation truth、伪造外部effect，
或把隐私上的“仍保留明文”描述成删除。

备选产品语义包括：A append-only logical Undo；B 保留bytes但隔离普通读取/Reflection；C destructive
forget或crypto-shred。当前选择A。它适合local-only、单事务、可审计闭包，但必须清楚暴露其保留代价。

## Decision

1. Undo是批准和execute之后的**第三个显式owner手势**。第一次批准、第二次execute、timer、历史
   result、页面恢复或Artifact edit都不能继承该手势。
2. client生成新的`undoNonce`，并按`emergeos.local-draft-undo-scope.v1`对20个固定字段进行
   length-prefixed canonical encoding和SHA-256。scope精确绑定configured principal、Draft、creation
   attempt/Receipt、Artifact version/hash、expected`ACTIVE`、retention、policy、local connector/
   audience/account、nonce与`maxCalls=1`。
3. 成功路径在一个PostgreSQL事务中只追加一条
   `LOCAL_DRAFT_LOGICALLY_UNDONE_V1 / LOGICALLY_UNDONE /
   CAPTURE_ARTIFACT_HISTORY_RETAINED / SUCCEEDED / simulated=false` Receipt。DB生成`occurredAt`；
   principal/attempt、Draft、receipt ID与nonce唯一，UPDATE/DELETE均被拒绝。
4. 当前local-only操作不新建第二套ActionAttempt ledger。显式POST、exact scope+nonce、单事务append与
   unique typed Receipt row共同构成操作身份和结果truth。若未来引入异步workflow、provider effect、
   lease或reconciliation，必须重新评估attempt ledger，不能沿用此简化结论。
5. `local_drafts.state`继续保持raw、不可变的`ACTIVE`。read model只有在creation truth与exact Undo
   Receipt同时成立时才投影`LOGICALLY_UNDONE`；projection不得反写raw row。
6. creation ActionAttempt/transitions、creation Receipt、Capture、Artifact及version/hash全部保留且不变。
   Undo不执行DELETE、不撤销数据库history、不恢复调用预算，也不产生legacy provider Receipt。
7. response loss或timeout进入`UNDO_UNKNOWN`。UI不自动POST、不自动GET、不显示成功；只有新的显式
   “查询撤销结果”手势才GET同一creation attempt并接受strict canonical committed truth。
8. 成功后不提供restore。新Artifact继续走独立edit/revision流程；旧terminal context不得绑定到新
   Artifact。
9. Undo不访问provider/Connector，不调用legacy action/reconcile，不产生Reflection或Working Self
   response/mutation。
10. UI与文档必须明确“逻辑撤销；Capture/Artifact内容和历史仍保留”。不得使用“删除”“清除”
    “忘记”或暗示可恢复的文案。

## Alternatives

- **UPDATE `local_drafts.state`。** 拒绝。它把effective projection混入creation truth，允许同值UPDATE
  改变row identity，并削弱append-only审计。
- **DELETE Draft或级联删除Capture/Artifact。** 拒绝。它是不可逆、破坏性行为，超出当前owner手势与
  retention授权。
- **Isolate retention。** 延后。它需要普通读取、Reflection、导出、备份和管理员路径的完整访问矩阵；
  不能把“未显示”冒充隔离完成。
- **Permanent forget / crypto-shred。** 延后。它需要独立key custody、备份语义、恢复不可行性和明确
  destructive confirmation。
- **为本地同步Undo复制provider ActionAttempt状态机。** 当前拒绝。单事务typed Receipt已提供精确
  identity、outcome与replay；复制异步状态机会增加两套truth而没有新的失败窗口。

## Consequences

- creation与Undo都可审计；same scope/nonce replay返回同一Receipt，race只有一个winner。
- raw SQL消费者不能仅凭`local_drafts.state`判断当前有效状态，必须使用受约束projection。
- 明文Capture/Artifact仍在数据库及其既有备份/保留边界中。这是产品与privacy限制，不是实现细节。
- 一条Draft当前只允许一次logical Undo，没有restore；different nonce或same nonce绑定其他Draft均冲突。
- configured local principal仍不是production authentication；loopback Engineering evidence不等于Live。
- append-only Receipt会增加少量持久存储和schema/ACL/backup审计面。

## Evidence and validation

- outside-in HTTP Acceptance首先得到
  `REAL_LOCAL_DRAFTBOX_LOGICAL_UNDO_MISSING expected=201 actual=404`；
- PostgreSQL adapter full gate为`465/465` Green，selected graph compatibility为`3/3` Green；
- API classpath selected gate为`12/12` Green，覆盖canonical HTTP、4个Undo UI场景、16个既有UI场景、
  race/no-leak、commit前rollback、commit后response loss、legacy crash与exact approval UI；
- crash/isolation receipts固定raw`ACTIVE`、projected`LOGICALLY_UNDONE`、retention retained、
  provider calls 0、Reflection/Working Self mutation `NOT_OBSERVABLE`；
- 初始fat JAR parity P1与随后`12/12`、`7/7` reactor packaged recovery保留为historical
  pre-pin/pre-late-P1 evidence；当时fat JAR `639ed678…`不是current root。显式
  `maven-jar-plugin` `3.5.0` pin已落地并通过独立只读review。
- late review的API reset child-first P1修复已落地并通过独立源码review；其runtime
  路径已在fresh root first cycle/API Surefire中执行。historical Undo inflight-edit recovery P1已
  outside-in Red -> focused Green。
  最终UI focused gate为`7/7` Green（Store 6 + UI 1），覆盖5个Undo与16个legacy场景；new
  fat JAR为`a355feb4…`，UI resource parity Green，plugin-version warning absent。
- 唯一一次fresh root first cycle使用`./mvnw --batch-mode --no-transfer-progress clean verify`，
  `4m25s / exit 1`。Contracts `59`、Core `210`、Agent Loop `41`、OpenAI `39`、InMemory `37`、
  PostgreSQL `196`与API Surefire `33`均Green；API Failsafe `22`中唯一失败为
  `RecoverableLocalActionHttpIT` readiness仍期待schema 18而实际为19，reactor后三个
  module `SKIPPED`。failure XML SHA-256为
  `628335f891e35746b62930a67c7e499a72ecd8744b3fd96738ea3307f621252b`，fresh fat JAR前缀为
  `7ba5b19b…`。这是全仓唯一stale current-schema test fixture，不是production故障；root
  仍不是Green。
- minimum test-only fix只将`CURRENT_SCHEMA_VERSION` `18 → 19`，source SHA-256为
  `474528d20f858bd92a60ec9c4aeef70f9e2e75fe78e8a04e9da9e45270c3042a`；独立review为
  `GO / P0=0 / P1=0 / P2=0`，全仓无第二current-head oracle。修复后单次focused verify在
  `21.993s`内`BUILD SUCCESS`，Store `6/6` + IT `1/1` Green；XML SHA-256为
  `a9afe0890ddb77b3f9254894afe499e50587b45ea46537523578a731fc11b03c` /
  `113bd4e2d3840f97dd3499e7d85972dc2bd3e9df1a347fd6e95b670e5644ac2a`，6次readiness均为
  `schemaVersion=19 / pendingMigrations=0 / schemaValid=true`。fresh fat JAR `bdd5d664…`与
  nested adapter `76844d5b…` parity，source/target/nested V19均为`740bea…`且包含type
  `REVOKE`。该review与focused gate只是Engineering evidence，不是final release review或root Green。
- 唯一bounded root recovery随后使用同exact `clean verify`命令，以
  `exit 0 / BUILD SUCCESS / 09:28 / 11 of 11`结束；`179` fresh XML共`914 tests / 0 failures /
  0 errors / 0 skipped`，manifest SHA-256为
  `31d68d6d728a8f6cc8921d19dd153e5c7ae88e9d8df70be9b5efffe9695caca8`。关键XML前缀为
  V9 `5387d045…`、V19 `0b5564d9…`、UndoStore `25b5690f…`、Graph61 `a12ecd6d…`、
  DurableGraph `b6ff161a…`、UndoAPI `4f608710…`、UI `e7190259…`、Crash `d09a7f81…`、
  Isolation `956f8d21…`、Recoverable `7e4abcf2…`、Pack009 `70b6041c…`。API fat JAR为
  `1a0278d8…`；nested PostgreSQL/Core与reactor逐字节一致，V19全surfaces为`740bea…`且
  type `REVOKE` exact一次，UI `4/4` parity，adapter `130` / graph `61` payload均`0 mismatch`，
  `maven-jar-plugin 3.5.0`无warning，postflight无code/test/POM drift。因此fresh root Engineering
  Green，但该root早于本次terminal docs-only update，只覆盖当时code/test/POM与pre-update docs。
- causal-time、hostile named/default type ACL、generic Undone hash重算、historical-recovery card
  state、forced handler Acceptance、`EmergeDatabaseSnapshot`三表coverage与two-tab different-nonce
  loser UI recovery共7项known nonblocking P2。新增P2的backend仍正确`201 + 409`且one
  Receipt，无duplicate mutation、leak或false success；但loser UI把deterministic `409`视为
  `UNDO_UNKNOWN`，显式GET读到winner canonical Undone后仍因nonce/hash不匹配而UNKNOWN。
  最窄future是补two-tab UI Acceptance，识别exact `409`并在显式GET后显示
  “由另一操作逻辑撤销”，不声称loser POST成功。
- final independent release review为`GO / P0=0 / P1=0 / 7 P2 deferred`。root-covered 41-path
  code-bearing manifest已冻结为
  `bacb5b377e9cfa4c8d14f7a771d7c0742b4c6734290fb9804b9b9f80903ae98f`。pre-update
  45-path content/docs5/terminal-docs4 manifests只是historical snapshot，current值必须在writer
  stop-write后由独立reviewer外部重算并封存；不在文档中内嵌自指manifest。
  Receipt、DCO commit和non-force push已允许，exact-head CI仍待通过。Draft不Ready，
  不merge/release/deploy；所以本ADR不证明release完成或Authority / Live Green。

动态工程证据与剩余Gate见
[2026-08-16 Build Note](../../operations/build-notes/2026-08-16-s1-logical-local-draft-undo.md)。

## Rollback or migration

V19是forward-only migration。若产品暂时关闭Undo，可移除UI/HTTP wiring并撤销runtime insert authority，
但已提交的Undo Receipt仍是历史truth，不能UPDATE/DELETE或把projection悄悄改回`ACTIVE`。

未来restore、isolate retention或permanent forget必须由新ADR和新Acceptance明确scope、授权、备份与
不可逆边界；不得通过改写本ADR或删除既有Receipt实施。
