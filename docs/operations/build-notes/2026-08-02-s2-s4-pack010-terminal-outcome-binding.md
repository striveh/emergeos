# Pack010 terminal outcome binding 回执

日期：2026-08-02
Stage：Stage 2 / S4 / F6
结论：actual structured-final → process-local opaque outcome → typed TX-B command
focused Engineering Gate 为 Green；failure terminal、TX-C outcome binding、跨进程恢复与
shipping live route仍未完成，Authority/Live Gate保持 Red。

## Outcome

- `ProviderSession.next`不再把可由调用方自行构造的普通 `ModelStep`交给 production
  composition，而是在 exact attribution durable commit之后返回 private-constructor opaque
  outcome；outcome绑定 revision、manifest、exact Coordinator/egress、credential lease/expiry、
  当前 decision、request-2 attribution与完整 attribution prefix；
- 只有 typed `FinalDraft`，且 resolved model、完整 usage、response hash、Candidate content/
  evidence/output schema/attempt/slot/run/task均 exact-equal，才能 one-shot claim structured-final；
  wrong model、usage超限与 malformed final仍保留真实 attribution，但不能获得 success authority；
- dormant V9 runtime不再暴露 `completeChild(String)`。它先把 outcome的两条完整 attribution与
  PostgreSQL sequence-14 snapshot按有序 `attributionHash` exact-equal，再校验 V9 root与每种
  relation row exact keys，以 contract constructor重新计算 Candidate/WorkerResult nested integrity，
  并绑定 terminal Run/binding/event、完整 token/cost/model、strict JSON与 payload hash，随后才
  burn outcome并 mint private-constructor typed child command；
- forged content、额外 root/row key、Candidate/WorkerResult nested evidence一致篡改但保留旧
  integrity hash、duplicate key与 trailing token均在 outcome/terminal claim前拒绝，原 exact
  outcome仍可继续；outcome claim与
  command消费的并发均只有一个赢家。command绑定 runtime owner、manifest、exact cursor、两条
  attribution、Candidate integrity hash与 payload hash；
- actual loopback覆盖 exact structured-final、wrong model、usage超限、malformed final、expiry、
  replay与并发。packaged JVM在 outcome mint后 `halt(73)`；fresh packaged JVM只能读取 durable
  sequence 14，不能从 billing truth重建 process-local authority，也不会新增 provider client/HTTP；
- 已有 V9 packaged-process 37-point kill matrix、TX-B/TX-C two-JVM one-winner与 PostgreSQL process
  restart reconciliation继续作为 DB transaction原子性证据；本 slice不声称 provider exactly-once，
  也不声称 crash后能继续 terminalization；
- shipping Main仍对 broker/composer/runtime incoming reference为0，`--execute`不存在。真实 API
  key、provider、model、外网、真实 r1/r2/r3与 billing全部为0。
- 新增同链 integration：同一 durable session intent与同一 owner/Coordinator/egress capability下，
  两次 PUBLIC loopback response先形成 PostgreSQL seq14和 opaque structured-final outcome，再由
  typed command调用 exact V9 executor完成 TX-B；PostgreSQL restart后 seq15完整 reconciliation。
  payload staging只发生在同一 Testcontainers中的独立未 provisioning数据库，不是 shipping route，
  也不授予 migrator/runtime authority。

## Acceptance Red 与 Focused Red

Acceptance要求：两条 attribution或 caller自构造 Candidate/raw JSON不能取得 TX-B authority；
只有同一次 actual response #2产生的 exact structured-final outcome可派生一个 command；wrong、
stale、expired、replayed、concurrent与 hard-kill都 fail closed。

首轮 Red为三条：

1. `ProviderSession.next`返回 public `ModelStep`；
2. runtime production ABI为 `completeChild(String)`；
3. shipping artifact中不存在 opaque outcome / typed command。

