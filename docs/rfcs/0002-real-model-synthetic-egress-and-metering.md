# RFC-0002：真实模型只经 synthetic Eval egress，并绑定身份与计量

- 状态：Accepted；adapter、isolated Eval Runner、完整验证与独立审查均已通过。
  Accepted 不等于批准或执行 live-provider smoke
- 日期：2026-07-30
- 范围：Stage 2 S3

> 2026-07-30 correctness note：Accepted 的 egress/approval/metering 决策不变。
> 后续 filesystem review 证明 macOS JDK 不暴露 ACL view，且 `ATOMIC_MOVE` 的
> target-exists 行为是 provider-specific；下文已把对应安全表述收窄为 visible ACL
> 与 cooperative marker flow，no-overwrite hardening 继续作为独立实现增量。

## 决策摘要

首个真实模型 adapter 不进入普通 `apps/api`，也不处理现有个人 Capture。它只由独立
`apps/eval-runner` 对一份 checked-in、hash-frozen、PUBLIC 且明确 synthetic 的 Task Pack
调用。默认命令只做 preflight，绝不读取 API key、构建 live client 或发起网络请求；
一次真实 smoke 仍需项目所有者看到 pack hash、最大请求数与最大 reservation 后完成
interactive operator approval。

```text
apps/api → ScriptedFakeModel
apps/api -X→ adapters/openai

apps/eval-runner
  → frozen synthetic pack
  → server-owned execution profile
  → adapters/openai
  → provider-neutral AgentLoopKernel
  → Core verification
  → Trace + Bundle + receipt
```

`PUBLIC` 不等于 synthetic，也不等于同意第三方 egress；三者必须分别证明。

## 为什么隔离 live route

普通 Agent draft 入口当前能读取 PERSONAL Capture。若只靠 Spring Profile、环境变量或
客户端 `model` 参数切换 OpenAI，同一个 API 进程就可能在没有明确授权时把个人内容送出。
因此：

- `apps/api` 永久保持 Fake baseline，且构建时禁止 `com.openai:*`；
- HTTP command 不增加 provider、model、pricing、base URL、API key 或 live 开关；
- production adapter 不接受任意 base URL；
- loopback base URL 只允许测试构造器使用。

## Versioned execution identity

历史 Fake Run 保持：

```text
TaskEnvelope 1.0
HarnessRunBundle 1.0
model binding = absent/null
```

任何 billable/provider SDK execution 必须使用：

```text
TaskEnvelope 1.1
HarnessRunBundle 1.1
modelProvider
modelRequested
pricingProfile
idempotencyKey
environmentSnapshotRef = environment://sha256:<manifest hash>
componentVersions["model-adapter"]
componentVersions["execution-profile"]
componentVersions["execution-profile-fingerprint"]
componentVersions["pricing-profile-fingerprint"]
```

Bundle 版本必须与 Task 版本一致。Task 1.0 的三个新增字段不进入旧 canonical preimage，
但 constructor、JSON semantic verifier 与 PostgreSQL V5 constraint 会拒绝任何非 null
值，避免形成不受 hash 保护的 routing metadata。

V5 把 provider/requested model/pricing profile 同时写入 Task JSON 与 typed columns。
RUNNING 与 terminal read 均验证两者相等；terminal 还受 Bundle hash 与内嵌 Task 约束。
V5 不改写旧 V4 Task/Bundle JSON 或 bundle hash。

`AgentExecutionProfile` 是 server-owned immutable route identity，统一承载 schema、
risk、step/tool/deadline/budget、逐 step token upper bounds、pricing/model adapter、
Harness experiment、policy/state/context/tool registry、environment、capability 与
required data class。它的 fingerprint 覆盖所有会改变执行或计量语义的字段；同一个
profile id 但 fingerprint 不同必须在装配期失败。legacy Fake Kernel 必须保持
`profile id = null` 且 `profile fingerprint = null`，不能伪装成未绑定路径。

这仍然只证明 structural binding。`PUBLIC` 与 capability string 都不是 synthetic
provenance 或 operator consent；Eval runner 必须继续证明完整 Task/pack hash 和一次性
授权。

## Provider session 与失败事实

一个 Agent Run 打开一个 provider Session；并发 Run 不能共享 response ID、function
call ID 或 replay state。每个 model step 返回：

```text
decision + resolvedModel + usage
```

如果 provider 已返回可校验的 model identity 与 usage，但 final JSON、tool call 或
attribution 不能接受，adapter 返回 typed `Failed` decision，而不是抛掉 usage。只有
401/429/5xx、timeout、transport disconnect 或 usage 缺失等没有完整 receipt 的路径才抛
typed `AgentModelFailure`。

