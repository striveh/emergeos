# Build Note：Pack010 dormant provider capability / loopback ordering

- Change class：`R`（credential capability、compiled bytecode Gate、loopback effect ordering）
- Status：两个 focused Engineering slice Green；Authority/Live Gate仍为 Red
- Task：Stage 2 S4/F6 · Pack010
- Date：2026-08-02
- Commit：未提交；工作区含 owner既有 Pack010与 `.workbuddy`增量，以 Git history为准
- 前序回执：
  [V9 terminal authority / packaged successor](./2026-08-02-s2-s4-pack010-v9-terminal-authority.md)

## Outcome

shipping fat JAR现包含两个 package-private、Main不可达的 dormant capability：
`Pack010ProviderCredentialBroker`只在 exact owner permit已 single-use消费、revision-derived
manifest与 durable egress authority一致、`CREDENTIAL_READ_STARTED`已持久化之后读取
`OPENAI_API_KEY`；返回的 private-constructor `CredentialLease`绑定 revision、exact
`GraphAttemptCoordinator`与 exact `EgressAuthority` object identity，并只能 claim一次。

`Pack010ProviderSessionComposer`只接受 exact lease/authority，固定 production base URL、
`Proxy.NO_PROXY`、profile deadline、`LogLevel.OFF`与 reviewed `maxRetries(0)` client factory；
client/model durable marker均先于 session开放。每次 SDK调用前，exact observer先验证 ordinal、
request hash与 model identity，再同步持久化 `GraphProviderIntent`。当前 adapter没有 Pack010所需
raw response hash与完整 input/cached/output/reasoning token split，因此 response attribution
observer明确 fail-closed，不伪造 terminal receipt。

`GraphEvalBytecodeGate`同时检查 `target/classes`与最终 shaded JAR：敏感 Field/Method consumer
按 kind + owner + name + descriptor + exact class冻结；broker/composer/`ProviderSession.next`
production incoming refs为0；credential与 production URL literal、proxy/deadline/log policy、
唯一 lease claim consumer均受 Gate保护。常见 Class/ClassLoader/reflection/MethodHandles间接
接入默认拒绝，仅保留 report canonicalizer的 exact `Method.invoke` allowlist；
`CONSTANT_Dynamic`拒绝。`CONSTANT_InvokeDynamic`会解析到 `BootstrapMethods`与 MethodHandle，
只允许 exact `LambdaMetafactory.metafactory`、`StringConcatFactory.makeConcatWithConstants`与
record `ObjectMethods.bootstrap`；篡改 bootstrap owner的 classfile fixture必须 Red。

第二个 slice复用 production exact observer factory连接 reviewed client、OpenAI adapter、
GraphAttemptCoordinator与本机 loopback HTTP server：若 durable provider intent写入抛错，
SDK/HTTP request exact为0；若一次 PUBLIC synthetic response到达，因 attribution surface不足
立即拒绝，第二次调用也不会增加 HTTP request或 graph effect。该证据没有经过 production URL，
也没有读 credential environment；它只证明 exact observer的局部 effect ordering。

## Acceptance Red → Focused Red → minimum implementation

exact capability Acceptance Red：

```bash
./mvnw -pl apps/graph-eval-runner -am \
  -Dtest=Pack010ProviderCapabilityBytecodeGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

初始结果为 `ClassNotFoundException: Pack010ProviderCredentialBroker`。增加两个 dormant class后，
`GraphEvalArchitectureTest`又以六个 unreviewed production class Red；最小实现把 exact class与
capability surface加入 allowlist，没有把它们连接到 Main。

loopback Acceptance Red：

```bash
./mvnw -pl apps/graph-eval-runner \
  -Dtest=Pack010LoopbackEffectOrderingSentinelTest \
  -Dsurefire.failIfNoSpecifiedTests=true test
```

初始结果为 `No tests matching pattern`。实现后首次 Focused Red又暴露 synthetic response JSON
不符合 strict adapter shape，修正 exact response fixture后才 Green；没有放宽 parser或 observer。

## Executable evidence

focused capability + ordering Gate：

```bash
./mvnw -pl apps/graph-eval-runner -am \
  -Dtest=Pack010ProviderCapabilityBytecodeGateTest,Pack010ProviderSessionEffectOrderingTest,Pack010LoopbackEffectOrderingSentinelTest,GraphEvalArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`15 tests`，`0 failures / 0 errors / 0 skipped`。其中 loopback `2/2`证明：

- `providerIntent` persistence fault → `HTTP requests=0`；
- attribution surface不足 → `HTTP requests=1`、durable intents `=1`；replay后两者仍不增长。

真实 owner TTY/authority回归：

