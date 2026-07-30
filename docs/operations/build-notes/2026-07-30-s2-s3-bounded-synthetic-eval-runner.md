# Build Note · 2026-07-30 · Stage 2 S3 Bounded Synthetic Eval Runner

- Change class：`V`
- 实现提交：`e90c704`（`feat: add bounded synthetic eval execution`）
- RFC：[RFC-0002](../../rfcs/0002-real-model-synthetic-egress-and-metering.md)
- Eval 说明：[Evaluations](../../../evals/README.md)
- 状态：`engineering complete`；Live-provider smoke 尚未执行

> 后续状态：S4 fixed comparison、independent replay 与 canonical durable report
> 已完成各自工程切片，见
> [Verified Offline Comparison](./2026-07-30-s2-s4-verified-offline-comparison.md)
> 和
> [Durable Offline Comparison](./2026-07-30-s2-s4-durable-offline-comparison-report.md)。
> 本文其余 S4 表述保留 `e90c704` 时点的历史事实。

## 结果

新增独立 `apps/eval-runner`，把已经完成 loopback protocol 验证的 OpenAI Responses
adapter 装配到一个严格限定的 synthetic execution path：

```text
checked-in PUBLIC synthetic Task Pack
  → hash-frozen preflight
  → real TTY + exact attempt challenge
  → POSIX one-shot marker
  → 30 秒 Task-bound permit
  → permit 后单次 credential read
  → fixed production OpenAI client policy
  → provider-neutral AgentLoopKernel
  → deterministic product verification
  → local atomic terminal Run record
  → safe execution receipt + attempt journal
```

普通 `apps/api` 继续只装配 Fake baseline，不依赖 OpenAI SDK，也没有 live route。Runner
不依赖 API、PostgreSQL、Spring、Temporal、in-memory 产品 Adapter、Self Model、
Connector 或真实用户数据。

## 默认 zero-egress

默认 packaged command 只做 preflight：

```bash
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar
```

它核验：

- Java 21；
- Task Pack 与 environment manifest raw SHA-256；
- literal synthetic provenance 与 `dataClass=PUBLIC`；
- Capture request hash 与完整 Task hash；
- execution/pricing profile fingerprint；
- model、tool、policy、deadline、provider request count 和 token ceiling；
- whole-run reservation 与 exact attempt ID。

默认路径不得创建 marker、读取 credential、创建 client/model、启动 Run 或建立 socket。
`--help`、asset drift、missing TTY、错误 challenge、expired permit 与 replay 也必须在
相关副作用之前 fail closed。

## One-shot operator Gate

显式 `--execute` 只允许项目所有者在 real TTY 中手工启动。Gate 顺序被冻结为：

1. 完成 zero-egress preflight；
2. 验证 real TTY；
3. 在当前 owner home 的私有 POSIX 目录用 `CREATE_NEW` 创建 exact attempt marker；
4. 显示并要求逐字输入 `EXECUTE {attemptId}`；
5. arm 30 秒 permit，并再次绑定完整 Task hash 与 execution profile；
6. 只有以上步骤通过后才允许单次读取 `OPENAI_API_KEY`；
7. AgentKernel egress 前使用 CAS 消费 permit。

Marker 在 challenge 前创建，因此错误 challenge 也会烧掉当前 host 上这一次 attempt。
Production client 固定：

- base URL `https://api.openai.com/v1`；
- `Proxy.NO_PROXY`；
- `maxRetries(0)`；
- Run deadline timeout；
- SDK log level `OFF`。

CLI、Task 或 ambient environment 不能覆盖 model、pricing、tool、base URL、budget 或 key
source。

## POSIX、Journal 与 atomic record

本地状态位于：

```text
~/.emergeos/eval-attempts/{attemptId}.attempt
~/.emergeos/eval-attempts/{attemptId}.journal
~/.emergeos/eval-attempts/{attemptId}.run.json
```

安全边界：

- directory 必须是 `0700`，file 必须是 `0600`；
- 拒绝 symlink、foreign owner，以及 filesystem provider 可见的 foreign allow ACL；
  当前 macOS JDK 不暴露 ACL view，因此不可把它解释成 hostile-local-user
  authorization；
- journal 在 credential read、client creation、每次 provider SDK create intent、
  observed attribution 与 terminal publish 周围 append hash-chain event，并在每次写入
  后 `fsync`；
- provider SDK create intent 是保守事实，只表示调用可能发生，不证明 provider 已接收、
  已执行、已计费或未计费；
- terminal record 先以 `CREATE_NEW` 写私有 `.pending`，`fsync`、read-back 后使用
  `ATOMIC_MOVE` 发布，并 `fsync` directory；target 已存在时是否替换是
  provider-specific，当前 `CREATE_NEW` marker 约束 cooperative flow，但 record store
  自身的 no-overwrite hardening 仍开放；
- record 同时保存完整 synthetic AgentRun、Artifact、Bundle、provider-observed
  metering、Run metering 与 effects counter。

