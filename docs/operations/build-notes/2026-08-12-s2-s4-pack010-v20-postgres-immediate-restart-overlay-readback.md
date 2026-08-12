# Pack010 V20 PostgreSQL immediate-restart overlay readback Build Note

Date：2026-08-12

Stage：Stage 2 / S4

状态：`focused local Engineering Gate Green；overall Authority / Live Red`

结论：V20冻结时只新增一个test-only Testcontainers Acceptance，没有production、schema、provisioning、
Core、adapter或shipping App变化；另更新README与docs/README的V20导航。它在独立PostgreSQL 18.4
keepalive容器中先通过
production V13、V15与V18 typed path形成完整V16 overlay，再由fresh packaged JVM A取得V19
`Attributed`；随后真实执行同一容器、同一PGDATA上的`pg_ctl restart -m immediate -w`，由fresh
packaged JVM B重新验证同一durable receipt仍为`Attributed`。最终独占root `clean verify`与三路
独立review均已闭合。其后Linux CI真实TTY启用所暴露的PostgreSQL时间精度问题，以及最终root回归
暴露的offline cooperative claim写入窗口，已作为post-V20 portability/race recovery追加收口；没有新增
App route、schema或provisioning authority。本回执只签收local Engineering证据，不把它外推为App或Live能力。

```text
scope=TEST_ONLY_LOCAL_SAME_CONTAINER_POSTGRES_IMMEDIATE_RESTART_READBACK
v20HistoricalProductionSchemaProvisioningSourceDeltaAtSliceFreeze=0
v20HistoricalShippingCapabilityPayloadDeltaAtSliceFreeze=0
currentSchemaProvisioningDelta=0
postV20PortabilityAndRaceProductionFiles=4
postV20PortabilityAndRaceTestFiles=16
postV20PortabilityAndRaceCodeTestFiles=20
postV20PortabilityAndRaceAggregate=147f428084c8e0e97af8c22f7153e94e6d9ea254f174c82da2ef0a8660c8e471
providerSessionPrecisionTestFiles=9
providerSessionPrecisionAggregate=2881cc9fb0df725518f2f61225d1c42de35ff2701698a8cef5b5dc4fb82df3c9
historicalV19Aggregate=b33071c583d05d1ccd8f1c9ff4bf78e258a654a3b6a1fe32965de73163480fc2
postRebasePrePortabilityV19PathRecompute=610a958d1a4875b781857cae6f5ae2ede710f9ced8691432def6d8d8489218cf
currentV19PathRecompute=6ac6d56769545b12f8c9c4fd303e508bc8460dbfddb72271a47a028ef4d450e6
orderedV20SliceFiles=29
postRebasePrePortabilityOrderedV20SliceAggregate=0d8f3886d4bb6cf6abcab3c046caa64556eed9d39cacb8b0e556581d574704ee
orderedV20SliceAggregate=891e43dc5d7fcfdb535dc1baf4a9579f97ba05212f5c18fe05e0db80ed6e5d73
schemaVersion=16
freshVerifierJvms=2
postgresRestart=PG_CTL_IMMEDIATE_SAME_CONTAINER_SAME_PGDATA
restartExit=0
containerIdentity=SAME
systemIdentifier=SAME
postmasterStart=ADVANCED
preRestartSentinel=DISCONNECTED
postRestartConnection=READY
verdictBefore=ATTRIBUTED
verdictAfter=ATTRIBUTED
durableReceiptIdentity=UNCHANGED
publicTableJsonXmin=UNCHANGED
legacySequence=13
legacySequence14=0
overlayRows=4
shippingLiveRoute=DISABLED
rootClean=GREEN_160_XML_866_TESTS_0
remoteLinuxCiObserved=RED_RUN_31609813991_HEAD_39B6267_GRAPH_EVAL_4_ERRORS
remoteLinuxCiAfterFix=NOT_RUN
v20SourceSha256=cf0d12081fdfb50a911b576e4ff24ff2ef61e94b0b6b35e3ee4ecc71373eaa71
v20TestClassAggregate=b5c6e1ece85f808f7e7e1b65b24865582ae4f2dbab512e8fd193083e0d272591
v19ShippingPayloadParity=5cb31fde30537fef35f2732d0bf57b87356693cf2e540b319906353f14c413fa
coreJar=eab8c7bcbee097c497ef5e7151d823fee67315877e819a99bb31a30086209ddb
postgresJar=e65efd9b59a9d6a80b88726d3209cce0091b414fe17daa0f9bc50630052a5d8f
graphEvalJar=2afa3d668cd7d2452bcc3191d38d8a733a6bad3c2fc5beab64d608276d08e85e
shippingAppJar=89ad3a5250c36895e5dccb73fa28f4226af78b7214c3bcce890920d5694a9b44
```

