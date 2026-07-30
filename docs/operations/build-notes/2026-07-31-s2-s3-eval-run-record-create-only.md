# Build Note · 2026-07-31 · Stage 2 S3 Eval Run Record Create-only

- Change class：`R`
- Code commit：`2b50c66`（`fix: make eval run record create-only`）
- 关联 RFC：
  [RFC-0001](../../rfcs/0001-persistent-agent-run-trace-and-bundle.md)、
  [RFC-0002](../../rfcs/0002-real-model-synthetic-egress-and-metering.md)
- 历史回执：
  [Bounded Synthetic Eval Runner](./2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)、
  [Durable Eval Attempt Evidence](./2026-07-30-s2-s3-durable-attempt-evidence.md)
- 状态：工程 Gate Green；live-provider、power-loss 与产品价值 Gate 仍开放

## 这次交付的系统结果

`apps/eval-runner` 的 terminal JSON record 不再把 Java
`ATOMIC_MOVE` 当成 no-replace。writer 现在使用 hard-link create-only logical
commit：

```text
CREATE_NEW pending
  → pending file fsync
  → pending read-back + semantic verification
  → directory fsync
  → createLink(target, pending)
  → directory fsync
  → RUN_RECORD_LINK_COMMIT_COMPLETE
  → pending cleanup
  → directory fsync
  → authoritative target revalidation
  → terminal journal event
```

如果另一个进程在最初的 target precheck 之后创建 target，`createLink` 只能失败，
已有 target 的 file identity 和 bytes 不会被本 writer 覆盖。hard link 不支持时
fail closed，不降级为 move。

read-only verifier 同时理解 pending cleanup 的 crash residue：target 与 pending
只有在二者指向同一个 non-null file identity 时才是同一份 committed record；不同
inode 即使 bytes 完全相同也属于矛盾证据。

本增量没有扩大 HTTP、JSON Schema、Core contract 或 public Java API，也没有增加
真实模型权限。

## 为什么旧实现不够

旧顺序是：

```text
target precheck
  → CREATE_NEW pending
  → pending fsync/read-back
  → Files.move(pending, target, ATOMIC_MOVE)
```

“atomic”只说明 move 作为一个操作完成，不等于“target 已存在时绝不替换”。第一条
Acceptance Red 在 `RUN_RECORD_PENDING_DURABLE` observer 中创建一个 owner-private
竞争 target。macOS JDK provider 的旧实现随后覆盖了它，证明：

> one-shot marker 可以约束 cooperative product flow，但不能替 record store 自身提供
> create-only 线性化点。

第二条 Red 构造 hard-link commit 后、cleanup 前的 target+pending 同 inode状态。
旧 verifier 把它当作冲突，无法区分“同一文件有两个名字”和“两个不同文件内容碰巧相同”。

## TDD 缺陷链

第一条 exact Acceptance Red：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/eval-runner -am \
  -Dtest=SyntheticEvalLoopbackTest,PosixAttemptJournalVerifierTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

初始结果是恰好 2 个目标 failure：

1. precheck 后竞争 target 没有触发预期拒绝，旧 `ATOMIC_MOVE` 覆盖了竞争文件；
2. target+pending 同一 inode、无 terminal journal 被旧 verifier 判为 `INVALID`，
   而不是保守的 `UNKNOWN / FINAL_UNSEALED`。

Green 后又补入以下 adversarial regressions：

- `FileAlreadyExistsException` 固定为 `RUN_RECORD_ALREADY_EXISTS`；
- `UnsupportedOperationException` 固定为
  `RUN_RECORD_LINK_COMMIT_UNSUPPORTED`；
- 其他 link I/O failure 保守失败，三者都没有 fallback move；
- pending cleanup 报错后：
  - `SAME_FILE`：保留可恢复 committed residue；
  - `ABSENT`：cleanup 已实际完成；
  - `DIFFERENT_FILE`：fail closed；
- verifier race：
  - `SAME_FILE → ABSENT` 是合法 cleanup 前进；
  - `SAME_FILE → DIFFERENT_FILE` 固定 `INVALID`；
  - `DIFFERENT_FILE → ABSENT` 不能升级成合法；
- malformed pending-only bytes 仍是 non-authoritative `UNKNOWN`，不会被 decode；
- terminal journal + pending-only 固定 `INVALID`；
- equal bytes / different inode 无论有无 terminal journal 都固定 `INVALID`；
- precheck 后竞争 target 的 fileKey 和 bytes 保持不变，pending 仍是 owner-private
  `0600`，没有 terminal journal，safe receipt 保持 billing `UNKNOWN`。

## Writer 与 verifier 状态机