这是本地 atomic publish，不与 provider request 形成一个 transaction，也不替代 S2
PostgreSQL product `AgentRun`。One-shot 只覆盖当前 POSIX host 与当前 owner home；它不防
同 UID 恶意进程、owner/root 删除或重写状态、换主机重放，也不是 provider-side
idempotency、签名、WORM、remote approval 或跨主机 exactly-once。

## Billing 与 reservation 语义

Billing 三态：

| 状态 | 含义 |
|---|---|
| `NOT_INVOKED` | provider SDK create count 为 0 |
| `ATTRIBUTED` | 每次 create 都有可信 model/usage |
| `UNKNOWN` | 至少一次 create 可能发生，但 observed attribution 不完整 |

`UNKNOWN + observedCostUsd=0` 只表示费用未观测，不能解释成免费，也不能自动 retry。

Terminal record 与 safe receipt 分开保存：

- `observedCostUsd/observedTokenCount`：adapter 从 provider response 实际观察并按 frozen
  PricingProfile 归因的顶层计量；billing 与后续对账必须读取这里；
- `runCostUsd/runTokenCount`：经过 product sanitizer 的 Run/Bundle projection；
  失败路径可以合法为 `0`；
- `meteringMatchesRun`：两层是否一致。`false` 时 Run usage 不能充当 invoice truth。

Reservation 是 egress 前的 authorization ceiling，不是最终记账上限。Provider 已返回的
observed usage 即使超过 reservation 或 requested budget，也必须完整保留并形成 paid
failure，不能截断、清零或丢进 exception。该实现仍不提供 provider invoice
reconciliation 或全账户 hard spend cap。

## TDD、故障与审查证据

当前实现包含下列可证伪测试边界：

- packaged default preflight 的 zero-egress receipt；
- packaged `--execute` 在 missing TTY 时 key/client/marker 均为零；
- wrong challenge burns attempt，随后 replay 仍不读取 credential；
- invalid asset 在 TTY 与所有副作用前失败；
- credential missing/read failure/client creation failure 都留下
  `NOT_INVOKED` safe receipt；
- loopback happy path 恰好两次 Responses create，并持久化 attributed metering；
- disconnect/timeout 或 post-provider integrity failure 使用 conservative
  billing `UNKNOWN`；
- sanitizer 把 failed Run usage 合法归零时，top-level observed metering 仍保留且
  `meteringMatchesRun=false`；
- observed usage 超 reservation 时仍持久化完整计量；
- terminal record 与 journal 不包含 credential、raw provider body、synthetic Seed
  或 reasoning。

独立安全审查最终为 `P0=0、P1=0`。审查要求形成了本 Build Note 中的核心边界：
provider-observed metering 与 Run projection 分栏、`UNKNOWN` 非免费、reservation 非记账
截断线，以及 POSIX/journal/atomic publish 的准确威胁模型。

## 最终验证回执

实现提交 `e90c704` 的隔离 worktree 已完成 full clean verification：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/eval-runner -am verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
./mvnw --batch-mode --no-transfer-progress clean verify
git diff --check
```

```text
Contracts                         26 tests
Core                              65 tests
Provider-neutral Agent Loop        8 tests
OpenAI Responses Adapter          19 tests
In-memory Adapters                21 tests
PostgreSQL Adapter                45 tests
API unit + packaged IT            32 tests
Synthetic Eval unit + packaged IT 30 tests
──────────────────────────────────────────
全仓 clean verify                246 tests
failures / errors / skipped        0 / 0 / 0
```

同时通过：

- focused Eval reactor：Contracts 26、Core 65、Agent Loop 8、OpenAI 19、
  Eval unit 28、packaged IT 2；
- `./scripts/verify-contracts.sh`：5 Schemas、33 fixtures、2 Task hash vectors、
  3 synthetic Task Packs、1 environment manifest；
- `./scripts/verify-doc-links.sh`：70 Markdown files；
- 两路独立审查：`P0=0、P1=0`；
- `git diff --check`。

## 不宣称

- 没有读取 real API key；
- 没有访问 live OpenAI provider；
- 没有 real model result、real token receipt 或 billing receipt；
- 没有证明一次 provider request 的 invoice 金额；
- 没有证明模型质量、用户价值、production readiness 或 Harness 优势；
- 没有 product live route、Temporal、Self Model、Connector 或社交平台动作；
- 没有解决 stale Run 的 durable resume、跨主机 one-shot 或恶意 owner/root；
- S4 尚未开始。

## 下一项可证伪假设

Runner engineering Gate 已关闭。下一步自动推进 production read-only journal verifier
与真实 fat-JAR crash/restart evidence，不读取 credential、不访问 live provider。唯一一次
bounded live-provider smoke 仍由项目所有者另行批准；没有 live receipt 时不得声称已有
real-model fixed baseline。S4 offline Harness/fault infrastructure 可继续研发，live
experiment 继续保持 Gate closed。