```bash
./mvnw -pl adapters/postgres -am \
  -Dtest=OwnerTtyGraphAuthoritySurfaceTest,PostgresGraphAttemptStoreTest#ownerAuthorityRealTtyMatrixBurnsRejectedClaimsAndPermit+ownerAuthorityRejectsNoTtyBeforeClaim+pack010PredecessorVerificationAndClaimAreOneTransaction \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`6 tests` Green。它证明真实 PTY/Testcontainers authority matrix没有被 broker return
type与 revision binding回归；它没有实际读取 credential。

完整 graph packaged/fault Gate：

```bash
./mvnw -pl apps/graph-eval-runner -am verify
```

结果：7-module reactor Green；graph unit `45`、IT `7`全部 Green，总耗时 `03:23 min`。
37-point kill matrix、packaged successor、TX-A/B/C two-JVM race、Harness Report与 Pack009
loopback crash receipt同次重现。该次 full Gate之后只增加 observer factory内部的 exact manifest
复核；最终快照又以 focused `15/15`和 actual shaded-JAR targeted IT `1/1`复核为 Green：

```bash
./mvnw -pl apps/graph-eval-runner -am \
  -Dtest=GraphEvalArchitectureTest,Pack010ProviderCapabilityBytecodeGateTest,Pack010ProviderSessionEffectOrderingTest,Pack010LoopbackEffectOrderingSentinelTest \
  -Dit.test=Pack010DurableGraphTerminalProcessIT#shippingJarContainsVerifierButNoHarnessOrCliRoute \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

结果：unit `15/15`、actual shaded-JAR IT `1/1` Green。根级 `./mvnw verify`在同轮 V9/
successor稳定快照为 `11/11 BUILD SUCCESS`、`04:39 min`；后续变更仅在上述7-module范围，
没有用早期 root结果替代受影响模块的最终复核。

最终 shipping artifact仍明确拒绝 route：

```bash
java -jar apps/graph-eval-runner/target/emerge-graph-eval-runner-0.1.0-SNAPSHOT-app.jar --verify
```

结果：receipt为 `keyReads=0 clientFactories=0 modelFactories=0 httpRequests=0
shippingExecute=false`，随后 `SHIPPING_VERIFY_ROUTE_DISABLED`、exit `2`。

## Independent review

三路 bounded read-only subagent均在最终 post-fix快照实际复跑，而不是沿用首次 review。
Acceptance/Gate review对 dormant broker/composer与 process-local loopback两个 focused scope均
给出 `P0=0、P1=0、P2=0`。SQL/contract review首次将 observer factory列为 P2，随后核对发现
descriptor-exact self-consumer规则原本已存在；factory内部再增加 exact manifest复核后，reviewer
实际重跑 `15/15`并明确关闭该 P2，最终为 `P0=0、P1=0、P2=0`。

Threat review曾在前一快照发现 custom InvokeDynamic bootstrap绕过 P1；最终实现解析
`BootstrapMethods`/MethodHandle、增加 exact bootstrap allowlist与 negative fixture后，reviewer
在最终 manifest-binding/shaded-JAR快照实际复跑，确认该 P1关闭。其 focused结论为
`P0=0、P1=0、P2=2`，两个 P2是证据 scope：loopback不是 broker→composer→PostgreSQL→
packaged-process kill/restart端到端；bytecode allowlist也不是完整 control/data-flow与所有
library-mediated gadget的形式化闭包。三路 review因此一致确认 focused Engineering Green，
但没有一致声明 `P2=0`。

Authority/Live由 threat review单列为 `P0=0、P1=3、P2=1`：缺少 live authority bridge、
完整 response attribution与真实 provider receipt；同 JVM/in-memory loopback也不证明 HTTP后
crash/restart reconciliation。Authority/Live Gate保持 Red，不得把 focused review外推为 live
`P1=0`。

## Evidence boundary

Engineering：PUBLIC synthetic data、本机 Testcontainers、real local PTY、test-only loopback与
no-egress shipping route。真实 API key、provider/model result、provider network、真实
r1/r2/r3与 billing均为 `0`。loopback发生一次本机 HTTP，不是 provider network evidence。

Human-learning：暂停；没有 teach-back、owner真实批准或学习回执。

Commercial：没有访谈、报价、付款、留存或真实用户验证。

## Remaining risk / next falsifiable slice

Authority/Live Gate保持 Red。下一条最高价值 Acceptance是 production capability handoff：
一次 owner-approved durable cursor必须派生 coordinator typestate，不能让 Owner facade与
Coordinator分别 claim/approve同一 execution slot；随后 generic prefix writer与 V9
`graph_executor` TX-B/TX-C semantic function必须在同一 shipping App transaction topology内
可组合，且完整 response hash/token attribution必须先落库。production provisioning bootstrap
也必须独立于 Flyway schema migration、无 runtime migrator/table owner，并保持 shipping
execute disabled，直到上述边界与独立 review同时 Green。
