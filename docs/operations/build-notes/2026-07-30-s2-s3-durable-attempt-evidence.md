# Build Note · 2026-07-30 · Stage 2 S3 Durable Eval Attempt Evidence

- Change class：`V`
- Journal verifier：`3a0d1fc`（`feat: verify durable eval attempt evidence`）
- S4 comparison foundation：`c14285a`（`evals: freeze offline verifier comparison`）
- Crash/restart evidence：`23e1773`（`feat: prove eval attempt crash recovery`）
- 前置回执：[Bounded Synthetic Eval Runner](./2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)
- 状态：本地 durability engineering slice 完成；live-provider Gate 继续关闭

> 后续状态：S4 Task Pack 004 后续已完成 fixed comparison、independent replay 与
> canonical durable report，见
> [Verified Offline Comparison](./2026-07-30-s2-s4-verified-offline-comparison.md)
> 和
> [Durable Offline Comparison](./2026-07-30-s2-s4-durable-offline-comparison-report.md)。
> 本文其余 S4 “下一步”表述保留本回执时点的历史事实。

## 结果

本切片没有扩大真实模型权限，而是回答一个更基础的问题：

> Eval 进程在 provider 边界或 terminal record 发布边界被强制终止后，新进程能否只根据
> 已经 durable 的本地证据，说清楚“可以确认什么、仍然未知什么、哪些证据已经无效”？

结果由两部分组成：

```text
POSIX attempt marker
  → bounded、hash-chained journal read
  → provider intent / attributed usage 的可信前缀
  → pending / final / terminal-linked record state
  → VERIFIED / UNKNOWN / INVALID

packaged fat-JAR process
  → 到达选定 durable phase 后阻塞
  → parent process 强制终止
  → 新 JVM 执行 read-only verification
  → 再次核验结果不变且文件不变
  → replay 被既有 marker 拒绝，HTTP count 不增加
```

`PosixAttemptJournalVerifier` 位于 production source，但当前没有把它开放成 product API
或 shipping CLI。Crash injection main 只位于 `target/test-classes`，不会打入 shipping
fat JAR；正式 CLI 对 `--crash-at=...` 返回 `ARGUMENTS_INVALID`，且不创建
`~/.emergeos` 状态。

## Read-only verifier 的真相边界

Verifier 同时返回三条互补信息，调用方不能只读取其中一条：

| 维度 | 状态 | 含义 |
|---|---|---|
| `Verdict` | `VERIFIED` | terminal journal event 与其引用的 cooperative append-once run record 在 hash、identity 与语义上相互成立 |
| `Verdict` | `UNKNOWN` | 证据是合法但尚未闭合的 crash snapshot，不能推断 provider 没有执行或没有计费 |
| `Verdict` | `INVALID` | path、permission、sequence、hash chain、record hash 或 terminal semantics 存在矛盾 |
| `BillingStatus` | `NOT_INVOKED / ATTRIBUTED / UNKNOWN` | 表达完整可信 journal prefix 中能够归因的 provider intent 与 usage |
| `RecordState` | `ABSENT / PENDING_NON_AUTHORITATIVE / FINAL_UNSEALED / TERMINAL_LINKED` | 表达 atomic publish 走到哪一层；只有 `TERMINAL_LINKED` 可以支撑 `VERIFIED` |

Verifier 使用 strict duplicate-key JSON parsing、trailing-token rejection、有界
journal/line/record read、POSIX owner/permission 检查，并在 filesystem provider 暴露
ACL 时检查 visible foreign allow ACL；随后校验事件 sequence/hash-chain，并重新验证
terminal record 中的 Task、Run、Artifact、Bundle、profile、pricing 与 environment
绑定。损坏或矛盾证据 fail closed；它不会读取 credential、构建 model client 或访问
网络。当前 macOS JDK 不暴露 ACL view，因此该边界不抵御 hostile local user。