| Terminal journal | Record paths | 解释 |
|---|---|---|
| 无 | 无 target / pending | `UNKNOWN / ABSENT` |
| 无 | pending only | `UNKNOWN / PENDING_NON_AUTHORITATIVE`；不读取为结果 |
| 无 | target only | `UNKNOWN / FINAL_UNSEALED` |
| 无 | target + pending，同一 non-null identity | `UNKNOWN / FINAL_UNSEALED` |
| 无 | target + pending，不同 identity | `INVALID / RUN_RECORD_STATE_INVALID` |
| 有 | target 匹配 terminal，无 pending | `VERIFIED / TERMINAL_LINKED` |
| 有 | target + pending，同一 identity，target 匹配 terminal | `VERIFIED / TERMINAL_LINKED` |
| 有 | target + pending，不同 identity | `INVALID` |
| 有 | pending only | `INVALID` |
| 有 | target / pending 均缺失 | `UNKNOWN / TERMINAL_RECORD_MISSING / ABSENT` |

`VERIFIED` 仍要求 terminal journal event、record SHA-256、Task、Run、Trace、
Artifact、Bundle、profile、pricing、environment 与 observed billing 语义全部一致。
hard-link identity 只处理 publish/cleanup 状态，不取代 terminal evidence。

## 8-window fat-JAR 强制终止矩阵

`SyntheticEvalCrashRestartProcessIT` 加载 shipping fat JAR 的 production classes；
crash harness 仍只存在于 `target/test-classes`。每个 case 都在对应 operation 完成后
写 READY，parent 确认 child 存活，再执行 `destroyForcibly()`：

| Crash phase | HTTP | Verdict | Billing | Record state |
|---|---:|---|---|---|
| `GATE_APPROVED_DURABLE` | 0 | `UNKNOWN` | `NOT_INVOKED` | `ABSENT` |
| `CREDENTIAL_READ_STARTED` | 0 | `UNKNOWN` | `NOT_INVOKED` | `ABSENT` |
| `PROVIDER_SDK_CREATE_INTENT_DURABLE` | 0 | `UNKNOWN` | `UNKNOWN` | `ABSENT` |
| `PROVIDER_ATTRIBUTED_DURABLE` | 1 | `UNKNOWN` | `ATTRIBUTED` | `ABSENT` |
| `RUN_RECORD_PENDING_DURABLE` | 2 | `UNKNOWN` | `ATTRIBUTED` | `PENDING_NON_AUTHORITATIVE` |
| `RUN_RECORD_LINK_COMMIT_COMPLETE` | 2 | `UNKNOWN` | `ATTRIBUTED` | `FINAL_UNSEALED`，同 inode residue |
| `RUN_RECORD_FINAL_DURABLE` | 2 | `UNKNOWN` | `ATTRIBUTED` | `FINAL_UNSEALED` |
| `TERMINAL_JOURNAL_DURABLE` | 2 | `VERIFIED` | `ATTRIBUTED` | `TERMINAL_LINKED` |

新增 link-complete phase 位于 `createLink` 和 target-directory `fsync` 完成之后、
pending cleanup 之前。它证明 selected process-kill 后的新 JVM 能看到并解释该状态；
不把 Java `FileChannel.force`、进程死亡或测试机文件系统扩大解释为物理断电保证。

每个 case 还证明：

- 两个 fresh verifier JVM 输出相同；
- 每次 verifier 后 target/pending shape 与 `Files.isSameFile` 关系不变；
- evidence directory 的 fileKey、POSIX mode、owner，以及各 evidence file 的
  fileKey、POSIX mode、owner、size、mtime 与 SHA-256 snapshot 均不变；
- replay 固定被 marker 拒绝，loopback HTTP request count 不增加；
- link-residue case 把 pending 替换为 equal bytes / different inode 后，packaged
  verifier 返回 exit 3 + `INVALID`，且不改写 evidence；
- output 不含 sentinel key、Authorization、raw provider response、reasoning、
  synthetic Seed 或 exception；
- shipping CLI 拒绝 crash injection 参数，shipping fat JAR 不包含 harness class。

## ACL 与本地威胁边界

重复的 ACL predicate 已抽成 package-private `VisibleAclPolicy`。它只检测
filesystem provider **可见**的 foreign `ALLOW` entry：

- owner `ALLOW`：不拒绝；
- foreign `DENY`：不误判为授权；
- foreign `ALLOW`：拒绝；
- provider 不暴露 `AclFileAttributeView`：不能声称 ACL 已验证。

directory `0700`、file `0600`、owner、regular file、no symlink 与 visible ACL
共同构成 cooperative local boundary。它不抵御 same-UID、owner 或 root，也没有
`dirfd/openat/openat2` pathname anchoring。verifier 的 fileKey 检查和二次核验降低
误判风险，但不是 hostile-local-user authorization 或 forensic filesystem audit。

## 验证回执

Focused：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/eval-runner -am \
  -Dtest=PosixEvalRunRecordStoreTest,PosixAttemptJournalVerifierTest,SyntheticEvalLoopbackTest,VisibleAclPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：39 tests，0 failure/error/skipped。

Packaged process clean verification：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/eval-runner -am clean verify \
  -Dit.test=SyntheticEvalCrashRestartProcessIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

