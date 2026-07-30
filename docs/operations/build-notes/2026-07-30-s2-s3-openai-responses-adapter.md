# Build Note · 2026-07-30 · Stage 2 S3 OpenAI Responses Adapter

- Change class：`V`
- 实现提交：`f1e1b43`（`feat: add isolated OpenAI Responses adapter`）
- RFC：[RFC-0002](../../rfcs/0002-real-model-synthetic-egress-and-metering.md)
- 状态：adapter protocol/safety slice 完成；live Gate 继续关闭

## 结果

新增独立 `adapters/openai`，把 OpenAI Java SDK `4.43.0` 的 Responses API 映射到
provider-neutral `AgentModel` Session：

```text
server-owned AgentExecutionProfile + exact TaskEnvelope
  → OpenAiResponsesModel Session
  → Responses call 1：strict capture_read
  → Core-owned capture.read ToolResult
  → manual item replay
  → Responses call 2：strict structured final
  → attributed ModelStep（decision + resolvedModel + usage/cost）
```

这个模块没有进入普通 `apps/api`，也不读取环境变量、API key 或 base URL。全部测试只连接
本机 `127.0.0.1` 的 `HttpServer`，使用 sentinel key；没有发起真实 OpenAI 请求，也没有产生
live model result。

## 固定的 protocol 边界

- `store=false`；
- `parallel_tool_calls=false`；
- `service_tier=default`；
- 不使用 `previous_response_id` 或 conversation；
- 第一轮只允许一个 strict `capture_read`，并强制 function choice；
- 第二轮 tools 为空、`tool_choice=none`，要求 strict JSON Schema final；
- reasoning/function call/function output 采用 manual item replay；
- 最终响应允许合法 reasoning items，但不持久化它们；
- 每次 request timeout 使用 Run 的 remaining deadline；
- 每个 socket 前先检查 cancellation 与完整单次 worst-case reservation；
- Session 在 provider call 被接受前即切为 terminal；timeout/断线后不能隐式重复 egress。

## 计量与失败语义

provider 返回完整 model identity 与 usage 后，即使 tool、final 或 attribution 不能接受，
adapter 也返回带真实 token usage 与按 frozen `PricingProfile` 估算 cost 的 typed
`ModelStep.Failed`，不把已发生的费用丢进 exception。

已覆盖：

- usage missing、negative、inconsistent、超 reviewed token ceiling；
- safe resolved model mismatch 与跨 step model drift；
- typed final 非法 JSON、duplicate keys、trailing tokens、foreign Evidence；
- foreign/ambiguous tool arguments；
- `401`、`400/403/404/422`、`429`、`500/503`、timeout 与 disconnect；
- raw provider body/exception 不进入 public message、cause、Trace 或日志；
- cancellation、budget、task/session mismatch 在 socket 前失败；
- closed/terminal Session 不会重新打开 socket；
- 两个交错 Session 的 replay state 不串线。

`costUsd` 是 observed tokens 按冻结价格表计算的审计估算，不是 provider invoice。若 provider
意外解析为另一模型，当前没有该模型的可信价格表，因此只能保留 token 与 requested-model
pricing estimate，不能宣称账单金额准确。

## TDD 与审查回执

- 初始 Acceptance Red：仓库没有 `adapters/openai` 或 `OpenAiResponsesModel`。
- Happy Path Green：两次 loopback Responses request、exact request JSON、manual replay、
  structured final 与 `.000710/.001200` 两步 cost 通过。
- 审查驱动的 Red/Green：
  - `403` 最初误归类为 authentication，修为 `MODEL_REQUEST_REJECTED`；
  - SDK exception cause 可能带 raw provider body，修为只保留 stable typed failure；
  - 合法 final reasoning item 被误判 malformed，修为 reasoning + 恰好一个 message；
  - safe model mismatch 原先抛错并丢 usage，修为 attributed failure；
  - SDK typed parser 可能先于 attribution 失败，修为发 strict schema request、接 raw
    `Response`，先核验 model/usage，再严格解析 output text；
  - timeout/断线后同 Session 原先可再次调用，修为 provider call 前 terminal；
  - ambiguous JSON 原先依赖默认 parser，修为 duplicate detection 与 trailing-token rejection。
- 最终独立复审：`P0=0、P1=0、P2=0`。

## 验证回执

```text
OpenAI adapter                 18 tests
Provider-neutral Agent Loop     8 tests
Contracts                      26 tests
Core                           65 tests
In-memory                      21 tests
PostgreSQL                     45 tests
API unit + packaged IT         32 tests
────────────────────────────────────────
全仓 clean verify             215 tests
failures / errors / skipped     0 / 0 / 0
```

同时通过：

- `./scripts/verify-contracts.sh`：5 Schemas、33 fixtures、2 Task hash vectors、
  2 synthetic Task Packs；
- `./scripts/verify-doc-links.sh`：68 Markdown files；
- `apps/api` dependency tree：不含 `com.openai:*`；
- Maven Enforcer：OpenAI adapter 不依赖 API、Spring、Temporal、PostgreSQL、
  LangChain4j 或上层 Agent framework；
- `git diff --check`。

## 不宣称

- 没有读取真实 API key；
- 没有公网模型请求、真实 token receipt 或真实费用；
- 没有 Eval runner、production client factory 或 `maxRetries(0)` composition proof；
- 没有证明 complete request token upper bound；
- 没有 one-shot operator permit、attempt marker、TTY challenge 或 safe live receipt；
- 没有接入产品 API、Self Model、Connector、Temporal 或社交平台；
- 没有证明模型质量、人格理解、用户价值或 production readiness。

## 下一项可证伪假设

`apps/eval-runner` 应默认只执行 packaged zero-egress preflight。给定 approved、hash-frozen、
PUBLIC 且 literal synthetic 的 Task Pack，它必须在任何 key read/client creation/socket
之前验证 catalog、environment、profile、pricing、完整 Task hash 与 worst-case reservation。
所有 invalid/default 路径都要证明：

```text
keyReads=0
modelFactories=0
clientFactories=0
runStarts=0
httpRequests=0
```

只有显式 `--execute`、一次性 permit、不可覆盖 marker 与交互式 TTY challenge 全部通过后，
才允许读取 key；即便完成这些工程证据，第一次 bounded live smoke 仍需项目所有者另行明确批准。
