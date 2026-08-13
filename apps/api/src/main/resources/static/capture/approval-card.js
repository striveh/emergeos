(() => {
  const TRUSTED_ARTIFACT_EVENT = "emergeos:trusted-artifact";
  const ARTIFACT_INVALIDATED_EVENT = "emergeos:artifact-invalidated";
  const MAX_RESPONSE_BYTES = 262144;
  const APPROVAL_TIMEOUT_MILLIS = 10000;
  const HASH_PATTERN = /^[0-9a-f]{64}$/;
  const JSON_MEDIA_TYPE = /^application\/json(?:\s*;|$)/i;
  const PROBLEM_MEDIA_TYPE = /^application\/problem\+json(?:\s*;|$)/i;
  const STALE_PROBLEM_ALLOWLIST = Object.freeze([
    Object.freeze({
      status: 412,
      type: "urn:emergeos:problem:approval-stale",
      title: "Approval stale",
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

  const card = document.querySelector("#approval-card");
  const artifactText = document.querySelector("#approval-artifact");
  const submit = document.querySelector("#approval-submit");
  const retry = document.querySelector("#approval-retry");
  const status = document.querySelector("#approval-status");

  if (!card || !artifactText || !submit || !retry || !status) {
    return;
  }

  let trustedArtifact = null;
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
    submit.disabled = busy || !trustedArtifact;
    retry.disabled = busy || !frozenApproval;
    card.setAttribute?.("aria-busy", String(busy));
  };

  const invalidate = () => {
    artifactEpoch += 1;
    requestController?.abort?.();
    requestController = null;
    trustedArtifact = null;
    frozenApproval = null;
    requestPending = false;
    submit.disabled = true;
    retry.hidden = true;
    retry.disabled = true;
    artifactText.textContent = "当前 Artifact 已失效；请重新保存可信版本";
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

  const showTrustedArtifact = (detail) => {
    const candidate = safeTrustedArtifact(detail);
    if (!candidate) {
      invalidate();
      return;
    }
    artifactEpoch += 1;
    requestController?.abort?.();
    requestController = null;
    trustedArtifact = candidate;
    frozenApproval = null;
    requestPending = false;
    card.hidden = false;
    artifactText.textContent = `v${candidate.artifactVersion} · SHA-256 ${candidate.artifactHash}`;
    submit.hidden = false;
    submit.disabled = false;
    submit.textContent = "记录本次批准";
    retry.hidden = true;
    retry.disabled = true;
    setState("READY", "等待你核对目标、风险和精确 Artifact 版本。");
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

  const validInstant = (value) =>
    typeof value === "string" && Number.isFinite(Date.parse(value));

  const validateApprovalReceipt = (response, body, attempt) => {
    const transition = body?.transitions?.[0];
    const location = response.headers.get("location");
    const cacheControl = response.headers.get("cache-control");
    return (
      [200, 201].includes(response.status) &&
      cacheControl === "private, no-store" &&
      safeIdentifier(body?.attemptId) &&
      location === `/api/v1/action-approvals/${body.attemptId}` &&
      body?.status === "PLANNED" &&
      safeIdentifier(body?.plan?.planId) &&
      HASH_PATTERN.test(body?.plan?.planHash ?? "") &&
      body?.plan?.artifactId === attempt.artifact.artifactId &&
      body?.plan?.artifactVersion === attempt.artifact.artifactVersion &&
      body?.plan?.artifactHash === attempt.artifact.artifactHash &&
      body?.plan?.idempotencyKey === attempt.request.approvalNonce &&
      validInstant(body?.plan?.expiresAt) &&
      safeIdentifier(body?.approval?.decisionId) &&
      body?.approval?.decision === "APPROVED" &&
      safeIdentifier(body?.approval?.actor) &&
      validInstant(body?.approval?.decidedAt) &&
      body?.approval?.planId === body.plan.planId &&
      body?.approval?.planHash === body.plan.planHash &&
      body?.approval?.artifactHash === attempt.artifact.artifactHash &&
      safeIdentifier(body?.capability?.capabilityId) &&
      body?.capability?.usedCalls === 0 &&
      Number.isSafeInteger(body?.capability?.maxCalls) &&
      body.capability.maxCalls >= 1 &&
      Array.isArray(body?.transitions) &&
      body.transitions.length === 1 &&
      transition?.sequence === 1 &&
      transition?.fromStatus === null &&
      transition?.toStatus === "PLANNED" &&
      validInstant(transition?.occurredAt) &&
      body?.receipt === null
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
      `${reason}批准结果未知。页面不会自动重试；如需恢复，只能明确重试同一批准、同一版本和同一 nonce。`,
      true,
    );
  };

  const postApproval = async (attempt) => {
    if (requestPending || !trustedArtifact || frozenApproval !== attempt) {
      return;
    }
    if (
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
      timeoutId = globalThis.setTimeout(() => {
        controller.abort();
      }, APPROVAL_TIMEOUT_MILLIS);
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
        return;
      }
      if (response.status === 409 || response.status === 412) {
        const problem = await readStrictProblem(response);
        if (problem.stale) {
          trustedArtifact = null;
          frozenApproval = null;
          submit.disabled = true;
          retry.hidden = true;
          artifactText.textContent = "服务端 Artifact 版本已变化；原批准已失效";
          setState(
            "STALE",
            "精确版本已变化，原批准已失效。请重新保存并核对当前 Artifact 后再批准。",
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
        "批准已记录 · 状态 PLANNED · 尚未执行 · Receipt：无。",
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
      if (artifactEpoch === attempt.epoch && frozenApproval === attempt) {
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
    if (requestPending || !trustedArtifact || frozenApproval) {
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
    });
    frozenApproval = Object.freeze({
      epoch: artifactEpoch,
      artifact: trustedArtifact,
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
