# Evaluations

Evaluation contributions are first-class product work.

```text
task-packs/synthetic/   Safe, reproducible tasks with no real user data
regression/             Failures promoted into permanent checks
reports/                Versioned aggregate results and limitations
```

Every Task Pack should state:

- seed and frozen evidence;
- required constraints and forbidden actions;
- expected Artifact class;
- deterministic acceptance checks;
- human review questions;
- risk and fault plan;
- model, Harness, state and tool versions required for replay.

Do not commit real conversations, voice, Self Model records, complete production traces or platform receipts. A realistic synthetic persona is preferable to weak anonymization.

## Gate types

- **Deterministic safety/correctness** checks—Schema, authority, tool allowlist, evidence linkage,
  duplicated side effect, invalid receipt—may block every PR.
- **Stochastic quality** checks—usefulness, style, planning quality—run with fixed versions and
  repeated trials. Judge distributions and failure categories, not one answer.
- **Live-provider smoke** may be scheduled or pre-release because of cost and availability. It must
  save a HarnessRunBundle、one-shot attempt marker、attempt journal、atomic terminal record
  或明确的 billing-`UNKNOWN` failure receipt，且永远不能替代 offline deterministic
  coverage。

Before changing a model, Prompt, Skill or Harness, record the baseline task set, repetitions, grader,
budget and decision threshold. A new version is not better because it is newer, and a Critic model is
not ground truth.

## 当前 Offline baseline

`task-packs/synthetic/002-fake-agent-draft-replay.json` 由
`OfflineFakeAgentRunnerTest` 在两个 fresh in-memory runner 中执行，并比较 exact
Artifact、Result、Safe Trace 和 HarnessRunBundle hashes：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl adapters/inmemory -am \
  -Dtest=OfflineFakeAgentRunnerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该 runner 是 test fixture，不是 CLI 或 product replay API；它不能读取 shared
store、真实用户数据，也不能产生外部副作用。当前结果只证明 frozen synthetic Fake
success 的确定性，不证明真实模型质量或 H0/H1 指标。

## Stage 2 S3 · Isolated synthetic provider runner

S3 只允许独立 `apps/eval-runner` 处理：

- `task-packs/synthetic/003-openai-public-draft-smoke.json`；
- `environments/openai-responses-synthetic-v1.json`；
- compiled catalog 中冻结的 pack、environment、Capture request、Task、execution
  profile、pricing profile 与 attempt hashes。

普通 `apps/api` 不依赖 OpenAI adapter，也没有 live provider route。默认 packaged
command 只执行 zero-egress preflight：

```bash
java -jar apps/eval-runner/target/emerge-eval-runner-0.1.0-SNAPSHOT.jar
```

默认、`--help`、invalid asset、missing TTY、错误 challenge、expired permit 与 replay
必须在读取 credential、创建 client/model、启动 Run 或建立 socket 前失败。只有项目
所有者另行明确批准一次 bounded smoke 后，才允许在 real TTY 手工执行 `--execute`；
它不是 CI、自动化或 Agent 可自行触发的命令。

### 本地 one-shot 状态

Runner 在当前 owner home 下使用：

```text
~/.emergeos/eval-attempts/{attemptId}.attempt
~/.emergeos/eval-attempts/{attemptId}.journal
~/.emergeos/eval-attempts/{attemptId}.run.json
```

- directory 必须是 `0700`，file 必须是 `0600`；
- symlink、foreign owner 与 foreign allow ACL 会 fail closed；
- marker 在 challenge 前使用 `CREATE_NEW`，错误 challenge 也会烧掉当前 host 上的
  attempt；
- 30 秒 permit 绑定 exact Task hash 与 execution profile，并在 provider egress 前
  以 CAS 消费；
- journal 在 credential read、client creation、provider SDK create intent、observed
  attribution 与 terminal publish 周围 append、hash-chain、`fsync`；
- terminal record 使用私有 `.pending`、read-back、`ATOMIC_MOVE` 与 directory
  `fsync` 发布。

这些是本机防误触与审计边界，不是跨主机 exactly-once、provider-side idempotency、
signature、WORM 或 invoice reconciliation。同 UID 恶意进程、owner/root 删除或改写
状态、换主机重放不在保护范围内。Provider SDK create intent 只表示调用可能发生，
不证明 provider 已接收、执行或计费。

### Billing receipt 语义

| 字段 / 状态 | 含义 |
|---|---|
| `NOT_INVOKED` | provider SDK create count 为 0 |
| `ATTRIBUTED` | 每次 create 都取得可信 model/usage，可按 frozen PricingProfile 估算 |
| `UNKNOWN` | 至少一次 create 可能发生，但 observed attribution 不完整 |
| `observedCostUsd/observedTokenCount` | provider response 中实际观察并归因的顶层计量；billing/对账读取这里 |
| `runCostUsd/runTokenCount` | 经过 product sanitizer 的 Run/Bundle projection；失败路径可合法为 0 |
| `meteringMatchesRun` | 两层计量是否一致；`false` 时 Run usage 不能充当 invoice truth |

`UNKNOWN + observedCostUsd=0` 只表示费用未观测，不能解释成免费。Reservation 是 egress
前的 authorization ceiling，不是记账截断上限；provider 已返回的 observed usage 即使
超过 reservation 或 requested budget，也必须完整保存并形成 paid failure。

### 当前证据边界

Runner engineering Gate 已通过：独立安全审查为 `P0=0、P1=0`，focused/package/full
verification、contracts、doc links 与最终 Diff 检查全部为 Green。当前执行证据仍来自 zero-egress packaged
preflight 与 loopback provider；没有读取 real key、执行 live-provider smoke、产生 real
model result、real token receipt 或 billing receipt，也没有开始 S4。`evals/reports/`
当前没有 live report。Preflight receipt、loopback receipt 或 engineering-complete
状态都不能冒充 live-provider receipt。