`trustedPrefix=true` 只表示**本次读取到的完整 journal prefix**通过了 hash 与状态机校验。
它不是 closed ledger，也不表示 `UNKNOWN` attempt 已终止；仍在运行的同 UID 进程可能在
这次 snapshot 之后继续追加 intent 或 attribution。Billing consumer 必须把
`Verdict + BillingStatus + RecordState` 一起解释。

## 7-point fat-JAR 强制终止矩阵

`SyntheticEvalCrashRestartProcessIT` 使用 loopback Responses server 和 sentinel
credential，在每个选定 durable phase 启动一个新 packaged process。READY 文件只在
对应 phase 的 durability operation 返回后写入；parent 确认 child 仍存活后
`destroyForcibly()`，再启动新的 JVM 核验。

下表中的费用和 token 全是 loopback synthetic fixture，不是真实 provider receipt：

| Crash phase | HTTP requests | Verdict | Billing | Observed synthetic usage | Record state |
|---|---:|---|---|---:|---|
| `GATE_APPROVED_DURABLE` | 0 | `UNKNOWN` | `NOT_INVOKED` | `$0 / 0` | `ABSENT` |
| `CREDENTIAL_READ_STARTED` | 0 | `UNKNOWN` | `NOT_INVOKED` | `$0 / 0` | `ABSENT` |
| `PROVIDER_SDK_CREATE_INTENT_DURABLE` | 0 | `UNKNOWN` | `UNKNOWN` | `$0 / 0` | `ABSENT` |
| `PROVIDER_ATTRIBUTED_DURABLE` | 1 | `UNKNOWN` | `ATTRIBUTED` | `$0.000165 / 120` | `ABSENT` |
| `RUN_RECORD_PENDING_DURABLE` | 2 | `UNKNOWN` | `ATTRIBUTED` | `$0.000413 / 300` | `PENDING_NON_AUTHORITATIVE` |
| `RUN_RECORD_FINAL_DURABLE` | 2 | `UNKNOWN` | `ATTRIBUTED` | `$0.000413 / 300` | `FINAL_UNSEALED` |
| `TERMINAL_JOURNAL_DURABLE` | 2 | `VERIFIED` | `ATTRIBUTED` | `$0.000413 / 300` | `TERMINAL_LINKED` |

每个 case 还证明：

- 首次和第二次新 JVM verification 输出完全相同；
- verification 前后的 evidence 文件长度、mtime 与 SHA-256 snapshot 不变；
- replay 因 `MARKER_ALREADY_EXISTS` 被拒绝；
- replay 后 loopback HTTP request count 不增加；
- process output 不包含 sentinel key、Authorization、provider response id、reasoning、
  synthetic Seed 或 raw exception；
- crash harness class 不在 shipping fat JAR，shipping CLI 不能触发该 observer。

因此，这组测试证明的是**选定 durable boundary 的 process-kill closure**，不是任意机器
指令处的崩溃、物理断电或 provider-side exactly-once。

## S4 Task Pack 004：只完成 comparison foundation

`c14285a` 冻结了
`evals/task-packs/synthetic/004-offline-reference-verifier-comparison.json` 与相应
repository validator。它只改变一个 Harness variable：

```text
H0: schema-only-eval-v1
H1: agent-draft-verifier-v1（reference grounding）
```

Pack 固定 4 个 deterministic Fake candidates：

1. grounded；
2. claim without tool；
3. omitted evidence claim；
4. extra forged claim。

设计矩阵要求 2 arms × 4 cases × 3 repetitions，共 24 runs / 12 paired candidates；
预期 H0 接受 12 个候选（其中 9 个 reference fault），H1 接受 3 个 grounded 候选并拒绝
9 个 fault。**这些数字当前只是 frozen expected matrix，不是已执行结果。** Comparison
runner、24-run report、真实模型、人工盲评与质量结论都尚不存在。

## TDD、审查与验证回执

