# ADR-0019：采用 Apache-2.0、DCO 与公开治理边界

- Status: Accepted
- Date: 2026-08-01
- Supersedes: none
- Owner decision: GitHub public release option A

## Context

EmergeOS 已形成可以外部理解、运行和验证的 Pack009 工程基线，但根仓库此前没有
**LICENSE**，外部参与者即使看到源码，也没有得到明确的复制、修改和分发授权。
仓库还需要明确贡献来源、Maintainer、安全报告与项目名称的使用边界，才能开始接受
外部参与。

## Decision

1. 除文件自身明确声明的第三方材料外，整个仓库统一采用
   [Apache License 2.0](../../../LICENSE)。代码、公共 contracts、SDK、测试、合成
   fixture 与项目文档均在同一许可下发布。
2. 外部贡献采用 [Developer Certificate of Origin 1.1](../../../DCO)，不在首发阶段
   引入 CLA。每个 2026-08-01 及之后的新贡献 commit 必须包含与提交者身份一致的
   **Signed-off-by**；保留的 pre-adoption history 不追溯改写或补签。
3. 首任 Maintainer 与 Security Response owner 为
   [@striveh](https://github.com/striveh)。漏洞与敏感事件通过 GitHub Private
   Vulnerability Reporting 私密提交。
4. Apache-2.0 不授予 EmergeOS、显现、Logo 或官方背书权；具体见
   [TRADEMARKS.md](../../../TRADEMARKS.md)。
5. 首次公开发布保留现有 Git 历史。项目所有者已明确接受 Git author metadata 与历史
   本机路径随 Git 对象公开；后续不得把凭据、真实用户数据或私有研究资料写入公开历史。
6. 首发仅发布源码与 synthetic evidence，不发布 binary、container image、Maven/npm
   artifact，也不把 research prototype 描述为 production-ready 产品。

## Alternatives

- 全仓库无许可证：拒绝。它只能形成 source-visible 仓库，无法形成可合法复用的
  open-source 项目。
- 服务端 AGPL、contracts/SDK Apache-2.0、文档 CC-BY-4.0：暂不采用。它更强调托管
  修改回馈，但目录边界、NOTICE、CLA 与商业双许可治理成本更高，不利于首期采用和作品
  传播。
- CLA：暂不采用。当前阶段 DCO 更轻量；若未来需要专有双许可或版权集中，再以新 ADR
  评估。
- squash 后公开：未采用。项目所有者选择保留完整工程演进历史，接受相应 metadata
  公开边界。

## Consequences

- 外部参与者获得明确、统一的使用与分发权；
- 贡献者必须对每个新贡献 commit 做 DCO sign-off，提交身份会长期存在于公开历史；
- 未来若改为 copyleft、多许可证或 CLA，必须处理已接受贡献的权利边界，不能自动
  relicensing；
- 名称政策保护来源与官方版本辨识，但不声称尚未取得的注册商标权；
- 开源许可不改变产品安全边界：无认证、无真实 Connector、无 live-model 回执的能力
  仍不得对外宣称完成。

## Evidence and validation

- 项目所有者于 2026-08-01 明确选择公开方案 A；
- 根 **LICENSE** 使用 GitHub License API 提供的未修改 Apache-2.0 标准文本；
- 根 **DCO** 使用 Developer Certificate of Origin 1.1 标准文本；
- Maintainer、Security、Code of Conduct、Contributing 与 Pull Request 模板同步到
  同一公开边界；
- GitHub Private Vulnerability Reporting 已于 2026-08-01 启用；动态设置、CI 与保护
  分支回执见[公开准备清单](../../community/open-source-readiness.md)和
  [GitHub Public Release Build Note](../../operations/build-notes/2026-08-01-github-public-release.md)。

本 ADR 只记录长期决策，不承担动态 readiness 证明。CI、Secret/PII scan、英文入口、
演示和 GitHub 设置只以准备清单、Build Note 与远端回读为准。当前不声明安装了自动
DCO bot；Pull Request 模板的 sign-off 由 Maintainer 人工核验。

## Rollback or migration

Apache-2.0 已发布版本的授权不可从下游追溯撤回。未来可以对新版本采用不同策略，但必须
由新 ADR 明确适用版本、既有贡献处理、NOTICE/SPDX 与迁移方案。DCO 可由未来 CLA 决策
替代，但已经公开的 sign-off 记录继续保留。
