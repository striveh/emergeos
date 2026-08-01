# Open Source Readiness

目标是让外部参与者能理解、运行、验证和共同改进系统，同时不把真实用户人格变成社区资产。

## 公开前必须完成

- [x] 选择并记录代码、SDK、文档许可证；
- [x] 完成商标政策；
- [x] 填写真实 Maintainer 与安全联系方式；
- [ ] 启用 Private Vulnerability Reporting；
- [x] 建立 DCO 或 CLA 决策；
- [x] 所有公开 Fixture 均为合成数据；
- [x] 运行当前 tree 与 Git history 的 Secret、PII 和大文件扫描；
- [x] 英文 README 与最小英文文档；
- [ ] CI 在全新环境通过；
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

2026-08-01 发布前扫描未发现常见 API key、private key、token、私有 research ledger 或
超过 1 MiB 的历史 blob。项目所有者选择接受现有 Git author metadata 与历史本机路径
公开。该扫描是本地规则审计，不等同于 GitHub 对未来 commit 的持续 Secret Scanning。

同日 `./scripts/run-stage1-operating-demo.sh` 在 loopback-only、synthetic data、
独立 file-backed Fake Provider 与 packaged application JVM 下 Green；它不调用真实
平台、不读取真实 provider key，也不是 production deployment 演示。

## 公共/私有边界

可以公开源码、Schema、ADR、合成任务、聚合 Benchmark 和脱敏故障分类。

永不公开：

- 原始语音、聊天、浏览、Self Model 与 Embedding；
- 凭据、账号标识、真实草稿 ID 与 Receipt；
- 可重建个人经历的完整 Prompt 或 Trace；
- 未修复漏洞的利用细节。

生产日志默认记录引用、版本、Hash 与结果，不记录正文。维护者身份不自动赋予用户数据访问权。