## Acceptance Red → Green

- 首次历史探索复用默认PostgreSQL容器；postmaster作为PID 1时，`pg_ctl restart -m immediate`
  终止容器主进程并连带杀死Docker exec transport，得到`137`。该结果没有postmaster时间前进、
  restart后连接或第二个verifier证据，明确不计Red或restart成功；
- 新Red改用独立keepalive容器：shell保持PID 1，PostgreSQL为后台子进程。fixture完成
  V13 + V15 + V18 Attributed，fresh JVM A与全部mutation fence均通过，但测试故意不执行restart，
  最终精确失败于`V20_POSTMASTER_START_DID_NOT_ADVANCE_BEFORE_RESTART`；
- minimum Green只在同一test文件加入真实`gosu postgres pg_ctl restart -D "$PGDATA"
  -m immediate -w`与只读观察闭包，没有修改production或V19。restart command必须exit 0；失败断言
  只使用固定marker，不输出命令stderr；
- restart前sentinel physical connection在restart后必须拒绝查询；随后bounded新physical connection
  必须成功。container ID与`pg_control_system().system_identifier`保持相同，
  `pg_postmaster_start_time()`严格前进；
- fresh JVM A已经退出后才restart；fresh JVM B使用不同OS PID。两者均从actual shaded App JAR优先
  classpath加载production V19 reader，credential只经cleared environment后的framed stdin输入；
- restart前后及JVM B读后，所有public regular/partition tables的row count与
  `to_jsonb(row)::text + xmin` image exact不变；legacy verified snapshot保持seq13、legacy seq14为0，
  四张V16 overlay表合计4行；
- durable identity不只比较verdict或row count：将typed V18 completion receipt的protocol、sequence、
  state version、overlay head、statement、attribution、event、transcript、validation receipt hash与state，
  同四张V16表join投影在restart前后交叉exact-equal；
- restart后没有再次运行Flyway、roles provisioning、fixture restore或任何writer helper；第二次
  `Attributed`来自既有durable bytes的fresh packaged JVM只读重验。

## Focused evidence

命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am clean verify \
  -Dtest=__NoUnitTests__ \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=Pack010V20ExactPicoOverlayReaderPostgresRestartIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

结果：`BUILD SUCCESS`，`1 test / 0 failures / 0 errors / 0 skipped`。

```text
PACK010_V20_OVERLAY_RESTART version=1 fixture=V13_V15_V18_ATTRIBUTED postgresRestart=IMMEDIATE restartExit=0 keepalivePid1=true containerIdentity=SAME systemIdentifier=SAME postmasterChanged=true oldSentinel=DISCONNECTED newConnection=READY freshVerifierJvms=2 verifierPidsDistinct=true verdict=ATTRIBUTED durableReceiptIdentity=UNCHANGED publicTableJsonXmin=UNCHANGED legacySequence=13 legacySequence14=0 overlayRows=4 postRestartMigrateProvisionFixture=0 shippingLiveRoute=DISABLED
```

focused source当前SHA-256：
`cf0d12081fdfb50a911b576e4ff24ff2ef61e94b0b6b35e3ee4ecc71373eaa71`。
该值只定位本次focused test source，不是最终ordered slice aggregate。8个top/nested test class的
当前ordered aggregate为`b5c6e1ece85f808f7e7e1b65b24865582ae4f2dbab512e8fd193083e0d272591`；它们只存在于
`target/test-classes`，production classes、graph module JAR与shipping App JAR中的V20 class计数均为0。

