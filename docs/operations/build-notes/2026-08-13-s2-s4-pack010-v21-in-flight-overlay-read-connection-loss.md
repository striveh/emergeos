# Pack010 V21 in-flight overlay-read connection-loss Build Note

Date：2026-08-13

Stage：Stage 2 / S4

状态：`focused/root local Engineering Gate Green；overall Authority / Live Red`

结论：V21只新增两个test-only source，没有修改production、schema、provisioning、V19
reader、ProcessSupport、Bytecode Gate或shipping App。Testcontainers fixture先以V13、V15与
V18 typed path形成完整V16 overlay，fresh packaged JVM A得到V19 `Attributed`；测试随后对最终
`agent_graph_exact_attempt_heads_v16` read持有`ACCESS EXCLUSIVE` lock，以database、role、
application name、relation与wait state精确绑定唯一reader backend，并由test-admin调用
`pg_terminate_backend`。fault child只能输出固定fail-closed receipt并exit 21，不能输出
`Missing / Required / Attributed / Invalid`中的任何一个；数据库public image、`xmin`、legacy
seq13/no14及durable overlay receipt identity均不变。故障后没有migrate、provision、fixture writer或
automatic retry；只有显式启动的fresh packaged JVM C再次从既有bytes取得`Attributed`。

这只证明本机Testcontainers中，V19 reader在最终overlay-head `SELECT`等relation lock时被server主动
终止后会映射为application-level integrity failure，并能由显式fresh JVM重新读取；不证明其余12张表
的任意read point、transaction begin/commit、TCP half-open、网络分区、host/power/storage/HA、任意
driver/pool retry、automatic reconciliation、同attempt并发race、App wiring、external configuration、
Live或current authorization。`pg_terminate_backend`使用test-admin authority，不是V19 reader role能力。

```text
scope=TEST_ONLY_LOCAL_IN_FLIGHT_FINAL_OVERLAY_HEAD_READ_CONNECTION_LOSS
baseHead=ded10f3052d70b103fd582277924735bdc4778a6
schemaVersion=16
v21ProductionSourceDelta=0
v21SchemaProvisioningSourceDelta=0
v21ShippingCapabilitySourceDelta=0
v21ProcessSupportDelta=0
v21BytecodeGateDelta=0
v21ShippingTestDelta=0
v21TestSourceFiles=2
v21TestSourceAggregate=88a498e567ba1871d2f309296b2f26fefa48f65dc49579f37d7a507f6406f479
v21TestClassArtifacts=8
v21TestClassAggregate=c78a7baff73af3ce81de128451a3147a79cb759b3ef78f079e7cc1fc43521980
v21ProductionTargetClassArtifacts=0
v21GraphModuleJarClassArtifacts=0
v21ShippingAppJarClassArtifacts=0
fault=TERMINATE_WAITING_FINAL_OVERLAY_HEAD_READ
backendBinding=DATABASE_ROLE_APPLICATION_RELATION_WAIT
terminated=true
faultChildExit=21
faultVerdict=NONE
failClosed=true
explicitFreshRecovery=ATTRIBUTED
freshVerifierJvms=3
durableReceiptIdentity=UNCHANGED
publicTableJsonXmin=UNCHANGED
legacySequence=13
legacySequence14=0
overlayRows=4
postFaultMigrateProvisionFixture=0
shippingAppOpenFindConsumer=0
shippingGateViolations=0
shippingLiveRoute=DISABLED
rootClean=GREEN_163_XML_874_TESTS_0
v21FailsafeReportSha256=87945db1514524ebae901e786724ff9ecbb0a8798686ef180dc9153a9a1bf92c
allTestReportAggregate=703ba11a57eb55f24c111645efc76951a4db83095f8c29501176b8fdc08c28d3
currentV19PathRecompute=6ac6d56769545b12f8c9c4fd303e508bc8460dbfddb72271a47a028ef4d450e6
currentV20PathRecompute=891e43dc5d7fcfdb535dc1baf4a9579f97ba05212f5c18fe05e0db80ed6e5d73
v19ShippingPayloadParity=5cb31fde30537fef35f2732d0bf57b87356693cf2e540b319906353f14c413fa
coreJar=a286fd01d786abedea9dde942b7f77493fe77ec861072c2652c263f028f5ccd7
postgresJar=410f784ebabe16dc4423631ecef38f5e4030050a645effa4747467c27f9afb31
apiJar=18e55e43214bc5c508f96b6978bf7a00cd15438bd87a3b98a23cc3022f78f3ad
graphEvalJar=c8b930133cf9004fc55675e40a81c247f3f1d0990c7274ab450e6a0379ba287d
shippingAppJar=645a2454b9c2ca77bbdfc5049171b87f584b30f51b72eb5e8c29a0ca5a1d1e66
integratedApiPrivacyProductionFiles=1
integratedApiPrivacyTestFiles=1
apiPrivacySourceAggregate=b538e836c4398ce11daba45998c80874d96a224e1fd36e45a95533c7547da63b
apiPrivacyReportSha256=dda40854446ef4d117058a410708715895c00a3a596e94bd5abb06eb65a1aefb
integratedQuickCaptureProductionFiles=5
integratedQuickCaptureTestFiles=1
quickCaptureSourceAggregate=92f5fae9645c09c737abf74c98c1dbc2edb660a733659678887e50ab03d3a965
quickCaptureReportSha256=f0525891aef26db3dc6c492c739eaed73157c08dfab054e35c7474bf5ece1924
independentReview=P0_0_P1_0_P2_0
```