- `3a0d1fc` 增加 17 个 focused journal-verifier tests，覆盖合法 terminal、
  gate/intent/attribution/final 的 incomplete snapshots、torn tail、sequence/hash
  tamper、unsafe path/permission、oversized evidence 与 terminal record mismatch；
- `c14285a` 把 Pack 004 的 provenance、单变量、arms、cases、repetitions 与 expected
  totals 变成 repository contract validation，不执行实验；
- `23e1773` 增加 7-case crash matrix 与 shipping CLI negative case，共 2 个 Failsafe
  integration test methods；
- 独立最终审查：`P0=0、P1=0`；剩余限制作为 P2 / known limits 保留，不把测试强度扩大
  解释成 production guarantee。

`23e1773` 的 isolated clean verification：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/eval-runner -am clean verify \
  -Dit.test=SyntheticEvalCrashRestartProcessIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

```text
Contracts Surefire                 26 tests
Core Surefire                      65 tests
Provider-neutral Agent Loop         8 tests
OpenAI Responses Adapter           19 tests
Synthetic Eval Surefire            45 tests
Synthetic Eval Failsafe             2 tests
──────────────────────────────────────────
合计                             165 tests
failures / errors / skipped       0 / 0 / 0
总耗时                           26.531 s
```

其中 Failsafe 的第一个 test method 内部逐一执行上述 7 个 crash cases；“2 个 Failsafe
tests”不能误读成只有 2 个 crash boundary。

## P2 / known limits

- **Path TOCTOU**：Verifier 在 path/owner/permission 检查、bounded read 与再次检查之间
  仍以 pathname 访问，没有 dirfd/openat-style identity pinning；同 UID 恶意进程不在
  当前威胁模型内。
- **Marker locator**：Verifier 校验 marker 的 exact pathname、file type、owner 与权限，
  但不校验 marker body；当前 marker 是 attempt evidence locator，不是带内容承诺的
  signed record。
- **`trustedPrefix` snapshot**：它只认证本次读取到的 journal prefix，不关闭
  `UNKNOWN` attempt，也不证明之后不会再追加费用证据。
- **Observer seam**：crash harness 依赖 package-private `EvalExecutionObserver` 在 7 个
  选定 durable operation 之后阻塞；这不是 arbitrary-instruction fault injection。
- **CodeSource assertion**：测试通过 `PropertiesLauncher`、shipping fat JAR 与
  `target/test-classes` loader path 启动，并证明 harness 不在 fat JAR；但尚未逐个断言
  production class 的 `ProtectionDomain/CodeSource` 一定指向该 fat JAR。
- **Storage scope**：只验证本机 POSIX filesystem；没有物理断电、disk-cache fault、
  NFS/non-POSIX filesystem、fs corruption 或跨主机恢复证据。
- **Network scope**：只用 loopback `HttpServer` 的 request counter；没有 packet
  capture，也没有真实 provider acceptance、provider invoice reconciliation 或远端
  idempotency 证据。

## 不宣称

- 没有读取真实 API key；
- 没有访问 live OpenAI provider；
- 没有 real model result、real token receipt、真实费用或账单回执；
- 没有执行一次 Pack 004 comparison run，更没有完成 24-run report；
- 没有证明断电、NFS、磁盘硬件故障或任意 instruction crash；
- 没有证明 provider-side exactly-once、跨主机 one-shot 或恶意 same-UID/root 防护；
- 没有接入 product API、Temporal、Self Model、Connector 或社交平台动作；
- 没有证明模型质量、人格理解、用户价值或 production readiness。

## 下一项可证伪假设

下一步应实现 Pack 004 所需的最小 `AgentDraftVerifier` seam 与隔离 offline comparison
runner，让 H0 只存在于 Eval path、H1 保持 product default；然后真正执行 24 个
deterministic runs，输出 pair-complete、fingerprint-bound report。只有实际 run artifact
与 verifier 重算都成立后，才能把 expected matrix 升级为 experiment result。