| Module / phase | Tests |
|---|---:|
| Contracts Surefire | 26 |
| Core Surefire | 79 |
| Provider-neutral Agent Loop | 8 |
| OpenAI Responses Adapter | 19 |
| Synthetic Eval Surefire | 61 |
| Synthetic Eval Failsafe | 2 |
| 合计 | 195 |

结果：0 failure/error/skipped，总耗时 `18.555 s`。Failsafe 的第一个 test method
内部逐一执行上述 8 个 crash cases；“2 个 Failsafe tests”不能误读成只有 2 个窗口。

两名独立 code/test reviewer 的最终结论均为 `P0=0、P1=0`。保留的 P2：

- same-UID pathname ABA、inode reuse 与完整 identity receipt 未解决；
- Java `fileKey().toString()` snapshot 不是跨 provider 的正式 identity contract；
- cleanup failure 由 helper-level deterministic tests 与 verifier residue tests
  分段证明，尚无整条 injected `save → cleanup failure → terminal → fresh JVM`
  process test。

最终全仓 clean reactor：

```text
Contracts                         26
Core                              79
Agent Loop                         8
OpenAI Adapter                    19
In-memory Adapters                21
PostgreSQL Adapter                45
API                               32
Synthetic Eval Runner             65
Offline Harness Runner            83
────────────────────────────────────
合计                              378
failures / errors / skipped       0 / 0 / 0
总耗时                            01:19
```

其中 Synthetic Eval Runner 是 61 个 Surefire + 4 个 Failsafe；完整 reactor 同时包含
原有 packaged preflight tests 和本增量的 8-window crash/restart matrix。

最终命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
```

Contract verifier 通过：

- 5 个 JSON Schema；
- 33 个 fixtures；
- 2 个 cross-language Task hash golden vectors；
- 4 个 synthetic Task Packs；
- 1 个 synthetic environment manifest。

Doc link verifier 在排除 local-only `.workbuddy` 后通过 74 个 Markdown files；
`git diff --check` Green。

三名独立 docs merge reviewer 均为 `P0=0、P1=0`。审查中发现的两处非阻塞措辞问题
也已在提交前修正：directory/file snapshot 字段不再被混写，eval-runner 的 8-window
matrix 也与 S4 offline runner 的独立 7-window matrix 明确区分。

## 可以声明与不能声明

可以声明：

> 在 tested local POSIX filesystem 与 cooperative owner boundary 内，eval-runner
> 使用 hard-link create-only logical commit 发布 frozen PUBLIC synthetic run record；
> precheck 后出现的 target 不会被覆盖。selected process kill 后，fresh JVM 能把
> pending-only、同 inode committed residue、不同 inode conflict 与 terminal-linked
> record 分别解释为保守且可复现的状态。

不能声明：

- 物理断电、kernel panic、机器 reboot、disk cache、filesystem corruption 或 NFS
  durability；
- arbitrary-instruction crash coverage；
- hostile same-UID、owner/root、hidden ACL 或 pathname TOCTOU 防护；
- 跨主机 one-shot、provider idempotency 或 provider exactly-once；
- 本地 record 与 provider request 形成 transaction；
- signature、HMAC、WORM、producer authentication 或 non-repudiation；
- 已读取 real key、调用 live provider、取得 real model/token/billing receipt；
- 已接入 product API、Temporal、Self Model、Connector 或社交平台动作；
- 工程 Green 证明模型质量、用户价值、付费、收入或 production autonomy。

## Five-outcome receipt

- **AI Coding**：Acceptance Red 先证明 `ATOMIC_MOVE` 可覆盖 target；主线程修复后由
  两名 read-only reviewer 找出 phase/`fsync` 顺序、cleanup 三分支、identity race 与
  snapshot 证据缺口，再逐项转成 regression。
- **Agent Engineering**：把 provider billing prefix、local record publish truth、
  terminal journal closure 与 product `AgentRun` 分层；pending 从不冒充 Agent 输出。
- **Product/Production**：增加真实 fat-JAR process-kill/restart、race-safe create-only、
  fresh-JVM read-only verification 与 conservative failure evidence。
- **Career**：形成可讲清的案例：atomic rename 为什么不是 no-replace、hard-link
  residue 为什么必须看 identity、process kill 为什么不是 power-loss。
- **Business**：`N/A`。没有新增真实 Seed、访谈、复用、报价、付款或收入证据。

## 下一项可证伪假设

不自动调用付费 provider。下一项进入 S4 fault suite：

> 当 Model 发出 schema-invalid tool arguments 时，Agent Loop 必须在任何 Tool side
> effect 前返回 typed failure；safe Trace 必须可归因到参数拒绝，Artifact 与 terminal
> success 均不得产生。

先用 deterministic Fake Model、现有 Tool SPI 与 offline Harness 做 Red/Green；
随后才扩展 timeout/rate limit、Context Drift、Prompt Injection 与 typed read-only
Worker handoff。live-provider smoke 继续需要 owner 对真实 key、费用和 egress 的单独批准。
