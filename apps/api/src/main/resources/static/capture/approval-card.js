(() => {
  const TRUSTED_ARTIFACT_EVENT = "emergeos:trusted-artifact";
  const ARTIFACT_INVALIDATED_EVENT = "emergeos:artifact-invalidated";
  const SCOPE_SCHEMA = "emergeos.action-approval-scope.v1";
  const UNDO_SCOPE_SCHEMA = "emergeos.local-draft-undo-scope.v1";
  const MAX_RESPONSE_BYTES = 262144;
  const APPROVAL_TIMEOUT_MILLIS = 10000;
  const EXECUTION_ROUTE = "LOCAL_DRAFTBOX_V2";
  const POLICY_VERSION = "local-action-v2";
  const CONNECTOR = "emergeos.local-draftbox";
  const AUDIENCE = "emergeos:local-draftbox";
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
  const execute = document.querySelector("#approval-execute");
  const checkExecution = document.querySelector("#approval-check-execution");
  const undo = document.querySelector("#approval-undo");
  const checkUndo = document.querySelector("#approval-undo-recover");
  const status = document.querySelector("#approval-status");
  const result = document.querySelector("#approval-result");
  const resultContext = document.querySelector("#approval-result-context");
  const localDraft = document.querySelector("#approval-local-draft");
  const draftSummary = document.querySelector("#approval-draft-summary");
  const receipt = document.querySelector("#approval-receipt");
  const receiptSummary = document.querySelector("#approval-receipt-summary");
  const undoReceiptLine = document.querySelector("#approval-undo-receipt");
  const undoReceiptSummary = document.querySelector("#approval-undo-receipt-summary");
  const undoBoundary = document.querySelector("#approval-undo-boundary");

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
    !execute ||
    !checkExecution ||
    !undo ||
    !checkUndo ||
    !status ||
    !result ||
    !resultContext ||
    !localDraft ||
    !draftSummary ||
    !receipt ||
    !receiptSummary ||
    !undoReceiptLine ||
    !undoReceiptSummary ||
    !undoBoundary
  ) {
    return;
  }

  let trustedArtifact = null;
  let trustedScope = null;
  let frozenApproval = null;
  let plannedExecution = null;
  let recoverableExecution = null;
  let recoverableExecutionHistorical = false;
  let recoverableUndo = null;
  let recoverableUndoHistorical = false;
  let terminalResult = null;
  let requestPending = false;
  let artifactEpoch = 0;
  let requestController = null;

  const hasUnresolvedHistoricalExecution = () =>
    recoverableExecutionHistorical && recoverableExecution !== null;

  const hasUnresolvedHistoricalUndo = () =>
    recoverableUndoHistorical && recoverableUndo !== null;

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
    submit.disabled =
      busy ||
      hasUnresolvedHistoricalExecution() ||
      hasUnresolvedHistoricalUndo() ||
      !trustedArtifact ||
      !trustedScope;
    retry.disabled =
      busy ||
      !frozenApproval ||
      plannedExecution !== null ||
      card.dataset.state !== "UNKNOWN";
    execute.disabled =
      busy ||
      hasUnresolvedHistoricalExecution() ||
      hasUnresolvedHistoricalUndo() ||
      !plannedExecution ||
      card.dataset.state !== "PLANNED";
    checkExecution.disabled =
      busy ||
      !recoverableExecution ||
      (!recoverableExecutionHistorical && card.dataset.state !== "UNKNOWN");
    undo.disabled =
      busy ||
      card.dataset.state !== "SUCCEEDED" ||
      terminalResult?.draft?.state !== "ACTIVE" ||
      terminalResult?.undoAvailable !== true;
    checkUndo.disabled =
      busy || !recoverableUndo || card.dataset.state !== "UNDO_UNKNOWN";
    card.setAttribute?.("aria-busy", String(busy));
  };

  const hideExecutionControls = () => {
    execute.hidden = true;
    execute.disabled = true;
    checkExecution.hidden = true;
    checkExecution.disabled = true;
  };

  const hideUndoControls = () => {
    undo.hidden = true;
    undo.disabled = true;
    checkUndo.hidden = true;
    checkUndo.disabled = true;
  };

  const hideResult = () => {
    result.hidden = true;
    result.removeAttribute?.("data-context");
    resultContext.hidden = true;
    localDraft.hidden = true;
    draftSummary.hidden = true;
    receipt.hidden = true;
    receiptSummary.hidden = true;
    undoReceiptLine.hidden = true;
    undoReceiptSummary.hidden = true;
    undoBoundary.hidden = true;
    hideUndoControls();
    resultContext.textContent = "历史执行结果；不授权当前 Artifact";
    draftSummary.textContent = "";
    receiptSummary.textContent = "";
    undoReceiptSummary.textContent = "";
    if (!draftSummary.parentElement) {
      localDraft.textContent = "";
    }
    if (!receiptSummary.parentElement) {
      receipt.textContent = "";
    }
    if (!undoReceiptSummary.parentElement) {
      undoReceiptLine.textContent = "";
    }
  };

  const markTerminalResultHistorical = () => {
    if (!terminalResult) {
      return;
    }
    result.hidden = false;
    result.setAttribute?.("data-context", "HISTORICAL");
    resultContext.textContent = "历史执行结果；不授权当前 Artifact";
    resultContext.hidden = false;
    undo.hidden = true;
    undo.disabled = true;
  };

  const showHistoricalExecutionUnknown = () => {
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    hideUndoControls();
    execute.hidden = true;
    execute.disabled = true;
    checkExecution.hidden = false;
    checkExecution.disabled = !recoverableExecution;
    if (terminalResult) {
      markTerminalResultHistorical();
    } else {
      hideResult();
    }
    card.hidden = false;
    setState(
      "UNKNOWN",
      "历史 attempt 的执行结果未知。旧批准已永久禁止再次执行；只能由你明确查询同一 attempt 的持久状态。",
      true,
    );
  };

  const showHistoricalUndoUnknown = () => {
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    hideExecutionControls();
    undo.hidden = true;
    undo.disabled = true;
    checkUndo.hidden = recoverableUndo === null;
    checkUndo.disabled = requestPending || recoverableUndo === null;
    if (terminalResult) {
      markTerminalResultHistorical();
    } else {
      hideResult();
      checkUndo.hidden = recoverableUndo === null;
      checkUndo.disabled = requestPending || recoverableUndo === null;
    }
    card.hidden = false;
    setState(
      "UNDO_UNKNOWN",
      "历史 attempt 的逻辑撤销结果未知。旧 operation 已永久禁止再次 POST；只能由你明确查询同一 attempt 的持久状态。",
      true,
    );
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
    const preserveHistoricalResult = terminalResult !== null;
    const preserveAmbiguousExecution =
      hasUnresolvedHistoricalExecution() ||
      (recoverableExecution !== null &&
        ["EXECUTING", "UNKNOWN"].includes(card.dataset.state));
    const preserveAmbiguousUndo =
      hasUnresolvedHistoricalUndo() ||
      (recoverableUndo !== null &&
        (card.dataset.state === "UNDO_UNKNOWN" ||
          (card.dataset.state === "UNDOING" && requestController !== null)));
    artifactEpoch += 1;
    requestController?.abort?.();
    requestController = null;
    trustedArtifact = null;
    trustedScope = null;
    frozenApproval = null;
    plannedExecution = null;
    requestPending = false;
    card.setAttribute?.("aria-busy", "false");
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    hideExecutionControls();
    hideUndoControls();
    if (!preserveHistoricalResult) {
      hideResult();
    }
    artifactText.textContent = "当前 Artifact 已失效；请重新保存可信版本";
    resetScopeFacts();
    card.hidden = false;
    if (preserveAmbiguousExecution) {
      recoverableExecutionHistorical = true;
      showHistoricalExecutionUnknown();
      return;
    }
    recoverableExecution = null;
    recoverableExecutionHistorical = false;
    if (preserveAmbiguousUndo) {
      recoverableUndoHistorical = true;
      showHistoricalUndoUnknown();
      return;
    }
    recoverableUndo = null;
    recoverableUndoHistorical = false;
    if (preserveHistoricalResult) {
      markTerminalResultHistorical();
    }
    setState(
      "INVALIDATED",
      preserveHistoricalResult
        ? "当前 Artifact 已编辑；上次执行的草稿与 Receipt 仅作为历史只读结果，不授权当前版本。"
        : "批准卡已失效。请先重新保存 Outcome Canvas，再核对并批准新的精确版本。",
      false,
    );
  };

  const safeIdentifier = (value) =>
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 200 &&
    !value.includes("\0") &&
    !/[/?#]/.test(value);

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

  const canonicalUndoScopeValues = (creation, undoNonce) => [
    "CONFIGURED_LOCAL_PRINCIPAL",
    creation.approvalPrincipal.configuredPrincipalId,
    "EXPLICIT_LOCAL_OWNER_INPUT",
    "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1",
    "LOGICALLY_UNDO_LOCAL_DRAFT",
    `local://drafts/${creation.localDraft.draftId}`,
    creation.localDraft.draftId,
    creation.attemptId,
    creation.receipt.receiptId,
    creation.localDraft.artifactId,
    String(creation.localDraft.artifactVersion),
    creation.localDraft.artifactHash,
    "ACTIVE",
    "CAPTURE_ARTIFACT_HISTORY_RETAINED",
    "local-draft-undo-v1",
    "emergeos.local-draftbox",
    "emergeos:local-draftbox",
    `local-draftbox:${creation.approvalPrincipal.configuredPrincipalId}`,
    undoNonce,
    "1",
  ];

  const recomputeUndoScopeHash = async (creation, undoNonce) => {
    const subtle = globalThis.crypto?.subtle;
    const TextEncoderConstructor = globalThis.TextEncoder;
    if (
      !subtle ||
      typeof subtle.digest !== "function" ||
      typeof TextEncoderConstructor !== "function"
    ) {
      throw clientUnavailable("UNDO_SCOPE_CRYPTO_UNAVAILABLE");
    }
    const encoder = new TextEncoderConstructor();
    const schemaBytes = encoder.encode(UNDO_SCOPE_SCHEMA);
    const valueBytes = canonicalUndoScopeValues(creation, undoNonce).map((value) =>
      encoder.encode(value),
    );
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
      body.provenance.executionRoute !== EXECUTION_ROUTE ||
      body.action.actionType !== "CREATE_LOCAL_DRAFT" ||
      body.action.targetRef !== "local://drafts" ||
      body.action.risk !== "REVERSIBLE" ||
      body.action.policyVersion !== POLICY_VERSION ||
      body.artifact.artifactId !== artifact.artifactId ||
      body.artifact.artifactVersion !== artifact.artifactVersion ||
      body.artifact.artifactHash !== artifact.artifactHash ||
      body.capability.connector !== CONNECTOR ||
      body.capability.audience !== AUDIENCE ||
      body.capability.accountRef !==
        `local-draftbox:${body.approvalPrincipal.configuredPrincipalId}` ||
      !Number.isSafeInteger(body.capability.capabilityTtlMicros) ||
      body.capability.capabilityTtlMicros < 1 ||
      body.capability.maxCalls !== 1 ||
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
      terminalResult
        ? `${scope.action.risk} · Policy ${scope.action.policyVersion}`
        : `${scope.action.risk}（风险分类；撤销暂未开放） · Policy ${scope.action.policyVersion}`;
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
    plannedExecution = null;
    resetScopeFacts();
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    hideExecutionControls();
    if (recoverableUndoHistorical && recoverableUndo) {
      showHistoricalUndoUnknown();
      return;
    }
    if (recoverableExecutionHistorical && recoverableExecution) {
      showHistoricalExecutionUnknown();
      return;
    }
    if (!terminalResult) {
      hideResult();
    }
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
      plannedExecution = null;
      displayVerifiedScope(artifact, scope);
      card.hidden = false;
      submit.hidden =
        hasUnresolvedHistoricalExecution() || hasUnresolvedHistoricalUndo();
      submit.disabled =
        hasUnresolvedHistoricalExecution() || hasUnresolvedHistoricalUndo();
      submit.textContent = "记录本次批准";
      retry.hidden = true;
      hideExecutionControls();
      if (recoverableUndoHistorical && recoverableUndo) {
        showHistoricalUndoUnknown();
        return;
      }
      if (recoverableExecutionHistorical && recoverableExecution) {
        checkExecution.hidden = false;
        if (terminalResult) {
          markTerminalResultHistorical();
        }
      }
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
    plannedExecution = null;
    requestPending = false;
    card.hidden = true;
    hideExecutionControls();
    if (!terminalResult) {
      hideResult();
    }
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
        "localDraft",
        "scopeSchema",
        "scopeHash",
        "approvalPrincipal",
        "provenance",
        "action",
        "artifact",
        "executionState",
        "undoReceipt",
        "undoAvailable",
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
      body.localDraft === null &&
      body.undoReceipt === null &&
      body.undoAvailable === false &&
      body.scopeSchema === scope.scopeSchema &&
      body.scopeHash === scope.scopeHash &&
      samePrincipal(body.approvalPrincipal, scope.approvalPrincipal) &&
      sameProvenance(body.provenance, scope.provenance) &&
      sameAction(body.action, scope.action) &&
      sameArtifact(body.artifact, scope.artifact) &&
      body.executionState === scope.executionState
    );
  };

  const samePlan = (actual, expected) =>
    hasExactKeys(actual, [
      "planId",
      "planHash",
      "artifactId",
      "artifactVersion",
      "artifactHash",
      "idempotencyKey",
      "expiresAt",
    ]) &&
    actual.planId === expected.planId &&
    actual.planHash === expected.planHash &&
    actual.artifactId === expected.artifactId &&
    actual.artifactVersion === expected.artifactVersion &&
    actual.artifactHash === expected.artifactHash &&
    actual.idempotencyKey === expected.idempotencyKey &&
    actual.expiresAt === expected.expiresAt &&
    validInstant(actual.expiresAt);

  const sameApproval = (actual, expected) =>
    hasExactKeys(actual, [
      "decisionId",
      "decision",
      "actor",
      "decidedAt",
      "planId",
      "planHash",
      "artifactHash",
    ]) &&
    actual.decisionId === expected.decisionId &&
    actual.decision === expected.decision &&
    actual.actor === expected.actor &&
    actual.decidedAt === expected.decidedAt &&
    actual.planId === expected.planId &&
    actual.planHash === expected.planHash &&
    actual.artifactHash === expected.artifactHash &&
    validInstant(actual.decidedAt);

  const sameCapability = (actual, expected, usedCalls) =>
    hasExactKeys(actual, [
      "capabilityId",
      "connector",
      "audience",
      "accountRef",
      "capabilityTtlMicros",
      "usedCalls",
      "maxCalls",
    ]) &&
    actual.capabilityId === expected.capabilityId &&
    actual.connector === expected.connector &&
    actual.audience === expected.audience &&
    actual.accountRef === expected.accountRef &&
    actual.capabilityTtlMicros === expected.capabilityTtlMicros &&
    actual.usedCalls === usedCalls &&
    actual.maxCalls === expected.maxCalls;

  const sameTransition = (actual, expected) =>
    hasExactKeys(actual, ["sequence", "fromStatus", "toStatus", "occurredAt"]) &&
    actual.sequence === expected.sequence &&
    actual.fromStatus === expected.fromStatus &&
    actual.toStatus === expected.toStatus &&
    actual.occurredAt === expected.occurredAt &&
    validInstant(actual.occurredAt);

  const freezePlannedExecution = (body, attempt) =>
    Object.freeze({
      epoch: attempt.epoch,
      artifact: attempt.artifact,
      scope: attempt.scope,
      attemptId: body.attemptId,
      plan: Object.freeze({ ...body.plan }),
      approval: Object.freeze({ ...body.approval }),
      capability: Object.freeze({ ...body.capability }),
      plannedTransition: Object.freeze({ ...body.transitions[0] }),
      executeBody: JSON.stringify({
        scopeSchema: attempt.scope.scopeSchema,
        scopeHash: attempt.scope.scopeHash,
      }),
    });

  const hasBoundApprovalEnvelope = (body, execution) =>
    hasExactKeys(body, [
      "attemptId",
      "status",
      "plan",
      "approval",
      "capability",
      "transitions",
      "receipt",
      "localDraft",
      "scopeSchema",
      "scopeHash",
      "approvalPrincipal",
      "provenance",
      "action",
      "artifact",
      "executionState",
      "undoReceipt",
      "undoAvailable",
    ]) &&
    body.attemptId === execution.attemptId &&
    samePlan(body.plan, execution.plan) &&
    sameApproval(body.approval, execution.approval) &&
    body.scopeSchema === execution.scope.scopeSchema &&
    body.scopeHash === execution.scope.scopeHash &&
    samePrincipal(body.approvalPrincipal, execution.scope.approvalPrincipal) &&
    sameProvenance(body.provenance, execution.scope.provenance) &&
    sameAction(body.action, execution.scope.action) &&
    sameArtifact(body.artifact, execution.scope.artifact);

  const validateLogicalUndoReceipt = (body, execution, expectedUndo = null) => {
    const undoReceipt = body?.undoReceipt;
    return (
      hasExactKeys(undoReceipt, [
        "receiptType",
        "receiptId",
        "creationAttemptId",
        "draftId",
        "creationReceiptId",
        "artifactId",
        "artifactVersion",
        "artifactHash",
        "scopeSchema",
        "scopeHash",
        "undoNonce",
        "effect",
        "retention",
        "outcome",
        "occurredAt",
        "simulated",
      ]) &&
      undoReceipt.receiptType === "LOCAL_DRAFT_LOGICALLY_UNDONE_V1" &&
      safeIdentifier(undoReceipt.receiptId) &&
      undoReceipt.creationAttemptId === execution.attemptId &&
      undoReceipt.draftId === body.localDraft.draftId &&
      undoReceipt.creationReceiptId === body.receipt.receiptId &&
      undoReceipt.artifactId === execution.artifact.artifactId &&
      undoReceipt.artifactVersion === execution.artifact.artifactVersion &&
      undoReceipt.artifactHash === execution.artifact.artifactHash &&
      undoReceipt.scopeSchema === UNDO_SCOPE_SCHEMA &&
      HASH_PATTERN.test(undoReceipt.scopeHash ?? "") &&
      safeIdentifier(undoReceipt.undoNonce) &&
      undoReceipt.effect === "LOGICALLY_UNDONE" &&
      undoReceipt.retention === "CAPTURE_ARTIFACT_HISTORY_RETAINED" &&
      undoReceipt.outcome === "SUCCEEDED" &&
      validInstant(undoReceipt.occurredAt) &&
      Date.parse(undoReceipt.occurredAt) >= Date.parse(body.receipt.occurredAt) &&
      undoReceipt.simulated === false &&
      (expectedUndo === null ||
        (undoReceipt.undoNonce === expectedUndo.undoNonce &&
          undoReceipt.scopeSchema === expectedUndo.scopeSchema &&
          undoReceipt.scopeHash === expectedUndo.scopeHash))
    );
  };

  const validatePersistedPlanned = (body, execution) =>
    hasBoundApprovalEnvelope(body, execution) &&
    body.status === "PLANNED" &&
    body.executionState === "NOT_EXECUTED" &&
    sameCapability(body.capability, execution.capability, 0) &&
    Array.isArray(body.transitions) &&
    body.transitions.length === 1 &&
    sameTransition(body.transitions[0], execution.plannedTransition) &&
    body.receipt === null &&
    body.localDraft === null &&
    body.undoReceipt === null &&
    body.undoAvailable === false;

  const validateTerminalExecution = (body, execution, expectedUndo = null) => {
    const completed = body?.transitions?.[1];
    return (
      hasBoundApprovalEnvelope(body, execution) &&
      body.status === "SUCCEEDED" &&
      body.executionState === "EXECUTED" &&
      sameCapability(body.capability, execution.capability, 1) &&
      Array.isArray(body.transitions) &&
      body.transitions.length === 2 &&
      sameTransition(body.transitions[0], execution.plannedTransition) &&
      hasExactKeys(completed, ["sequence", "fromStatus", "toStatus", "occurredAt"]) &&
      completed.sequence === 2 &&
      completed.fromStatus === "PLANNED" &&
      completed.toStatus === "SUCCEEDED" &&
      validInstant(completed.occurredAt) &&
      hasExactKeys(body.localDraft, [
        "draftId",
        "state",
        "artifactId",
        "artifactVersion",
        "artifactHash",
        "createdAt",
      ]) &&
      safeIdentifier(body.localDraft.draftId) &&
      body.localDraft.artifactId === execution.artifact.artifactId &&
      body.localDraft.artifactVersion === execution.artifact.artifactVersion &&
      body.localDraft.artifactHash === execution.artifact.artifactHash &&
      validInstant(body.localDraft.createdAt) &&
      hasExactKeys(body.receipt, [
        "receiptType",
        "receiptId",
        "attemptId",
        "draftId",
        "outcome",
        "occurredAt",
        "simulated",
      ]) &&
      body.receipt.receiptType === "LOCAL_DRAFT_CREATED_V1" &&
      safeIdentifier(body.receipt.receiptId) &&
      body.receipt.attemptId === execution.attemptId &&
      body.receipt.draftId === body.localDraft.draftId &&
      body.receipt.outcome === "SUCCEEDED" &&
      validInstant(body.receipt.occurredAt) &&
      body.receipt.occurredAt === body.localDraft.createdAt &&
      body.receipt.simulated === false &&
      ((body.localDraft.state === "ACTIVE" &&
        body.undoReceipt === null &&
        body.undoAvailable === true) ||
        (body.localDraft.state === "LOGICALLY_UNDONE" &&
          body.undoAvailable === false &&
          validateLogicalUndoReceipt(body, execution, expectedUndo)))
    );
  };

  const sameCreationProjection = (body, operation, expectedState) => {
    const creation = operation.creation;
    return (
      body.localDraft.state === expectedState &&
      body.localDraft.draftId === creation.localDraft.draftId &&
      body.localDraft.artifactId === creation.localDraft.artifactId &&
      body.localDraft.artifactVersion === creation.localDraft.artifactVersion &&
      body.localDraft.artifactHash === creation.localDraft.artifactHash &&
      body.localDraft.createdAt === creation.localDraft.createdAt &&
      body.receipt.receiptType === creation.receipt.receiptType &&
      body.receipt.receiptId === creation.receipt.receiptId &&
      body.receipt.attemptId === creation.receipt.attemptId &&
      body.receipt.draftId === creation.receipt.draftId &&
      body.receipt.outcome === creation.receipt.outcome &&
      body.receipt.occurredAt === creation.receipt.occurredAt &&
      body.receipt.simulated === creation.receipt.simulated &&
      Array.isArray(body.transitions) &&
      body.transitions.length === 2 &&
      creation.transitions.length === 2 &&
      sameTransition(body.transitions[0], creation.transitions[0]) &&
      sameTransition(body.transitions[1], creation.transitions[1])
    );
  };

  const validateLogicalUndoResult = (body, operation) =>
    validateTerminalExecution(body, operation.execution, operation) &&
    body.localDraft.state === "LOGICALLY_UNDONE" &&
    sameCreationProjection(body, operation, "LOGICALLY_UNDONE");

  const setSummaryText = (container, summary, value) => {
    summary.textContent = value;
    if (!summary.parentElement) {
      container.textContent = value;
    }
  };

  const hasDifferentCurrentArtifact = (execution) =>
    trustedArtifact !== null &&
    (trustedArtifact.artifactId !== execution.artifact.artifactId ||
      trustedArtifact.artifactVersion !== execution.artifact.artifactVersion ||
      trustedArtifact.artifactHash !== execution.artifact.artifactHash);

  const renderTerminalExecution = (body, execution) => {
    const preserveDifferentCurrentArtifact = hasDifferentCurrentArtifact(execution);
    const historicalResult =
      recoverableExecutionHistorical ||
      recoverableUndoHistorical ||
      preserveDifferentCurrentArtifact;
    const retainedUndo =
      recoverableUndo?.execution === execution ? recoverableUndo : null;
    const draft = Object.freeze({ ...body.localDraft });
    const durableReceipt = Object.freeze({ ...body.receipt });
    const durableUndoReceipt = body.undoReceipt
      ? Object.freeze({ ...body.undoReceipt })
      : null;
    const creation =
      retainedUndo?.creation ??
      Object.freeze({
        attemptId: body.attemptId,
        approvalPrincipal: Object.freeze({ ...body.approvalPrincipal }),
        localDraft: Object.freeze({ ...body.localDraft, state: "ACTIVE" }),
        receipt: durableReceipt,
        transitions: Object.freeze(
          body.transitions.map((transition) => Object.freeze({ ...transition })),
        ),
      });
    terminalResult = Object.freeze({
      draft,
      receipt: durableReceipt,
      undoReceipt: durableUndoReceipt,
      undoAvailable: body.undoAvailable,
      artifact: execution.artifact,
      execution,
      creation,
    });
    recoverableUndo = draft.state === "ACTIVE" ? retainedUndo : null;
    recoverableUndoHistorical = false;
    plannedExecution = null;
    recoverableExecution = null;
    recoverableExecutionHistorical = false;
    if (!preserveDifferentCurrentArtifact) {
      submit.hidden = true;
    }
    retry.hidden = true;
    hideExecutionControls();
    result.hidden = false;
    result.setAttribute?.(
      "data-context",
      historicalResult ? "HISTORICAL" : "CURRENT",
    );
    resultContext.hidden = !historicalResult;
    localDraft.hidden = false;
    draftSummary.hidden = false;
    receipt.hidden = false;
    receiptSummary.hidden = false;
    undoReceiptLine.hidden = durableUndoReceipt === null;
    undoReceiptSummary.hidden = durableUndoReceipt === null;
    undoBoundary.hidden = false;
    checkUndo.hidden = true;
    checkUndo.disabled = true;
    undo.hidden = draft.state === "ACTIVE" ? historicalResult : false;
    undo.disabled =
      requestPending ||
      historicalResult ||
      draft.state !== "ACTIVE" ||
      body.undoAvailable !== true;
    setSummaryText(
      localDraft,
      draftSummary,
      `本地草稿已创建 · 状态 ${draft.state} · Draft ID ${draft.draftId} · Artifact v${draft.artifactVersion} · SHA-256 ${draft.artifactHash}`,
    );
    setSummaryText(
      receipt,
      receiptSummary,
      `Receipt ${durableReceipt.receiptType} · Receipt ID ${durableReceipt.receiptId} · Attempt ID ${durableReceipt.attemptId} · Draft ID ${durableReceipt.draftId} · Outcome ${durableReceipt.outcome} · simulated=false`,
    );
    if (durableUndoReceipt) {
      setSummaryText(
        undoReceiptLine,
        undoReceiptSummary,
        `Undo Receipt ${durableUndoReceipt.receiptType} · Receipt ID ${durableUndoReceipt.receiptId} · Effect ${durableUndoReceipt.effect} · Retention ${durableUndoReceipt.retention} · Outcome ${durableUndoReceipt.outcome} · simulated=false`,
      );
      undoBoundary.textContent =
        "逻辑状态变化已核验；本地明文、Capture、Artifact、创建 Receipt 与历史均保留。";
    } else {
      undoReceiptSummary.textContent = "";
      undoBoundary.textContent =
        "逻辑撤销只改变本地草稿的有效状态；本地明文、Capture、Artifact、创建 Receipt 与历史均保留。";
    }
    if (historicalResult) {
      markTerminalResultHistorical();
    }
    if (preserveDifferentCurrentArtifact) {
      riskText.textContent =
        `${trustedScope.action.risk} · Policy ${trustedScope.action.policyVersion}`;
      submit.hidden = false;
      submit.disabled =
        requestPending || !trustedArtifact || !trustedScope;
      return;
    }
    riskText.textContent =
      `${execution.scope.action.risk} · Policy ${execution.scope.action.policyVersion}`;
    boundaryText.textContent =
      "已执行精确批准；下方仅展示本地草稿与独立 Receipt 的安全摘要。";
    if (draft.state === "LOGICALLY_UNDONE") {
      setState(
        "LOGICALLY_UNDONE",
        "持久化结果已核验 · 有效状态 LOGICALLY_UNDONE · 原创建 Receipt 与 Undo Receipt 均为只读摘要。",
      );
    } else {
      setState(
        "SUCCEEDED",
        "持久化结果已核验 · 状态 SUCCEEDED · executionState EXECUTED。",
      );
    }
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
    if (hasUnresolvedHistoricalExecution()) {
      showHistoricalExecutionUnknown();
      return;
    }
    plannedExecution = null;
    submit.hidden = true;
    retry.hidden = false;
    retry.disabled = false;
    hideExecutionControls();
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
    hideExecutionControls();
    setState("APPROVING", "正在记录精确批准；尚未执行，也尚无 Receipt。");
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
          plannedExecution = null;
          submit.disabled = true;
          retry.hidden = true;
          hideExecutionControls();
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
      plannedExecution = freezePlannedExecution(body, attempt);
      submit.hidden = true;
      retry.hidden = true;
      execute.hidden = false;
      checkExecution.hidden = true;
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
          execute.disabled = false;
        }
      }
    }
  };

  const executionStillCurrent = (execution) =>
    plannedExecution === execution &&
    artifactEpoch === execution.epoch &&
    trustedArtifact === execution.artifact &&
    trustedScope === execution.scope;

  const showExecutionUnknown = (kind = "TRANSPORT_UNKNOWN") => {
    submit.hidden = true;
    retry.hidden = true;
    execute.hidden = true;
    execute.disabled = true;
    checkExecution.hidden = false;
    checkExecution.disabled = requestPending || !recoverableExecution;
    const reason =
      kind === "INVALID_RESPONSE"
        ? "服务端执行回执不满足可信协议，"
        : kind === "CLIENT_UNAVAILABLE"
          ? "当前浏览器缺少安全超时能力，执行请求没有发出，"
          : "执行请求超时、连接中断或响应丢失，";
    setState(
      "UNKNOWN",
      `${reason}执行结果未知。页面不会自动 POST 或重试；只能由你明确查询同一 attempt 的持久状态。`,
      true,
    );
  };

  const showExecutionStale = () => {
    trustedArtifact = null;
    trustedScope = null;
    frozenApproval = null;
    plannedExecution = null;
    recoverableExecution = null;
    recoverableExecutionHistorical = false;
    submit.hidden = true;
    retry.hidden = true;
    hideExecutionControls();
    artifactText.textContent = "服务端 Artifact 或执行 scope 已变化；原批准已失效";
    setState(
      "STALE",
      "精确版本或执行 scope 已变化，原批准已失效。没有写入本地草稿，也没有产生本次 Receipt。",
      true,
    );
  };

  const showPersistedPlanned = (execution) => {
    if (
      recoverableExecution !== execution ||
      recoverableExecutionHistorical
    ) {
      showHistoricalExecutionUnknown();
      return;
    }
    if (!executionStillCurrent(execution)) {
      invalidate();
      return;
    }
    submit.hidden = true;
    retry.hidden = true;
    checkExecution.hidden = true;
    checkExecution.disabled = true;
    execute.hidden = false;
    execute.disabled = requestPending;
    setState(
      "PLANNED",
      "持久状态仍为 PLANNED · executionState NOT_EXECUTED · Receipt：无。只有新的明确执行手势才会写入本地草稿。",
    );
  };

  const executePlannedApproval = async (execution) => {
    if (
      requestPending ||
      card.dataset.state !== "PLANNED" ||
      !executionStillCurrent(execution)
    ) {
      return;
    }

    const AbortControllerConstructor = globalThis.AbortController;
    if (
      typeof AbortControllerConstructor !== "function" ||
      typeof globalThis.fetch !== "function" ||
      typeof globalThis.setTimeout !== "function" ||
      typeof globalThis.clearTimeout !== "function"
    ) {
      showExecutionUnknown("CLIENT_UNAVAILABLE");
      return;
    }

    const controller = new AbortControllerConstructor();
    requestController = controller;
    recoverableExecution = execution;
    recoverableExecutionHistorical = false;
    let timeoutId = null;
    setBusy(true);
    execute.hidden = true;
    checkExecution.hidden = true;
    setState(
      "EXECUTING",
      "正在执行这一次精确批准；在可信终态回执到达前不会宣称成功。",
    );
    try {
      timeoutId = globalThis.setTimeout(() => controller.abort(), APPROVAL_TIMEOUT_MILLIS);
      const path =
        `/api/v1/action-approvals/${encodeURIComponent(execution.attemptId)}`;
      const response = await fetch(`${path}/execute`, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: execution.executeBody,
        signal: controller.signal,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
      });
      if (!executionStillCurrent(execution) || requestController !== controller) {
        await cancelResponseBody(response);
        return;
      }
      if (response.status === 409 || response.status === 412) {
        const problem = await readStrictProblem(response);
        if (problem.stale) {
          showExecutionStale();
          return;
        }
        throw invalidResponse("EXECUTION_CONFLICT_NOT_PROVEN_STALE");
      }
      if (
        ![200, 201].includes(response.status) ||
        response.headers.get("cache-control") !== "private, no-store" ||
        response.headers.get("location") !== path
      ) {
        await cancelResponseBody(response);
        throw invalidResponse("EXECUTION_RESPONSE_HEADERS_INVALID");
      }
      const body = await readBoundedJson(response, JSON_MEDIA_TYPE);
      if (!validateTerminalExecution(body, execution)) {
        throw invalidResponse("EXECUTION_RESPONSE_BINDING_INVALID");
      }
      renderTerminalExecution(body, execution);
    } catch (failure) {
      if (
        artifactEpoch !== execution.epoch ||
        requestController !== controller ||
        plannedExecution !== execution
      ) {
        return;
      }
      const typedFailure =
        failure instanceof ApprovalClientError
          ? failure
          : transportUnknown(
              controller.signal.aborted
                ? "EXECUTION_TIMEOUT_OR_ABORT"
                : "EXECUTION_TRANSPORT_UNKNOWN",
            );
      showExecutionUnknown(typedFailure.kind);
    } finally {
      if (timeoutId !== null) {
        globalThis.clearTimeout(timeoutId);
      }
      if (requestController === controller) {
        requestController = null;
        setBusy(false);
      }
    }
  };

  const recoverExecution = async (execution) => {
    const preserveDifferentCurrentArtifact =
      recoverableExecutionHistorical && hasDifferentCurrentArtifact(execution);
    if (
      requestPending ||
      recoverableExecution !== execution ||
      (!recoverableExecutionHistorical && card.dataset.state !== "UNKNOWN")
    ) {
      return;
    }

    const AbortControllerConstructor = globalThis.AbortController;
    if (
      typeof AbortControllerConstructor !== "function" ||
      typeof globalThis.fetch !== "function" ||
      typeof globalThis.setTimeout !== "function" ||
      typeof globalThis.clearTimeout !== "function"
    ) {
      if (recoverableExecutionHistorical) {
        showHistoricalExecutionUnknown();
      } else {
        showExecutionUnknown("CLIENT_UNAVAILABLE");
      }
      return;
    }

    const controller = new AbortControllerConstructor();
    requestController = controller;
    let timeoutId = null;
    setBusy(true);
    checkExecution.disabled = true;
    if (!preserveDifferentCurrentArtifact) {
      setState(
        "UNKNOWN",
        "正在查询同一 attempt 的持久状态；不会再次发送执行 POST。",
      );
    }
    try {
      timeoutId = globalThis.setTimeout(() => controller.abort(), APPROVAL_TIMEOUT_MILLIS);
      const response = await fetch(
        `/api/v1/action-approvals/${encodeURIComponent(execution.attemptId)}`,
        {
          method: "GET",
          headers: { Accept: "application/json" },
          signal: controller.signal,
          credentials: "same-origin",
          cache: "no-store",
          redirect: "error",
        },
      );
      if (
        recoverableExecution !== execution ||
        requestController !== controller
      ) {
        await cancelResponseBody(response);
        return;
      }
      if (response.status === 409 || response.status === 412) {
        const problem = await readStrictProblem(response);
        if (problem.stale) {
          showExecutionStale();
          return;
        }
        throw invalidResponse("EXECUTION_LOOKUP_CONFLICT_NOT_PROVEN_STALE");
      }
      if (
        response.status !== 200 ||
        response.headers.get("cache-control") !== "private, no-store"
      ) {
        await cancelResponseBody(response);
        throw invalidResponse("EXECUTION_LOOKUP_HEADERS_INVALID");
      }
      const body = await readBoundedJson(response, JSON_MEDIA_TYPE);
      if (body?.status === "SUCCEEDED" && validateTerminalExecution(body, execution)) {
        renderTerminalExecution(body, execution);
        return;
      }
      if (body?.status === "PLANNED" && validatePersistedPlanned(body, execution)) {
        showPersistedPlanned(execution);
        return;
      }
      throw invalidResponse("EXECUTION_LOOKUP_BINDING_INVALID");
    } catch (failure) {
      if (
        requestController !== controller ||
        recoverableExecution !== execution
      ) {
        return;
      }
      const typedFailure =
        failure instanceof ApprovalClientError
          ? failure
          : transportUnknown(
              controller.signal.aborted
                ? "EXECUTION_LOOKUP_TIMEOUT_OR_ABORT"
                : "EXECUTION_LOOKUP_TRANSPORT_UNKNOWN",
            );
      if (recoverableExecutionHistorical) {
        showHistoricalExecutionUnknown();
      } else {
        showExecutionUnknown(typedFailure.kind);
      }
    } finally {
      if (timeoutId !== null) {
        globalThis.clearTimeout(timeoutId);
      }
      if (requestController === controller) {
        requestController = null;
        setBusy(false);
      }
    }
  };

  const hasCurrentUndoOperation = (operation, snapshot) =>
    operation !== null &&
    snapshot !== null &&
    recoverableUndo === operation &&
    operation.undoNonce === recoverableUndo.undoNonce &&
    operation.attemptId === operation.execution.attemptId &&
    operation.attemptId === operation.creation.attemptId &&
    operation.epoch === artifactEpoch &&
    operation.artifact === trustedArtifact &&
    operation.artifact === operation.execution.artifact &&
    snapshot === terminalResult &&
    snapshot.execution === operation.execution &&
    snapshot.creation === operation.creation &&
    snapshot.artifact === operation.artifact &&
    snapshot.draft.state === "ACTIVE";

  const hasHistoricalUndoOperation = (operation, snapshot) =>
    operation !== null &&
    snapshot !== null &&
    recoverableUndoHistorical &&
    recoverableUndo === operation &&
    operation.undoNonce === recoverableUndo.undoNonce &&
    operation.attemptId === operation.execution.attemptId &&
    operation.attemptId === operation.creation.attemptId &&
    snapshot === terminalResult &&
    snapshot.execution === operation.execution &&
    snapshot.creation === operation.creation &&
    snapshot.artifact === operation.artifact &&
    operation.artifact === operation.execution.artifact &&
    snapshot.draft.state === "ACTIVE";

  const hasRecoverableUndoOperation = (operation, snapshot) =>
    hasCurrentUndoOperation(operation, snapshot) ||
    hasHistoricalUndoOperation(operation, snapshot);

  const ownsUndoController = (operation, snapshot, controller) =>
    requestController === controller &&
    hasRecoverableUndoOperation(operation, snapshot);

  const hasLiveUndoFence = (operation, snapshot, controller) =>
    ownsUndoController(operation, snapshot, controller) &&
    !controller.signal.aborted;

  const showUndoBlocked = (message) => {
    recoverableUndo = null;
    recoverableUndoHistorical = false;
    submit.hidden = true;
    retry.hidden = true;
    hideExecutionControls();
    undo.hidden = true;
    undo.disabled = true;
    checkUndo.hidden = true;
    checkUndo.disabled = true;
    setState("UNDO_BLOCKED", message, true);
  };

  const showUndoUnknown = (operation, kind = "TRANSPORT_UNKNOWN") => {
    recoverableUndo = operation;
    recoverableUndoHistorical = false;
    submit.hidden = true;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    hideExecutionControls();
    undo.hidden = true;
    undo.disabled = true;
    checkUndo.hidden = false;
    checkUndo.disabled = requestPending;
    undoReceiptLine.hidden = true;
    undoReceiptSummary.hidden = true;
    undoReceiptSummary.textContent = "";
    undoBoundary.hidden = false;
    undoBoundary.textContent =
      "请求结果未知；本地明文、Capture、Artifact、创建 Receipt 与历史仍保持原样。";
    const reason =
      kind === "INVALID_RESPONSE"
        ? "服务端回执不满足可信协议，"
        : "请求超时、连接中断或响应丢失，";
    setState(
      "UNDO_UNKNOWN",
      `${reason}逻辑撤销结果未知。页面不会自动 POST、重试或 GET；只能由你明确核验同一 attempt 的持久状态。`,
      true,
    );
  };

  const beginLogicalUndo = async () => {
    if (
      requestPending ||
      hasUnresolvedHistoricalUndo() ||
      card.dataset.state !== "SUCCEEDED" ||
      !terminalResult ||
      terminalResult.draft.state !== "ACTIVE" ||
      terminalResult.undoAvailable !== true ||
      !terminalResult.execution ||
      !terminalResult.creation
    ) {
      return;
    }

    const snapshot = terminalResult;
    const execution = snapshot.execution;
    const epoch = artifactEpoch;
    const artifact = trustedArtifact;
    if (execution.epoch !== epoch || execution.artifact !== artifact) {
      return;
    }
    let operation =
      recoverableUndo?.execution === execution ? recoverableUndo : null;
    if (operation !== null && !hasCurrentUndoOperation(operation, snapshot)) {
      return;
    }
    let controller = null;
    let timeoutId = null;
    let requestStarted = false;
    let staleCompletion = false;
    setBusy(true);
    submit.hidden = true;
    retry.hidden = true;
    hideExecutionControls();
    undo.hidden = true;
    undo.disabled = true;
    checkUndo.hidden = true;
    checkUndo.disabled = true;
    setState(
      "UNDOING",
      "正在提交逻辑状态变更；在可信终态回执到达前不会宣称结果。",
    );

    try {
      if (!operation) {
        const undoNonce = randomApprovalNonce();
        if (!undoNonce) {
          throw clientUnavailable("UNDO_NONCE_UNAVAILABLE");
        }
        const scopeHash = await recomputeUndoScopeHash(
          snapshot.creation,
          undoNonce,
        );
        if (
          terminalResult !== snapshot ||
          card.dataset.state !== "UNDOING" ||
          artifactEpoch !== epoch ||
          trustedArtifact !== artifact
        ) {
          staleCompletion = true;
          return;
        }
        const request = Object.freeze({
          undoNonce,
          scopeSchema: UNDO_SCOPE_SCHEMA,
          scopeHash,
        });
        operation = Object.freeze({
          execution,
          creation: snapshot.creation,
          attemptId: execution.attemptId,
          epoch,
          artifact,
          undoNonce,
          scopeSchema: UNDO_SCOPE_SCHEMA,
          scopeHash,
          body: JSON.stringify(request),
        });
        recoverableUndo = operation;
      }

      const AbortControllerConstructor = globalThis.AbortController;
      if (
        typeof AbortControllerConstructor !== "function" ||
        typeof globalThis.fetch !== "function" ||
        typeof globalThis.setTimeout !== "function" ||
        typeof globalThis.clearTimeout !== "function"
      ) {
        throw clientUnavailable("UNDO_CLIENT_UNAVAILABLE");
      }

      controller = new AbortControllerConstructor();
      requestController = controller;
      timeoutId = globalThis.setTimeout(
        () => controller.abort(),
        APPROVAL_TIMEOUT_MILLIS,
      );
      const attemptPath =
        `/api/v1/action-approvals/${encodeURIComponent(execution.attemptId)}`;
      requestStarted = true;
      const response = await fetch(`${attemptPath}/undo`, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: operation.body,
        signal: controller.signal,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
      });
      if (!hasLiveUndoFence(operation, snapshot, controller)) {
        await cancelResponseBody(response);
        if (ownsUndoController(operation, snapshot, controller)) {
          throw transportUnknown("UNDO_RESPONSE_AFTER_ABORT");
        }
        staleCompletion = true;
        return;
      }
      if (
        ![200, 201].includes(response.status) ||
        response.headers.get("cache-control") !== "private, no-store" ||
        response.headers.get("location") !== attemptPath
      ) {
        await cancelResponseBody(response);
        throw invalidResponse("UNDO_RESPONSE_HEADERS_INVALID");
      }
      const body = await readBoundedJson(response, JSON_MEDIA_TYPE);
      if (!hasLiveUndoFence(operation, snapshot, controller)) {
        if (ownsUndoController(operation, snapshot, controller)) {
          throw transportUnknown("UNDO_BODY_AFTER_ABORT");
        }
        staleCompletion = true;
        return;
      }
      if (!validateLogicalUndoResult(body, operation)) {
        throw invalidResponse("UNDO_RESPONSE_BINDING_INVALID");
      }
      renderTerminalExecution(body, execution);
    } catch (failure) {
      if (
        terminalResult !== snapshot ||
        artifactEpoch !== epoch ||
        trustedArtifact !== artifact ||
        (operation !== null && recoverableUndo !== operation) ||
        (controller !== null && requestController !== controller)
      ) {
        staleCompletion = true;
        return;
      }
      const typedFailure =
        failure instanceof ApprovalClientError
          ? failure
          : transportUnknown(
              controller?.signal?.aborted
                ? "UNDO_TIMEOUT_OR_ABORT"
                : "UNDO_TRANSPORT_UNKNOWN",
            );
      if (!requestStarted || !operation) {
        showUndoBlocked(
          "当前浏览器无法安全准备逻辑撤销请求；没有发送请求，原本地草稿状态保持不变。",
        );
      } else {
        showUndoUnknown(operation, typedFailure.kind);
      }
    } finally {
      if (timeoutId !== null) {
        globalThis.clearTimeout(timeoutId);
      }
      if (
        !staleCompletion &&
        controller !== null &&
        requestController === controller
      ) {
        requestController = null;
        setBusy(false);
      } else if (!staleCompletion && controller === null && requestPending) {
        setBusy(false);
      }
    }
  };

  const recoverLogicalUndo = async (operation) => {
    const snapshot = terminalResult;
    const historicalRecovery = recoverableUndoHistorical;
    const operationStillRecoverable = () =>
      historicalRecovery
        ? hasHistoricalUndoOperation(operation, snapshot)
        : hasCurrentUndoOperation(operation, snapshot);
    if (
      requestPending ||
      card.dataset.state !== "UNDO_UNKNOWN" ||
      !operationStillRecoverable()
    ) {
      return;
    }

    const AbortControllerConstructor = globalThis.AbortController;
    if (
      typeof AbortControllerConstructor !== "function" ||
      typeof globalThis.fetch !== "function" ||
      typeof globalThis.setTimeout !== "function" ||
      typeof globalThis.clearTimeout !== "function"
    ) {
      if (historicalRecovery) {
        showHistoricalUndoUnknown();
      } else {
        showUndoUnknown(operation, "CLIENT_UNAVAILABLE");
      }
      return;
    }

    const controller = new AbortControllerConstructor();
    requestController = controller;
    let timeoutId = null;
    let staleCompletion = false;
    setBusy(true);
    checkUndo.disabled = true;
    setState(
      "UNDO_UNKNOWN",
      "正在核验同一 attempt 的持久状态；不会发送逻辑撤销 POST。",
    );
    try {
      timeoutId = globalThis.setTimeout(
        () => controller.abort(),
        APPROVAL_TIMEOUT_MILLIS,
      );
      const response = await fetch(
        `/api/v1/action-approvals/${encodeURIComponent(operation.execution.attemptId)}`,
        {
          method: "GET",
          headers: { Accept: "application/json" },
          signal: controller.signal,
          credentials: "same-origin",
          cache: "no-store",
          redirect: "error",
        },
      );
      if (!hasLiveUndoFence(operation, snapshot, controller)) {
        await cancelResponseBody(response);
        if (ownsUndoController(operation, snapshot, controller)) {
          throw transportUnknown("UNDO_LOOKUP_RESPONSE_AFTER_ABORT");
        }
        staleCompletion = true;
        return;
      }
      if (
        response.status !== 200 ||
        response.headers.get("cache-control") !== "private, no-store"
      ) {
        await cancelResponseBody(response);
        throw invalidResponse("UNDO_LOOKUP_HEADERS_INVALID");
      }
      const body = await readBoundedJson(response, JSON_MEDIA_TYPE);
      if (!hasLiveUndoFence(operation, snapshot, controller)) {
        if (ownsUndoController(operation, snapshot, controller)) {
          throw transportUnknown("UNDO_LOOKUP_BODY_AFTER_ABORT");
        }
        staleCompletion = true;
        return;
      }
      if (validateLogicalUndoResult(body, operation)) {
        renderTerminalExecution(body, operation.execution);
        return;
      }
      if (
        validateTerminalExecution(body, operation.execution) &&
        body.localDraft.state === "ACTIVE" &&
        sameCreationProjection(body, operation, "ACTIVE")
      ) {
        renderTerminalExecution(body, operation.execution);
        if (historicalRecovery) {
          recoverableUndo = null;
          recoverableUndoHistorical = false;
          hideUndoControls();
        }
        return;
      }
      throw invalidResponse("UNDO_LOOKUP_BINDING_INVALID");
    } catch (failure) {
      if (
        requestController !== controller ||
        !operationStillRecoverable()
      ) {
        staleCompletion = true;
        return;
      }
      const typedFailure =
        failure instanceof ApprovalClientError
          ? failure
          : transportUnknown(
              controller.signal.aborted
                ? "UNDO_LOOKUP_TIMEOUT_OR_ABORT"
                : "UNDO_LOOKUP_TRANSPORT_UNKNOWN",
            );
      if (historicalRecovery) {
        showHistoricalUndoUnknown();
      } else {
        showUndoUnknown(operation, typedFailure.kind);
      }
    } finally {
      if (timeoutId !== null) {
        globalThis.clearTimeout(timeoutId);
      }
      if (!staleCompletion && requestController === controller) {
        requestController = null;
        setBusy(false);
      }
    }
  };

  submit.addEventListener("click", async (event) => {
    event.preventDefault?.();
    if (
      hasUnresolvedHistoricalExecution() ||
      hasUnresolvedHistoricalUndo() ||
      requestPending ||
      !trustedArtifact ||
      !trustedScope ||
      frozenApproval
    ) {
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
    if (
      requestPending ||
      hasUnresolvedHistoricalUndo() ||
      !frozenApproval ||
      card.dataset.state !== "UNKNOWN"
    ) {
      return;
    }
    await postApproval(frozenApproval);
  });

  execute.addEventListener("click", async (event) => {
    event.preventDefault?.();
    if (
      hasUnresolvedHistoricalExecution() ||
      hasUnresolvedHistoricalUndo()
    ) {
      return;
    }
    const execution = plannedExecution;
    if (!execution) {
      return;
    }
    await executePlannedApproval(execution);
  });

  checkExecution.addEventListener("click", async (event) => {
    event.preventDefault?.();
    const execution = recoverableExecution;
    if (!execution) {
      return;
    }
    await recoverExecution(execution);
  });

  undo.addEventListener("click", async (event) => {
    event.preventDefault?.();
    await beginLogicalUndo();
  });

  checkUndo.addEventListener("click", async (event) => {
    event.preventDefault?.();
    const operation = recoverableUndo;
    if (!operation) {
      return;
    }
    await recoverLogicalUndo(operation);
  });

  document.addEventListener(TRUSTED_ARTIFACT_EVENT, (event) => {
    showTrustedArtifact(event?.detail);
  });
  document.addEventListener(ARTIFACT_INVALIDATED_EVENT, invalidate);
})();
