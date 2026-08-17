# Pack010 exact provider response attribution 回执

日期：2026-08-02
Stage：Stage 2 / S4 / F6
结论：production response attribution focused Engineering Gate 为 Green；三路 actual
post-fix review 为 `P0=0 / P1=0 / P2=0`。terminal outcome binding、shipping execute与真实
owner/live route仍未完成，Authority/Live Gate保持 Red。

## Outcome

- provider response先按最多 1 MiB读取 content-decoded HTTP entity bytes，拒绝 duplicate key、
  trailing token、非单一 root object与 oversize；`responseHash`是该 exact byte array的 SHA-256，
  不是 SDK对象重新序列化结果；
- 安全 attribution receipt只携带 invocation、request/response hash、resolved model、
  input/cached/output/reasoning/total token与cost，不携带 raw body、response id、header、SDK type
  或 credential；
- 完整、自洽 usage先形成 attribution并由 Composer durable commit，随后才允许 model/profile、
  limit与 output semantic判定；missing/inconsistent usage保持 attribution为0；
- 两次 loopback response的 exact attribution在真实 PostgreSQL中先于 ToolCall/FinalDraft落库，
  `pg_ctl -m immediate` restart后由新连接 exact read-back；
- packaged hard-kill覆盖 attribution commit前与commit后。每个 case都由 fresh packaged JVM实际
  尝试相同 execution slot，并在 provider client/HTTP之前以 durable conflict拒绝；在线 loopback
  request count从1保持1，随后再由独立 fresh verifier复核 sequence/attribution truth；
- shipping Main仍不可达 dormant broker/composer，真实 provider、API key、model、外网与billing
  均为0；不声称 provider exactly-once。

## Acceptance Red 与 Focused Red

Acceptance要求：durable intent必须先于任何 provider session/effect；完整
`responseHash + input/cached/output/reasoning token attribution`必须先落库；并发 session不能串
receipt；parse、DB fault、PostgreSQL restart与process hard-kill均 fail closed。

首次独立复核实际发现两个 P1：

1. transport在 attribution observer前对整个 SDK `Response.validate()`做递归校验。完整 usage已
   存在时，malformed/future output仍可能提前抛错并丢失真实billing attribution；
2. hard-kill后的 `providerReplay=0`只是 verifier固定文本，没有 fresh producer实际尝试，也没有
   在线 loopback计数反证第二次HTTP。

Focused修复移除 whole-response递归校验，但保留 bounded raw read与strict JSON envelope；
attribution仍严格校验 model和完整 usage，再由局部 output parser映射 typed failure。新增 numeric
`call_id`和unknown output回归，均证明 `HTTP=1 / observer=1 / exact responseHash / typed Failed`；
missing usage、total mismatch和cached大于input仍为 `observer=0`。process harness新增独立
`REPLAY`模式，fresh JVM只能尝试 durable slot claim，不能构造 provider client/session。

## 可复现 evidence

```bash
./mvnw -pl adapters/openai -am \
  -Dtest='OpenAiResponsesModelProtocolTest#reviewedCompleteUsagePersistsBeforeMalformedOutputDecision+reviewedRawResponseHashesExactWireBytesAndEmitsCompleteTypedAttribution+reviewedResponseRejectsDuplicateTrailingAndOversizedBodiesWithoutAttribution+reviewedMissingAndInconsistentUsageEmitNoExactAttribution' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 4/4 Green

./mvnw -pl apps/graph-eval-runner -am \
  -Dtest='Pack010LoopbackEffectOrderingSentinelTest,Pack010ProviderCapabilityBytecodeGateTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='Pack010ExactAttributionProcessIT,Pack010ExactProviderAttributionPostgresIT' \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
# surefire 9/9 + failsafe 2/2 Green
# PostgreSQL receipt: requests=2 sequence=14 restart=RECONCILED
# process receipt: kill before/after commit, fresh JVM providerReplay=0,
# providerExactlyOnceClaim=false

./mvnw clean verify
# 11 modules；136 XML reports；782 tests；0 failure/error/skipped；BUILD SUCCESS

node scripts/validate-contracts.mjs
# 8 schemas / 70 fixtures / 10 task packs / 4 environments，Green
```

最终冻结身份：HEAD `6d914d8b44498c857768ea5da97fa204db562966`；按三路 reviewer共同的
exact 11文件顺序，aggregate SHA-256为
`b9d138178026264013175db65e8ecf58f76464f2f2fb04f378419ddc04d8e9ce`。三路均重新复算、
复跑并完成实际 post-fix review，结论均为 `P0=0 / P1=0 / P2=0`。

## 剩余风险与下一条 Acceptance

本 slice只证明 transport attribution及其 durable ordering。当前真实 integration停在sequence 14
attribution truth；terminal fixture的 content/Trace/Result仍是synthetic构造值。下一条独立
Acceptance必须把第二响应的 exact `responseHash + attribution hash + Candidate integrity hash +
terminal payload hash`绑定为 one-shot outcome capability，并证明 wrong model、usage limit、
malformed final、wrong/stale/replayed/concurrent/hard-kill只能得到 exact failure terminal或在TX-B
前拒绝。不能用普通 `String payload`、两条billing attribution或自行构造 Candidate取得success
terminal authority。

## Evidence lane

- Engineering：本 focused slice Green；Authority/Live Gate Red；
- Human-learning：0；没有teach-back、真实访谈或owner live验证；
- Commercial：0；没有报价、付款、真实billing或客户验证；
- Human-in-the-loop：本 focused slice无需；下一步若进入真实owner授权、credential/provider/
  model/network/billing，必须逐次等待owner授权。
