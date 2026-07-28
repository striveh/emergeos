# Security Policy

EmergeOS 当前是研究原型，不应处理生产凭据、真实公开发布或不可逆行动。

## 报告漏洞

请不要在公开 Issue 中披露可利用漏洞、凭据或真实用户数据。公开仓库上线前必须启用 GitHub Private Vulnerability Reporting，并在此补充真实安全联系人。

## 安全不变量

- 模型不能直接获得长期密钥。
- 外部动作必须经过确定性授权、幂等和 Receipt。
- Worker 只获得完成任务所需的最小数据与工具。
- Self Model 更新只能写候选区，不能由 Worker 直接晋升。
- Trace 保存任务状态、版本、工具、权限和结果，不保存隐藏思维链。
- 生产数据不得进入公开 Issue、PR、Fixture 或 Benchmark。

## 当前支持范围

仅 `main` 分支原型接受安全修复。尚无生产支持版本。

