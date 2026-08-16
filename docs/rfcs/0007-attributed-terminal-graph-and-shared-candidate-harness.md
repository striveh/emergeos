# RFC-0007：Attributed terminal graph 与 shared-candidate Harness pilot

> Extended by
> [RFC-0008](0008-bounded-provider-validation-attestation.md), which adds a
> bounded validation-transcript experiment without changing this RFC's raw-body
> retention boundary or authorizing live execution.

- Status: Proposed
- Author: project owner + main Codex agent
- Created: 2026-07-31
- Scope: Stage 2 Pack010
- Extends:
  [RFC-0005](0005-model-bound-read-only-worker-eval-baseline.md)、
  [RFC-0006](0006-postgresql-canonical-one-shot-graph-attempt.md)
- Decision record:
  [ADR-0011](../architecture/decisions/0011-attributed-terminal-graph-and-live-harness-pilot.md)

## Problem

Pack009 已经证明：当 loopback provider 接收了 request，而 writer 在 attribution
落库前死亡时，PostgreSQL 能持续保存 `INCOMPLETE + UNKNOWN`，并阻止同一个
execution slot 再次产生 provider request。但它有意停在 sequence 11：

```text
PROVIDER_INTENT(requestOrdinal=1)
```

因此它还没有证明以下能力：

- provider usage、resolved model 与 exact durable intent 一一对应；
- child AgentRun、WorkerResult、parent AgentRun、Artifact 与 terminal journal
  原子一致；
- graph seal 能跨 JVM 重建，并绑定完整 Run/Bundle/provider truth；
- 同一个真实模型 structured final 可以同时交给 H0/H1，而不是每个 evaluator
  各生成一次；
- 三次 repetition 可以形成 complete-only、可复核、但不夸大统计意义的报告；
- shipping route 无法借 public Store、fake console、caller clock 或 raw credential
  绕过 owner TTY approval。

如果先写 V8 再补 Candidate contract，会把一个根本性遗漏固化进数据库：
H1 拒绝的 structured final 仍是需要审计的模型 observation，但它不是成功
`WorkerResultEnvelope`。若 seal 只绑定 WorkerResult，失败 Candidate 会消失；
若把 Candidate 冒充 WorkerResult，则会伪造 product success truth。

## Proposal

### 1. Pack010 的问题是协议 pilot，不是模型排行榜

第一代 pilot 固定：

```text
provider               = openai.responses
modelRequested         = gpt-5.6-terra
task                    = PUBLIC synthetic Capture
graph depth             = 1
generation repetitions  = 3
provider requests/run   = exact 2
evaluator arms          = H0 schema-only + H1 reference-grounding
```

选择 `gpt-5.6-terra` 是为了在 protocol proof 中平衡 intelligence 与 cost。
这不是对 `gpt-5.6-sol` 的质量结论。首个 pilot 不启用 persisted reasoning、
prompt-cache 写入、parallel tool calls、multi-agent beta 或 Connector。

每次 graph 的两次 Responses request 固定为：

```text
request 1 → model requests capture.read
capture.read → exact PUBLIC synthetic Capture
request 2 → structured final
```

因此 frozen success prefix 不是“最多两次，随意一或两次”，而是 exact two。
以后若要评测 one-call route，必须新建 protocol/pack，不得让同一 seal shape
出现两种解释。

### 2. `HarnessCandidateEnvelope` 是模型 observation，不是 product Result

新增 public contract：

```text
HarnessCandidateEnvelope 1.0
```

最小字段：

```text
schemaVersion = 1.0
candidateRef
attemptId
executionSlotId
repetition = 1 | 2 | 3
childRunId
childTaskId
sourceRequestOrdinal = 2
sourceResponseHash
traceRootHash
outputSchema
content
contentHash
evidenceRefs
obtainedEvidenceRefs
requiredEvidenceRef
requiredEvidenceAvailable
integrityProfile
integrityHash
```

不变量：

1. `candidateRef == "harness-candidate://" + childRunId`；
2. `sourceRequestOrdinal` 固定为 2，`sourceResponseHash` 必须与第二次
   provider attribution 的 response hash exact-equal；
3. child hash-chained Trace 必须有 exactly one `STRUCTURED_FINAL`，其
   `proposal://sha256:` 后缀等于 `contentHash`；