跨 step resolved model 漂移立即失败，但两步已观察 usage 都保留。raw response、
exception message、header、prompt、tool content、encrypted reasoning 与 API key 均不得
进入 Trace、Bundle、receipt 或日志。

## Responses API 边界

首版固定：

- OpenAI Java SDK `4.43.0`；
- Responses API；
- `store=false`；
- manual Item replay，不使用 `previous_response_id` 或 conversation；
- 请求 encrypted reasoning item 以支持 stateless replay，但不解析、不持久化；
- `parallel_tool_calls=false`；
- strict `capture.read` function schema；
- 第一轮强制唯一 tool，第二轮禁止 tool 并要求 strict structured final；
- SDK `maxRetries(0)`；
- 每次 request timeout 使用 Run 的 remaining deadline；
- `service_tier=default`；
- text input/output、无 hosted tools、无外部 Action。

自动测试只连 loopback `HttpServer`，使用 sentinel key；永远不访问真实 provider。

## Pricing 与 budget

Pricing profile 是 immutable identity。费率变化新增 profile，不能覆写旧 profile。
S3 的 `costUsd` 是公开 list-price estimate，不是 invoice reconciliation。

金额内部使用 integer nano/micro USD，禁止 `double`。observed cost 按每次 provider
call 使用 `HALF_UP` 取整到 micro USD；reservation 不假定 cache hit，并按每次调用先
`CEILING` 到 micro USD，再乘最大 model step 数。每次真实调用前必须有覆盖完整
request 的可信 input token upper bound；首个 smoke 使用 pack hash 对应的 reviewed
upper bound。字符数猜测、经验倍率或上次 usage 都不能放行 egress。

```text
perCallReservationMicroUsd =
  ceilToMicroUsd(
    maxInputTokensPerStep × uncachedInputNanoUsdPerToken
    + maxOutputTokensPerStep × outputNanoUsdPerToken
  )

wholeRunReservationMicroUsd =
  perCallReservationMicroUsd × maxModelSteps
```

首版不允许 upper bound 超过 272,000 tokens，不假定 cache hit。若缺少上界、预算不足、
profile 不匹配或 pack hash 漂移，在建立 socket 前失败。

`budgetUsd` 是 requested ceiling：

- 成功 Result 不得超过 ceiling；
- provider 已产生可归因 usage 时，非成功 Result 允许如实记录 actual cost 超出 ceiling，
  failure 为 `MODEL_BUDGET_EXHAUSTED`；
- timeout/断线后 outcome 可能未知、usage 也可能未知，`costUsd=0` 只表示未观测，不能
  宣称 provider 没有计费；
- 不自动 retry outcome-unknown 调用。

Eval terminal record 必须把 provider-observed metering 与 Run projection 分开：

- `observedCostUsd/observedTokenCount` 是 adapter 在 provider response 中实际观察并按
  frozen PricingProfile 归因的顶层计量；billing 与后续对账必须读取这两个字段；
- `runCostUsd/runTokenCount` 来自经过 deterministic product sanitizer 的 Run/Bundle；
  失败路径可以合法为 `0`，不能据此断言 provider 未计费；
- `meteringMatchesRun=false` 明确表达两层不同。Run usage 不是 invoice truth；
- provider observed usage 即使超过 reservation 或 requested budget，也必须完整保留并
  形成 paid failure，不能截断、清零或丢进 exception；
- reservation 只是在 egress 前判断是否授权本次 worst-case request 的 authorization
  ceiling，不是 provider 最终 usage 的记账上限。

完整 reservation/unknown billing 审计仍需要 provider invoice reconciliation；S3 不宣称
invoice-level 对账或全账户 hard spend cap。

## Synthetic preflight

只有同时满足下列条件才可能进入 interactive smoke：

1. pack 位于 approved repository path；
2. pack SHA-256 位于 compiled catalog；
3. seed 为 literal synthetic content，`dataClass=PUBLIC`；
4. 无真实账号、conversation、Self Model 或个人标识；
5. 只允许 `capture.read`，无 hosted tool、Connector 或 Action；
6. execution profile id/fingerprint、environment manifest 与 pricing profile
   fingerprint 全部匹配；
7. 最大 provider requests、deadline、output tokens 与 reservation 有界；
8. preflight receipt 未过期、未消费，且本机 attempt marker 以 `CREATE_NEW` 取得；
9. operator 在 TTY 中核对 hash/预算后输入一次性 challenge；
10. 前九步完成后才读取 `OPENAI_API_KEY`。

默认、`--help`、invalid pack、missing TTY、错误 challenge 与 replayed receipt 都必须证明
key read count、model factory count、HTTP request count 均为零。