## Acceptance Red -> focused Green

- Acceptance先保留string-only `FAULT_MAIN` reference，并由`assertFaultMainPresent`精确失败于缺失的
  test-only Main；minimum implementation只加入该Main，不修改production reader或数据库authority；
- child classpath以actual shaded shipping App JAR在前、`target/test-classes`在后；
  `Pack009ProcessSupport.assertCodeSources`验证V19 production classes来自shipping JAR、fault Main来自
  test classes。环境完全清空，reader password只通过bounded framed stdin输入；
- test-admin先锁定最终overlay-head relation。只有同时匹配database、11th LOGIN/NOINHERIT
  SELECT-only reader role、unique application name、public exact relation、`AccessShareLock`未grant及
  active lock wait的唯一backend才能被终止；ambiguous/missing binding均fail closed；
- child必须在bounded timeout内exit 21并只输出
  `PACK010_V21_EXACT_OVERLAY_CONNECTION_LOSS version=1 verdict=FAIL_CLOSED
  cause=GRAPH_ATTEMPT_INTEGRITY`。其它异常、null/normal return及任意四态verdict都不能计为Green；
- A、fault child、C三个OS process PID pairwise distinct。每一步后完整public table JSON/`xmin`、
  legacy snapshot、四张V16 overlay表及typed durable identity exact不变；C是明确的新调用，不是自动恢复。

Focused command：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am clean verify \
  -Dtest=__NoUnitTests__ \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=Pack010V21ExactPicoOverlayReaderConnectionLossIT \
  -Dfailsafe.failIfNoSpecifiedTests=true
