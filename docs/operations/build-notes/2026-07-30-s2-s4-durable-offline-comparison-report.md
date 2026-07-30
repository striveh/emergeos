# Build Note · 2026-07-30 · Stage 2 S4 Durable Offline Comparison Report

- Change class：`R`
- Code commit：`a2cb02b`
- 前置 comparison commit：`8fa96cd`
- 时间归档：切片于 2026-07-30 开始；commit 于
  `2026-07-31T00:04:29+08:00` 完成，文件名沿用开始日批次
- 状态：工程 Green；live-provider、stochastic Harness 与产品价值 Gate 仍开放

## 这次交付的系统结果

`apps/offline-harness-runner` 不再只在内存中得到一次 deterministic
comparison。固定 Pack 004 的 report 现在可以由 packaged app JAR 写入
owner-local state，再由两个 fresh JVM 只读加载并独立 replay：

```text
fixed Pack 004
  → 12 次 shared candidate generation
  → 24 次 H0/H1 VerifierEvaluation
  → independent in-memory verification
  → canonical bounded JSON
  → claim + pending write/read-back/fsync
  → hard-link create-only commit
  → directory fsync + pending unlink
  → fresh JVM read-only load
  → independent replay
  → VERIFIED_PASSED
```

固定交付回执为：

```text
verdict=VERIFIED_PASSED
fileState=FINAL
reportId=comparison-report-10d922825a4edb0d050f79f91f795a1b5ec5f122d182ab9ebd66e159cd0ecdf9
reportHash=9ff55976a03af6c871f87da54e1e00d083ed3782d353ec677ab49d6bbb963ab1
fileSha256=b1152849fc2807d536d59e7a1412bfe4336df4ded51a74ec412bc94d840b12a7
bytes=28343
```

这是一份 frozen PUBLIC synthetic report。它不是 product `AgentRun`、
`HarnessRunBundle`、真实模型响应或用户数据。

## 为什么这是 Risky boundary

把 report 写进文件并不自动产生 durable evidence。主要风险是：

1. writer 在 write、file `fsync`、logical commit 或 directory `fsync` 之间死亡；
2. 两个 JVM 同时写入时，一个后到的 writer 覆盖先到结果；
3. reader 把 `.pending` 或冲突路径误判成最终成功；
4. symlink、owner/mode、文件替换或读取期间变化绕过可信路径；
5. serializer 接受“能 parse 但不是 canonical”的另一份 bytes；
6. test-only crash hook、依赖或法律文件没有进入真正 shipping artifact 的正确边界。

因此本切片没有使用“写临时文件再改名就行”的宽泛说法，而是把
state machine、commit primitive、读回 identity 与 packaged-process evidence
分别固定。

## Canonical report

`OfflineComparisonReportJson` 固定：

- storage profile 与 schema version；
- exact field set、field order 与 list order；
- UTF-8、Unicode scalar 与 JavaScript-safe integer domain；
- 最多 `1 MiB` 的 parser/encoder 上界；
- duplicate/unknown/trailing/missing/null 与 non-canonical bytes rejection；
- decode 后重新 encode，只有 byte-for-byte 相同才接受。

Golden report 的 28,343 bytes 和 SHA-256 由 process tests 冻结。SHA-256 与
independent semantic replay 用来发现 corruption 或不一致 tampering；它们不是
signature、HMAC、producer authentication 或历史执行 attestation。

## 文件状态机

固定 state 目录：

```text
${user.home}/.emergeos/offline-comparisons/
```

其中只允许三个固定名称：

```text
pack004-reference-grounding-v1.claim
pack004-reference-grounding-v1.report.json.pending
pack004-reference-grounding-v1.report.json
```

写入时间线：

```text
ABSENT
  → CREATE_NEW claim
  → claim directory fsync
  → CREATE_NEW pending
  → pending file fsync
  → pending read-back + canonical decode
  → pending directory fsync
  → createLink(target, pending)
  → target directory fsync
  → unlink pending
  → target directory fsync
  → final read-back
```

`Files.createLink(target, pending)` 是 tested POSIX filesystem 上的 create-only
logical commit：target 已存在时不会被替换。第一版曾使用
`Files.move(..., ATOMIC_MOVE)`；adversarial Red 在 macOS 上证明它可能替换已存在
target，因此被否决。hard-link commit 无法完成时系统 fail closed，不降级到可覆盖
move：显式 `UnsupportedOperationException` 映射为
`REPORT_LINK_COMMIT_UNSUPPORTED`，其他 I/O failure 映射为
`REPORT_PERSIST_FAILED`。

Reader 对磁盘组合的解释：

| 磁盘状态 | 解释 |
|---|---|
| 无 claim / pending / target | `UNKNOWN / ABSENT` |
| 只有 claim | `UNKNOWN / CLAIM_NON_AUTHORITATIVE` |
| claim + pending，无 target | `UNKNOWN / PENDING_NON_AUTHORITATIVE` |
| claim + target，无 pending | `FINAL`，仍须 canonical decode 与 independent replay |
| claim + target + pending，pending/target 是同一 regular inode | committed crash residue，可为 `FINAL` |
| target 无 claim、pending 无 claim、pending/target 不同 inode | `INVALID` |
| unexpected entry 或 unsafe path/metadata | `INVALID` |
| authoritative target 是 invalid/non-canonical bytes | `INVALID` |