最小实现后，integration首次仍 Red：runtime把 PostgreSQL relation row的 snake_case列与 nested
contract envelope的 camelCase字段混读，exact synthetic payload被错误拒绝。首次独立复核又发现
两个 P1：只检查 nested envelope部分字段会允许 Candidate/WorkerResult evidence一致篡改；未知
root/row key会在 outcome claim后才由 V9拒绝。修复改为 exact decoded contract equality、全部 V9
row shape与 explicit no-burn negative。actual同链随后又击中 BigDecimal scale-sensitive equality：
DB read-back `0.00011 → 0.000110`造成假 drift；改用已绑定全部字段的有序 attribution hash比较。

## 可复现 evidence

```bash
./mvnw -pl apps/graph-eval-runner -am \
  -Dtest='Pack010TerminalOutcomeBindingAcceptanceTest,Pack010LoopbackEffectOrderingSentinelTest,Pack010ProviderCapabilityBytecodeGateTest,Pack010PostgresRuntimeCompositionIT,Pack010ExactProviderAttributionPostgresIT' \
  -Dit.test='Pack010ExactAttributionProcessIT' verify
# surefire 17/17 + failsafe 1/1 Green
# actualOpaqueOutcomeTxB=SUCCEEDED durableSessionIntent=true
# killPoints=BEFORE_ATTRIBUTION,AFTER_COMMIT,AFTER_OUTCOME
# recovery=FRESH_PACKAGED_JVM outcomeRecovery=FAIL_CLOSED providerReplay=0

./mvnw clean verify
# root clean verify快照：11 modules；137 XML reports；788 tests；
# 0 failure/error/skipped；BUILD SUCCESS

node scripts/validate-contracts.mjs
# 8 schemas / 70 fixtures / 10 task packs / 4 environments，Green
```

## 独立复核

冻结快照：HEAD `6d914d8b44498c857768ea5da97fa204db562966`；12-file ordered
aggregate `95e1110740cea58675679b8ea902a61c0a698e8d8d4d68874324cc4bb42b6980`。
三路 actual post-fix final review均先独立复算匹配该 hash，再完成复核：

- capability / opaque outcome：`P0=0、P1=0、P2=0`；
- Candidate/WorkerResult/V9 authority closure：`P0=0、P1=0、P2=0`；
- Gate/evidence：`P0=0、P1=0、P2=1`；唯一 P2是 report-tree hygiene。

因此本 focused success→TX-B slice最终为 Engineering Green，合并结论
`P0=0、P1=0、P2=1`。整体 Authority/Live Gate仍为 Red，不受该局部结论降低。

root `clean verify`完成时报告树精确为 `137 reports / 788 tests / 0`；独立 reviewer随后运行
focused suite，新增两份 Surefire IT report，使当前累计目录成为 `139/790/0`。这不改变已观察到的
root-clean结果，但后者只能称为“root clean verify快照”，不能称为当前累计 target目录。

## 剩余风险与下一条 Acceptance

- 当前只收口 actual successful structured-final到 TX-B。pre-Candidate failure terminal仍没有 typed
  outcome；TX-C仍接受 raw parent payload，不能进入 live writer route；
- outcome/command是 process-local capability。hard-kill后保持 sequence 14是安全的 fail-closed，
  但没有 liveness。若产品要求 restart后继续，必须用 forward-only V10 immutable outcome sidecar
  绑定 seq14 cursor/head与 exact attribution，并在 semantic transaction内原子 consume；不能从
  `responseHash`或 billing row猜测原 structured-final；
- 同链 evidence仍由 test-only reflection bridge把 loopback client注入 private production session，
  terminal payload也由独立 ephemeral staging DB生成；它证明 production opaque outcome/runtime
  composition contract，不是 packaged shipping App route。shipping execute继续 disabled；
- 当前 App pre-claim closure锁定 V9 schema的 exact row keys与 Candidate/WorkerResult contract
  integrity；未来 forward migration增加列时必须同步更新 Acceptance，不能自动容忍未知字段。

## Evidence lane

- Engineering：本 focused slice Green；Authority/Live Gate Red；
- Human-learning：0；没有teach-back、真实访谈或owner live验证；
- Commercial：0；没有报价、付款、真实billing或客户验证；
- Human-in-the-loop：本 focused slice无需；任何真实owner授权、credential/provider/model/network/
  billing或shipping enablement都必须逐次等待owner授权。