4. `contentHash` 是 exact UTF-8 content SHA-256；
5. `content` 最大 65,536 UTF-16 code units；
6. evidence ref 列表唯一、按 Unicode code point canonical order、最多 128 项；
7. `obtainedEvidenceRefs` 必须等于 child durable Result 的 Evidence refs；
8. `requiredEvidenceRef` 必须是 typed `capture://` ref；
9. terminal graph 中的 `requiredEvidenceAvailable` 由 child durable Result 是否
   含 exact required ref 推导，不能只信 Candidate 自报；
10. `integrityHash` 使用 length-prefixed canonical encoding，不依赖 JSON rendering；
11. `toString()` 不得输出 content；
12. structured final parse/schema failure时没有 Candidate，不能造空对象；此时
    Trace 可以保留 proposal hash，但 child 必须以 `INVALID_STRUCTURED_FINAL` 结束；
13. contract 允许“结构合法但 grounding 错误”的 Candidate，因为这正是 H1
   要测量的 observation。

Candidate 不能扩展或替代 `WorkerResultEnvelope`：

- H1 success：Candidate 可被投影成 exact WorkerResult；
- H1 failure：Candidate 仍存在，但没有 successful WorkerResult、Artifact；
- Candidate 由 graph seal单独绑定。

### 3. `HarnessEvaluationReport` 只表示完整协议，不表示显著性

新增 public contract：

```text
HarnessEvaluationReport 1.0
reportKind = LIVE_MODEL_SHARED_CANDIDATE_VERIFIER_PILOT
```

它固定包含：

- 三个 ordered repetition reports：`r1/r2/r3`；
- 两个 ordered evaluator arms：
  `h0-schema-only`、`h1-reference-grounding`；
- 六个 ordered evaluations：
  `r1/H0, r1/H1, r2/H0, r2/H1, r3/H0, r3/H1`；
- exact provider request/attribution、Run/Bundle、Candidate、seal、cost/token
  聚合；
- 全零 evaluator effects；
- `reportStatus=COMPLETE`。

报告不得包含：

- partial/null-filled repetition；
- 自动 replacement repetition；
- acceptance rate、置信区间、p-value、显著性；
- “某模型更好”或“可上线”的自动结论。

任一 repetition 为 `INVALID / INCOMPLETE / UNKNOWN / NOT_INVOKED` 时，不创建
Report，不继续下一个 live slot，也不自动 retry。

### 4. H0/H1 必须共享同一 Candidate

H0 只验证 Candidate contract、content hash 与 integrity hash。合法 Candidate
必然由 H0 接受；H0 只能存在于隔离 evaluator，不能成为 product verifier option。

H1 调用 production reference-grounding pure function，顺序保持：

```text
obtainedEvidenceRefs empty
→ MISSING_REQUIRED_EVIDENCE

obtainedEvidenceRefs != [requiredEvidenceRef]
→ UNSAFE_EVIDENCE_BINDING

evidenceRefs != [requiredEvidenceRef]
→ INVALID_EVIDENCE_CLAIM

requiredEvidenceAvailable == false
→ REQUIRED_EVIDENCE_NOT_FOUND

otherwise
→ SUCCEEDED
```

每个 Candidate 只能评测两次，不能为 H0/H1 分别请求模型、修改 content 或重新
执行 Tool。Evaluator effects 必须 exact：

```text
sharedCandidateInputs = 3
evaluations = 6
modelInvocations = 0
toolExecutions = 0
networkCalls = 0
credentialReads = 0
connectorCalls = 0
productTruthWrites = 0
externalSideEffects = 0
realUserDataReads = 0
```

### 5. terminal journal 固定为 17 个 event

Pack010 继续使用 Pack009 sequence 1–11，随后固定：

```text
12 PROVIDER_ATTRIBUTED(requestOrdinal=1)
13 PROVIDER_INTENT(requestOrdinal=2)
14 PROVIDER_ATTRIBUTED(requestOrdinal=2)
15 CHILD_TERMINAL
16 PARENT_TERMINAL
17 TERMINAL_SEALED
```

合法 durable snapshots 是：

- V7/Pack009 protocol 的任意连续 prefix：sequence 1–11；
- Pack010 protocol 的任意连续 prefix：sequence 1–15；其中 12、13、14
  分别是已归因 request 1、pending request 2、已归因 request 2 的真实可提交状态；
- Pack010 complete terminal：sequence 17。

