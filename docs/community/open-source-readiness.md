# Open Source Readiness

目标是让外部参与者能理解、运行、验证和共同改进系统，同时不把真实用户人格变成社区资产。

## 首次公开基线 readiness 状态

- [x] 选择并记录代码、SDK、文档许可证；
- [x] 完成商标政策；
- [x] 填写真实 Maintainer 与安全联系方式；
- [x] 启用 Private Vulnerability Reporting；
- [x] 建立 DCO 或 CLA 决策；
- [x] 所有公开 Fixture 均为合成数据；
- [x] 运行当前 tree 与 Git history 的 Secret、PII 和大文件扫描；
- [x] 英文 README 与最小英文文档；
- [x] GitHub Actions 在 fresh `ubuntu-latest` 通过；
- [x] `main` 保护分支要求 `verify`、linear history 与 conversation resolution，禁止
  force-push 和 delete；
- [x] 至少一个五分钟安全演示；
- [x] 已知限制、数据边界和成熟度醒目标注。

## 已接受的许可证与贡献决策

项目所有者于 2026-08-01 接受：

- 整个仓库使用 Apache License 2.0，第三方文件保留自身声明；
- 外部贡献采用 DCO 1.1，不在首发阶段使用 CLA；
- 首次公开发布保留完整 Git 历史；
- 项目名称与官方背书边界由 `TRADEMARKS.md` 约束。

完整理由与迁移边界见
[ADR-0012](../architecture/decisions/0012-open-source-license-and-contributions.md)。

## 2026-08-01 GitHub 公开回执

公开仓库为 [striveh/emergeos](https://github.com/striveh/emergeos)。首次公开过程保留了
真实的失败与修复时间线：

1. `3cb4d04` 完成 Apache-2.0、DCO、中英文入口与公开治理，首次 Actions
   [run 30702356029](https://github.com/striveh/emergeos/actions/runs/30702356029)
   中 contract/docs Green、Maven Red；
2. 根因是 Linux `Clock.systemUTC()` 可能产生 sub-microsecond `Instant`，而 PostgreSQL
   只持久化到 microsecond，`AgentRun` 写后 exact read-back 因精度变化失败；
3. `b3e6db2` 在 application boundary 规范时间、在 PostgreSQL adapter 写入前 fail-fast，
   并加入确定性纳秒时钟回归；fresh Ubuntu
   [run 30702976116](https://github.com/striveh/emergeos/actions/runs/30702976116)
   的 `verify` check Green；
4. GitHub API 回读确认 PVR、Secret Scanning、Push Protection、Dependabot security
   updates 与上述 `main` protection 已启用。

完整证据、范围和 nonclaims 见
[GitHub Public Release Build Note](../operations/build-notes/2026-08-01-github-public-release.md)。

2026-08-01 首次公开扫描未发现常见 API key、private key、token、私有 research ledger 或
超过 1 MiB 的历史 blob。项目所有者选择接受现有 Git author metadata 与历史本机路径
公开。该扫描是本地规则审计，不等同于 GitHub 对未来 commit 的持续 Secret Scanning。

同日 `./scripts/run-stage1-operating-demo.sh` 在 loopback-only、synthetic data、
独立 file-backed Fake Provider 与 packaged application JVM 下 Green；它不调用真实
平台、不读取真实 provider key，也不是 production deployment 演示。

DCO 从 2026-08-01 起约束新贡献；为保留真实工程演进，pre-adoption history 不追溯
改写。Pull Request 模板中的 sign-off 目前是人工 gate，不宣称存在自动 DCO bot。

## 公共/私有边界

可以公开源码、Schema、ADR、合成任务、聚合 Benchmark 和脱敏故障分类。

永不公开：

- 原始语音、聊天、浏览、Self Model 与 Embedding；
- 凭据、账号标识、真实草稿 ID 与 Receipt；
- 可重建个人经历的完整 Prompt 或 Trace；
- 未修复漏洞的利用细节。

生产日志默认记录引用、版本、Hash 与结果，不记录正文。维护者身份不自动赋予用户数据访问权。
