# EmergeOS · 显现

> A user-owned Personal AI OS that turns what you see, think, and intend into verified outcomes.

EmergeOS 不是聊天机器人、笔记 App 或自动化工具箱。它要解决的是：

> **把一个人刚刚看到、想到、想要的东西，在几分钟内变成有证据、可编辑、可执行、可复盘的作品或行动。**

当前阶段是 `0.1 / research prototype`。仓库已经建立第一条无外部副作用的纵向闭环，并完成
四个 Stage 1 PostgreSQL 工程切片：Capture 可在真实应用进程重启后取回；两个独立应用实例并发
修订同一 Artifact base 时，数据库 compare-and-swap 只允许一个新版本；独立、持久化状态的
Fake Provider 在响应丢失与真实应用 JVM 重启后，可把一个模拟对象对账成一张 Receipt。
readiness、populated schema 升级、incompatible migration fail-fast 与 PostgreSQL
backup/restore 已有可重放证据。但 provider success 后、local outcome 持久化前崩溃可能遗留
`DISPATCHING`，当前没有安全 lease/fencing 接管；ADR-0004 仍为 Proposed，真实 Connector
Gate 保持关闭。
Stage 2 S1 另增加一条无框架 Fake Agent 草稿闭环：脚本 Fake Model 必须通过声明的
`capture.read` 工具读取 owner-scoped Capture，结构化结果经确定性校验后才能写入 Artifact；
Stage 2 S2 又把 AgentRun、safe hashed Trace、Result、immutable resource binding 与
HarnessRunBundle 写入 PostgreSQL；成功 Artifact 与 terminal Run 在同一个 transaction
提交，JVM 重启后仍能 verified read。冻结的 synthetic Task Pack 可以在无网络 Offline
runner 中重复得到 exact golden hashes。它们是 Agent Runtime/Harness 的确定性基线，
不是真实模型质量证明，也不是可对真实用户数据执行的 product replay API。
完整工程回执见
[Stage 2 S2 Build Note](./docs/operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)。
真实模型、Temporal、生产认证、加密存储和平台连接器尚未接入，不应将演示结果理解为生产
自治能力或真实平台结果。

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

Stage 0 Manifestation 闭环仍刻意使用内存存储和确定性 Stub。Stage 1 已把独立的 Capture、
Artifact lineage 和 local ActionAttempt/Receipt 边界替换为 PostgreSQL；S3 Action 只连接
loopback-only 模拟 Provider；S4 只增加薄 operations/readiness 边界和迁移/恢复证据，没有
增加通用运维平台。Stage 2 S2 的 additive V4 新增三张 AgentRun/Trace/binding 表，并通过
V1/V2/V3 → V4 升级与恢复演练保持旧 truth；V4 同时为 Capture identity/request hash
增加 composite unique constraint。

当前 Stage 2 S1 + S2 Agent 路径是：

```text
PostgreSQL Capture reference
  → server-owned TaskEnvelope
  → scripted Fake Model
  → capture.read
  → structured draft proposal
  → deterministic evidence validation
  → PostgreSQL Artifact v1 + terminal AgentRun（同一 transaction）
  → verified Result + safe hashed Trace + HarnessRunBundle
```

## 快速开始

要求：Java 21、Node.js 22、可用的 Docker 和 PostgreSQL。仓库自带 Maven Wrapper；Node
用于校验公共 JSON Schema，Docker 用于 Testcontainers 验收。

```bash
npm ci
./scripts/verify-contracts.sh
./mvnw --batch-mode --no-transfer-progress verify

# clean-checkout、packaged JVM、独立 Fake Provider 的 Stage 1 operating demo
./scripts/run-stage1-operating-demo.sh

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

S3 Action API 还要求另行启动测试用的 loopback Fake Provider。默认地址故意指向不可用的
`127.0.0.1:1`，因此普通快速启动不会产生模拟对象；若批准 Action，只会得到可解释的
`UNKNOWN`，不会伪造成功 Receipt。完整外部进程与故障恢复证据由 packaged-process 验收测试
提供，不把 test fixture 包装成可用 Connector。

另开终端。liveness 表示进程可服务；readiness 还会检查 PostgreSQL、Flyway 与当前
server-configured principal 的 unresolved Action：

```bash
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8080/actuator/health/readiness

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
    "intent": "把这个想法整理成一篇有证据的中文文章"
  }' \
  http://localhost:8080/api/v1/agent-drafts

# 使用 POST response 的 runId 读取持久执行真相
curl -s http://localhost:8080/api/v1/agent-runs/{runId}
curl -s http://localhost:8080/api/v1/agent-runs/{runId}/trace
curl -s http://localhost:8080/api/v1/agent-runs/{runId}/bundle

# 也可以绕过 Fake Agent，直接用确定性内容创建 Artifact v1
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

Capture、Agent 生成的 Artifact、AgentRun、safe Trace、HarnessRunBundle 与独立 Artifact
lineage 写入本机 PostgreSQL。Manifestation 仍只写入应用进程内存中的草稿回执。独立 local
ActionAttempt/Receipt 也写入 PostgreSQL，但只在测试中访问 loopback Fake Provider 并产生
模拟对象。它们都不会访问或发布到任何真实外部平台。

状态含义、`UNKNOWN` 恢复入口、迁移/备份演练和明确限制见
[Stage 1 Operating Runbook](./docs/operations/stage1-operating-runbook.md)。

## 仓库地图

```text
apps/api/                 HTTP 入口与依赖装配
modules/contracts/        跨 Agent、工具、人类边界的稳定契约
modules/core/             纯 Java 领域、用例、AgentKernel 与端口
adapters/inmemory/        本地适配器、有限 Fake Agent 循环与 Offline golden runner
adapters/postgres/        Capture、Artifact、local Action、AgentRun/Trace 的 PostgreSQL 适配器
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