## Root clean、artifact与ordered slice

最终rebase `origin/main`后，远端新增的timestamp precision测试最初仍使用pre-Pack010清表集合，
被V8+外键闭包正确拒绝。minimum integration fix改为复用schema16 canonical 28-table
`truncateBusinessTruth`，focused `1/0`后重新执行root
`./mvnw --batch-mode --no-transfer-progress clean verify`，11个module均`BUILD SUCCESS`。
精确聚合为`160 XML / 866 tests / 0 failures / 0 errors / 0 skipped / 0 flakes`；
V20 restart IT为`1/0`，V19 Acceptance为`3/0`，provider bytecode Gate为`14/0`，architecture为
`5/0`，V19 shipping-JAR IT为`1/0`。8个JSON Schema 2020-12 contract与70个fixture通过，
128个tracked Markdown文件的链接检查及`git diff --check`均Green；本机另有1个out-of-scope、untracked
`CLAUDE.md`，未纳入本回执、测试聚合或提交。

最终whole-artifact SHA-256：

```text
core=eab8c7bcbee097c497ef5e7151d823fee67315877e819a99bb31a30086209ddb
postgres=e65efd9b59a9d6a80b88726d3209cce0091b414fe17daa0f9bc50630052a5d8f
graphEval=2afa3d668cd7d2452bcc3191d38d8a733a6bad3c2fc5beab64d608276d08e85e
shippingApp=89ad3a5250c36895e5dccb73fa28f4226af78b7214c3bcce890920d5694a9b44
```

whole-JAR值因本次clean重新生成archive而变化；项目没有冻结reproducible ZIP timestamp，因此不把
整包字节相等误写成production zero-delta。V19当前10个Core class、16个reader class及2份provisioning
resource在target/module/App三层逐字一致，其规范化28-entry payload aggregate为
`5cb31fde30537fef35f2732d0bf57b87356693cf2e540b319906353f14c413fa`；shipping Gate violations与
App `open/findVerified` consumer均为0。

ordered V20 slice沿用V19固定28路径与顺序，并在末尾追加V20 test source，共29个路径。对每个路径执行
`shasum -a 256 <path>`，将29行完整标准输出逐字拼接后再次执行`shasum -a 256`，得到：

```text
891e43dc5d7fcfdb535dc1baf4a9579f97ba05212f5c18fe05e0db80ed6e5d73
```

本Build Note与living ExecPlan排除在aggregate之外以避免自引用。V19签收时历史aggregate仍为
`b33071c583d05d1ccd8f1c9ff4bf78e258a654a3b6a1fe32965de73163480fc2`；README与docs/README追加
V20导航、rebase合入公开发布导航并将开源治理ADR无歧义重编号为0019，同时在发布前清理RFC/ADR的
尾随空格与多余EOF空行后的pre-portability重算为
`610a958d1a4875b781857cae6f5ae2ede710f9ced8691432def6d8d8489218cf`。post-V20 bytecode Gate把
timestamp canonicalization精确绑定到durable sinks后，当前树按同一manifest重算为
`6ac6d56769545b12f8c9c4fd303e508bc8460dbfddb72271a47a028ef4d450e6`；对应29-path当前值为
`891e43dc5d7fcfdb535dc1baf4a9579f97ba05212f5c18fe05e0db80ed6e5d73`。这些值分别代表历史V19冻结、
post-rebase pre-portability与当前bytes，不互相覆盖，也不伪称zero-delta。

## Post-V20 CI portability与root race recovery

- GitHub Actions显式安装真实`/usr/bin/expect`后，Linux real-TTY矩阵首先暴露test harness把
  nanosecond `Instant.now()`传给PostgreSQL microsecond contract；三处test-only durable时间统一截断到
  `MICROS`，而专测challenge expiry的窗口保持不变。两个post-approval expiry场景的test-only TTL从
  250ms提高到3000ms，仍等待`TTL + 150ms`并要求精确`owner capability expired`、durable sequence与
  replay fence，不放宽production expiry语义；