sequence 16 不允许成为 committed state。它只存在于 parent terminal + seal
transaction 内部。

Attribution 至少绑定：

```text
requestOrdinal
requestHash
responseHash
providerActor
modelRequested
modelResolved
pricingProfileFingerprint
inputTokens
outputTokens
totalTokens
observedCostUsd
attributionHash
```

它明确不保存 raw request/response、header、credential、reasoning、exception text。
Store 必须用 frozen PricingProfile 重算 cost，不信任调用方自报金额。

### 6. PostgreSQL V8 的三个 transaction

V8 只做 forward migration，不修改 V7 bytes/checksum。

#### TX-A：provider attribution

```text
lock head
→ verify expected cursor + matching intent
→ insert immutable attribution
→ append PROVIDER_ATTRIBUTED
→ CAS head + derive billing
→ full aggregate assertion
→ commit
```

provider HTTP 与数据库不可能形成同一个 transaction。response 已返回但 TX-A
未 commit 时仍是合法 `INCOMPLETE + UNKNOWN`，不能自动 retry。

#### TX-B：child terminal

一个 transaction 内：

```text
lock head → parent Run → child Run
→ persist Candidate
→ optional successful WorkerResult
→ child Trace/Bundle/resource bindings
→ RUNNING → terminal child
→ append CHILD_TERMINAL
→ CAS head
→ full verified read
→ commit
```

H1 success 必须有 exact WorkerResult；H1 failure 必须没有 WorkerResult。
无论 H1 结果如何，结构合法的 Candidate 都存在并等待 seal 绑定。

这里的 `persist Candidate` 只适用于已经产生并通过 structured-final schema 的
observation。若 provider/model/parse 在 Candidate 形成前失败，TX-B 允许明确的
`pre-candidate failure`：Candidate 与 WorkerResult 都不存在，child/parent 仍以稳定
failure code terminalize。该 graph 可以被 seal 为可解释失败，但不能进入 Harness
Report，也不能继续下一个 live slot。

#### TX-C：parent terminal + seal

一个 transaction 内：

```text
lock head → parent Run → child Run
→ verify child terminal/Candidate/usage
→ optional success Artifact/version
→ parent Trace/HANDOFF/bindings
→ RUNNING → terminal parent
→ append PARENT_TERMINAL + CAS
→ compute seal from pre-seal head and complete truth
→ append TERMINAL_SEALED + CAS
→ insert create-only seal
→ full verified read
→ commit
```

两个 head CAS 都是 `sequence/stateVersion + 1`，不能一次跳两格。seal hash绑定
`PARENT_TERMINAL` 后的 pre-seal head；`TERMINAL_SEALED` event再把 seal hash
绑定进 final head，避免 circular preimage。

### 7. terminal seal 必须绑定已经存在的 Candidate

`GraphTerminalSeal` 至少绑定：

```text
attemptId / manifestHash
finalSequence
preSealHeadHash
graphOutcome / billingStatus
providerAttributionHashes
candidateRef / candidateIntegrityHash
childTerminalHash
parentTerminalHash
sealedAt
sealHash
```

seal row再引用 final event/head。`finalHeadHash` 不进入 seal preimage，因为 final
event本身包含 seal hash；reader必须交叉核验两者。

`candidateRef` 与 `candidateIntegrityHash` 必须 all-or-none。success 与 H1 rejection
都必须绑定 Candidate；只有在 structured final 形成前失败时，两者才允许同时为空。

对于 success：

```text
Candidate + WorkerResult + Artifact + both terminal Runs
```

对于 H1 rejection：

```text
Candidate + failed child/parent + no WorkerResult + no Artifact
```

对于 pre-Candidate failure：

```text
no Candidate + failed child/parent + no WorkerResult + no Artifact
```

这种失败是 terminal graph truth，不是完整 Harness repetition。

### 8. parent/child metering 与 Handoff 是 terminal truth

Pack010 只有一个 model-bound child，因此：

- provider attribution cost/token sum == child Result；
- parent aggregate cost/token == child Result；
- parent Bundle 有 exactly one HANDOFF binding；
- binding ref 指向 exact child Run；
- binding hash 指向 exact child Bundle；
- parent Trace 的 request/completion指向同一个 child；
- completion status与 child status、parent failure code一致。

只验证两个独立 AgentRun 各自合法，不足以证明 graph relation。