Reader 永不删除、修复或重写 evidence。writer cleanup 只 unlink pending，不会
truncate pending；cleanup 前后的 `SAME_FILE → ABSENT` 是合法单调前进。安全且有界的
pending-only bytes 不会被解释或升级为成功，即使内容无效也保持
`UNKNOWN / PENDING_NON_AUTHORITATIVE`。

## 路径与 identity 边界

Store 会：

- 对传入 owner home 做 absolute/normalized 检查，并以 `toRealPath()` 固定当前
  canonical home；
- 拒绝 final symlink、非目录、group/others writable home；
- 要求内部目录为 owner-owned `0700`，文件为 owner-owned regular `0600`；
- provider 能暴露 ACL 时拒绝 foreign `ALLOW` ACL；
- 使用 `NOFOLLOW_LINKS`，读取前后比较 non-null `fileKey`、size、mtime、
  `creationTime`；
- decode 和 pending transition 后，再次把 target 绑定到读取后的 identity receipt。

这不是 hostile-local-user authorization boundary。macOS 当前 JDK provider 不暴露
`AclFileAttributeView`；同 UID、owner 或 root 仍可删改文件。实现也没有使用
`dirfd/openat/openat2`，最终 identity check 之后仍有极小 pathname ABA 窗口。
目录只保存 frozen synthetic data，不能把它当成敏感数据加密或保密边界。

## Packaged CLI

Maven Shade 生成 attached artifact：

```text
apps/offline-harness-runner/target/
  emerge-offline-harness-runner-0.1.0-SNAPSHOT-app.jar
```

本模块自有 `io.emergeos.offlineharness` package 只有一个 public top-level entry
point；该 architecture assertion 不扫描 shaded dependencies 的 public API：

```bash
java -jar \
  apps/offline-harness-runner/target/emerge-offline-harness-runner-0.1.0-SNAPSHOT-app.jar \
  --execute

java -jar \
  apps/offline-harness-runner/target/emerge-offline-harness-runner-0.1.0-SNAPSHOT-app.jar \
  --verify
```

- `--execute` 固定运行 Pack 004、先做 in-memory independent verify，再持久化并做
  post-publish read-only verify；
- `--verify` 只执行 bounded read、canonical decode 和 independent replay，
  不调用 Runner/generator，不创建目录，不修复状态；
- shipping CLI 不接受 crash injection 参数；
- crash harness 只存在于 `target/test-classes`，production classes 必须从 app JAR
  CodeSource 加载，test classes 不得 shadow shipping classes；
- app JAR 的 `Main-Class`、test-only crash class 不进入 shipping JAR、production
  CodeSource/test-class shadowing、第三方 `NOTICE`，以及 Apache-2.0 dependency
  license 文件的存在与首尾 sentinel 由 process IT 检查。

第三方 license materials 不会给 EmergeOS 项目本身授予许可证。项目 license 仍是
public distribution / open-source release 之前的人类 Gate。

## Red → Green 缺陷链

本切片的关键 Red 不是只覆盖 happy path：

| Red 暴露的问题 | Green 不变量 |
|---|---|
| parseable JSON 可有不同 bytes | decode 后必须重新 encode 且 byte-equal |
| `ATOMIC_MOVE` 在 macOS 可覆盖 target | hard-link create-only commit |
| precheck 后竞争者创建 target | 竞争 target 保留，当前 writer 固定拒绝 |
| ancestor symlink 可在构造后指向另一 home | constructor 固定 canonical `toRealPath()` |
| `fileKey=null` 或 metadata 变化仍可通过 | identity 缺失 fail closed；绑定 key/size/mtime/creationTime |
| read 后 chmod/替换同字节 inode | final target identity recheck 拒绝 |
| writer unlink pending 时 reader误判冲突 | 允许 `SAME_FILE → ABSENT` |
| pending 与 target 同时删除，或 target 同字节换 inode | 无论 pending 状态如何，最终 target 必须匹配读取 receipt |
| link-complete crash 遗留两个名字 | 只接受 pending/target 同一 regular inode |
| test harness 可能从 test classpath shadow production | CodeSource 与 shipping JAR entry assertions |

## 真实进程与 fault evidence

Failsafe 使用真正 packaged app JAR，而不是测试内直接调用 production class：

- writer JVM 完成后，两个 fresh read-only JVM 读取同一 file snapshot，均得到完全相同
  的 verdict、report ID/hash、file SHA 和 byte length；
- 两个独立 packaged writer JVM 指向同一个新 owner home，最终恰好一个成功，另一个
  只允许 `REPORT_COMMIT_INCOMPLETE` 或 `REPORT_ALREADY_EXISTS`，随后 fresh JVM
  得到唯一 final report；
