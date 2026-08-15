# EmergeOS 文档地图

文档不是宣传材料，而是参与者理解、验证和改变系统的共同入口。

## 真相层级

出现冲突时，以更靠前者为准：

```text
代码与自动化测试
→ 已接受的规格与不变量
→ 已接受 ADR
→ Roadmap
→ 产品叙事与探索记录
```

RFC 记录提议与讨论，并可进入 Accepted、Rejected 或 Withdrawn；ADR 记录已经采用的
决定。ADR 接受后不改写历史，只能由新 ADR 替代。

## 阅读路线

### 想理解产品

- [五结果项目宪章](./strategy/five-outcome-charter.md)
- [产品蓝图](../PRODUCT-BLUEPRINT.md)
- [第一条纵向闭环](./product/first-vertical-slice.md)
- [路线图](../ROADMAP.md)

### 想理解架构

- [系统总览](./architecture/system-overview.md)
- [模块地图](./architecture/module-map.md)
- [状态、凭证与持久执行](./architecture/runtime-and-state.md)
- [术语表](./glossary.md)

### 想参与研发

- [贡献指南](../CONTRIBUTING.md)
- [研发方法与分层测试](./engineering/development-method.md)
- [Codex 协作手册](./engineering/codex-playbook.md)
- [Task Brief 模板](./engineering/task-brief-template.md)
- [长任务 ExecPlan 规范](../PLANS.md)
- [Stage 1 Durable Correctness ExecPlan](./plans/2026-07-28-stage-1-durable-correctness.md)
- [Stage 2 AgentKernel ExecPlan](./plans/2026-07-29-stage-2-agent-kernel-evaluation.md)
- [模块化单体 ADR](./architecture/decisions/0001-modular-monolith.md)
- [真相与上下文 ADR](./architecture/decisions/0002-truth-and-projections.md)
- [外部副作用 ADR](./architecture/decisions/0003-durable-side-effects.md)
- [ActionAttempt 与对账决策](./architecture/decisions/0004-action-attempt-reconciliation.md)
- [五结果研发制度 ADR](./architecture/decisions/0005-five-outcome-development-system.md)
- [持久 AgentRun 真相 ADR](./architecture/decisions/0006-persistent-agent-run-truth.md)
- [Observed latency 与 post-dispatch deadline ADR](./architecture/decisions/0007-observed-latency-and-post-dispatch-tool-deadline.md)
- [Typed read-only Worker handoff ADR](./architecture/decisions/0008-typed-read-only-worker-handoff.md)
- [Child-only model Worker Eval boundary ADR](./architecture/decisions/0009-child-only-model-worker-eval-boundary.md)
- [PostgreSQL canonical graph attempt ADR](./architecture/decisions/0010-postgresql-canonical-graph-attempt.md)
- [Attributed terminal graph 与 shared-candidate Harness ADR](./architecture/decisions/0011-attributed-terminal-graph-and-live-harness-pilot.md)
- [DB-authenticated provider validation attestation ADR](./architecture/decisions/0012-db-authenticated-provider-validation-attestation.md)
- [Exact provider profile assertion foundation ADR](./architecture/decisions/0013-exact-provider-profile-assertion-foundation.md)
- [Exact provider TX-A requirement guard ADR](./architecture/decisions/0014-exact-provider-tx-a-requirement-guard.md)
- [Exact-pico provider TX-A overlay ADR](./architecture/decisions/0015-exact-pico-provider-tx-a-overlay.md)
- [Dormant V16 Ed25519 public verifier ADR](./architecture/decisions/0016-dormant-v16-ed25519-public-verifier.md)
- [Dormant typed V16 stage-verify-commit attestor ADR](./architecture/decisions/0017-dormant-typed-v16-stage-verify-commit-attestor.md)
- [Fresh-JVM verified exact-pico overlay reader ADR](./architecture/decisions/0018-fresh-jvm-verified-exact-pico-overlay-reader.md)
- [Apache-2.0、DCO 与公开治理 ADR](./architecture/decisions/0019-open-source-license-and-contributions.md)
- [Append-only logical local Draft Undo ADR](./architecture/decisions/0020-append-only-logical-local-draft-undo.md)
- [RFC-0001：持久 AgentRun、Safe Trace 与 HarnessRunBundle](./rfcs/0001-persistent-agent-run-trace-and-bundle.md)
- [RFC-0002：真实模型 synthetic egress 与计量边界](./rfcs/0002-real-model-synthetic-egress-and-metering.md)
- [RFC-0003：read-only Tool post-dispatch deadline truth](./rfcs/0003-post-dispatch-read-only-tool-deadline-truth.md)
- [RFC-0004：typed read-only Worker handoff 与 durable Worker Result](./rfcs/0004-typed-read-only-worker-handoff.md)
- [RFC-0005：child-only model-bound read-only Worker Eval baseline](./rfcs/0005-model-bound-read-only-worker-eval-baseline.md)
- [RFC-0006：PostgreSQL canonical one-shot graph attempt](./rfcs/0006-postgresql-canonical-one-shot-graph-attempt.md)
- [RFC-0007：Attributed terminal graph 与 shared-candidate Harness pilot](./rfcs/0007-attributed-terminal-graph-and-shared-candidate-harness.md)
- [RFC-0008：bounded provider validation attestation](./rfcs/0008-bounded-provider-validation-attestation.md)
- [RFC-0009：exact provider profile assertion foundation](./rfcs/0009-exact-provider-profile-assertion-foundation.md)
- [RFC-0010：exact provider TX-A requirement guard](./rfcs/0010-exact-provider-tx-a-requirement-guard.md)
- [RFC-0011：exact-pico provider TX-A overlay](./rfcs/0011-exact-pico-provider-tx-a-overlay.md)
- [RFC-0012：dormant V16 Ed25519 public verifier](./rfcs/0012-dormant-v16-ed25519-public-verifier.md)
- [RFC-0013：dormant typed V16 stage-verify-commit attestor](./rfcs/0013-dormant-typed-v16-stage-verify-commit-attestor.md)
- [RFC-0014：fresh-JVM verified exact-pico overlay reader](./rfcs/0014-fresh-jvm-verified-exact-pico-overlay-reader.md)
- [Eval Task Packs、Offline baseline 与 bounded provider runner](../evals/README.md)