### 9. 真实 PostgreSQL writer 必须是不可取得的 object capability

Pack009 的 public Store constructor 与 injected console/clock在没有 shipping execute
时是已知 P2；一旦开放 live route就升级为 P1。

Pack010 采用：

```text
GraphAttemptReader                     public read-only port
PostgresGraphAttemptAccess.openReader  public safe reader factory
OwnerTtyGraphAuthority                 public high-level facade
PostgresGraphAttemptStore              non-public construction
MutationPermit                         private-constructor, single-use
Pack010ProviderSession                 private composer
```

真实 Store实例本身是 mutation capability。public API不得返回或接受 Store、
Coordinator、InteractiveConsole、Clock、GraphOperatorApproval、DataSource writer、
raw credential/client/model。

owner authority内部直接使用 real `System.console()` 与 system/DB time；测试 fake
console只能驱动 fake Store，不能取得真实 PostgreSQL writer。

### 10. r1 → r2 → r3 逐次 owner approval

三个 execution slot checked-in、server-owned：

```text
pack010-r1
pack010-r2
pack010-r3
```

一次进程只允许一个 exact revision。r2 claim transaction必须 fresh 验证 r1：

```text
VALID + terminal seal + ATTRIBUTED
+ exact Candidate/Bundle/metering
```

r3 对 r2执行相同检查。predecessor verification与新 claim必须同一 transaction，
避免 TOCTOU。

owner 对 r1、r2、r3 分别在 real TTY批准。一次批准不能批量释放三次；Codex、
agent、automation不得代输 challenge。任何 model、prompt、profile、endpoint、
budget、request cap、slot变化都需要新 frozen pack和新批准。

## Acceptance

### Contract slice

- Candidate/Report JSON Schema 与 Java record parity；
- semantic negative fixtures保持 outer hash正确但因目标 invariant失败；
- Chinese + astral Unicode golden vectors；
- H0/H1 deterministic evaluator与 reducer全零 effects；
- complete-only report；不生成 partial report。

### Core terminal slice

- exact two intent/attribution pair；
- request/model/provider/pricing/ordinal mismatch fail closed；
- provider usage == child usage == parent aggregate usage；
- sequence 15 valid、16 invalid、17 sealed；
- Candidate、Run/Bundle、WorkerResult/Artifact relation；
- self-consistent tampered seal仍被 fresh reader拒绝。

### PostgreSQL slice

- fresh V1→V8 与 populated V7 1/10/11 prefix upgrade；
- old rows/hashes/timestamps/`xmin` unchanged；
- TX-A/TX-B/TX-C statement-level process-kill matrix；
- child-terminal durable recovery与 parent local finalization；
- two JVM same-cursor one-winner；
- generic AgentRunStore不能 terminalize graph-bound Run；
- update/delete/second seal/post-seal append拒绝；
- fresh repeatable-read verifier重建 exact Candidate/terminal truth。

### Authority and App slice

- Store constructor/public surface reflection Gate；
- same-package permit forge compile-negative；
- no-TTY、piped input、wrong/expired/replayed challenge均 0 credential/client/request；
- App bytecode只有 exact authority/broker可访问 `System.console`、key、client/model；
- r2-before-r1、r3-before-r2与 predecessor invalid/unknown全部 fail closed；
- shaded JAR不包含 test console、loopback provider、crash harness、credential fixture。

### Live Gate

Contract、terminal PostgreSQL、fault/concurrency、authority、loopback、全量 repository
Gate 与独立 P0/P1 review全部 Green之后，代码仍不等于 live authorization。
项目 owner才可以逐次决定是否批准 r1、r2、r3。

## Non-goals

Pack010 不声明：

- 通用 multi-agent runtime；
- parallel graph、deep delegation或 Temporal workflow；
- OpenAI invoice reconciliation、provider idempotency或 exactly-once HTTP；
- 自动 recovery/retry；
- product API真实用户接入；
- Connector、社交发布或外部 Action；
- 模型质量显著性、商业验证或付费意愿；
- hostile DBA/superuser不可篡改。

## Rollback

RFC仍为 Proposed时，shipping execute保持 disabled。Contract 与 V8一旦产生 durable
truth，只能 forward migration，不删除、不降级、不把 UNKNOWN人工改成 ATTRIBUTED。
若 fault Gate失败，保留可解释 prefix，修复新版本协议；不得开放 live route绕过。
