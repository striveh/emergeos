# Build Note：Pack010 attributed terminal graph / Harness Report

- Change class：`R`（durable terminal graph / attribution / Harness truth）
- Status：offline terminal/Harness slice Engineering Green；Pack010 Authority/Live Gate开放
- Task：Stage 2 S4/F6 · Pack010
- Date：2026-08-01
- Commit：未提交；当前工作区含 owner既有 Pack010增量，以 Git history为准
- RFC：
  [RFC-0007](../../rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)
- ADR：
  [ADR-0011](../../architecture/decisions/0011-attributed-terminal-graph-and-live-harness-pilot.md)

## Outcome

本切片把 Pack010 的 offline synthetic attributed terminal graph从 contract/pure Core
推进到真实 PostgreSQL authority，并从三份独立、fresh verified的 sequence-17 snapshot
生成一份 complete-only、canonical `HarnessEvaluationReport`：

```text
V7 frozen prefix
→ V8 TX-A: exact two request/provider attributions durable
→ V8 TX-B: child terminal + Candidate/WorkerResult durable
→ V8 TX-C: parent terminal + Artifact lineage + terminal binding/seal durable
→ fresh restricted READ ONLY REPEATABLE READ reader
→ r1/r2/r3 exact catalog snapshots
→ H0/H1 shared-candidate evaluations（6）
→ canonical HarnessEvaluationReport（COMPLETE）
```

V8保持 forward-only，不修改 V7 migration bytes/checksum。terminal protocol的
`maximumProviderRequests`由 Core constructor与 PostgreSQL CHECK共同固定为 exact `2`，
避免 manifest自洽地漂移到 1或3，而 terminal snapshot仍暗中按2解释。

## Acceptance Red → minimum implementation

保留并关闭的 runnable Red包括：

1. terminal Store三个方法仍落到 port default，V8 latest shape缺失；
2. populated V7 sequence 1/10/11升级时不得改写 legacy row、hash、timestamp、`xmin`
   与 Flyway 1–7 history；
3. aggregate migration test仍把 latest version冻结在 V7；
4. shipping App新增 production classes未进入 package-independent exact allowlist；
5. Pack010 environment 0.2尚未进入 frozen contract identity与 semantic validation；
6. terminal manifest把 request limit改为 1或3时，Core与数据库都曾接受。
7. 根级 Gate首次进入 API时，V8 terminal binding外键使五个 V7-era test cleanup
   无法 truncate `agent_runs`；共享 fixture现在显式列出完整 V8 business truth table set，
   不使用 `CASCADE`掩盖依赖；
8. packaged recoverable-action IT的 readiness仍把 current migration硬编码为 V6，已更新
   为 V8，并重新取得真实 restart/reconcile process receipt。

minimum implementation只增加完成上述契约所需的 V8 tables/constraints、三个 semantic
transaction、read-only access、Report projector/codec与 exact shipping allowlist；没有
开放 shipping execute，也没有引入 provider credential或网络 construction path。

## Integration / fault evidence

真实 PostgreSQL 18.4 Testcontainers process matrix产生：

```text
PACK010_PROCESS_KILL_MATRIX_RECEIPT
transactions=3 probes=37 externalVisibility=OLD_PREFIX_ONLY
recovery=FRESH_JVM freshVerifiersPerProbe=2
modelCalls=0 providerCredentialFrames=0 providerNetworkCalls=0 synthetic=true

PACK010_TWO_JVM_RACE_RECEIPT
transactions=TX_A,TX_B,TX_C writersPerRace=2
result=ONE_ADVANCED_ONE_CONFLICT staleWriter=CONFLICT
freshVerifiersPerRace=2 modelCalls=0 providerNetworkCalls=0 synthetic=true

PACK010_POSTGRES_REPORT_RECEIPT
repetitions=3 sequence=17 providerAttributions=6 evaluations=6
reportStatus=COMPLETE evaluatorEffects=ZERO
reader=RESTRICTED_REPEATABLE_READ realModel=false providerNetworkCalls=0 synthetic=true
reportId=harness-evaluation-report-e3eb6f7cbe1d96aeaa2de59c2709d777f23f6ce0422e84eff7006c2d2341a01f
reportHash=58fc1bec2d56e570abfc1515f20e094202700897ec3470d1258c1629693ba025
```