### 想参与研究与共同进化

- [产品驱动学习路线](./learning/curriculum.md)
- [能力矩阵](./learning/capability-matrix.md)
- [Learning Note 模板](./learning/learning-note-template.md)
- [S1 Capture 学习草稿](./learning/notes/2026-07-28-s1-restart-safe-capture.md)
- [S2 Conflict-safe Revision 学习草稿](./learning/notes/2026-07-28-s2-conflict-safe-revision.md)
- [S3 Recoverable local Action 学习草稿](./learning/notes/2026-07-28-s3-recoverable-local-action.md)
- [S4 Operating evidence 学习草稿](./learning/notes/2026-07-29-s4-operating-evidence.md)
- [Harness 验证计划](./research/harness-validation.md)
- [公开运营模型](./operations/operating-model.md)
- [五结果周度/月度计分卡](./operations/five-outcome-scorecard.md)
- [2026-07 Foundation 基线](./operations/scorecards/2026-07-foundation-baseline.md)
- [Build Note 模板](./operations/build-note-template.md)
- [首个 Foundation Build Note](./operations/build-notes/2026-07-28-foundation.md)
- [五结果研发制度 Build Note](./operations/build-notes/2026-07-28-five-outcome-process.md)
- [S1 Restart-safe Capture Build Note](./operations/build-notes/2026-07-28-s1-restart-safe-capture.md)
- [S2 Conflict-safe Revision Build Note](./operations/build-notes/2026-07-28-s2-conflict-safe-revision.md)
- [S3 Recoverable local Action Build Note](./operations/build-notes/2026-07-28-s3-recoverable-local-action.md)
- [S4 Operating and Gate-Closure Build Note](./operations/build-notes/2026-07-29-s4-operating-gate-closure.md)
- [Stage 2 S1 Fake Agent Draft Build Note](./operations/build-notes/2026-07-30-s2-s1-fake-agent-draft-loop.md)
- [Stage 2 S2 持久 AgentRun Build Note](./operations/build-notes/2026-07-30-s2-persistent-agent-run-trace.md)
- [Stage 2 S3 OpenAI Responses Adapter Build Note](./operations/build-notes/2026-07-30-s2-s3-openai-responses-adapter.md)
- [Stage 2 S3 Bounded Synthetic Eval Runner Build Note](./operations/build-notes/2026-07-30-s2-s3-bounded-synthetic-eval-runner.md)
- [Stage 2 S3 Durable Eval Attempt Evidence Build Note](./operations/build-notes/2026-07-30-s2-s3-durable-attempt-evidence.md)
- [Stage 2 S3 Eval Run Record Create-only Build Note](./operations/build-notes/2026-07-31-s2-s3-eval-run-record-create-only.md)
- [Stage 2 S4 Offline Comparison Loader Build Note](./operations/build-notes/2026-07-30-s2-s4-offline-comparison-loader.md)
- [Stage 2 S4 Verified Offline Comparison Build Note](./operations/build-notes/2026-07-30-s2-s4-verified-offline-comparison.md)
- [Stage 2 S4 Durable Offline Comparison Build Note](./operations/build-notes/2026-07-30-s2-s4-durable-offline-comparison-report.md)
- [Stage 2 S4 Tool Arguments Fault Build Note](./operations/build-notes/2026-07-31-s2-s4-tool-argument-fault.md)
- [Stage 2 S4 Post-dispatch Deadline Build Note](./operations/build-notes/2026-07-31-s2-s4-post-dispatch-deadline.md)
- [Stage 2 S4 Typed Read-only Worker Handoff Build Note](./operations/build-notes/2026-07-31-s2-s4-typed-read-only-worker-handoff.md)
- [Stage 2 S4 Model-bound Read-only Worker Baseline Build Note](./operations/build-notes/2026-07-31-s2-s4-model-bound-read-only-worker-baseline.md)
- [Stage 2 S4 Pack009 Durable Graph Crash Build Note](./operations/build-notes/2026-07-31-s2-s4-pack009-durable-graph-crash.md)
- [GitHub 公开发布 Build Note](./operations/build-notes/2026-08-01-github-public-release.md)
- [Stage 2 S4 Pack010 Terminal Graph / Harness Report Build Note](./operations/build-notes/2026-08-01-s2-s4-pack010-terminal-graph-harness-report.md)
- [Stage 2 S4 Pack010 Owner TTY / Predecessor Authority Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-owner-tty-predecessor-authority.md)
- [Stage 2 S4 Pack010 V9 Terminal Authority Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-v9-terminal-authority.md)
- [Stage 2 S4 Pack010 Dormant Provider Capability Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-dormant-provider-capabilities.md)
- [Stage 2 S4 Pack010 Capability Handoff / Provisioning Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-capability-handoff-provisioning.md)
- [Stage 2 S4 Pack010 Exact Provider Attribution Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-exact-provider-attribution.md)
- [Stage 2 S4 Pack010 Terminal Outcome Binding Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-terminal-outcome-binding.md)
- [Stage 2 S4 Pack010 Typed TX-C Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-typed-tx-c.md)
- [Stage 2 S4 Pack010 Canonical Terminal Transitions Build Note](./operations/build-notes/2026-08-02-s2-s4-pack010-canonical-terminal-transitions.md)
- [Stage 2 S4 Pack010 V10 Attributed Failure Authority Build Note](./operations/build-notes/2026-08-09-s2-s4-pack010-v10-attributed-failure-authority.md)
- [Stage 2 S4 Pack010 Durable Attributed Failure Resume Build Note](./operations/build-notes/2026-08-09-s2-s4-pack010-durable-attributed-failure-resume.md)
- [Stage 2 S4 DeepSeek V4 Flash Responses Compatibility Probe Build Note](./operations/build-notes/2026-08-11-s2-s4-deepseek-v4-flash-responses-compatibility-probe.md)
- [Stage 2 S4 Pack010 V14 Exact Provider Profile Assertion Build Note](./operations/build-notes/2026-08-11-s2-s4-pack010-v14-exact-provider-profile-assertion.md)
- [Stage 2 S4 Pack010 V15 Exact TX-A Requirement Guard Build Note](./operations/build-notes/2026-08-12-s2-s4-pack010-v15-exact-tx-a-requirement-guard.md)
- [Stage 2 S4 Pack010 V16 Exact-pico Provider TX-A Overlay Build Note](./operations/build-notes/2026-08-12-s2-s4-pack010-v16-exact-pico-provider-tx-a-overlay.md)
- [Stage 2 S4 Pack010 V17 Dormant V16 Ed25519 Verifier Build Note](./operations/build-notes/2026-08-12-s2-s4-pack010-v17-dormant-v16-ed25519-verifier.md)
- [Stage 2 S4 Pack010 V18 Dormant Typed V16 Attestor Build Note](./operations/build-notes/2026-08-12-s2-s4-pack010-v18-dormant-typed-v16-attestor.md)
- [Stage 2 S4 Pack010 V19 Fresh-JVM Exact-pico Overlay Reader Build Note](./operations/build-notes/2026-08-12-s2-s4-pack010-v19-fresh-jvm-exact-pico-overlay-reader.md)
- [Stage 2 S4 Pack010 V20 PostgreSQL Immediate-restart Overlay Readback Build Note](./operations/build-notes/2026-08-12-s2-s4-pack010-v20-postgres-immediate-restart-overlay-readback.md)
- [Stage 1 Operating Runbook](./operations/stage1-operating-runbook.md)
- [S4 Durable Operations Case Card](./interview/case-cards/2026-07-29-stage1-durable-operations.md)
- [商业验证路线](./business/validation-roadmap.md)
- [14 天 Founder Seed Log 模板](./business/founder-seed-log-template.md)
- [用户研究数据协议](./business/research-data-protocol.md)
- [用户问题访谈模板](./business/problem-interview-template.md)
- [面试证据包](./interview/evidence-pack.md)
- [开放源码准备清单](./community/open-source-readiness.md)

## 仍需逐步拆分的历史文档

[PRODUCT-BLUEPRINT.md](../PRODUCT-BLUEPRINT.md) 与 [AGENT-FABRIC.md](../AGENT-FABRIC.md) 暂时作为完整设计背景保留。稳定的契约和决策会逐步迁移到规格、ADR 和可执行测试，不在两份长文档中无限追加。
