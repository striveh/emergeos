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

## 下一阶段

依次替换：

1. 内存 Ledger → PostgreSQL；
2. Template Generator → AgentKernel Fake / real adapter；
3. 同步用例 → Temporal Workflow；
4. Local Draft Stub → 可撤销真实平台草稿。

每次只替换一个变量，并保留上一实现作对照。

