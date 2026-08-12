# Pack010 V20 PostgreSQL immediate-restart overlay readback Build Note

Date：2026-08-12

Stage：Stage 2 / S4

状态：`focused local Engineering Gate Green；overall Authority / Live Red`

结论：本切片只新增一个test-only Testcontainers Acceptance，没有production、schema、provisioning、
Core、adapter或shipping App变化；另更新README与docs/README的V20导航。它在独立PostgreSQL 18.4
keepalive容器中先通过
production V13、V15与V18 typed path形成完整V16 overlay，再由fresh packaged JVM A取得V19
`Attributed`；随后真实执行同一容器、同一PGDATA上的`pg_ctl restart -m immediate -w`，由fresh
packaged JVM B重新验证同一durable receipt仍为`Attributed`。最终独占root `clean verify`与三路
独立review均已闭合；本回执只签收local Engineering证据，不把它外推为App或Live能力。

```text
scope=TEST_ONLY_LOCAL_SAME_CONTAINER_POSTGRES_IMMEDIATE_RESTART_READBACK
productionSchemaProvisioningSourceDelta=0
shippingCapabilityPayloadDelta=0
historicalV19Aggregate=b33071c583d05d1ccd8f1c9ff4bf78e258a654a3b6a1fe32965de73163480fc2
currentV19PathRecompute=610a958d1a4875b781857cae6f5ae2ede710f9ced8691432def6d8d8489218cf
orderedV20SliceFiles=29
orderedV20SliceAggregate=0d8f3886d4bb6cf6abcab3c046caa64556eed9d39cacb8b0e556581d574704ee
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
rootClean=GREEN_160_XML_865_TESTS_0
v20SourceSha256=160a9c26a29ebaa94a78116d282f419539a721ae05a355e77df7b89a2108a547
v20TestClassAggregate=b64ad3fc35eff9a2acd9efbe4ad41abbf07469c87c637463d311b4310f4caf2b
v19ShippingPayloadParity=5cb31fde30537fef35f2732d0bf57b87356693cf2e540b319906353f14c413fa
coreJar=2624840ad1c3820c2edd3d111dfa5df9b9a6140981ff06f1cacde1f0260e27ad
postgresJar=c0f77b273dc9b6359b17a4837323b4f7bbd795d4d9da7acca1ab41e258023671
graphEvalJar=a4955a5682bdb710242796866ff2791b24fd2a2a081e4c4d04f0a3a87d4f39b0
shippingAppJar=3a85326c1909b685fb7193832fa35f75fe70980ffefac6a9478ebc108a0811e3
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

focused source SHA-256：
`160a9c26a29ebaa94a78116d282f419539a721ae05a355e77df7b89a2108a547`。
该值只定位本次focused test source，不是最终ordered slice aggregate。8个top/nested test class的
ordered aggregate为`b64ad3fc35eff9a2acd9efbe4ad41abbf07469c87c637463d311b4310f4caf2b`；它们只存在于
`target/test-classes`，production classes、graph module JAR与shipping App JAR中的V20 class计数均为0。

## Root clean、artifact与ordered slice

最终rebase `origin/main`后，远端新增的timestamp precision测试最初仍使用pre-Pack010清表集合，
被V8+外键闭包正确拒绝。minimum integration fix改为复用schema16 canonical 28-table
`truncateBusinessTruth`，focused `1/0`后重新执行root
`./mvnw --batch-mode --no-transfer-progress clean verify`，11个module均`BUILD SUCCESS`。
精确聚合为`160 XML / 865 tests / 0 failures / 0 errors / 0 skipped / 0 flakes`；
V20 restart IT为`1/0`，V19 Acceptance为`3/0`，provider bytecode Gate为`14/0`，architecture为
`5/0`，V19 shipping-JAR IT为`1/0`。8个JSON Schema 2020-12 contract与70个fixture通过，
129个Markdown文件的链接检查及`git diff --check`均Green。

最终whole-artifact SHA-256：

```text
core=2624840ad1c3820c2edd3d111dfa5df9b9a6140981ff06f1cacde1f0260e27ad
postgres=c0f77b273dc9b6359b17a4837323b4f7bbd795d4d9da7acca1ab41e258023671
graphEval=a4955a5682bdb710242796866ff2791b24fd2a2a081e4c4d04f0a3a87d4f39b0
shippingApp=3a85326c1909b685fb7193832fa35f75fe70980ffefac6a9478ebc108a0811e3
```

whole-JAR值因本次clean重新生成archive而变化；项目没有冻结reproducible ZIP timestamp，因此不把
整包字节相等误写成production zero-delta。V19当前10个Core class、16个reader class及2份provisioning
resource在target/module/App三层逐字一致，其规范化28-entry payload aggregate为
`5cb31fde30537fef35f2732d0bf57b87356693cf2e540b319906353f14c413fa`；shipping Gate violations与
App `open/findVerified` consumer均为0。

ordered V20 slice沿用V19固定28路径与顺序，并在末尾追加V20 test source，共29个路径。对每个路径执行
`shasum -a 256 <path>`，将29行完整标准输出逐字拼接后再次执行`shasum -a 256`，得到：

```text
0d8f3886d4bb6cf6abcab3c046caa64556eed9d39cacb8b0e556581d574704ee
```

本Build Note与living ExecPlan排除在aggregate之外以避免自引用。V19签收时历史aggregate仍为
`b33071c583d05d1ccd8f1c9ff4bf78e258a654a3b6a1fe32965de73163480fc2`；README与docs/README追加
V20导航、rebase合入公开发布导航并将开源治理ADR无歧义重编号为0019，同时在发布前清理RFC/ADR的
尾随空格与多余EOF空行后，当前树按同一V19 28-path manifest重算为
`610a958d1a4875b781857cae6f5ae2ede710f9ced8691432def6d8d8489218cf`。二者分别代表历史冻结bytes与
当前rebase/navigation/whitespace normalization后的bytes，不互相覆盖，也不伪称zero-delta。

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

- `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V20ExactPicoOverlayReaderPostgresRestartIT.java`
- `docs/operations/build-notes/2026-08-12-s2-s4-pack010-v20-postgres-immediate-restart-overlay-readback.md`
- `docs/plans/2026-07-29-stage-2-agent-kernel-evaluation.md`
- `docs/README.md`
- `README.md`

V19 RFC/ADR、production/resource/Gate source与shipping capability bytes均不属于V20修改范围。
V19的`b33071...`仍是其当时历史冻结回执；因README与docs/README追加V20导航，
当前树对V19 28-path manifest的重算必然不再等于历史值，不得将二者写成zero-delta或回写V19回执。