- 第一轮timestamp修复后的Linux CI已经越过上述精度拒绝，但进一步暴露
  `session-intent-expired`仍使用1秒test-only TTL：慢runner可能在provider session intent落库前先过期，
  使泛化expiry message通过而durable row为0。该场景现与其他post-approval expiry测试统一为3秒，仍等待
  `TTL + 150ms`，并新增`PROVIDER_SESSION_INTENT_DURABLE`必须严格早于
  `REJECTED owner capability expired`的顺序断言；row=1、seq7、restart replay fence均未放宽。修复后本地
  focused连续3次`1/0`、完整Store suite `61/0`及本次root clean均Green；新的Linux CI在本回执冻结时仍待重跑，
  因此不把本地Green写成remote CI Green；
- 第二轮Linux CI（run `31609813991`，head `39b6267`）已越过Store与API模块，却在4个graph-eval
  Acceptance首次写provider session intent时触发同一exact read-back fence：16个test fixture调用点把host
  nanosecond `Instant.now() + 5m`送入test-only bridge，PostgreSQL `timestamptz`回读为microsecond后与参与hash的
  原始expiry不再逐字相等，production store因此正确回滚。确定性`+1ns` Acceptance先证明旧bridge Red；minimum
  recovery随后让bridge在打开store/事务前以固定消息拒绝sub-micro输入并证明row=0，让16个positive callers显式
  canonicalize到`MICROS`，默认synthetic binding同步收口，而两个原本取自数据库`clock_timestamp()`的调用保持
  不变。production store的hash/read-back equality、TTL与replay fence均未修改。focused 4个Acceptance为`4/0`，
  覆盖全部18个bridge callsites的consumer suite为`19/0`，最终root clean为`160 XML / 866 tests / 0`；新的Linux CI仍待本次push后重跑，
  不能把本地证据表述成remote Green；
- 同构的dormant production缺口在`Pack010ProviderCredentialBroker`一个durable sink与
  `Pack010ProviderSessionComposer`六个durable sinks精确canonicalize；`CredentialLease.requireFresh`
  继续使用raw clock，避免改变expiry判断。Composer由nanosecond unit与真实PostgreSQL/restart行为覆盖；
  Broker由compiled order、exact call count与target/module/App byte parity覆盖。两类App consumer仍为0；
- 最终root clean又真实复现offline双writer窗口：winner以`CREATE_NEW`建立claim inode后、固定bytes写满前，
  loser曾把安全的0/partial claim误分类为通用`REPORT_FILE_UNSAFE`。minimum production fix只把
  safe regular/0600/owner/ACL正确、稳定读取时内容为固定claim合法前缀、且无pending/final的短claim归为
  non-authoritative `REPORT_COMMIT_INCOMPLETE`；短伪造、完整伪造、错误mode/owner/ACL/type/symlink、
  oversize及与pending/final共存仍分别fail closed。确定性latch测试停在inode创建后首byte写入前，
  证明竞争writer稳定拒绝、释放后winner为FINAL；packaged race没有放宽`REPORT_FILE_UNSAFE` allowlist；
- 孤立claim若恰在bounded读取期间发生size/identity变化，会保守降为一次non-authoritative UNKNOWN；它不会
  获得publish authority，第二个`save`仍固定拒绝。该分支未被误写成系统能区分合法增长与hostile replacement；
- 当前delta为4个production source与16个test source；schema/provisioning、App route、provider network、
  billing与Live均没有变化。下面Files节按固定词典序列出20个post-V20 code/test路径；逐路径执行
  `shasum -a 256 <path>`，将20行完整标准输出逐字拼接后再次SHA-256，得到
  `147f428084c8e0e97af8c22f7153e94e6d9ea254f174c82da2ef0a8660c8e471`。其中本轮9个test-only
  provider-session precision路径按同一算法得到
  `2881cc9fb0df725518f2f61225d1c42de35ff2701698a8cef5b5dc4fb82df3c9`；此前11-path恢复在本轮前的
  历史值为`5a03d6d099ece85a2766dbf311b2c672f464175d94aeaacd5ed8f75cb67758fa`。Build Note与living ExecPlan
  排除以避免自引用；同算法对offline三文件subset仍得到
  `fc552583b8207750f04704c8805f622badec6c256f870591df4c2cce19b79e74`。

