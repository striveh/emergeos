# Build Note：2026-08-01 · GitHub 公开发布

- Change class：`R`（开源发布、治理与跨环境 durable CI 修复）
- Status：Public source released / Engineering Green
- Repository：[github.com/striveh/emergeos](https://github.com/striveh/emergeos)
- Published baseline：Pack009 `6d914d8`
- Public release commit：`3cb4d04`
- Linux timestamp fix：`b3e6db2`
- License：[Apache License 2.0](../../../LICENSE)
- Contribution certificate：[DCO 1.1](../../../DCO)
- Decision：[ADR-0019](../../architecture/decisions/0019-open-source-license-and-contributions.md)

## Outcome

EmergeOS 已作为 public GitHub repository 发布，并保留从 Foundation 到 Pack009 的完整
Git history。首次公开基线补齐了 Apache-2.0、DCO、中英文 README、Maintainer、Security、
Code of Conduct、Contributing、Issue/PR templates、商标边界和开源 readiness 文档。

发布的是稳定 Pack009 与随后关闭 fresh Linux CI 的 timestamp precision 修复。原工作区
尚未提交的 Pack010 研发内容、`.workbuddy/`、私有 research ledger、凭据和真实用户数据
均未进入本次公开范围。

## 真实发布过程

| 时间线 | Commit / Run | 结果 | 事实 |
|---|---|---|---|
| 首次公开 | `3cb4d04` / [30702356029](https://github.com/striveh/emergeos/actions/runs/30702356029) | Red | contract 与 docs Green；Maven 在三个 Agent API tests 中 Red |
| 确定性复现 | local fixed Clock `2026-08-01T01:02:03.123456789Z` | Red | `PostgresAgentRunStore.start` 抛 `Stored AgentRun cannot be verified` |
| 最小修复 | `b3e6db2` | Green | parent、worker、terminal 与 Artifact 时间先规范到 microsecond；store 在任何 mutation 前拒绝超精度时间 |
| fresh Ubuntu | `b3e6db2` / [30702976116](https://github.com/striveh/emergeos/actions/runs/30702976116) | Green | `verify` job 在 `ubuntu-latest` 完整通过，用时 5m25s |

首次 Red 没有被删除或改写。它揭示了 macOS Java 21.0.10 与 GitHub Linux Java 21.0.11
时钟精度差异：Linux 的 `Instant` 可带非整微秒纳秒，而 PostgreSQL `TIMESTAMPTZ` 的
canonical precision 是 microsecond。原实现写入后对完整 `AgentRun` exact-equal，导致
数据库读回值与内存值不同。

修复不放宽 integrity verification，也不通过改测试顺序或 ID 隐藏问题。application
boundary 统一 `truncatedTo(MICROS)`；PostgreSQL adapter 的 `start`、`startWorker`、
`complete`、`completeWorker` 在 SQL/transaction mutation 前 fail-fast。固定纳秒时钟测试
同时核验 parent、worker 与 `artifact_versions.created_at` 的真实数据库值。

## Local evidence

- `npm ci`：Green，0 vulnerabilities；
- `./scripts/verify-contracts.sh`：6 schemas、54 fixtures、9 synthetic task packs、
  3 environments；
- `./scripts/verify-doc-links.sh`：全部本地 Markdown links Green；
- `./mvnw --batch-mode --no-transfer-progress verify`：11 reactor modules、618 test
  executions、0 failures、0 errors、0 skipped；
- `./scripts/run-stage1-operating-demo.sh`：loopback-only、synthetic data、独立
  file-backed Fake Provider 与 packaged application JVM Green；
- `git diff --check`：Green；
- current tree 与 Git history 的 Secret、PII、大文件扫描未发现常见 API key、private
  key、token、私有 research ledger 或超过 1 MiB 的历史 blob；
- 两路根因诊断一致；独立 code review 最终 `P0=0、P1=0`，发现的 Artifact timestamp
  P2 测试缺口已补齐并复跑 Green。

这些是 synthetic engineering receipts。它们不等于真实模型质量、真实 Connector、用户
价值或 production deployment 证据。

## GitHub remote receipts

远端 API 回读确认：

- repository 为 public，default branch 为 `main`；Issues 与 Discussions 开启；
- squash/rebase merge 开启，merge commit 关闭，merged branch 自动删除；
- Private Vulnerability Reporting、Secret Scanning、Secret Scanning Push Protection、
  vulnerability alerts、Dependabot security updates 与 automated security fixes 开启；
- `main` required status check 为 GitHub Actions `verify`，strict/up-to-date 开启；
- `main` 要求 linear history 与 conversation resolution，force-push/delete 关闭；
- `enforce_admins=false`、无 required PR review：这是单 Maintainer pre-alpha 阶段的有意
  取舍，不宣称 owner 无法 bypass；
- DCO 目前通过文档、`git commit -s` 与 Pull Request checkbox 人工执行，不宣称安装了
  自动 DCO bot。

## Failure and limits

- GitHub Green run 有一条非阻断 annotation：`actions/checkout@v4`、`setup-java@v4`、
  `setup-node@v4` 的 Node.js 20 runtime 被 runner 强制迁移到 Node.js 24；应在独立维护
  变更中升级并复验 Actions major versions；
- 本次没有创建 release tag、GitHub Release、binary、container image、Maven/npm
  artifact 或 cloud deployment；
- 产品仍是 `0.1 / pre-alpha research prototype`，只允许 synthetic、non-sensitive data；
- 没有真实 API key、real model result、live social connector、production authentication、
  encrypted user vault、付款或收入回执；
- 完整历史意味着已公开 Git author metadata 与旧本机路径；项目所有者在方案 A 中明确
  接受此边界；
- Apache-2.0 已发布版本的授权不可对下游追溯撤回；名称与官方背书边界仍受
  [TRADEMARKS.md](../../../TRADEMARKS.md) 约束。

## Principle learned

公开 CI 不是发布后的装饰，而是第二种运行环境。任何进入 durable truth 的时间、数字、
JSON 和 hash 都必须先采用数据库与跨语言共同理解的 canonical representation；否则
“本机全 Green”只能证明恰好没有撞上平台差异。

开源发布也不是把 repository visibility 改成 public。可复用许可、贡献来源、私密漏洞
通道、保护分支、可运行文档、真实失败记录和明确 nonclaims 一起构成可信的公共入口。

## Next falsifiable hypothesis

最小下一步不是扩大公开承诺，而是让一位没有项目上下文的外部开发者仅按 README，在全新
环境 15 分钟内完成 safe preflight，并记录首次成功时间、卡点和文档缺口。该 evidence
决定 onboarding、Dev Container/Bootstrap 与首个 `good first issue` 的优先级。
