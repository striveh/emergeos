# EmergeOS · 显现

> A user-owned Personal AI OS that turns what you see, think, and intend into verified outcomes.

EmergeOS 不是聊天机器人、笔记 App 或自动化工具箱。它要解决的是：

> **把一个人刚刚看到、想到、想要的东西，在几分钟内变成有证据、可编辑、可执行、可复盘的作品或行动。**

当前阶段是 `0.1 / research prototype`。仓库已经建立第一条无外部副作用的纵向闭环，并完成
前两个 PostgreSQL 纵向切片：Capture 可在真实应用进程重启后取回；两个独立应用实例并发
修订同一 Artifact base 时，数据库 compare-and-swap 只允许一个新版本。真实模型、Temporal、
生产认证、加密存储和平台连接器尚未接入，不应将演示结果理解为生产能力。

API 默认只监听 `127.0.0.1`，并在未认证阶段拒绝非 loopback 绑定。所有请求都被当作服务端配置
中的单用户 `local-user`，没有实现登录或多租户身份验证。主体不接受 Header 或请求体覆盖，
读取也按该主体查询，但这仍不是认证。不要把它暴露到局域网或公网；在加密持久化与
Secret Broker 落地前，持久化 Capture 会拒绝 `SENSITIVE` 和 `SECRET` 输入。Artifact 原型
没有独立的数据分类或加密边界，只能使用合成、非敏感内容。

## 系统原则

- **One Self, Many Workers**：一个可审阅、可纠正的 Self Model；多个短命、最小权限 Worker。
- **Context is a projection**：Working Self 是任务视图，不是人格真相。
- **Evidence before inference**：经历与凭证是事实，人格是可撤销推断。
- **No receipt, no completion**：没有外部回执，就不能声称行动已经完成。
- **Evolution is evaluated**：Prompt、Skill、模型和策略只能经评测、灰度与回滚演进。
- **Human authority remains explicit**：公开发布、消息、删除等动作必须经过明确授权。

## 当前可运行闭环

```text
思想种子
  → EvidenceEvent
  → WorkingSelf 投影
  → Artifact 草稿
  → 等待批准
  → 本地 Action Stub
  → Receipt
  → ReflectionCandidate
```

Stage 0 Manifestation 闭环仍刻意使用内存存储和确定性 Stub。Stage 1 已把独立的 Capture
和 Artifact lineage 边界替换为 PostgreSQL；Action 和 Operations 将继续按纵向切片逐个验证。

## 快速开始

要求：Java 21、Node.js 22、可用的 Docker 和 PostgreSQL。仓库自带 Maven Wrapper；Node
用于校验公共 JSON Schema，Docker 用于 Testcontainers 验收。

```bash
npm ci
./scripts/verify-contracts.sh
./mvnw --batch-mode --no-transfer-progress verify

docker run --detach --rm --name emerge-postgres \
  -e POSTGRES_DB=emerge \
  -e POSTGRES_USER=emerge \
  -e POSTGRES_PASSWORD=local-prototype-only \
  -p 127.0.0.1:5432:5432 \
  postgres:18.4-alpine

EMERGE_DB_URL='jdbc:postgresql://127.0.0.1:5432/emerge' \
EMERGE_DB_USER='emerge' \
EMERGE_DB_PASSWORD='local-prototype-only' \
java -jar apps/api/target/emerge-api-0.1.0-SNAPSHOT.jar
```

另开终端：

```bash
curl -s http://localhost:8080/actuator/health

curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "clientNonce": "local-demo-001",
    "content": "把我关于长期个人 Agent 的想法留到重启后继续",
    "sourceType": "TEXT",
    "sourceRef": "local-demo",
    "dataClass": "PERSONAL"
  }' \
  http://localhost:8080/api/v1/captures

# 用上一步的 captureId 创建 Artifact v1
curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "captureId": "{captureId from the latest response}",
    "content": "把这个想法整理成一篇有证据的文章"
  }' \
  http://localhost:8080/api/v1/artifacts

# 修订必须绑定当前版本和服务端返回的 currentHash
curl -s \
  -X PUT \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "Artifact v2",
    "expectedBaseVersion": 1,
    "expectedBaseHash": "{currentHash from the latest Artifact response}"
  }' \
  http://localhost:8080/api/v1/artifacts/{artifactId}

curl -s \
  -H 'Content-Type: application/json' \
  -d '{
    "content": "把我关于长期个人 Agent 的想法整理成一篇有证据的文章",
    "sourceType": "VOICE",
    "sourceRef": "local-demo",
    "dataClass": "PERSONAL"
  }' \
  http://localhost:8080/api/v1/manifestations
```

响应会返回 `manifestationId`、草稿和待批准动作。再调用：

```bash
curl -s \
  -X POST \
  -H 'Content-Type: application/json' \
  -d '{
    "artifactHash": "{artifact.contentHash from the latest response}"
  }' \
  http://localhost:8080/api/v1/manifestations/{manifestationId}/approve
```

Capture 与独立 Artifact lineage 写入本机 PostgreSQL；Manifestation 仍只写入应用进程内存
中的草稿回执。它们都不会访问或发布到任何外部平台。

## 仓库地图

```text
apps/api/                 HTTP 入口与依赖装配
modules/contracts/        跨 Agent、工具、人类边界的稳定契约
modules/core/             纯 Java 领域、用例与端口
adapters/inmemory/        本地开发与测试适配器
adapters/postgres/        S1 Capture、S2 Artifact lineage 的薄 PostgreSQL 适配器与 migrations
contracts/                跨语言 JSON Schema
evals/                    合成任务与回归证据
docs/                     产品、架构、研究、运营和共同治理
```

依赖只允许由外向内：

```text
api → adapters → core → contracts
api ───────────→ core
```

核心层不能依赖 Spring、Temporal、AgentScope、数据库或模型 SDK。

## 从哪里读起

1. [文档地图](./docs/README.md)
2. [系统架构](./docs/architecture/system-overview.md)
3. [第一条纵向闭环](./docs/product/first-vertical-slice.md)
4. [公共契约](./contracts/README.md)
5. [Harness 验证计划](./docs/research/harness-validation.md)
6. [产品蓝图](./PRODUCT-BLUEPRINT.md)
7. [Agent Fabric 历史候选设计](./AGENT-FABRIC.md)
8. [路线图](./ROADMAP.md)

## 参与共同进化

贡献不只包括代码，也包括评测任务、故障案例、交互设计、研究、文档和翻译。先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [GOVERNANCE.md](./GOVERNANCE.md)。

共同进化的对象是代码、协议、评测和方法，绝不是任何用户的真实人格与生活数据。禁止在 Issue、PR、测试样例或 Trace 中提交真实用户数据、凭据或可识别个人的信息。

## 为什么做，以及怎样不偏航

项目同时追求 AI Coding 能力、Agent Engineering 能力、生产级产品、可信职业作品与商业结果。
每个任务留下相关证据，每个 Roadmap Stage/Release Gate 同时检查这五项结果。具体见：

- [Five-Outcome Charter](./docs/strategy/five-outcome-charter.md)
- [Product-driven Curriculum](./docs/learning/curriculum.md)
- [Development Method](./docs/engineering/development-method.md)
- [Codex Playbook](./docs/engineering/codex-playbook.md)
- [Business Validation Roadmap](./docs/business/validation-roadmap.md)

## 许可证状态

仓库尚未公开发布，许可证仍是待决 ADR。在许可证被明确接受前，代码不应被视为已授予开源使用权。原因与候选方案见 [开放源码准备清单](./docs/community/open-source-readiness.md)。
