# First Vertical Slice

## 用户故事

> 我说下一个零散念头，系统把它显现为一篇草稿；我修改并批准后，它写入本地平台草稿箱，给我真实回执，并从我的修改中提出一条待确认的表达偏好。

## 为什么从这里开始

这条切片同时触及：

- 捕获摩擦；
- Evidence 与 Self Model 投影；
- Artifact 版本和“像我”；
- 审批绑定内容；
- 幂等外部行动；
- Receipt 与反思候选。

它暂不依赖真实模型、OAuth 或平台权限，因此领域错误不会被基础设施掩盖。

## 接口

```text
POST /api/v1/manifestations
PUT  /api/v1/manifestations/{id}/artifact
POST /api/v1/manifestations/{id}/approve
GET  /api/v1/manifestations/{id}
```

### Capture

输入 Thought Seed 与来源，系统先追加 Evidence，再生成 Working Self 与 Artifact v1，返回 `AWAITING_APPROVAL`。

### Revise

用户编辑草稿后生成新 Artifact 版本、新 Hash 和新 ActionPlan。任何旧批准立即失效。

### Approve

批准请求必须带当前 Artifact Hash。Policy Gate 签发短期 Capability，Action Stub 以幂等键写入本地草稿，并返回 Receipt。

### Reflect

系统比较生成版本和用户批准版本，只生成 `PENDING` ReflectionCandidate 或 `NO_CHANGE`，不会自动修改 Self Model。

## 第一阶段验收

- 重复批准不产生第二份外部草稿；
- 使用旧 Artifact Hash 的批准被拒绝；
- 没有 Receipt 时状态不为完成；
- Artifact 可追溯到 Evidence 和 Working Self；
- 候选未经确认，不影响下一次 Working Self；
- 所有行为可在无模型密钥、无数据库、无外部网络下复现。

## Stage 1 · Restart-safe Capture

第一个持久化切片没有横向替换整个 Manifestation 聚合，而是增加一个极薄边界：

```text
POST /api/v1/captures
GET  /api/v1/captures/{captureId}
```

- 客户端提交 `clientNonce`、内容、`TEXT | LINK | VOICE_FILE` 来源引用和数据分类；
- 主体只来自服务端配置，`requestHash` 只由 Core 对语义字段计算；
- 同一主体的相同 nonce + 相同请求返回原 Capture，不同请求返回明确且不泄露原内容的冲突；
- PostgreSQL 唯一约束负责并发收敛，读取始终带主体条件；
- 未认证原型只允许 loopback 绑定，持久化边界只接收 `PUBLIC` 或 `PERSONAL`；
- 验收必须杀死打包应用并启动不同 JVM，不能用同 JVM 重建对象代替。

Manifestation、Action、Reflection 仍是 Stage 0 内存实现；Capture 不会触发模型或外部动作。

## Stage 1 · Conflict-safe Revision

第二个持久化切片从一个已归属的 S1 Capture 建立独立 Artifact lineage：

```text
POST /api/v1/artifacts
GET  /api/v1/artifacts/{artifactId}
PUT  /api/v1/artifacts/{artifactId}
```

- v1 引用 owner-scoped Capture，内容 Hash 只由 Core 计算；
- 修订请求必须提供 `expectedBaseVersion + expectedBaseHash`；
- PostgreSQL head 的单条 `UPDATE` 同时匹配主体、Artifact、版本和 Hash，成功后才在同一事务
  追加 immutable version；
- 两个独立应用实例竞争同一 base 时恰好一个返回 `200`，另一个返回专用 `409`；
- `409` 只返回安全的 `currentVersion` 并要求重新加载，不回显正文或内容 Hash；
- foreign 与 missing Artifact 的 GET/精确-base PUT 都使用相同 `404` 形态；
- 验收必须终止两个竞争 JVM，再由第三个 JVM 返回完全相同的有序 lineage。

这条边界不持久化旧的 Stage 0 `Manifestation`、Working Self、ActionPlan 或 Reflection，也不
引入认证、模型、Temporal、Connector 或通用 outbox。

## Stage 2 · Framework-free Fake Agent Draft

第一条可执行 Agent 路径只替换“如何从 Capture 产生 Artifact”这一个变量：

```text
POST /api/v1/agent-drafts
```

- 客户端只提交 `captureId + intent`；主体、模型、预算、工具白名单和策略版本由服务端拥有；
- 初始模型回合只获得 Capture 引用，必须请求唯一声明的 `capture.read` 才能看到正文；
- 工具同时经过注册、Task allowlist、输入引用和 owner-scoped 查询检查；
- 模型 structured final 只是提案；缺少正文或 Capture Evidence 时，Core 不创建 Artifact；
- 成功后复用 PostgreSQL Artifact lineage 写入 v1，并返回 `ResultEnvelope` 与安全事件摘要；
- Trace 只包含事件类型、工具、状态和引用，不保存正文或 hidden chain-of-thought；
- 步数、工具次数、deadline 与协作取消有界，越权工具、畸形工具结果和无证据结果都不能落库。

这条路径使用脚本 Fake Model，成本为零且没有外部网络副作用。它没有证明真实模型质量、
个性化、持久 Agent checkpoint、异步取消或生产自治。S2 将先决定 run/Trace 的持久化与
`HarnessRunBundle` 绑定，再接一个真实模型适配器。

## 后续切片

依次替换：

1. ActionAttempt / Receipt → PostgreSQL 与独立 Fake Provider；
2. Template Generator → AgentKernel Fake（S1 已完成）/ real adapter；
3. endpoint-local Trace → 可解析 run/Trace 与 HarnessRunBundle；
4. 同步用例 → Temporal Workflow；
5. Local Draft Stub → 可撤销真实平台草稿。

每次只替换一个变量，并保留上一实现作对照。