## Claim boundary

- 本证据只覆盖本机Docker/Testcontainers、同一container ID、同一PostgreSQL system identifier、
  同一PGDATA上的PostgreSQL 18.4 immediate process restart；它不证明container stop/start、container
  recreation、volume detach/remount、host crash、power loss、storage durability、backup/restore、HA、
  failover、replication或upgrade；
- fresh JVM与database restart是两条独立证据：JVM A/B不同只证明verifier process freshness，
  postmaster时间前进和sentinel断连才证明database process restart；不能用其中一项替代另一项；
- 本测试没有模拟reader调用中connection loss、自动retry、transaction reconciliation、same-attempt
  race、concurrent DDL或hard-kill窗口，也没有证明restart期间正在执行的read如何恢复；
- `Attributed`仍只表示V19定义的历史`VALID_AT_COMMIT`。本切片不增加current authorization、
  readiness、pricing freshness、production signer/key custody、PostgreSQL-native Ed25519 verification、
  raw V16 credential bypass closure、TX-B/TX-C或pre-egress authority；
- 没有App route、configuration、deployment、provider network、真实模型、billing、DeepSeek r1/r2/r3、
  Human-learning或Commercial证据；overall Authority/Live继续Red；
- process hard-kill以外的host/storage fault、same-attempt race与connection-loss outcome仍未证明；
  本地Green不能上调overall Authority / Live，后者继续Red。

## Files

V20冻结切片：

- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V20ExactPicoOverlayReaderPostgresRestartIT.java`
- `docs/operations/build-notes/2026-08-12-s2-s4-pack010-v20-postgres-immediate-restart-overlay-readback.md`
- `docs/plans/2026-07-29-stage-2-agent-kernel-evaluation.md`
- `docs/README.md`
- `README.md`

Post-V20 portability/race recovery code/test source（固定词典序）：

- `adapters/postgres/src/test/java/io/emergeos/adapters/postgres/PostgresGraphAttemptStoreTest.java`
- `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack010ProviderCredentialBroker.java`
- `apps/graph-eval-runner/src/main/java/io/emergeos/grapheval/Pack010ProviderSessionComposer.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/adapters/postgres/Pack010GraphTerminalStoreBridge.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableAttributedFailureResumeProcessIT.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableFailureTerminalResumeProcessIT.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010DurableGraphTerminalProcessIT.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayReaderAcceptanceIT.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactPicoOverlayTxAAcceptanceTest.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactProviderAttributionPostgresIT.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ExactTxARequirementAcceptanceTest.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderSessionEffectOrderingTest.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderValidationAttestationAcceptanceTest.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderValidationAttestationProcessIT.java`
- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V20ExactPicoOverlayReaderPostgresRestartIT.java`
- `apps/offline-harness-runner/src/main/java/io/emergeos/offlineharness/OfflineComparisonPersistenceObserver.java`
- `apps/offline-harness-runner/src/main/java/io/emergeos/offlineharness/PosixOfflineComparisonReportStore.java`
- `apps/offline-harness-runner/src/test/java/io/emergeos/offlineharness/PosixOfflineComparisonReportStoreTest.java`

在V20冻结时，V19 RFC/ADR、production/resource/Gate source与shipping capability bytes均不属于V20修改范围；
当前post-V20 addendum已显式列出两项dormant production与Gate、test及offline store delta。
V19的`b33071...`仍是其当时历史冻结回执；因README与docs/README追加V20导航，
当前树对V19 28-path manifest的重算必然不再等于历史值，不得将二者写成zero-delta或回写V19回执。
