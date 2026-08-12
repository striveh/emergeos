# DeepSeek V4 Flash Responses compatibility probe 回执

日期：2026-08-11
Stage：Stage 2 / S4
结论：DeepSeek V4 Flash Responses 的 dormant、单请求、本地兼容性 Engineering slice 已 Green；
真实 provider compatibility、V13 durable attestation、billing 与 shipping live route 仍为 Red。

## Outcome

- 按 DeepSeek 官方 [Responses API](https://api-docs.deepseek.com/guides/responses_api/)
  构造独立 `deepseek-v4-flash` compatibility probe，不修改 Pack010 已冻结的 OpenAI
  transport/parser/schema profile，也不改写 V13 canonical transcript；
- production probe 固定 `https://api.deepseek.com`与`/responses`，使用SDK非stream create path；
  request显式发送`store=false`、`reasoning.effort=none`、单一 strict `json_schema` output与
  `max_output_tokens=64`，省略unsupported `previous_response_id`，并把request body限制为4096 bytes；
  response则必须把`previous_response_id`投影为null；
- HTTP transport 显式关闭 connection retry、SDK retry、HTTP redirect 与 HTTPS redirect，使用
  `Proxy.NO_PROXY`、30 秒 live timeout 与 `LogLevel.OFF`。307、429、503 executable canary 均精确
  证明只发一次请求；provider error body、reasoning sentinel、credential 与 raw response 不进入异常或回执；
- response validation fail closed：固定 envelope/store/status/parallel-tools/service-tier/error/
  incomplete/previous-id 形状，只接受一个 completed message、一个 output-text、闭合 JSON
  `{status: "DEEPSEEK_COMPATIBLE"}`；拒绝 reasoning item 或非零 reasoning token；usage 必须满足
  cached、input、output、total 算术；
- receipt 只携带 requested model、response-reported bounded model family 的 SHA-256、request/response
  hash、bounded token usage、dated list-price profile 与 post-response estimate。raw model、response id、
  body、reasoning、credential和provider error text均不输出或持久化；
- 当前公开价格按官方 [Pricing](https://api-docs.deepseek.com/quick_start/pricing/) 的
  `2026-08-11` 快照冻结为 cache hit `$0.0028/M`、cache miss `$0.14/M`、output `$0.28/M`。
  `$0.001` 只是在收到响应后决定是否接受 receipt 的 estimate ceiling，不是pre-egress reservation、
  provider budget、invoice或账单真实性证明；
- actual shaded graph-eval app JAR 包含 dormant probe及其production nested classes，但不包含test-only
  live Main或ProbeTest；first-party编译输出对 `execute`/`executeForTest` 的consumer count为0，完整
  `shippingJarViolations`为空。该证据只证明当前artifact未接shipping route，不证明外部恶意reflection
  或agent instrumentation绝对不可达。

## Acceptance Red → minimum fix

1. 初始slice没有DeepSeek production probe与actual-JAR Gate；minimum fix新增独立package-private probe、
   test-only stdin Main、本地HTTP fixture与shade后JAR inspection，不复用Pack010 live wiring；
2. OpenAI Java 4.43的标准 `Response.validate()`要求OpenAI特有的cache-write字段，无法验证DeepSeek官方
   compatibility shape。minimum fix仍用固定SDK DTO/codec解码，但对DeepSeek支持字段执行独立、闭合、
   fail-closed语义校验；
3. 初始request/response与JSON negatives存在duplicate/trailing、reasoning item/token、
   raw model泄漏与provider error text假绿面。minimum fix逐支加入exact failure code、null cause、secret/raw
   absence与strict JSON canary；response fixture明确省略非官方必需的`tool_choice/tools` echo；
4. SDK transport虽然已禁connection retry，最初no-retry Gate可能被同class旧factory代打，redirect也未锁。
   minimum fix加入专用no-retry/no-redirect factory、exact consumer Gate、307 redirect与429/503单请求Red；
5. 初始bytecode focused Gate未检查最新shaded artifact。minimum fix新增无Docker的
   `DeepSeekV4FlashShippingJarIT`，在shade完成后直接检查真实app JAR、Probe/Main/Test composition与全量
   first-party violations；
6. list-price ceiling最初措辞可能被误读为pre-egress reservation。minimum fix改为post-response accepted
   estimate，增加dated profile id，并明确官方价格可变、不能外推为billing或commercial receipt。

## 本地 executable evidence

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=DeepSeekV4FlashResponsesProbeTest,Pack010ProviderCapabilityBytecodeGateTest,GraphEvalArchitectureTest \
  -Dit.test=DeepSeekV4FlashShippingJarIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
# DeepSeek probe 9 + Architecture 5 + Bytecode 10 + actual shaded-JAR 1 = 25/25 Green

./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
```

最终独占root `clean verify`于21:47–21:55完成，11 modules、146 `TEST-*.xml` reports、
831 tests、0 failure/error/skipped/flake，`BUILD SUCCESS`，总时长8m00s。contracts为8 schemas/
70 fixtures，105 Markdown files的local links与`git diff --check`均Green。

Probe/Test source 21:43:04 < clean-built classes 21:47:11 < Probe XML 21:47:13；
Bytecode Gate XML为21:50:59；actual app JAR 21:51:03 < shaded-JAR IT XML 21:54:49。

## Bounded live probe receipt

- owner授权一次bounded live probe。credential只经隐藏输入直接进入test-only Main的stdin，未进入command
  argument、environment、代码、文件、Maven report或本回执；任何曾直接粘贴到conversation的credential
  都应立即rotate/revoke，后续不得复用；
- operator-attested evidence记录实际只发出一次client call；该调用发生在最终细分metadata rejection
  code之前的intermediate bytes，收到的redacted结果为
  `verdict=REJECTED reason=RESPONSE_METADATA_MISMATCH`。client没有自动retry或redirect，未输出raw
  body、response id、provider error、credential或signature；当时的intermediate source/class/JAR hash
  没有冻结，因此该live receipt不可独立复现，也不证明当前final bytes的live行为；
- bounded recovery只重新打开隐藏credential输入；因没有输入而取消，未发第二次provider请求。因此
  当前无法判定具体是哪一个metadata field不兼容，最终细分code也没有live执行证据；
- 该结果是安全fail-closed，不是live PASS。真实鉴权、官方线上schema兼容性、实际model/backend、usage、
  latency、availability、provider retention、请求是否计费及最终bill均未证明；provider端可能已接受并计费
  这一次请求，不能写`billing=0`或provider exactly-once。

## 冻结快照

HEAD保持`6d914d8b44498c857768ea5da97fa204db562966`；没有commit、push、deploy或release。

DeepSeek 9-file ordered slice aggregate为
`39e8099816b22dbcc4bce6e1605fbe2d38d026277e73d52064cd00193e2d3b33`；shipping graph-eval
app JAR SHA-256为
`6743fa97be33a3c3dcd5b35beb0f168505709499cba8ee2fe7509d544fc7b6ba`。

该aggregate按以下固定顺序对逐文件`shasum -a 256`完整输出再次执行`shasum -a 256`得到；不包含
本Build Note或living ExecPlan：

1. `pom.xml`；
2. `adapters/openai/pom.xml`；
3. `adapters/openai/src/main/java/io/emergeos/adapters/openai/ReviewedOpenAiClient.java`；
4. `adapters/openai/src/main/java/io/emergeos/adapters/openai/DeepSeekV4FlashResponsesProbe.java`；
5. `adapters/openai/src/test/java/io/emergeos/adapters/openai/DeepSeekV4FlashResponsesProbeTest.java`；
6. `adapters/openai/src/test/java/io/emergeos/adapters/openai/DeepSeekV4FlashResponsesProbeMain.java`；
7. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/GraphEvalBytecodeGate.java`；
8. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/Pack010ProviderCapabilityBytecodeGateTest.java`；
9. `apps/graph-eval-runner/src/test/java/io/emergeos/grapheval/DeepSeekV4FlashShippingJarIT.java`。

Probe outer、ProbePayload、Receipt、ProbeRejected与ReviewedClient class在`target/classes`和shipping JAR
entry逐字一致，SHA-256分别为
`21d3373dee7a58c0724305400cb357b3d3a68450259ee4908d19329b879a7c9a`、
`b17daaaf390b06cb8f61098216515c40a82de6e077e63c449b04eed472bb5f69`、
`a6be2c1db42588791a697c22b06eafe2123878061bd44feab44501975308ca9c`、
`6e929d6c48288223ab53ccbff1fad63e2bb19a335bd50b933536b62051d21705`与
`034986697efe22e16f0419b91ffaaf02d144e49daf798d6ffbbea32986925c20`。

当前代码、测试、artifact与claim的三路独立review均未发现P0/P1；本地dormant Engineering slice
可签Green。review保留的边界已列入下节，不能借本地Green改写live receipt。

## Remaining boundaries / 下一条Acceptance Red

- `deepseek-v4-flash-*` response-reported family只进入hash；它不能证明exact backend provenance。
  `created_at`目前只做非负检查，no-redirect factory依赖精确固定的OpenAI Java 4.43 implementation
  constructor，SDK升级必须重新审查；
- 当前probe没有consumer到Pack010 mapper、V13 attestor或shipping App。它只证明offline protocol-shaped
  compatibility与artifact closure，不证明provider semantic attestation、durable pricing或模型质量；
- V13的integer nano-USD/token无法无损表达DeepSeek cache-hit `2.8 nanoUSD/token`，也把
  `$0.000014056` round到micro-USD。不得静默重解释V13或复用OpenAI profile；
- durable multi-provider authority必须forward-only进入V14：冻结`deepseek.responses` provider identity、
  transport/parser/schema/model-resolution profile、pricing source/effective window，并用pico-USD或精确
  decimal/rational表达价格；wrong provider/profile/pricing/model/parser/schema/signer、replay与splice必须
  在PostgreSQL中fail closed且JSON/`xmin`不变；
- shipping live route前还需要独立pre-egress token/cost upper-bound authority，拒绝时必须证明
  `HTTP requests=0`。下一次真实请求需要owner重新通过隐藏stdin提供已rotate的credential；在新的live
  PASS与独立billing核验前，Authority/Live保持Red。
