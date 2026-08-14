(() => {
  const TRUSTED_ARTIFACT_EVENT = "emergeos:trusted-artifact";
  const ARTIFACT_INVALIDATED_EVENT = "emergeos:artifact-invalidated";
  const SCOPE_SCHEMA = "emergeos.action-approval-scope.v1";
  const MAX_RESPONSE_BYTES = 262144;
  const APPROVAL_TIMEOUT_MILLIS = 10000;
  const HASH_PATTERN = /^[0-9a-f]{64}$/;
  const JSON_MEDIA_TYPE = /^application\/json(?:\s*;|$)/i;
  const PROBLEM_MEDIA_TYPE = /^application\/problem\+json(?:\s*;|$)/i;
  const STALE_PROBLEM_ALLOWLIST = Object.freeze([
    Object.freeze({
      status: 412,
      type: "urn:emergeos:problem:approval-stale",
      title: "Action approval stale",
    }),
  ]);

  class ApprovalClientError extends Error {
    constructor(kind, code) {
      super(code);
      this.name = "ApprovalClientError";
      this.kind = kind;
      this.code = code;
    }
  }

  const invalidResponse = (code) =>
    new ApprovalClientError("INVALID_RESPONSE", code);
  const transportUnknown = (code) =>
    new ApprovalClientError("TRANSPORT_UNKNOWN", code);
  const clientUnavailable = (code) =>
    new ApprovalClientError("CLIENT_UNAVAILABLE", code);

  const card = document.querySelector("#approval-card");
  const principalText = document.querySelector("#approval-principal");
  const provenanceText = document.querySelector("#approval-provenance");
  const actionText = document.querySelector("#approval-action");
  const targetText = document.querySelector("#approval-target");
  const riskText = document.querySelector("#approval-risk");
  const artifactText = document.querySelector("#approval-artifact");
  const scopeText = document.querySelector("#approval-scope");
  const boundaryText = document.querySelector("#approval-boundary");
  const submit = document.querySelector("#approval-submit");
  const retry = document.querySelector("#approval-retry");
  const status = document.querySelector("#approval-status");

  if (
    !card ||
    !principalText ||
    !provenanceText ||
    !actionText ||
    !targetText ||
    !riskText ||
    !artifactText ||
    !scopeText ||
    !boundaryText ||
    !submit ||
    !retry ||
    !status
  ) {
    return;
  }

  let trustedArtifact = null;
  let trustedScope = null;
  let frozenApproval = null;
  let requestPending = false;
  let artifactEpoch = 0;
  let requestController = null;

  const setState = (state, message, alert = false) => {
    card.dataset.state = state;
    status.dataset.state = state;
    status.textContent = message;
    status.setAttribute?.("role", alert ? "alert" : "status");
    status.setAttribute?.("aria-live", alert ? "assertive" : "polite");
    if (alert) {
      status.focus?.();
    }
  };

  const setBusy = (busy) => {
    requestPending = busy;
    submit.disabled = busy || !trustedArtifact || !trustedScope;
    retry.disabled = busy || !frozenApproval;
    card.setAttribute?.("aria-busy", String(busy));
  };

  const resetScopeFacts = () => {
    principalText.textContent = "尚未验证批准范围";
    provenanceText.textContent = "尚未验证批准范围";
    actionText.textContent = "尚未验证批准范围";
    targetText.textContent = "尚未验证批准范围";
    riskText.textContent = "尚未验证批准范围";
    scopeText.textContent = "尚未验证批准范围";
    boundaryText.textContent =
      "批准范围尚未通过本地校验；不会发送批准请求，也不会执行任何行动。";
  };

  const invalidate = () => {
    artifactEpoch += 1;
    requestController?.abort?.();
    requestController = null;
    trustedArtifact = null;
    trustedScope = null;
    frozenApproval = null;
    requestPending = false;
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    artifactText.textContent = "当前 Artifact 已失效；请重新保存可信版本";
    resetScopeFacts();
    card.hidden = false;
    setState(
      "INVALIDATED",
      "批准卡已失效。请先重新保存 Outcome Canvas，再核对并批准新的精确版本。",
      false,
    );
  };

  const safeIdentifier = (value) =>
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 200 &&
    !value.includes("\0") &&
    !/[/?#]/.test(value);

  const safeScopeText = (value) =>
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 1000 &&
    !value.includes("\0");

  const hasExactKeys = (value, expected) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return false;
    }
    const actual = Object.keys(value).sort();
    const sortedExpected = [...expected].sort();
    return (
      actual.length === sortedExpected.length &&
      actual.every((key, index) => key === sortedExpected[index])
    );
  };

  const safeTrustedArtifact = (detail) => {
    if (
      !detail ||
      !safeIdentifier(detail.artifactId) ||
      !Number.isSafeInteger(detail.artifactVersion) ||
      detail.artifactVersion < 1 ||
      !HASH_PATTERN.test(detail.artifactHash ?? "")
    ) {
      return null;
    }
    return Object.freeze({
      artifactId: detail.artifactId,
      artifactVersion: detail.artifactVersion,
      artifactHash: detail.artifactHash,
    });
  };

  const randomApprovalNonce = () => {
    const randomUUID = globalThis.crypto?.randomUUID;
    if (typeof randomUUID !== "function") {
      return null;
    }
    try {
      const nonce = randomUUID.call(globalThis.crypto);
      return safeIdentifier(nonce) ? nonce : null;
    } catch (_failure) {
      return null;
    }
  };

  const hasMediaType = (response, pattern) =>
    pattern.test(response.headers.get("content-type") ?? "");

  const cancelResponseBody = async (response) => {
    try {
      await response.body?.cancel?.();
    } catch (_failure) {
      // The response is already untrusted; cancellation is best-effort only.
    }
  };

  const readBoundedJson = async (response, mediaType) => {
    const declared = response.headers.get("content-length");
    if (declared !== null) {
      const length = Number(declared);
      if (!Number.isSafeInteger(length) || length < 0 || length > MAX_RESPONSE_BYTES) {
        await cancelResponseBody(response);
        throw invalidResponse("APPROVAL_RESPONSE_SIZE_INVALID");
      }
    }
    if (!hasMediaType(response, mediaType)) {
      await cancelResponseBody(response);
      throw invalidResponse("APPROVAL_RESPONSE_MIME_INVALID");
    }

    if (!response.body?.getReader) {
      const text = await response.text();
      if (new TextEncoder().encode(text).byteLength > MAX_RESPONSE_BYTES) {
        throw invalidResponse("APPROVAL_RESPONSE_TOO_LARGE");
      }
      try {
        return JSON.parse(text);
      } catch (_failure) {
        throw invalidResponse("APPROVAL_RESPONSE_JSON_INVALID");
      }
    }

    const reader = response.body.getReader();
    const chunks = [];
    let size = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > MAX_RESPONSE_BYTES) {
          await reader.cancel?.();
          throw invalidResponse("APPROVAL_RESPONSE_TOO_LARGE");
        }
        chunks.push(value);
      }
    } finally {
      reader.releaseLock?.();
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    try {
      return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    } catch (_failure) {
      throw invalidResponse("APPROVAL_RESPONSE_JSON_INVALID");
    }
  };

  const canonicalScopeValues = (scope) => [
    scope.approvalPrincipal.basis,
    scope.approvalPrincipal.configuredPrincipalId,
    scope.provenance.approvalOrigin,
    scope.provenance.executionRoute,
    scope.action.actionType,
    scope.action.targetRef,
    scope.artifact.artifactId,
    String(scope.artifact.artifactVersion),
    scope.artifact.artifactHash,
    scope.action.risk,
    scope.action.policyVersion,
    scope.capability.connector,
    scope.capability.audience,
    scope.capability.accountRef,
    String(scope.capability.capabilityTtlMicros),
    String(scope.capability.maxCalls),
  ];

  const recomputeScopeHash = async (scope) => {
    const subtle = globalThis.crypto?.subtle;
    const TextEncoderConstructor = globalThis.TextEncoder;
    if (
      !subtle ||
      typeof subtle.digest !== "function" ||
      typeof TextEncoderConstructor !== "function"
    ) {
      throw clientUnavailable("APPROVAL_SCOPE_CRYPTO_UNAVAILABLE");
    }
    const encoder = new TextEncoderConstructor();
    const schemaBytes = encoder.encode(SCOPE_SCHEMA);
    const valueBytes = canonicalScopeValues(scope).map((value) => encoder.encode(value));
    const totalLength =
      schemaBytes.byteLength +
      1 +
      valueBytes.reduce((total, value) => total + 4 + value.byteLength, 0);
    const canonical = new Uint8Array(totalLength);
    const view = new DataView(canonical.buffer);
    canonical.set(schemaBytes, 0);
    let offset = schemaBytes.byteLength;
    canonical[offset] = 0;
    offset += 1;
    for (const value of valueBytes) {
      view.setUint32(offset, value.byteLength, false);
      offset += 4;
      canonical.set(value, offset);
      offset += value.byteLength;
    }
    const digest = await subtle.digest("SHA-256", canonical);
    return Array.from(new Uint8Array(digest), (byte) =>
      byte.toString(16).padStart(2, "0"),
    ).join("");
  };

  const freezeScope = (body) =>
    Object.freeze({
      scopeSchema: body.scopeSchema,
      scopeHash: body.scopeHash,
      approvalPrincipal: Object.freeze({ ...body.approvalPrincipal }),
      provenance: Object.freeze({ ...body.provenance }),
      action: Object.freeze({ ...body.action }),
      artifact: Object.freeze({ ...body.artifact }),
      capability: Object.freeze({ ...body.capability }),
      executionState: body.executionState,
    });

  const validateScopePreview = async (body, artifact) => {
    if (
      !hasExactKeys(body, [
        "scopeSchema",
        "scopeHash",
        "approvalPrincipal",
        "provenance",
        "action",
        "artifact",
        "capability",
        "executionState",
      ]) ||
      !hasExactKeys(body.approvalPrincipal, ["basis", "configuredPrincipalId"]) ||
      !hasExactKeys(body.provenance, ["approvalOrigin", "executionRoute"]) ||
      !hasExactKeys(body.action, ["actionType", "targetRef", "risk", "policyVersion"]) ||
      !hasExactKeys(body.artifact, ["artifactId", "artifactVersion", "artifactHash"]) ||
      !hasExactKeys(body.capability, [
        "connector",
        "audience",
        "accountRef",
        "capabilityTtlMicros",
        "maxCalls",
        "usedCalls",
      ])
    ) {
      throw invalidResponse("APPROVAL_SCOPE_SHAPE_INVALID");
    }
    if (
      body.scopeSchema !== SCOPE_SCHEMA ||
      !HASH_PATTERN.test(body.scopeHash ?? "") ||
      body.approvalPrincipal.basis !== "CONFIGURED_LOCAL_PRINCIPAL" ||
      !safeIdentifier(body.approvalPrincipal.configuredPrincipalId) ||
      body.provenance.approvalOrigin !== "EXPLICIT_LOCAL_OWNER_INPUT" ||
      body.provenance.executionRoute !== "LOCAL_DRAFTBOX_V1" ||
      body.action.actionType !== "CREATE_LOCAL_DRAFT" ||
      body.action.targetRef !== "local://drafts" ||
      body.action.risk !== "REVERSIBLE" ||
      !safeScopeText(body.action.policyVersion) ||
      body.artifact.artifactId !== artifact.artifactId ||
      body.artifact.artifactVersion !== artifact.artifactVersion ||
      body.artifact.artifactHash !== artifact.artifactHash ||
      !safeScopeText(body.capability.connector) ||
      !safeScopeText(body.capability.audience) ||
      !safeScopeText(body.capability.accountRef) ||
      !Number.isSafeInteger(body.capability.capabilityTtlMicros) ||
      body.capability.capabilityTtlMicros < 1 ||
      !Number.isSafeInteger(body.capability.maxCalls) ||
      body.capability.maxCalls < 1 ||
      body.capability.usedCalls !== 0 ||
      body.executionState !== "NOT_EXECUTED"
    ) {
      throw invalidResponse("APPROVAL_SCOPE_BINDING_INVALID");
    }
    const scope = freezeScope(body);
    if ((await recomputeScopeHash(scope)) !== scope.scopeHash) {
      throw invalidResponse("APPROVAL_SCOPE_HASH_INVALID");
    }
    return scope;
  };

  const displayVerifiedScope = (artifact, scope) => {
    principalText.textContent =
      "已绑定本机配置主体；不显示主体标识，且不代表身份认证。";
    provenanceText.textContent =
      `${scope.provenance.approvalOrigin} · ${scope.provenance.executionRoute}`;
    actionText.textContent = scope.action.actionType;
    targetText.textContent = `Local Draftbox · ${scope.action.targetRef}`;
    riskText.textContent =
      `${scope.action.risk}（可撤销） · Policy ${scope.action.policyVersion}`;
    artifactText.textContent =
      `v${artifact.artifactVersion} · SHA-256 ${artifact.artifactHash}`;
    scopeText.textContent = `${scope.scopeSchema} · SHA-256 ${scope.scopeHash}`;
    boundaryText.textContent =
      "批准只记录授权，尚未执行，不产生 Receipt；当前为本机单用户原型，未验证真实身份。";
  };

  const showPreviewUnknown = (kind) => {
    trustedArtifact = null;
    trustedScope = null;
    frozenApproval = null;
    resetScopeFacts();
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    card.hidden = false;
    const reason =
      kind === "CLIENT_UNAVAILABLE"
        ? "当前浏览器无法使用 WebCrypto 或安全超时能力，"
        : kind === "INVALID_RESPONSE"
          ? "服务端 scope preview 不满足可信协议，"
          : "scope preview 超时、连接中断或响应丢失，";
    setState(
      "UNKNOWN",
      `${reason}批准范围未知。没有发送批准请求；请重新保存可信 Artifact 后再试。`,
      true,
    );
  };

  const loadScopePreview = async (artifact, epoch) => {
    const AbortControllerConstructor = globalThis.AbortController;
    if (
      typeof AbortControllerConstructor !== "function" ||
      typeof globalThis.fetch !== "function" ||
      typeof globalThis.setTimeout !== "function" ||
      typeof globalThis.clearTimeout !== "function"
    ) {
      showPreviewUnknown("CLIENT_UNAVAILABLE");
      return;
    }

    const controller = new AbortControllerConstructor();
    requestController = controller;
    let timeoutId = null;
    setBusy(true);
    try {
      timeoutId = globalThis.setTimeout(() => controller.abort(), APPROVAL_TIMEOUT_MILLIS);
      const query = new URLSearchParams({
        artifactVersion: String(artifact.artifactVersion),
        artifactHash: artifact.artifactHash,
      });
      const response = await fetch(
        `/api/v1/artifacts/${encodeURIComponent(artifact.artifactId)}/action-approval-scope?${query}`,
        {
          method: "GET",
          headers: { Accept: "application/json" },
          signal: controller.signal,
          credentials: "same-origin",
          cache: "no-store",
          redirect: "error",
        },
      );
      if (artifactEpoch !== epoch || requestController !== controller) {
        await cancelResponseBody(response);
        return;
      }
      if (
        response.status !== 200 ||
        response.headers.get("cache-control") !== "private, no-store"
      ) {
        await cancelResponseBody(response);
        throw invalidResponse("APPROVAL_SCOPE_HEADERS_INVALID");
      }
      const body = await readBoundedJson(response, JSON_MEDIA_TYPE);
      const scope = await validateScopePreview(body, artifact);
      if (artifactEpoch !== epoch || requestController !== controller) {
        return;
      }
      trustedArtifact = artifact;
      trustedScope = scope;
      frozenApproval = null;
      displayVerifiedScope(artifact, scope);
      card.hidden = false;
      submit.hidden = false;
      submit.textContent = "记录本次批准";
      retry.hidden = true;
      setState("READY", "scope 已在本地复算验证；请核对全部边界后记录精确批准。");
    } catch (failure) {
      if (artifactEpoch !== epoch || requestController !== controller) {
        return;
      }
      const typedFailure =
        failure instanceof ApprovalClientError
          ? failure
          : transportUnknown(
              controller.signal.aborted
                ? "APPROVAL_SCOPE_TIMEOUT_OR_ABORT"
                : "APPROVAL_SCOPE_TRANSPORT_UNKNOWN",
            );
      showPreviewUnknown(typedFailure.kind);
    } finally {
      if (timeoutId !== null) {
        globalThis.clearTimeout(timeoutId);
      }
      if (artifactEpoch === epoch && requestController === controller) {
        requestController = null;
        setBusy(false);
      }
    }
  };

  const showTrustedArtifact = (detail) => {
    const candidate = safeTrustedArtifact(detail);
    if (!candidate) {
      invalidate();
      return;
    }
    artifactEpoch += 1;
    const epoch = artifactEpoch;
    requestController?.abort?.();
    requestController = null;
    trustedArtifact = null;
    trustedScope = null;
    frozenApproval = null;
    requestPending = false;
    card.hidden = true;
    resetScopeFacts();
    artifactText.textContent =
      `正在验证 v${candidate.artifactVersion} · SHA-256 ${candidate.artifactHash}`;
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    setState(
      "VERIFYING",
      "正在验证当前 Artifact 的精确批准范围；尚未开放批准。",
    );
    void loadScopePreview(candidate, epoch);
  };

  const validInstant = (value) =>
    typeof value === "string" && Number.isFinite(Date.parse(value));

  const samePrincipal = (actual, expected) =>
    hasExactKeys(actual, ["basis", "configuredPrincipalId"]) &&
    actual.basis === expected.basis &&
    actual.configuredPrincipalId === expected.configuredPrincipalId;

  const sameProvenance = (actual, expected) =>
    hasExactKeys(actual, ["approvalOrigin", "executionRoute"]) &&
    actual.approvalOrigin === expected.approvalOrigin &&
    actual.executionRoute === expected.executionRoute;

  const sameAction = (actual, expected) =>
    hasExactKeys(actual, ["actionType", "targetRef", "risk", "policyVersion"]) &&
    actual.actionType === expected.actionType &&
    actual.targetRef === expected.targetRef &&
    actual.risk === expected.risk &&
    actual.policyVersion === expected.policyVersion;

  const sameArtifact = (actual, expected) =>
    hasExactKeys(actual, ["artifactId", "artifactVersion", "artifactHash"]) &&
    actual.artifactId === expected.artifactId &&
    actual.artifactVersion === expected.artifactVersion &&
    actual.artifactHash === expected.artifactHash;

  const validateApprovalReceipt = (response, body, attempt) => {
    const transition = body?.transitions?.[0];
    const location = response.headers.get("location");
    const cacheControl = response.headers.get("cache-control");
    const scope = attempt.scope;
    return (
      [200, 201].includes(response.status) &&
      cacheControl === "private, no-store" &&
      hasExactKeys(body, [
        "attemptId",
        "status",
        "plan",
        "approval",
        "capability",
        "transitions",
        "receipt",
        "scopeSchema",
        "scopeHash",
        "approvalPrincipal",
        "provenance",
        "action",
        "artifact",
        "executionState",
      ]) &&
      safeIdentifier(body.attemptId) &&
      location === `/api/v1/action-approvals/${body.attemptId}` &&
      body.status === "PLANNED" &&
      hasExactKeys(body.plan, [
        "planId",
        "planHash",
        "artifactId",
        "artifactVersion",
        "artifactHash",
        "idempotencyKey",
        "expiresAt",
      ]) &&
      safeIdentifier(body.plan.planId) &&
      HASH_PATTERN.test(body.plan.planHash ?? "") &&
      body.plan.artifactId === attempt.artifact.artifactId &&
      body.plan.artifactVersion === attempt.artifact.artifactVersion &&
      body.plan.artifactHash === attempt.artifact.artifactHash &&
      body.plan.idempotencyKey === attempt.request.approvalNonce &&
      validInstant(body.plan.expiresAt) &&
      hasExactKeys(body.approval, [
        "decisionId",
        "decision",
        "actor",
        "decidedAt",
        "planId",
        "planHash",
        "artifactHash",
      ]) &&
      safeIdentifier(body.approval.decisionId) &&
      body.approval.decision === "APPROVED" &&
      body.approval.actor === scope.approvalPrincipal.configuredPrincipalId &&
      validInstant(body.approval.decidedAt) &&
      body.approval.planId === body.plan.planId &&
      body.approval.planHash === body.plan.planHash &&
      body.approval.artifactHash === attempt.artifact.artifactHash &&
      hasExactKeys(body.capability, [
        "capabilityId",
        "connector",
        "audience",
        "accountRef",
        "capabilityTtlMicros",
        "usedCalls",
        "maxCalls",
      ]) &&
      safeIdentifier(body.capability.capabilityId) &&
      body.capability.connector === scope.capability.connector &&
      body.capability.audience === scope.capability.audience &&
      body.capability.accountRef === scope.capability.accountRef &&
      body.capability.capabilityTtlMicros === scope.capability.capabilityTtlMicros &&
      body.capability.usedCalls === scope.capability.usedCalls &&
      body.capability.maxCalls === scope.capability.maxCalls &&
      Array.isArray(body.transitions) &&
      body.transitions.length === 1 &&
      hasExactKeys(transition, ["sequence", "fromStatus", "toStatus", "occurredAt"]) &&
      transition.sequence === 1 &&
      transition.fromStatus === null &&
      transition.toStatus === "PLANNED" &&
      validInstant(transition.occurredAt) &&
      body.receipt === null &&
      body.scopeSchema === scope.scopeSchema &&
      body.scopeHash === scope.scopeHash &&
      samePrincipal(body.approvalPrincipal, scope.approvalPrincipal) &&
      sameProvenance(body.provenance, scope.provenance) &&
      sameAction(body.action, scope.action) &&
      sameArtifact(body.artifact, scope.artifact) &&
      body.executionState === scope.executionState
    );
  };

  const readStrictProblem = async (response) => {
    if (
      ![409, 412].includes(response.status) ||
      response.headers.get("cache-control") !== "private, no-store"
    ) {
      await cancelResponseBody(response);
      throw invalidResponse("APPROVAL_PROBLEM_HEADERS_INVALID");
    }
    const problem = await readBoundedJson(response, PROBLEM_MEDIA_TYPE);
    if (
      !problem ||
      problem.status !== response.status ||
      typeof problem.type !== "string" ||
      typeof problem.title !== "string"
    ) {
      throw invalidResponse("APPROVAL_PROBLEM_BINDING_INVALID");
    }
    const stale = STALE_PROBLEM_ALLOWLIST.some(
      (allowed) =>
        allowed.status === problem.status &&
        allowed.type === problem.type &&
        allowed.title === problem.title,
    );
    return Object.freeze({ stale });
  };

  const showUnknown = (kind = "TRANSPORT_UNKNOWN") => {
    submit.hidden = true;
    retry.hidden = false;
    retry.disabled = false;
    const reason =
      kind === "INVALID_RESPONSE"
        ? "服务端回执或错误响应不满足可信协议，"
        : kind === "CLIENT_UNAVAILABLE"
          ? "当前浏览器缺少安全超时能力，请求没有发出，"
          : "请求超时、连接中断或响应丢失，";
    setState(
      "UNKNOWN",
      `${reason}批准结果未知。页面不会自动重试；如需恢复，只能明确重试同一批准、同一版本、同一 scope 和同一 nonce。`,
      true,
    );
  };

  const postApproval = async (attempt) => {
    if (
      requestPending ||
      !trustedArtifact ||
      !trustedScope ||
      frozenApproval !== attempt
    ) {
      return;
    }
    if (
      trustedArtifact !== attempt.artifact ||
      trustedScope !== attempt.scope ||
      trustedArtifact.artifactId !== attempt.artifact.artifactId ||
      trustedArtifact.artifactVersion !== attempt.artifact.artifactVersion ||
      trustedArtifact.artifactHash !== attempt.artifact.artifactHash
    ) {
      invalidate();
      return;
    }

    const AbortControllerConstructor = globalThis.AbortController;
    if (
      typeof AbortControllerConstructor !== "function" ||
      typeof globalThis.fetch !== "function" ||
      typeof globalThis.setTimeout !== "function" ||
      typeof globalThis.clearTimeout !== "function"
    ) {
      showUnknown("CLIENT_UNAVAILABLE");
      return;
    }

    const controller = new AbortControllerConstructor();
    requestController = controller;
    let timeoutId = null;
    setBusy(true);
    submit.hidden = true;
    retry.hidden = true;
    setState("SUBMITTING", "正在记录精确批准；尚未执行，也尚无 Receipt。");
    try {
      timeoutId = globalThis.setTimeout(() => controller.abort(), APPROVAL_TIMEOUT_MILLIS);
      const response = await fetch(
        `/api/v1/artifacts/${encodeURIComponent(attempt.artifact.artifactId)}/action-approvals`,
        {
          method: "POST",
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json",
          },
          body: attempt.body,
          signal: controller.signal,
          credentials: "same-origin",
          cache: "no-store",
          redirect: "error",
        },
      );
      if (artifactEpoch !== attempt.epoch || frozenApproval !== attempt) {
        await cancelResponseBody(response);
        return;
      }
      if (response.status === 409 || response.status === 412) {
        const problem = await readStrictProblem(response);
        if (problem.stale) {
          trustedArtifact = null;
          trustedScope = null;
          frozenApproval = null;
          submit.disabled = true;
          retry.hidden = true;
          artifactText.textContent = "服务端 Artifact 或批准 scope 已变化；原批准已失效";
          setState(
            "STALE",
            "精确版本或批准 scope 已变化，原批准已失效。请重新保存并核对当前 Artifact 后再批准。",
            true,
          );
          return;
        }
        throw invalidResponse("APPROVAL_CONFLICT_NOT_PROVEN_STALE");
      }
      if (![200, 201].includes(response.status)) {
        await cancelResponseBody(response);
        throw invalidResponse("APPROVAL_STATUS_INVALID");
      }
      const body = await readBoundedJson(response, JSON_MEDIA_TYPE);
      if (!validateApprovalReceipt(response, body, attempt)) {
        throw invalidResponse("APPROVAL_RECEIPT_INVALID");
      }
      submit.hidden = true;
      retry.hidden = true;
      setState(
        "PLANNED",
        "批准已记录 · 状态 PLANNED · executionState NOT_EXECUTED · Receipt：无。",
      );
    } catch (failure) {
      if (artifactEpoch !== attempt.epoch || frozenApproval !== attempt) {
        return;
      }
      const typedFailure =
        failure instanceof ApprovalClientError
          ? failure
          : transportUnknown(
              controller.signal.aborted
                ? "APPROVAL_REQUEST_TIMEOUT_OR_ABORT"
                : "APPROVAL_TRANSPORT_UNKNOWN",
            );
      showUnknown(typedFailure.kind);
    } finally {
      if (timeoutId !== null) {
        globalThis.clearTimeout(timeoutId);
      }
      if (artifactEpoch === attempt.epoch && requestController === controller) {
        requestController = null;
        setBusy(false);
        if (card.dataset.state === "PLANNED") {
          submit.disabled = true;
          retry.disabled = true;
        }
      }
    }
  };

  submit.addEventListener("click", async (event) => {
    event.preventDefault?.();
    if (requestPending || !trustedArtifact || !trustedScope || frozenApproval) {
      return;
    }
    const approvalNonce = randomApprovalNonce();
    if (!approvalNonce) {
      setState(
        "UNKNOWN",
        "无法安全创建本次批准 nonce；没有发送请求，也没有记录批准。",
        true,
      );
      return;
    }
    const request = Object.freeze({
      approvedArtifactVersion: trustedArtifact.artifactVersion,
      approvedArtifactHash: trustedArtifact.artifactHash,
      approvalNonce,
      approvedScopeSchema: trustedScope.scopeSchema,
      approvedScopeHash: trustedScope.scopeHash,
    });
    frozenApproval = Object.freeze({
      epoch: artifactEpoch,
      artifact: trustedArtifact,
      scope: trustedScope,
      request,
      body: JSON.stringify(request),
    });
    await postApproval(frozenApproval);
  });

  retry.addEventListener("click", async (event) => {
    event.preventDefault?.();
    if (requestPending || !frozenApproval || card.dataset.state !== "UNKNOWN") {
      return;
    }
    await postApproval(frozenApproval);
  });

  document.addEventListener(TRUSTED_ARTIFACT_EVENT, (event) => {
    showTrustedArtifact(event?.detail);
  });
  document.addEventListener(ARTIFACT_INVALIDATED_EVENT, invalidate);
})();