Report integration先只完成 r1/r2，并断言 projector返回
`REPETITION_MISSING / 3`、不产生 partial report；完成 r3后才允许 `COMPLETE`。随后由新建
restricted reader在单个 repeatable-read snapshot内读取 exact 14 relations，重建三份
terminal truth；Report encode → decode保持 object equality，re-encode byte-exact。

可复现命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl apps/graph-eval-runner -am \
  -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=Pack010DurableGraphTerminalProcessIT,Pack010PostgresHarnessReportIT \
  -Dfailsafe.failIfNoSpecifiedTests=true verify

./scripts/verify-contracts.sh
./mvnw --batch-mode --no-transfer-progress verify
./scripts/verify-doc-links.sh
git diff --check
```

最终根级 `verify`在同一次 reactor execution中得到：

```text
Maven reactor: 11/11 modules SUCCESS
Surefire/Failsafe XML reports: 127
tests=732 failures=0 errors=0 skipped=0
contracts: 8 schemas / 70 fixtures / 10 task packs / 4 environments
Markdown links: 90 files
```

两条 repo-wide integration Red另分别由 `apps/api -am test`的 API `24/24` Green，
以及 `RecoverableLocalActionHttpIT` packaged process `1/1` Green关闭；最终根级 Gate在
修复后从仓库根重新完整执行，不使用 `-rf`拼接最终结果。

## Independent review

三路 read-only audit分别检查 architecture/contract、test/evidence与 dirty diff。full Gate
和 code freeze后的最终复核结论为 `P0=0、P1=0`。发现并已关闭一项 P2：terminal
protocol request limit的 Core/DB双层 exact-two约束缺口；复核确认两层 negative均可执行。

仍保留一项 P2 future-live风险：V8 terminal mutation permit当前使用 transaction-local
custom GUC。只要同一数据库 writer role被交给任意 raw SQL session，该 session就可能伪造
GUC；当前 shipping execute disabled、App无 raw writer construction path，因此不阻断
offline slice，但任何 live writer route开放前必须以最小数据库权限或不可伪造的高层
authority收口。restricted reader只表示 least-authority read capability，不等于认证、
tenant isolation或 hostile DBA防护。

## Evidence boundary

本切片明确不声明：

- real TTY、owner真实逐次批准、真实 API key、external provider或真实 model result；
- 真实 token、cost、invoice、provider-side idempotency或“网络 exactly-once”；
- r2必须原子依赖已验证 r1、r3必须原子依赖已验证 r2；
- shipping execute/product API/live Connector已开放；
- hostile DBA、签名、WORM、cross-host或跨数据库 replay protection；
- 三次 synthetic repetition构成模型优劣、统计显著性、用户价值或商业证据。

Engineering证据仅覆盖上述 offline protocol slice。Human-learning当前暂停，本次没有
teach-back；Commercial没有访谈、报价、付款、留存或真实用户验证。

## Next falsifiable slice

下一条 Acceptance Red是 authority/App boundary：同 package forge、no-TTY、piped TTY、
wrong/expired/replayed challenge、r2-before-r1、r3-before-r2、predecessor invalid/unknown
都必须在 credential/client/model/provider effect construction前 fail closed并保持数据库
零变化。production App只能取得 high-level `OwnerTtyGraphAuthority`与 single-use permit，
不能取得 raw Store/Coordinator/Console/Clock/key/client/model。该 engineering Gate完成也
不自动授权 live r1/r2/r3；真实调用仍需 owner逐次明确批准。