## Local one-shot、journal 与 terminal record

- attempt marker 在 challenge 前使用 `CREATE_NEW` 创建；错误 challenge 也会烧掉当前
  host 上的 attempt；
- marker、journal 与 run record 只允许位于当前 owner home 下的私有 POSIX 目录：
  directory `0700`、file `0600`，拒绝 symlink、foreign owner，以及 filesystem
  provider 可见的 foreign allow ACL；provider 不暴露 ACL view 时不宣称已验证 ACL；
- 30 秒 permit 绑定完整 Task hash 与 execution profile，并在 AgentKernel egress 前以
  CAS 消费；
- attempt journal 在 credential read、client creation、provider SDK create intent、
  attributed usage 与 terminal record publish 周围 append + `fsync` hash-chain event；
- provider SDK create intent 是保守事实，只表示调用可能发生。进程随后死亡时不能证明
  provider 是否接收或计费，必须保留 `billingStatus=UNKNOWN`；
- terminal record 先写 `.pending`、`fsync`、read-back，再使用 `ATOMIC_MOVE` 发布并
  `fsync` directory。这是当前 cooperative marker flow 的本地 atomic publish，不与
  provider request 构成 transaction；target 已存在时的 move 行为是 provider-specific，
  record store 自身的 no-overwrite hardening 仍是后续工作；
- one-shot 只覆盖当前 POSIX host/owner home。它不防同 UID 恶意进程、owner/root
  删除或重写文件、换主机重放，也不是 provider-side idempotency、签名、WORM 或远程
  approval。

## 不做的事

S3 不：

- 把现有 PERSONAL Task Pack 发送给真实模型；
- 给普通 API 增加 live route；
- 自动 retry outcome-unknown provider call；
- 保存 raw prompt/response/reasoning；
- 声称一次 smoke 证明模型质量、用户价值或 production readiness；
- 接入社交平台 Connector；
- 解决 stale RUNNING/崩溃恢复；这仍需要 lease/fencing 与独立 RFC。

## Acceptance

RFC 转为 Accepted 前必须具备：

- Java/Node v1.0 与 v1.1 cross-language golden hashes；
- populated V4 → V5 真实 PostgreSQL migration rehearsal；
- v1.0 old JSON/hash verified read；
- v1.1 JSON/typed columns 双向一致与 tamper tests；
- OpenAI adapter loopback protocol、retry=0、timeout、failure mapping、usage retention tests；
- `apps/api` dependency isolation；
- packaged Eval preflight 的 zero-egress evidence；
- real TTY/错误 challenge/replay/expired permit 的 zero-effect evidence；
- POSIX marker、attempt journal、atomic terminal record 与 billing
  `NOT_INVOKED/ATTRIBUTED/UNKNOWN` evidence；
- terminal record 顶层 observed metering 与 Run metering mismatch evidence；
- 一份中文 Build Note 和独立 security/metering review；
- 只有 owner 明确批准后，才允许最多一次 bounded live smoke。

## Implementation note · 2026-07-30

OpenAI Responses adapter 与 loopback protocol/safety tests 已实现，且只由独立
`apps/eval-runner` 装配；普通 API 仍没有 live route。Runner 已形成 compiled synthetic
catalog、默认 packaged zero-egress preflight、real TTY challenge、exact Task-bound
one-shot permit、POSIX marker/journal、atomic terminal record 与 safe receipt。

独立安全审查关闭为 `P0=0、P1=0`；其审查修正要求已经冻结在本 RFC：billing 读取顶层
`observedCostUsd/observedTokenCount`，不能把 sanitizer 后的 Run usage 当 invoice truth；
`UNKNOWN` 不能解释成免费，reservation 只是 authorization ceiling。

Focused/package/full verification、contracts、doc links 与最终 Diff 检查已通过，
因此本 RFC 转为 Accepted。这只代表 runner engineering Gate 完成；live-provider smoke
仍需 owner 另行明确批准。当前没有读取 real key、发起 live request、产生 real model
result 或 billing receipt，S4 Harness comparison 也尚未开始。

## 参考

- [OpenAI · Function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [OpenAI · Conversation state](https://developers.openai.com/api/docs/guides/conversation-state)
- [OpenAI · Tools](https://developers.openai.com/api/docs/guides/tools)
- [OpenAI · Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create)

实现首先以仓库固定的 `openai-java 4.43.0` 与本机 Maven sources 为版本真相，再用以上
官方文档核对 strict tools、manual item replay、encrypted reasoning 与 Responses request
边界；未采用未被官方 Responses reference 证实的 request-level idempotency header。
