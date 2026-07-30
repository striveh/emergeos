# Build Note · 2026-07-30 · Stage 2 S4 Offline Comparison Loader

- Change class：`R`
- Implementation commit：`623abf3`（`feat: add strict offline comparison pack loader`）
- 状态：strict input/preflight boundary 完成；comparison execution 尚未开始

## 这次真正完成了什么

新增独立 `apps/offline-harness-runner`，但没有把现有 OpenAI Eval Runner、产品 API
或任何 Adapter 搬进来。当前唯一 production 行为是：

```text
repo root
  → 固定 Pack 004 relative path
  → symlink / regular-file / 64 KiB bounded read
  → strict JSON parse
  → exact synthetic provenance 与 comparison semantics
  → raw SHA-256 binding
  → immutable typed Pack
```

它只接受：

```text
evals/task-packs/synthetic/
004-offline-reference-verifier-comparison.json
```

Pack raw SHA-256 固定为：

```text
acbafceb05832330165cf065969311fd82d7adba1fa4916f3ed181ebfe5acf44
```

Loader 没有 arbitrary Pack path、CLI、model client、credential reader、HTTP client、
Connector、数据库或 product Store。所有 records 与 loader 都是 package-private，
不会形成新的跨模块公共 API。

## 独立隔离边界

Maven Enforcer 使用 `*:*` default-deny，再精确放行：

- `modules/contracts`；
- `modules/core`；
- Jackson；
- test-only JUnit 及其已知闭包。

一次临时 mutation 把 `org.postgresql:postgresql` 加入本模块 test dependency；
`mvn validate` 在 Enforcer phase 明确失败并指出 PostgreSQL 被 allowlist 拒绝。撤销
mutation 后 dependency tree 只有 contracts、core、Jackson 与 test-only JUnit。

JDK 自带网络类不属于 Maven dependency，因此另有 class-file constant-pool gate。它扫描：

- contracts production classes；
- core production classes；
- offline runner production classes。

Gate 拒绝已知 `java.net`、`javax.net`、RMI、sync/async socket channel、channel
provider，以及 `ProcessBuilder` / `Runtime` process escape；同时覆盖 JVM slash name、
descriptor 和 literal reflection dot binary name。Test-only fixture 真实编译
`AsynchronousSocketChannel`、`ProcessBuilder`、
`Class.forName("java.net.Socket")` 与
`Class.forName("java.lang.ProcessBuilder")`，证明 classifier 不是常量自证。

这个 gate 只约束当前允许 production closure 的已编译 references。它不是 OS sandbox、
firewall、packet capture 或恶意 native-code 证明。

## TDD 与审查驱动的 Red

### Null / semantic Red

先加入 missing reference、explicit null reference、null primitive 与 null narrative
array tests。实现前：

```text
Pack004LoaderTest
tests       16
failures     2
```

两个关键失败分别是：

- 期望 `PACK_JSON_INVALID`，实际得到 `PACK_SEMANTICS_MISMATCH`；
- 期望 `PACK_JSON_INVALID`，实际得到 `PACK_RAW_SHA256_MISMATCH`。

这证明 reference `null` 曾穿过 strict parser，而 narrative `null` 只被最终 hash
偶然挡住。Green 让所有非 nullable record component 在 construction 时 fail-fast，
只保留 `ExpectedOutcome.failureReason` 为显式 nullable，并把 `acceptanceChecks`、
`humanReviewQuestions` 与 `faultPlan` 纳入 exact semantic binding。

### Dependency allowlist Red

临时加入 PostgreSQL dependency 后：

```text
Rule: BannedDependencies
Result: BUILD FAILURE
org.postgresql:postgresql:42.7.11
  <--- banned via the exclude/include list
```

这证明规则是 default-deny allowlist，而不是只列举若干已知坏依赖的 denylist。

### Bytecode classifier Red

第一版 classifier 漏掉 descriptor 与 dot binary-name。Fixture tests 分别先因
`AsynchronousSocketChannel` descriptor 和 `Class.forName("java.net.Socket")`
未命中而 Red；Green 后 direct、descriptor、slash 与 dot literal 四类都被识别。

## 最终验证

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/offline-harness-runner -am clean verify
```

```text
Contracts                         26 tests
Core                              79 tests
Offline Harness Runner            18 tests
──────────────────────────────────────────
合计                             123 tests
failures / errors / skipped       0 / 0 / 0
```

同时：

- Maven Enforcer Green；
- compile dependency tree 只有 contracts/core/Jackson；
- Pack raw SHA 与 compiled constant 一致；
- `git diff --check` Green；
- 独立最终审查：`P0=0、P1=0`。

## 已知限制

- component check 与 file open 仍是多次 pathname 操作；ancestor symlink 与同 UID
  TOCTOU 属于后续 defense-in-depth。最终 raw hash 会拒绝不同 bytes，但不是 dirfd/
  `openat` identity pinning。
- bytecode gate 扫描所有 UTF8 constants，普通文案若恰好包含 class-like name 可能
  false positive；当前选择 fail-closed。
- 当前没有 candidate generator、H0/H1 execution、comparison report、report verifier、
  atomic report store 或跨 JVM report verification。

## 不宣称

- 没有执行 12 个 paired candidates 或 24 次 verifier evaluation；
- 没有形成 observed Harness comparison result；
- 没有读取 credential、调用模型或产生费用；
- 没有访问网络、真实用户数据、账号、Connector 或 PostgreSQL product truth；
- 没有证明真实模型质量、用户价值、Self Model 能力或 production autonomy。

## 下一项可证伪假设

下一切片在同一隔离模块中：

1. 每个 case/repetition 只生成一次 immutable candidate；
2. H0 与 H1 消费同一 candidate fingerprint；
3. H1 只调用 production `AgentDraftReferenceGrounding` facade；
4. 真实执行 12 个 shared candidate generations / 24 次 verifier evaluations；
5. report verifier 不信任 stored summary，而是重建 candidate、重跑两臂并重算
   IDs、hash、matrix 与 owned-effect counters。

完成这些验证前，Pack 中的 expected totals 继续只是设计输入。