```

结果：7-module reactor `BUILD SUCCESS`，`1 test / 0 failures / 0 errors / 0 skipped`，
结束于`2026-08-13T09:20:40+08:00`。

```text
PACK010_V21_OVERLAY_CONNECTION_LOSS version=1 fixture=V13_V15_V18_ATTRIBUTED fault=TERMINATE_WAITING_FINAL_OVERLAY_HEAD_READ backendBinding=DATABASE_ROLE_APPLICATION_RELATION_WAIT terminated=true faultVerdict=NONE failClosed=true explicitFreshRecovery=ATTRIBUTED freshVerifierJvms=3 durableReceiptIdentity=UNCHANGED publicTableJsonXmin=UNCHANGED legacySequence=13 legacySequence14=0 overlayRows=4 postFaultMigrateProvisionFixture=0 shippingLiveRoute=DISABLED
```

## Gate与shipping artifact边界

无需修改ProcessSupport、Bytecode Gate或shipping exclusion：

- existing `assertCodeSources`的vararg test-type contract已经覆盖V21 Main；扩大production allowlist反而会
  弱化test-only边界；
- V21不新增production consumer。V19 shipping IT继续证明5个public Core types、10个Core class、
  16个reader class与两份provisioning resource在target/module/App逐字一致，13-table read surface exact，
  App `open/findVerified` consumer与writer/signer/stage/commit/guard consumer均为0；
- `GraphEvalBytecodeGate`继续要求actual App violations为空；universal shipping test-class exclusion遍历
  全部`target/test-classes`并证明V21 top/nested classes不在App JAR。

相关Gate/Shipping focused command：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am verify \
  -Dtest=GraphEvalArchitectureTest,Pack010ProviderCapabilityBytecodeGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=Pack010V19ExactPicoOverlayReaderShippingJarIT,Pack009DurableGraphCrashProcessIT#shippingJarContainsNoTestExecutionSurface' \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

结果：architecture `5/0`、provider bytecode Gate `14/0`、V19 shipping IT `1/0`、
universal shipping exclusion `1/0`，合计`21 tests / 0`，7-module reactor `BUILD SUCCESS`，
结束于`2026-08-13T09:21:17+08:00`。

最终唯一integration root（同时签收下节单独记账的API privacy与Quick Capture delta）：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

11个module均`SUCCESS`，总耗时`08:54`，结束于`2026-08-13T10:07:22+08:00`。
最终为`163 XML / 874 tests / 0 failures / 0 errors / 0 skipped / 0 flakes`；V21 IT为
`1/0`，V19 Acceptance为`3/0`，provider bytecode Gate为`14/0`，architecture为`5/0`，
V19 shipping IT为`1/0`，universal test-class exclusion同样Green；API unit为`31/0`（其中privacy
`6/0`），API IT为`11/0`（其中Quick Capture `1/0`）。该冻结run的base HEAD为
`ded10f3052d70b103fd582277924735bdc4778a6`，输入delta为两个V21 test source、下节分别记账的
API privacy与Quick Capture source、以及本Note/living ExecPlan；out-of-scope untracked
`CLAUDE.md`明确排除。三个泳道在同一最终clean中全量重编译和测试，但API product delta不冒充V21能力。

## Frozen source、class、report与artifact

两个source SHA-256：

```text
144c37f8104289be55bfd2d9fd614e2cc3bacf77bd83c3625b32a031e84dc5d1  apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V21ExactPicoOverlayReaderConnectionLossIT.java
59930f603842955dbee0e575e2eacabd1ab801ecc3f6ac95b1a361ece955966e  apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V21ExactPicoOverlayReaderConnectionLossMain.java
```

8个compiled test class SHA-256：

```text
536e2e92374afd043419ef1bfcc15b53c6ec51885f94ab115150337f248b1517  Pack010V21ExactPicoOverlayReaderConnectionLossIT$DatabaseImage.class
dcf6773559abcf69e4b8e696214ed26942f84a103dbf00fa62f204ea19459f44  Pack010V21ExactPicoOverlayReaderConnectionLossIT$FaultObservation.class
45b54e5a30ab97386703ca5856d14e3e4f702e90d556aeedefb72fc69a67f9ba  Pack010V21ExactPicoOverlayReaderConnectionLossIT$OverlayIdentity.class
cdd2ed2aa94f7c5617b029d92774156555a2373b0f5170436d35a1b7fe7c905c  Pack010V21ExactPicoOverlayReaderConnectionLossIT$ProcessReceipt.class
61a81a288c871303daef027860dbddd6f6f37879f519badb035ad88a405b2272  Pack010V21ExactPicoOverlayReaderConnectionLossIT$SyntheticProfile.class
236c1519c23191b29523895e13592a20997a590cc97a248b050a43617a00659d  Pack010V21ExactPicoOverlayReaderConnectionLossIT$TableImage.class
ab5f35fec7ff6767611352faafe370594933fdb9fa3659ba5175c1ea9c43a439  Pack010V21ExactPicoOverlayReaderConnectionLossIT.class
9f48191561b096ca3e6c64362ecb2261e471d39a6981b967358089c2ec06e625  Pack010V21ExactPicoOverlayReaderConnectionLossMain.class
```

source aggregate算法：按下方fixed manifest顺序对每个path执行`shasum -a 256 <path>`，将两行完整
标准输出逐字拼接后再次SHA-256，得到`88a498e567ba1871d2f309296b2f26fefa48f65dc49579f37d7a507f6406f479`。
class aggregate对上述8个完整`target/test-classes`相对路径按bytewise词典序使用同一算法，得到
`c78a7baff73af3ce81de128451a3147a79cb759b3ef78f079e7cc1fc43521980`。这些class只存在于
`target/test-classes`；production target、graph module JAR与shipping App JAR中的V21 class计数均为0。

```text
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V21ExactPicoOverlayReaderConnectionLossIT.java
apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010V21ExactPicoOverlayReaderConnectionLossMain.java
```

V21 Failsafe XML SHA-256为
`87945db1514524ebae901e786724ff9ecbb0a8798686ef180dc9153a9a1bf92c`。最终163个XML report
按repo-relative path bytewise词典序执行同一完整`shasum`行聚合，得到
`703ba11a57eb55f24c111645efc76951a4db83095f8c29501176b8fdc08c28d3`。

最终whole-artifact SHA-256：

```text
core=a286fd01d786abedea9dde942b7f77493fe77ec861072c2652c263f028f5ccd7
postgres=410f784ebabe16dc4423631ecef38f5e4030050a645effa4747467c27f9afb31
api=18e55e43214bc5c508f96b6978bf7a00cd15438bd87a3b98a23cc3022f78f3ad
graphEval=c8b930133cf9004fc55675e40a81c247f3f1d0990c7274ab450e6a0379ba287d
shippingApp=645a2454b9c2ca77bbdfc5049171b87f584b30f51b72eb5e8c29a0ca5a1d1e66
```

whole-JAR hash会因clean生成archive timestamp变化，不能据此声称production source delta；V21 zero-delta依据
是V21-owned scope只有两个test sources与本Note/ExecPlan、production target没有V21 class、Gate/Shipping IT
以及normalized payload parity。它不对并行、out-of-scope工作区delta作签收。V19的10 Core + 16 reader + 2 provisioning entries以entry name与content
规范化后，在target/module/App三层均为
`5cb31fde30537fef35f2732d0bf57b87356693cf2e540b319906353f14c413fa`。
V19固定28-path当前重算仍为`6ac6d56769545b12f8c9c4fd303e508bc8460dbfddb72271a47a028ef4d450e6`，
V20固定29-path当前重算仍为`891e43dc5d7fcfdb535dc1baf4a9579f97ba05212f5c18fe05e0db80ed6e5d73`。
本Note与living ExecPlan不在V21 source aggregate内，避免自引用。

## Unified API delta ledger

最终root另签收两条独立API泳道，不能把它们归为V21 zero-production delta：

- private API cache boundary修改1个production filter并新增1个test，把`captures`、`artifacts`、
  `manifestations`、`agent-drafts`与`agent-runs`的root/subpath在success及400/404/405/415/malformed
  response上统一为`Cache-Control: private, no-store`，并证明error不回显submitted/stored sentinel。
  两文件fixed-order aggregate为
  `b538e836c4398ce11daba45998c80874d96a224e1fd36e45a95533c7547da63b`；root中的`6/0` XML为
  `dda40854446ef4d117058a410708715895c00a3a596e94bd5abb06eb65a1aefb`；
- Quick Capture新增2个production Java、3个static resource与1个packaged IT。实际fat API JAR在loopback
  launch下对`GET /capture`返回200、`private, no-store`、exact CSP与`text/html`；页面有可访问的
  TEXT/LINK表单和same-origin CSS/JS，没有fetch/XHR/WebSocket/storage/eval/外部resource。JS只切换LINK
  field并阻止submit，明确显示“提交能力尚未启用”；没有POST实现、voice、列表、provider call或浏览器
  persistence。六文件fixed-order aggregate为
  `92f5fae9645c09c737abf74c98c1dbc2edb660a733659678887e50ab03d3a965`；root中的`1/0` XML为
  `f0525891aef26db3dc6c492c739eaed73157c08dfab054e35c7474bf5ece1924`；
- 三份static resource的source与fat API JAR entry逐字一致：`index.html`为
  `fc35e75d3ec19d6fc6a20a47432c5c5e19ba130101062d2a3ab8ac62e6e1b605`、CSS为
  `ee409cfa814787ad228b2a559baf5401172bd0a6cb1e870f034d1b5b7dc6710c`、JS为
  `f1315cbf521d4f9b4cea356f52764c51450baef10b1fad1888c807e642e4c77b`；两个production
  page classes也实际存在于fat API JAR。API JAR SHA-256为
  `18e55e43214bc5c508f96b6978bf7a00cd15438bd87a3b98a23cc3022f78f3ad`。

Quick Capture实现与独立fault review均为`P0=0 / P1=0`；V21独立review保持
`P0=0 / P1=0 / P2=0`。这些local product/security证据不改变provider、Authority、billing或Live Gate。

docs落盘后，`./scripts/verify-doc-links.sh`验证当时workspace中130个Markdown文件（128个tracked
baseline、本Note，以及明确排除的out-of-scope `CLAUDE.md`），
`./scripts/verify-contracts.sh`验证8个JSON Schema 2020-12 contract与70个fixture，
`git diff --check`均Green；这些docs-only检查不会改写10:07 final-root的JAR、class或XML receipt。

独立只读终审对同一冻结快照复核backend double-binding、fault output/exit、credential frame、
全public image与durable identity、test-only class exclusion、Gate/App consumer及全部hash，结果为
`P0=0 / P1=0 / P2=0`。

## Five-outcome receipt

- AI Coding：完成Acceptance Red、minimum test-only Main、focused/root复核与可复算hash receipt；
- Agent Engineering：把in-flight read的exact backend binding、fail-closed output及explicit recovery分开验证；
- Product/Production：只增加local Engineering fault evidence，production与shipping capability delta为0；
- Career：`N/A`，没有新增可核验岗位证据；
- Business：`N/A`，没有用户、收入、billing或Commercial证据。

## Design references

- [RFC-0014](../../rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md)
- [ADR-0018](../../architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md)
- [V19 Build Note](2026-08-12-s2-s4-pack010-v19-fresh-jvm-exact-pico-overlay-reader.md)
- [V20 Build Note](2026-08-12-s2-s4-pack010-v20-postgres-immediate-restart-overlay-readback.md)
- [living ExecPlan](../../plans/2026-07-29-stage-2-agent-kernel-evaluation.md)