- test-only crash harness 分别停在
  `CLAIM_DURABLE`、`PENDING_WRITE_STARTED`、`PENDING_FILE_FSYNC_COMPLETE`、
  `PENDING_FILE_DURABLE`、`REPORT_COMMIT_STARTED`、
  `REPORT_LINK_COMMIT_COMPLETE`、`REPORT_DIRECTORY_DURABLE`；
- 主测试进程收到 ready receipt 后调用 `destroyForcibly()`，确认旧 PID 死亡，再启动
  fresh JVM B/C 做两次只读核验并比较目录 snapshot；
- link-complete case 明确断言 claim + pending + target 三文件，且 pending/target
  `Files.isSameFile`；directory-durable case 只保留 claim + target。

双 writer 测试没有 barrier 强迫两个进程恰好同时命中 hard-link syscall；它证明的是
两个真实进程竞争时最终不覆盖、只有一个物理 publisher，不声称每次都命中同一纳秒窗口。

这些证据覆盖 live OS process kill 和选定 observer phases，不覆盖物理断电、kernel panic、
reboot、device cache、filesystem corruption、NFS/network/object filesystem 或任意指令
级 crash。

## 验证回执

Focused clean reactor 及随后两次重复 `verify` 均为 Green：

| Module / phase | Tests |
|---|---:|
| Contracts | 26 |
| Core | 79 |
| Offline Harness Surefire | 78 |
| Offline Harness Failsafe | 5 |
| 合计 | 188 |

完整 reactor：

```text
Contracts                         26
Core                              79
Agent Loop                         8
OpenAI Adapter                    19
In-memory Adapters                21
PostgreSQL Adapter                45
API                               32
Synthetic Eval Runner             49
Offline Harness Runner            83
────────────────────────────────────
合计                              362
failures / errors / skipped       0 / 0 / 0
```

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/offline-harness-runner -am clean verify

./mvnw --batch-mode --no-transfer-progress verify
./scripts/verify-contracts.sh
./scripts/verify-doc-links.sh
git diff --check
```

Contract verifier：

- 5 个 JSON Schema；
- 33 个 fixtures；
- 2 个 cross-language Task hash golden vectors；
- 4 个 synthetic Task Packs；
- 1 个 synthetic evaluation environment manifest。

Doc link verifier 在排除 local-only `.workbuddy` 后通过 73 个 Markdown files，
`git diff --check` 同时为 Green。

三名独立 source reviewer 的最终结论均为 `P0=0、P1=0`。保留的 P2 是
pathname ABA、非 power-loss 证据、双 writer 无 barrier、duplicate `--execute`
会先运行固定无副作用 comparison，以及项目许可证待决。

## 可以声明与不能声明

可以声明：

> 在固定 Pack 004、tested local POSIX filesystem 与
> `OWNED_OFFLINE_RUNNER_SEAMS_V1` 范围内，packaged writer 以 no-overwrite
> hard-link commit 写入 canonical report；真实进程终止后的 fresh JVM 能只读加载
> 同一 bytes 并由独立 verifier replay 得到 `VERIFIED_PASSED`。pre-link incomplete
> evidence 保持 `UNKNOWN`；冲突或 authoritative target 不可信时保持 `INVALID`。

不能声明：

- 已通过断电、重启机器、NFS 或任意文件系统 durability；
- 当前目录是 hostile-local-user、same-UID、root 或 hidden ACL 的安全边界；
- 已使用 `dirfd/openat` 消除全部 pathname TOCTOU；
- report 是 signature、authenticated producer receipt、WORM 或 historical
  execution attestation；
- 已运行真实模型、读取 real key、产生 token/billing receipt；
- 已形成 product `AgentRun/HarnessRunBundle`、真实 `capture.read` 或真实用户 Evidence；
- 已访问 Connector、真实账号或外部平台；
- 4 个 deterministic synthetic cases 证明 Harness 普遍更优、模型质量、用户价值或
  production autonomy。

## Five-outcome receipt

- **AI Coding**：单 writer + 三个 read-only reviewer；reviewer 找到 no-overwrite、
  cleanup/read race、target replacement 和 ACL truth-boundary 缺陷，主线程逐个建立
  Red 再修复。
- **Agent Engineering**：把 comparison execution truth、canonical report、
  independent replay 与 durable storage truth 分层；没有让 hash 或 Verifier 冒充
  trusted execution。
- **Product/Production**：完成 fixed synthetic report 的 packaged-process、
  multi-writer、crash/restart 和 read-only verification evidence。
- **Career**：形成一个可追问的案例：为什么 `ATOMIC_MOVE` 不是 no-replace、为什么
  committed hard-link residue 可以安全识别、为什么 process kill 不等于 power-loss。
- **Business**：`N/A`。没有新增真实 Seed、复用、访谈、报价、付款或收入证据。

## 下一项可证伪假设

本切片之后不自动调用付费 provider。下一项安全工作是先把同类 POSIX/no-overwrite/ACL
truth boundary 复用到 `apps/eval-runner`，再扩展 S4 fault suite；live-provider smoke
继续需要 owner 对真实 key、费用和网络 egress 的单独批准。完整 10-task × 2-arm ×
3-repetition stochastic Harness、人工盲评、真实用户价值与 bounded read-only Worker
handoff 仍未完成。
