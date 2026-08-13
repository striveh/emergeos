(() => {
  const CAPTURE_ENDPOINT = "/api/v1/captures";
  const AGENT_DRAFT_ENDPOINT = "/api/v1/agent-drafts";
  const PERSONAL_DATA_CLASS = "PERSONAL";
  const OUTCOME_INTENT =
    "用本地 ScriptedFakeModel 将这条已保存 Capture 整理成一个可编辑演示草稿；不调用外部模型，不执行行动";
  const FAKE_BOUNDARY = "本地演示生成（Fake，无外部模型）";
  const MAX_OUTCOME_RESPONSE_BYTES = 262144;

  const form = document.querySelector("#quick-capture-form");
  const linkField = document.querySelector("#link-source-field");
  const sourceRef = document.querySelector("#source-ref");
  const content = document.querySelector("#capture-content");
  const status = document.querySelector("#capture-status");
  const submitButton = document.querySelector("#capture-submit");
  const abandonRetryButton = document.querySelector("#capture-abandon-retry");
  const newEntryButton = document.querySelector("#capture-new-entry");
  const outcomeCanvasTrigger = document.querySelector("#capture-emerge");
  const outcomeCanvasPreview = document.querySelector("#outcome-canvas");
  const outcomeCanvasBoundary = document.querySelector("#outcome-canvas-boundary");
  const outcomeCanvasTitle = document.querySelector("#outcome-canvas-title");
  const outcomeCanvasMessage = document.querySelector("#outcome-canvas-message");
  const outcomeCanvasContent = document.querySelector("#outcome-canvas-content");
  const outcomeMeta = document.querySelector("#outcome-meta");
  const outcomeSave = document.querySelector("#outcome-save");
  const outcomeConflict = document.querySelector("#outcome-conflict");
  const outcomeConflictMeta = document.querySelector("#outcome-conflict-meta");
  const outcomeConflictContent = document.querySelector("#outcome-conflict-content");
  const sourceChoices = document.querySelectorAll('input[name="sourceType"]');

  if (
    !form ||
    !linkField ||
    !sourceRef ||
    !content ||
    !status ||
    sourceChoices.length === 0
  ) {
    return;
  }

  let frozenAttempt = null;
  let retryRequired = false;
  let submitting = false;
  let successfulSnapshot = null;
  let abandonedUnknownSnapshot = null;
  let conflictSnapshot = null;
  let explicitNewEntry = false;
  let outcomeCapture = null;
  let outcomeGenerationStarted = false;
  let outcomeGenerationEpoch = 0;
  let outcomeGenerationController = null;
  let outcomeArtifact = null;
  let revisionSaving = false;
  let revisionEpoch = 0;
  let revisionController = null;

  const setOutcomeGenerationLocked = (locked) => {
    content.disabled = locked;
    sourceRef.disabled = locked;
    sourceChoices.forEach((choice) => {
      choice.disabled = locked;
    });
    if (submitButton) {
      submitButton.disabled = locked;
    }
    if (newEntryButton) {
      newEntryButton.disabled = locked;
    }
    form.setAttribute?.("aria-busy", String(locked));
  };

  const setOutcomeState = (state, title, message) => {
    if (!outcomeCanvasPreview) {
      return;
    }
    if (outcomeCanvasPreview.dataset) {
      outcomeCanvasPreview.dataset.state = state;
    } else {
      outcomeCanvasPreview.setAttribute?.("data-state", state);
    }
    const alertState = [
      "GENERATION_FAILED",
      "GENERATION_UNKNOWN",
      "REVISION_CONFLICT",
      "REVISION_UNKNOWN",
    ].includes(state);
    outcomeCanvasPreview.setAttribute?.("role", alertState ? "alert" : "status");
    outcomeCanvasPreview.setAttribute?.(
      "aria-live",
      alertState ? "assertive" : "polite",
    );
    if (outcomeCanvasBoundary) {
      outcomeCanvasBoundary.textContent = FAKE_BOUNDARY;
    }
    if (outcomeCanvasTitle) {
      outcomeCanvasTitle.textContent = title;
    }
    if (outcomeCanvasMessage) {
      outcomeCanvasMessage.textContent = message;
    }
    if (alertState) {
      outcomeCanvasPreview.focus?.();
    }
  };

  const resetOutcomeCanvas = () => {
    outcomeGenerationEpoch += 1;
    outcomeGenerationController?.abort?.();
    outcomeGenerationController = null;
    revisionEpoch += 1;
    revisionController?.abort?.();
    revisionController = null;
    setOutcomeGenerationLocked(false);
    outcomeCapture = null;
    outcomeGenerationStarted = false;
    outcomeArtifact = null;
    revisionSaving = false;
    if (outcomeCanvasTrigger) {
      outcomeCanvasTrigger.hidden = true;
      outcomeCanvasTrigger.disabled = false;
      outcomeCanvasTrigger.textContent = "让它显现";
      outcomeCanvasTrigger.setAttribute?.("aria-expanded", "false");
    }
    if (outcomeCanvasPreview) {
      outcomeCanvasPreview.hidden = true;
    }
    if (outcomeCanvasContent) {
      outcomeCanvasContent.hidden = true;
      outcomeCanvasContent.textContent = "";
      outcomeCanvasContent.setAttribute?.("contenteditable", "false");
      if (outcomeCanvasContent.dataset) {
        outcomeCanvasContent.dataset.state = "CAPTURED";
      }
    }
    if (outcomeMeta) {
      outcomeMeta.hidden = true;
      outcomeMeta.textContent = "";
    }
    if (outcomeSave) {
      outcomeSave.hidden = true;
      outcomeSave.disabled = false;
      outcomeSave.textContent = "保存本地修订";
    }
    if (outcomeConflict) {
      outcomeConflict.hidden = true;
    }
    if (outcomeConflictMeta) {
      outcomeConflictMeta.textContent = "";
    }
    if (outcomeConflictContent) {
      outcomeConflictContent.textContent = "";
    }
    setOutcomeState(
      "CAPTURED",
      "Outcome Canvas 尚未生成",
      "这个入口只表明已保存的 Capture 可以进入后续本地演示；当前没有调用模型、没有创建 Agent Draft，也没有执行任何行动。",
    );
  };

  const offerOutcomeCanvas = (receipt, expectedPayload) => {
    outcomeCapture = Object.freeze({
      captureId: receipt.captureId,
      capturedAt: receipt.capturedAt,
      payload: expectedPayload,
    });
    outcomeGenerationStarted = false;
    outcomeArtifact = null;
    revisionSaving = false;
    if (outcomeCanvasTrigger && outcomeCapture.captureId) {
      outcomeCanvasTrigger.hidden = false;
      outcomeCanvasTrigger.disabled = false;
      outcomeCanvasTrigger.textContent = "让它显现";
    }
    if (outcomeCanvasPreview) {
      outcomeCanvasPreview.hidden = true;
    }
  };

  const setStatus = (state, message) => {
    const failure = ["UNKNOWN", "CONFLICT", "INVALID"].includes(state);
    const focusableOutcome = ["SAVED", "REPLAYED", "UNKNOWN", "CONFLICT", "INVALID"].includes(
      state,
    );
    if (status.dataset) {
      status.dataset.state = state;
    } else {
      status.setAttribute?.("data-state", state);
    }
    status.setAttribute?.("role", failure ? "alert" : "status");
    status.setAttribute?.("aria-live", failure ? "assertive" : "polite");
    status.textContent = "";

    const code = document.createElement?.("strong");
    const detail = document.createElement?.("span");
    if (code && detail) {
      code.className = "status-code";
      code.textContent = state;
      detail.className = "status-message";
      detail.textContent = message;
      status.append?.(code, detail);
    } else {
      // The packaged acceptance harness intentionally exposes only a minimal DOM.
      status.textContent = `${state} — ${message}`;
    }

    if (focusableOutcome) {
      status.focus?.();
    }
  };

  const setBusy = (busy) => {
    submitting = busy;
    form.setAttribute?.("aria-busy", String(busy));
    if (submitButton) {
      submitButton.disabled = busy;
      submitButton.textContent = busy
        ? "正在保存…"
        : retryRequired
          ? "重试同一条"
          : "保存这条想法";
    }
  };

  const setFormLocked = (locked, action = null) => {
    content.disabled = locked;
    sourceRef.disabled = locked;
    sourceChoices.forEach((choice) => {
      choice.disabled = locked;
    });
    if (abandonRetryButton) {
      abandonRetryButton.hidden = action !== "UNKNOWN";
    }
    if (newEntryButton) {
      newEntryButton.hidden = action !== "SUCCESS";
    }
  };

  const markOutcomeDirty = () => {
    const outcomeState = outcomeCanvasPreview?.dataset?.state;
    if (
      !outcomeArtifact ||
      revisionSaving ||
      !["CANVAS_READY", "REVISION_SAVED", "DIRTY"].includes(outcomeState)
    ) {
      return;
    }
    if (outcomeCanvasContent?.dataset) {
      outcomeCanvasContent.dataset.state = "DIRTY";
    }
    if (outcomeSave) {
      outcomeSave.hidden = false;
      outcomeSave.disabled = false;
      outcomeSave.textContent = "保存本地修订";
    }
    setOutcomeState(
      "DIRTY",
      "Outcome Canvas 本地草稿已编辑",
      "这些修改只存在于当前页面，尚未写入 Artifact 新版本。",
    );
  };

  const markEditable = (event) => {
    if (event?.target === outcomeCanvasContent) {
      markOutcomeDirty();
      return;
    }
    if (submitting || retryRequired) {
      return;
    }
    frozenAttempt = null;
    explicitNewEntry = false;
    resetOutcomeCanvas();
    setFormLocked(false);
    setStatus("READY", "内容只会提交到当前 EmergeOS 实例。");
  };

  const syncSourceFields = () => {
    const selected = document.querySelector('input[name="sourceType"]:checked');
    const linkSelected = selected?.value === "LINK";
    linkField.hidden = !linkSelected;
    sourceRef.required = linkSelected;
  };

  sourceChoices.forEach((choice) => {
    choice.addEventListener("change", syncSourceFields);
  });

  form.addEventListener("input", markEditable);
  outcomeCanvasContent?.addEventListener("input", markOutcomeDirty);

  const newClientNonce = () => {
    const randomUUID = globalThis.crypto?.randomUUID;
    if (typeof randomUUID !== "function") {
      return null;
    }
    try {
      return randomUUID.call(globalThis.crypto);
    } catch (_failure) {
      return null;
    }
  };

  const createFrozenAttempt = () => {
    const selected = document.querySelector('input[name="sourceType"]:checked');
    const sourceType = selected?.value;
    const rawContent = content.value;
    const rawSourceRef =
      sourceType === "LINK" ? sourceRef.value : "quick-capture-ui:text";

    if (!rawContent.trim() || !["TEXT", "LINK"].includes(sourceType)) {
      return { error: "请填写想法，并选择有效的来源类型。" };
    }
    if (sourceType === "LINK" && !rawSourceRef.trim()) {
      return { error: "链接来源需要填写原始链接。" };
    }

    const snapshot = JSON.stringify({
      content: rawContent,
      sourceType,
      sourceRef: rawSourceRef,
      dataClass: PERSONAL_DATA_CLASS,
    });
    if (!explicitNewEntry && snapshot === successfulSnapshot) {
      return {
        state: "SAVED",
        error: "这条内容已经保存。请先编辑，或明确开始一个新条目。",
      };
    }
    if (snapshot === abandonedUnknownSnapshot) {
      return {
        state: "UNKNOWN",
        error: "这条内容的上次结果未知。请先修改内容，不能用新请求标识直接重提。",
      };
    }
    if (snapshot === conflictSnapshot) {
      return {
        state: "CONFLICT",
        error: "这条内容刚刚发生请求冲突。请先修改内容，不能生成新请求标识直接重提。",
      };
    }

    const clientNonce = newClientNonce();
    if (!clientNonce) {
      return {
        error: "当前浏览器无法生成安全请求标识，内容尚未提交。请更换受支持的浏览器后重试。",
      };
    }

    const payload = Object.freeze({
      clientNonce,
      content: rawContent,
      sourceType,
      sourceRef: rawSourceRef,
      dataClass: PERSONAL_DATA_CLASS,
    });
    return Object.freeze({
      payload,
      body: JSON.stringify(payload),
      snapshot,
    });
  };

  const CAPTURE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,200}$/;
  const SAFE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/;
  const SHA256_PATTERN = /^[a-f0-9]{64}$/;
  const CAPTURED_AT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
  const APPLICATION_JSON_CONTENT_TYPE_PATTERN =
    /^[ \t]*application\/json[ \t]*(?:;[ \t]*[!#$%&'*+.^_`|~0-9A-Za-z-]+[ \t]*=[ \t]*(?:[!#$%&'*+.^_`|~0-9A-Za-z-]+|"(?:[^"\\\r\n\x00-\x08\x0b\x0c\x0e-\x1f\x7f]|\\[\t\x20-\x7e])*")[ \t]*)*$/i;

  const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

  const exactList = (value, expected) =>
    Array.isArray(value) &&
    value.length === expected.length &&
    value.every((entry, index) => entry === expected[index]);

  const encodeUtf8 = (value) => {
    const bytes = [];
    for (const symbol of value) {
      const codePoint = symbol.codePointAt(0);
      if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
        throw new Error("OUTCOME_TEXT_INVALID_UTF8");
      }
      if (codePoint <= 0x7f) {
        bytes.push(codePoint);
      } else if (codePoint <= 0x7ff) {
        bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f));
      } else if (codePoint <= 0xffff) {
        bytes.push(
          0xe0 | (codePoint >> 12),
          0x80 | ((codePoint >> 6) & 0x3f),
          0x80 | (codePoint & 0x3f),
        );
      } else {
        bytes.push(
          0xf0 | (codePoint >> 18),
          0x80 | ((codePoint >> 12) & 0x3f),
          0x80 | ((codePoint >> 6) & 0x3f),
          0x80 | (codePoint & 0x3f),
        );
      }
    }
    return Uint8Array.from(bytes);
  };

  const decodeUtf8 = (bytes) => {
    let decoded = "";
    const continuation = (index) => {
      const value = bytes[index];
      if (value === undefined || (value & 0xc0) !== 0x80) {
        throw new Error("OUTCOME_RESPONSE_UTF8_INVALID");
      }
      return value;
    };
    for (let index = 0; index < bytes.length; ) {
      const first = bytes[index];
      let codePoint;
      let width;
      if (first <= 0x7f) {
        codePoint = first;
        width = 1;
      } else if (first >= 0xc2 && first <= 0xdf) {
        codePoint = ((first & 0x1f) << 6) | (continuation(index + 1) & 0x3f);
        width = 2;
      } else if (first >= 0xe0 && first <= 0xef) {
        const second = continuation(index + 1);
        const third = continuation(index + 2);
        if ((first === 0xe0 && second < 0xa0) || (first === 0xed && second >= 0xa0)) {
          throw new Error("OUTCOME_RESPONSE_UTF8_INVALID");
        }
        codePoint =
          ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
        width = 3;
      } else if (first >= 0xf0 && first <= 0xf4) {
        const second = continuation(index + 1);
        const third = continuation(index + 2);
        const fourth = continuation(index + 3);
        if ((first === 0xf0 && second < 0x90) || (first === 0xf4 && second > 0x8f)) {
          throw new Error("OUTCOME_RESPONSE_UTF8_INVALID");
        }
        codePoint =
          ((first & 0x07) << 18) |
          ((second & 0x3f) << 12) |
          ((third & 0x3f) << 6) |
          (fourth & 0x3f);
        width = 4;
      } else {
        throw new Error("OUTCOME_RESPONSE_UTF8_INVALID");
      }
      decoded += String.fromCodePoint(codePoint);
      index += width;
    }
    return decoded;
  };

  const sha256Utf8 = async (value) => {
    const subtle = globalThis.crypto?.subtle;
    if (!subtle || typeof subtle.digest !== "function") {
      throw new Error("OUTCOME_WEBCRYPTO_UNAVAILABLE");
    }
    const digest = new Uint8Array(await subtle.digest("SHA-256", encodeUtf8(value)));
    return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
  };

  class OutcomeResponseError extends Error {
    constructor(status, kind, reason) {
      super(reason);
      this.name = "OutcomeResponseError";
      this.status = status;
      this.kind = kind;
      this.receiptInvalid = kind === "RECEIPT_INVALID";
      this.reason = reason;
    }
  }

  const responseError = (response, reason, receiptInvalid = true) =>
    new OutcomeResponseError(
      Number.isInteger(response?.status) ? response.status : null,
      receiptInvalid ? "RECEIPT_INVALID" : "TRANSPORT_UNKNOWN",
      reason,
    );

  const requestOutcomeJson = async (
    path,
    method = "GET",
    body = null,
    attemptController = null,
  ) => {
    if (
      typeof path !== "string" ||
      !path.startsWith("/api/v1/") ||
      path.includes("//") ||
      typeof globalThis.AbortController !== "function"
    ) {
      throw new Error("OUTCOME_REQUEST_UNAVAILABLE");
    }
    const controller = attemptController ?? new globalThis.AbortController();
    if (controller.signal?.aborted) {
      throw new Error("OUTCOME_REQUEST_CANCELLED");
    }
    const timeout = setTimeout(() => controller.abort(), 10000);
    let observedResponse = null;
    try {
      const response = await fetch(path, {
        method,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: Object.freeze({
          Accept: "application/json",
          ...(body === null ? {} : { "Content-Type": "application/json" }),
        }),
        ...(body === null ? {} : { body: JSON.stringify(body) }),
        signal: controller.signal,
      });
      observedResponse = response;
      const cacheControl = response.headers?.get?.("cache-control") ?? null;
      if (cacheControl !== "private, no-store") {
        throw new Error("OUTCOME_RESPONSE_CACHE_BOUNDARY_INVALID");
      }
      const contentType = response.headers?.get?.("content-type") ?? null;
      if (
        typeof contentType !== "string" ||
        !APPLICATION_JSON_CONTENT_TYPE_PATTERN.test(contentType)
      ) {
        throw new Error("OUTCOME_CONTENT_TYPE_INVALID");
      }
      const contentLength = response.headers?.get?.("content-length");
      if (contentLength !== null && contentLength !== undefined) {
        if (!/^(?:0|[1-9][0-9]*)$/.test(contentLength)) {
          throw new Error("OUTCOME_CONTENT_LENGTH_INVALID");
        }
        const declaredLength = Number(contentLength);
        if (
          !Number.isSafeInteger(declaredLength) ||
          declaredLength > MAX_OUTCOME_RESPONSE_BYTES
        ) {
          controller.abort();
          throw new Error("OUTCOME_RESPONSE_TOO_LARGE");
        }
      }
      const reader = response.body?.getReader?.();
      if (!reader) {
        throw new Error("OUTCOME_RESPONSE_STREAM_UNAVAILABLE");
      }
      const chunks = [];
      let totalBytes = 0;
      while (true) {
        let readResult;
        try {
          readResult = await reader.read();
        } catch (_streamFailure) {
          throw responseError(response, "OUTCOME_RESPONSE_STREAM_INTERRUPTED", false);
        }
        if (!isObject(readResult) || typeof readResult.done !== "boolean") {
          throw new Error("OUTCOME_RESPONSE_STREAM_RESULT_INVALID");
        }
        const { done, value } = readResult;
        if (done) {
          break;
        }
        if (
          !ArrayBuffer.isView(value) ||
          value.BYTES_PER_ELEMENT !== 1 ||
          typeof value.byteLength !== "number"
        ) {
          controller.abort();
          throw new Error("OUTCOME_RESPONSE_STREAM_INVALID");
        }
        totalBytes += value.byteLength;
        if (totalBytes > MAX_OUTCOME_RESPONSE_BYTES) {
          controller.abort();
          try {
            await reader.cancel?.();
          } catch (_cancelFailure) {
            // The deterministic byte-cap violation remains the authoritative reason.
          }
          throw new Error("OUTCOME_RESPONSE_TOO_LARGE");
        }
        chunks.push(value);
      }
      if (contentLength !== null && contentLength !== undefined && Number(contentLength) !== totalBytes) {
        throw new Error("OUTCOME_CONTENT_LENGTH_MISMATCH");
      }
      const bytes = new Uint8Array(totalBytes);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      return Object.freeze({
        status: response.status,
        location: response.headers?.get?.("location") ?? null,
        cacheControl,
        contentType,
        bodyText: decodeUtf8(bytes),
      });
    } catch (failure) {
      if (failure instanceof OutcomeResponseError || observedResponse === null) {
        throw failure;
      }
      const reason =
        failure instanceof Error && /^OUTCOME_[A-Z0-9_]+$/.test(failure.message)
          ? failure.message
          : "OUTCOME_RESPONSE_READ_FAILED";
      throw responseError(observedResponse, reason);
    } finally {
      clearTimeout(timeout);
    }
  };

  const parseJsonObject = (response) => {
    try {
      if (
        typeof response.contentType !== "string" ||
        !APPLICATION_JSON_CONTENT_TYPE_PATTERN.test(response.contentType)
      ) {
        throw new Error("OUTCOME_CONTENT_TYPE_INVALID");
      }
      const value = JSON.parse(response.bodyText);
      if (!isObject(value)) {
        throw new Error("OUTCOME_RESPONSE_INVALID");
      }
      return value;
    } catch (failure) {
      if (failure instanceof OutcomeResponseError) {
        throw failure;
      }
      const reason =
        failure instanceof Error && /^OUTCOME_[A-Z0-9_]+$/.test(failure.message)
          ? failure.message
          : "OUTCOME_RESPONSE_JSON_INVALID";
      throw responseError(response, reason);
    }
  };

  const validateDraftReceipt = (draft, location, expectedCaptureId) => {
    const locationMatch =
      typeof location === "string"
        ? location.match(/^\/api\/v1\/artifacts\/([A-Za-z0-9][A-Za-z0-9._~-]{0,127})$/)
        : null;
    const artifactId = locationMatch?.[1] ?? null;
    const runId = typeof draft.runId === "string" ? draft.runId : "";
    const runRef = `/api/v1/agent-runs/${runId}`;
    const result = draft.result;
    if (
      !artifactId ||
      !SAFE_ID_PATTERN.test(runId) ||
      Object.prototype.hasOwnProperty.call(draft, "artifactId") ||
      draft.runRef !== runRef ||
      draft.bundleRef !== `${runRef}/bundle` ||
      !Array.isArray(draft.trace) ||
      !isObject(result) ||
      result.schemaVersion !== "1.0" ||
      result.runId !== runId ||
      result.status !== "SUCCEEDED" ||
      !exactList(result.artifactRefs, [`artifact-version://${artifactId}/1`]) ||
      !exactList(result.evidenceRefs, [`capture://${expectedCaptureId}`]) ||
      result.traceRef !== `${runRef}/trace` ||
      result.failureReason !== null ||
      result.resolvedModel !== "fake-model-v1" ||
      typeof result.costUsd !== "number" ||
      result.costUsd !== 0 ||
      !exactList(result.receiptRefs, [])
    ) {
      return null;
    }
    return Object.freeze({ artifactId, location });
  };

  const validateFailedDraft = (draft, location) => {
    const runId = typeof draft.runId === "string" ? draft.runId : "";
    const runRef = `/api/v1/agent-runs/${runId}`;
    const result = draft.result;
    return (
      location === null &&
      SAFE_ID_PATTERN.test(runId) &&
      draft.runRef === runRef &&
      draft.bundleRef === `${runRef}/bundle` &&
      Array.isArray(draft.trace) &&
      isObject(result) &&
      result.schemaVersion === "1.0" &&
      result.runId === runId &&
      result.status === "FAILED" &&
      result.traceRef === `${runRef}/trace` &&
      typeof result.failureReason === "string" &&
      /^[A-Z][A-Z0-9_]{0,127}$/.test(result.failureReason) &&
      result.resolvedModel === null &&
      exactList(result.artifactRefs, []) &&
      exactList(result.receiptRefs, [])
    );
  };

  const validatePersistedCapture = (persisted, expected) =>
    persisted.captureId === expected.captureId &&
    persisted.clientNonce === expected.payload.clientNonce &&
    persisted.content === expected.payload.content &&
    persisted.sourceType === expected.payload.sourceType &&
    persisted.sourceRef === expected.payload.sourceRef &&
    persisted.dataClass === expected.payload.dataClass &&
    persisted.capturedAt === expected.capturedAt;

  const validateArtifactLineage = async (
    artifact,
    expectedArtifactId,
    expectedCaptureId,
    expectedPrincipalId = null,
  ) => {
    if (
      !isObject(artifact) ||
      artifact.artifactId !== expectedArtifactId ||
      artifact.captureId !== expectedCaptureId ||
      typeof artifact.principalId !== "string" ||
      !artifact.principalId ||
      (expectedPrincipalId !== null && artifact.principalId !== expectedPrincipalId) ||
      !Number.isSafeInteger(artifact.currentVersion) ||
      artifact.currentVersion < 1 ||
      typeof artifact.currentHash !== "string" ||
      !SHA256_PATTERN.test(artifact.currentHash) ||
      !Array.isArray(artifact.versions) ||
      artifact.versions.length !== artifact.currentVersion
    ) {
      return null;
    }

    const versions = [];
    for (let index = 0; index < artifact.versions.length; index += 1) {
      const version = artifact.versions[index];
      const previous = versions[index - 1] ?? null;
      if (
        !isObject(version) ||
        version.version !== index + 1 ||
        typeof version.content !== "string" ||
        !version.content.trim() ||
        typeof version.contentHash !== "string" ||
        !SHA256_PATTERN.test(version.contentHash) ||
        typeof version.createdAt !== "string" ||
        !CAPTURED_AT_PATTERN.test(version.createdAt) ||
        !Number.isFinite(Date.parse(version.createdAt)) ||
        (index === 0
          ? version.baseVersion !== null || version.baseHash !== null
          : version.baseVersion !== index || version.baseHash !== previous.contentHash) ||
        (await sha256Utf8(version.content)) !== version.contentHash
      ) {
        return null;
      }
      versions.push(
        Object.freeze({
          version: version.version,
          content: version.content,
          contentHash: version.contentHash,
          baseVersion: version.baseVersion,
          baseHash: version.baseHash,
          createdAt: version.createdAt,
        }),
      );
    }

    const head = versions.at(-1);
    if (!head || artifact.currentHash !== head.contentHash) {
      return null;
    }
    return Object.freeze({
      artifactId: artifact.artifactId,
      principalId: artifact.principalId,
      captureId: artifact.captureId,
      currentVersion: artifact.currentVersion,
      currentHash: artifact.currentHash,
      content: head.content,
      versions: Object.freeze(versions),
    });
  };

  const trustedLineageMatches = (candidate, expected) =>
    candidate !== null &&
    candidate.artifactId === expected.artifactId &&
    candidate.principalId === expected.principalId &&
    candidate.captureId === expected.captureId &&
    candidate.currentVersion === expected.currentVersion &&
    candidate.currentHash === expected.currentHash &&
    candidate.content === expected.content &&
    Array.isArray(candidate.versions) &&
    Array.isArray(expected.versions) &&
    candidate.versions.length === expected.versions.length &&
    candidate.versions.every((version, index) => {
      const trusted = expected.versions[index];
      return (
        version.version === trusted.version &&
        version.content === trusted.content &&
        version.contentHash === trusted.contentHash &&
        version.baseVersion === trusted.baseVersion &&
        version.baseHash === trusted.baseHash &&
        version.createdAt === trusted.createdAt
      );
    });

  const validateTrustedNextRevision = (candidate, expectedBase, expectedContent) => {
    if (
      candidate === null ||
      candidate.artifactId !== expectedBase.artifactId ||
      candidate.principalId !== expectedBase.principalId ||
      candidate.captureId !== expectedBase.captureId ||
      candidate.currentVersion !== expectedBase.currentVersion + 1 ||
      candidate.versions.length !== expectedBase.versions.length + 1 ||
      !candidate.versions
        .slice(0, expectedBase.versions.length)
        .every((version, index) => {
          const trusted = expectedBase.versions[index];
          return (
            version.version === trusted.version &&
            version.content === trusted.content &&
            version.contentHash === trusted.contentHash &&
            version.baseVersion === trusted.baseVersion &&
            version.baseHash === trusted.baseHash &&
            version.createdAt === trusted.createdAt
          );
        })
    ) {
      return null;
    }
    const head = candidate.versions.at(-1);
    return head.version === expectedBase.currentVersion + 1 &&
      head.content === expectedContent &&
      head.baseVersion === expectedBase.currentVersion &&
      head.baseHash === expectedBase.currentHash &&
      candidate.currentHash === head.contentHash
      ? candidate
      : null;
  };

  const validateNextRevision = async (artifact, expectedBase, expectedContent) =>
    validateTrustedNextRevision(
      await validateArtifactLineage(
        artifact,
        expectedBase.artifactId,
        expectedBase.captureId,
        expectedBase.principalId,
      ),
      expectedBase,
      expectedContent,
    );

  const setArtifactMeta = (version, hash) => {
    if (!outcomeMeta) {
      return;
    }
    outcomeMeta.hidden = false;
    outcomeMeta.textContent = `持久版本 ${version} · SHA-256 ${hash}`;
  };

  const applyRevisionSaved = (lineage, reconciled) => {
    outcomeArtifact = Object.freeze({
      artifactId: lineage.artifactId,
      principalId: lineage.principalId,
      location: outcomeArtifact.location,
      captureId: lineage.captureId,
      currentVersion: lineage.currentVersion,
      currentHash: lineage.currentHash,
      content: lineage.content,
      versions: lineage.versions,
    });
    revisionSaving = false;
    if (outcomeCanvasContent) {
      outcomeCanvasContent.textContent = lineage.content;
      outcomeCanvasContent.setAttribute?.("contenteditable", "true");
      if (outcomeCanvasContent.dataset) {
        outcomeCanvasContent.dataset.state = "REVISION_SAVED";
      }
    }
    if (outcomeSave) {
      outcomeSave.hidden = true;
      outcomeSave.disabled = false;
      outcomeSave.textContent = "保存本地修订";
    }
    if (outcomeConflict) {
      outcomeConflict.hidden = true;
    }
    setArtifactMeta(lineage.currentVersion, lineage.currentHash);
    setOutcomeState(
      "REVISION_SAVED",
      "Outcome Canvas 修订已保存",
      reconciled
        ? `PUT 回执未确认，但只读核对已证明 Artifact v${lineage.currentVersion} 精确提交；没有重复写入。`
        : `Artifact v${lineage.currentVersion} 已通过完整版本链、内容、hash 与 base 链接校验。`,
    );
  };

  const allowExplicitRevisionRetry = () => {
    revisionSaving = false;
    if (outcomeCanvasContent) {
      outcomeCanvasContent.setAttribute?.("contenteditable", "true");
      if (outcomeCanvasContent.dataset) {
        outcomeCanvasContent.dataset.state = "DIRTY";
      }
    }
    if (outcomeSave) {
      outcomeSave.hidden = false;
      outcomeSave.disabled = false;
      outcomeSave.textContent = "明确重试保存";
    }
    setOutcomeState(
      "DIRTY",
      "服务端基线未变化",
      "只读核对确认 Artifact 仍是原版本；页面没有自动重试。可由你明确再次保存。",
    );
  };

  const showRevisionConflict = (head, reportedVersion = null) => {
    revisionSaving = false;
    if (outcomeCanvasContent) {
      outcomeCanvasContent.setAttribute?.("contenteditable", "true");
      if (outcomeCanvasContent.dataset) {
        outcomeCanvasContent.dataset.state = "REVISION_CONFLICT";
      }
    }
    if (outcomeSave) {
      outcomeSave.hidden = true;
      outcomeSave.disabled = true;
    }
    if (outcomeConflict) {
      outcomeConflict.hidden = false;
    }
    if (outcomeConflictMeta) {
      outcomeConflictMeta.textContent = `${
        reportedVersion === null ? "只读核对" : `409 回执版本 ${reportedVersion}`
      } · 服务端当前版本 ${head.currentVersion} · SHA-256 ${head.currentHash}`;
    }
    if (outcomeConflictContent) {
      outcomeConflictContent.textContent = head.content;
    }
    setOutcomeState(
      "REVISION_CONFLICT",
      "修订发生版本冲突",
      "你的本地编辑完整保留；服务端最新内容仅显示在下方，不会覆盖本地草稿。",
    );
  };

  const showRevisionUnknown = (
    message = "PUT 与只读核对都没有形成可信回执；页面不会自动重试，本地内容仍保留。",
  ) => {
    revisionSaving = false;
    if (outcomeCanvasContent) {
      outcomeCanvasContent.setAttribute?.("contenteditable", "true");
      if (outcomeCanvasContent.dataset) {
        outcomeCanvasContent.dataset.state = "REVISION_UNKNOWN";
      }
    }
    if (outcomeSave) {
      outcomeSave.hidden = true;
      outcomeSave.disabled = true;
      outcomeSave.textContent = "修订结果未知";
    }
    setOutcomeState(
      "REVISION_UNKNOWN",
      "无法确认修订结果",
      message,
    );
  };

  const readLatestArtifactLineage = async (expectedBase, controller = null) => {
    const response = await requestOutcomeJson(
      expectedBase.location,
      "GET",
      null,
      controller,
    );
    if (response.status !== 200 || response.cacheControl !== "private, no-store") {
      throw new Error("OUTCOME_REVISION_READBACK_FAILED");
    }
    const lineage = await validateArtifactLineage(
      parseJsonObject(response),
      expectedBase.artifactId,
      expectedBase.captureId,
      expectedBase.principalId,
    );
    if (!lineage) {
      throw new Error("OUTCOME_REVISION_LINEAGE_INVALID");
    }
    return lineage;
  };

  const reconcileRevision = async (attempt, reportedVersion = null) => {
    if (revisionEpoch !== attempt.epoch) {
      return;
    }
    revisionController?.abort?.();
    const reconcileController = new globalThis.AbortController();
    revisionController = reconcileController;
    try {
      const lineage = await readLatestArtifactLineage(attempt.base, reconcileController);
      if (revisionEpoch !== attempt.epoch || reconcileController.signal.aborted) {
        return;
      }
      const committed = validateTrustedNextRevision(
        lineage,
        attempt.base,
        attempt.content,
      );
      if (revisionEpoch !== attempt.epoch || reconcileController.signal.aborted) {
        return;
      }
      if (committed) {
        applyRevisionSaved(committed, true);
      } else if (trustedLineageMatches(lineage, attempt.base)) {
        allowExplicitRevisionRetry();
      } else {
        showRevisionConflict(lineage, reportedVersion);
      }
    } catch (_failure) {
      if (revisionEpoch === attempt.epoch) {
        showRevisionUnknown();
      }
    } finally {
      if (revisionEpoch === attempt.epoch && revisionController === reconcileController) {
        revisionController = null;
      }
    }
  };

  const captureReceipt = async (response, expectedPayload) => {
    try {
      const receipt = await response.json();
      const captureId = typeof receipt?.captureId === "string" ? receipt.captureId.trim() : "";
      const capturedAt =
        typeof receipt?.capturedAt === "string" ? receipt.capturedAt.trim() : "";
      const sourceType =
        typeof receipt?.sourceType === "string" ? receipt.sourceType.trim() : "";
      const dataClass =
        typeof receipt?.dataClass === "string" ? receipt.dataClass.trim() : "";
      const expectedLocation = `${CAPTURE_ENDPOINT}/${captureId}`;
      const location = response.headers?.get?.("location") ?? "";
      const createdLocationMatches = response.status !== 201 || location === expectedLocation;
      if (
        CAPTURE_ID_PATTERN.test(captureId) &&
        CAPTURED_AT_PATTERN.test(capturedAt) &&
        Number.isFinite(Date.parse(capturedAt)) &&
        sourceType === expectedPayload.sourceType &&
        dataClass === expectedPayload.dataClass &&
        createdLocationMatches
      ) {
        return Object.freeze({ captureId, capturedAt, sourceType, dataClass });
      }
    } catch (_failure) {
      // A success status without a valid durable receipt remains unknown.
    }
    return null;
  };

  const submitFrozenAttempt = async () => {
    setFormLocked(true);
    setBusy(true);
    setStatus("SAVING", retryRequired ? "正在核对同一条请求…" : "正在持久保存…");

    if (typeof globalThis.AbortController !== "function") {
      retryRequired = true;
      setFormLocked(true, "UNKNOWN");
      setStatus(
        "UNKNOWN",
        "当前浏览器无法限制请求时长，因此没有提交。请保留内容并更换受支持的浏览器。",
      );
      setBusy(false);
      return;
    }

    const controller = new globalThis.AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(CAPTURE_ENDPOINT, {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: Object.freeze({
          Accept: "application/json",
          "Content-Type": "application/json",
        }),
        body: frozenAttempt.body,
        signal: controller.signal,
      });

      if (response.status === 201 || response.status === 200) {
        const receipt = await captureReceipt(response, frozenAttempt.payload);
        if (!receipt) {
          retryRequired = true;
          setFormLocked(true, "UNKNOWN");
          setStatus(
            "UNKNOWN",
            "服务端没有返回有效的持久回执。请重试同一条请求，或放弃重试后修改内容。",
          );
          return;
        }

        retryRequired = false;
        successfulSnapshot = frozenAttempt.snapshot;
        abandonedUnknownSnapshot = null;
        conflictSnapshot = null;
        explicitNewEntry = false;
        setFormLocked(false, "SUCCESS");
        const replayed = response.status === 200;
        setStatus(
          replayed ? "REPLAYED" : "SAVED",
          `${replayed ? "这条请求已安全重放，没有重复保存。" : "想法已持久保存。"} 回执编号：${receipt.captureId}；捕获时间：${receipt.capturedAt}；来源类型：${receipt.sourceType}；数据级别：${receipt.dataClass}`,
        );
        offerOutcomeCanvas(receipt, frozenAttempt.payload);
      } else if (response.status === 409) {
        conflictSnapshot = frozenAttempt.snapshot;
        retryRequired = false;
        setFormLocked(false);
        setStatus(
          "CONFLICT",
          "该请求标识已绑定到不同内容；本次内容没有获得保存回执。请修改后重新提交。",
        );
      } else if (response.status === 400 || response.status === 415) {
        retryRequired = false;
        setFormLocked(false);
        setStatus("INVALID", "提交内容或格式无效；本次内容没有保存。请检查后重试。");
      } else {
        retryRequired = response.status >= 500;
        setFormLocked(retryRequired, retryRequired ? "UNKNOWN" : null);
        setStatus(
          "UNKNOWN",
          retryRequired
            ? "服务端没有给出可信结果。请重试同一条请求，或放弃重试后继续编辑。"
            : "无法确认这次请求的持久化结果。请先保留当前内容。",
        );
      }
    } catch (_failure) {
      retryRequired = true;
      setFormLocked(true, "UNKNOWN");
      setStatus(
        "UNKNOWN",
        "网络中断，无法确认是否已经保存。请重试同一条请求，或放弃重试后继续编辑。",
      );
    } finally {
      clearTimeout(timeout);
      setBusy(false);
    }
  };

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting) {
      return;
    }

    if (!retryRequired) {
      if (typeof form.reportValidity === "function" && !form.reportValidity()) {
        return;
      }
      const attempt = createFrozenAttempt();
      if (attempt.error) {
        setStatus(attempt.state ?? "INVALID", attempt.error);
        return;
      }
      frozenAttempt = attempt;
    }

    if (!frozenAttempt) {
      setStatus("UNKNOWN", "没有可提交的冻结请求，内容尚未离开页面。");
      return;
    }
    await submitFrozenAttempt();
  });

  abandonRetryButton?.addEventListener("click", () => {
    abandonedUnknownSnapshot = frozenAttempt?.snapshot ?? null;
    retryRequired = false;
    frozenAttempt = null;
    resetOutcomeCanvas();
    setFormLocked(false);
    setBusy(false);
    setStatus("READY", "已放弃未知请求的重试；输入仍保留，可编辑后重新提交。");
    content.focus?.();
  });

  newEntryButton?.addEventListener("click", () => {
    content.value = "";
    sourceRef.value = "";
    sourceChoices.forEach((choice) => {
      choice.checked = choice.value === "TEXT";
    });
    successfulSnapshot = null;
    abandonedUnknownSnapshot = null;
    conflictSnapshot = null;
    frozenAttempt = null;
    retryRequired = false;
    explicitNewEntry = false;
    resetOutcomeCanvas();
    syncSourceFields();
    setFormLocked(false);
    setBusy(false);
    setStatus("READY", "新条目已就绪，可以写下新的想法。");
    content.focus?.();
  });

  outcomeCanvasTrigger?.addEventListener("click", async (event) => {
    event.preventDefault?.();
    if (
      !successfulSnapshot ||
      !outcomeCapture ||
      !outcomeCanvasPreview ||
      outcomeGenerationStarted
    ) {
      return;
    }
    outcomeGenerationStarted = true;
    outcomeGenerationEpoch += 1;
    const generationController = new globalThis.AbortController();
    const generationAttempt = Object.freeze({
      epoch: outcomeGenerationEpoch,
      controller: generationController,
      capture: outcomeCapture,
    });
    outcomeGenerationController = generationController;
    const generationIsCurrent = () =>
      outcomeGenerationEpoch === generationAttempt.epoch &&
      outcomeGenerationController === generationAttempt.controller;
    setOutcomeGenerationLocked(true);
    outcomeCanvasTrigger.disabled = true;
    outcomeCanvasTrigger.textContent = "正在本地显现…";
    outcomeCanvasTrigger.setAttribute?.("aria-expanded", "true");
    outcomeCanvasPreview.hidden = false;
    setOutcomeState(
      "GENERATING",
      "正在准备本地演示草稿",
      "仅运行仓库内的 ScriptedFakeModel；不调用外部模型，也不执行任何行动。",
    );
    outcomeCanvasPreview.focus?.();

    try {
      const drafted = await requestOutcomeJson(
        AGENT_DRAFT_ENDPOINT,
        "POST",
        Object.freeze({ captureId: generationAttempt.capture.captureId, intent: OUTCOME_INTENT }),
        generationAttempt.controller,
      );
      if (!generationIsCurrent()) {
        return;
      }
      if (drafted.status === 422) {
        const failure = parseJsonObject(drafted);
        if (
          drafted.cacheControl === "private, no-store" &&
          validateFailedDraft(failure, drafted.location)
        ) {
          setOutcomeState(
            "GENERATION_FAILED",
            "本地演示生成失败",
            "服务端返回了受控失败；没有 Outcome Canvas，也没有执行任何行动。",
          );
          outcomeCanvasTrigger.textContent = "本地生成失败";
        } else {
          throw new Error("OUTCOME_FAILURE_RECEIPT_INVALID");
        }
        return;
      }
      if (drafted.status !== 201) {
        throw new Error("OUTCOME_GENERATION_UNCONFIRMED");
      }

      if (drafted.cacheControl !== "private, no-store") {
        throw new Error("OUTCOME_DRAFT_CACHE_BOUNDARY_INVALID");
      }
      const draft = parseJsonObject(drafted);
      const draftReceipt = validateDraftReceipt(
        draft,
        drafted.location,
        generationAttempt.capture.captureId,
      );
      if (!draftReceipt) {
        throw new Error("OUTCOME_SUCCESS_RECEIPT_INVALID");
      }

      const [captureResponse, artifactResponse] = await Promise.all([
        requestOutcomeJson(
          `${CAPTURE_ENDPOINT}/${encodeURIComponent(generationAttempt.capture.captureId)}`,
          "GET",
          null,
          generationAttempt.controller,
        ),
        requestOutcomeJson(
          draftReceipt.location,
          "GET",
          null,
          generationAttempt.controller,
        ),
      ]);
      if (!generationIsCurrent()) {
        return;
      }
      if (captureResponse.status !== 200 || artifactResponse.status !== 200) {
        throw new Error("OUTCOME_DURABLE_READBACK_FAILED");
      }
      if (
        captureResponse.cacheControl !== "private, no-store" ||
        artifactResponse.cacheControl !== "private, no-store"
      ) {
        throw new Error("OUTCOME_READBACK_CACHE_BOUNDARY_INVALID");
      }
      const persistedCapture = parseJsonObject(captureResponse);
      const persistedArtifact = parseJsonObject(artifactResponse);
      const artifactLineage = await validateArtifactLineage(
        persistedArtifact,
        draftReceipt.artifactId,
        generationAttempt.capture.captureId,
      );
      if (!generationIsCurrent()) {
        return;
      }
      if (
        !validatePersistedCapture(persistedCapture, generationAttempt.capture) ||
        artifactLineage === null ||
        artifactLineage.currentVersion !== 1
      ) {
        throw new Error("OUTCOME_DURABLE_BINDING_INVALID");
      }

      outcomeArtifact = Object.freeze({
        artifactId: artifactLineage.artifactId,
        principalId: artifactLineage.principalId,
        location: draftReceipt.location,
        captureId: artifactLineage.captureId,
        currentVersion: artifactLineage.currentVersion,
        currentHash: artifactLineage.currentHash,
        content: artifactLineage.content,
        versions: artifactLineage.versions,
      });

      if (outcomeCanvasContent) {
        outcomeCanvasContent.textContent = artifactLineage.content;
        outcomeCanvasContent.hidden = false;
        outcomeCanvasContent.setAttribute?.("contenteditable", "true");
        if (outcomeCanvasContent.dataset) {
          outcomeCanvasContent.dataset.state = "CANVAS_READY";
        }
      }
      if (outcomeSave) {
        outcomeSave.hidden = true;
        outcomeSave.disabled = false;
      }
      if (outcomeConflict) {
        outcomeConflict.hidden = true;
      }
      setArtifactMeta(outcomeArtifact.currentVersion, outcomeArtifact.currentHash);
      setOutcomeState(
        "CANVAS_READY",
        "Outcome Canvas 本地草稿已就绪",
        "已核对 Capture 与 Artifact v1 的持久绑定；内容来自 ScriptedFakeModel，可在本页本地编辑，但尚未保存修订。",
      );
      outcomeCanvasTrigger.textContent = "本地草稿已生成";
      outcomeCanvasContent?.focus?.();
    } catch (_failure) {
      if (!generationIsCurrent()) {
        return;
      }
      setOutcomeState(
        "GENERATION_UNKNOWN",
        "无法确认本地演示结果",
        "请求丢失、超时或回执不完整；页面不会自动重试，也不会发起第二次生成。",
      );
      outcomeCanvasTrigger.textContent = "本次结果未知";
    } finally {
      if (generationIsCurrent()) {
        outcomeGenerationController = null;
        setOutcomeGenerationLocked(false);
      }
    }
  });

  outcomeSave?.addEventListener("click", async (event) => {
    event.preventDefault?.();
    if (
      revisionSaving ||
      !outcomeArtifact ||
      !outcomeCanvasContent ||
      outcomeCanvasPreview?.dataset?.state !== "DIRTY"
    ) {
      return;
    }
    const revisedContent = outcomeCanvasContent.textContent;
    if (typeof revisedContent !== "string" || !revisedContent.trim()) {
      setOutcomeState(
        "DIRTY",
        "本地草稿不能为空",
        "没有发出修订请求；请保留至少一个非空字符。",
      );
      return;
    }

    revisionEpoch += 1;
    const putController = new globalThis.AbortController();
    revisionController = putController;
    const attempt = Object.freeze({
      epoch: revisionEpoch,
      content: revisedContent,
      base: Object.freeze({ ...outcomeArtifact }),
    });
    const revisionIsCurrent = () =>
      revisionEpoch === attempt.epoch &&
      revisionController === putController &&
      !putController.signal.aborted;

    revisionSaving = true;
    outcomeCanvasContent.setAttribute?.("contenteditable", "false");
    if (outcomeCanvasContent.dataset) {
      outcomeCanvasContent.dataset.state = "REVISION_SAVING";
    }
    outcomeSave.disabled = true;
    outcomeSave.textContent = "正在保存本地修订…";
    setOutcomeState(
      "REVISION_SAVING",
      "正在保存 Outcome Canvas 修订",
      "正在以当前持久版本与 hash 作为 compare-and-set 基线；不会自动重复 PUT。",
    );

    let response = null;
    try {
      response = await requestOutcomeJson(
        attempt.base.location,
        "PUT",
        Object.freeze({
          content: attempt.content,
          expectedBaseVersion: attempt.base.currentVersion,
          expectedBaseHash: attempt.base.currentHash,
        }),
        putController,
      );
      if (!revisionIsCurrent()) {
        return;
      }

      if (response.status === 200) {
        let saved = null;
        if (response.cacheControl === "private, no-store") {
          try {
            saved = await validateNextRevision(
              parseJsonObject(response),
              attempt.base,
              attempt.content,
            );
          } catch (_failure) {
            saved = null;
          }
        }
        if (!revisionIsCurrent()) {
          return;
        }
        if (saved) {
          applyRevisionSaved(saved, false);
          return;
        }
        // A malformed affirmative receipt cannot authorize either success or a
        // retry. Do not use a later read to reinterpret this response.
        showRevisionUnknown(
          "服务端返回了 200，但回执的媒体类型、完整版本链或绑定无法精确验证；页面未执行只读核对、不会自动重试，本地内容仍保留。",
        );
        return;
      }

      if (response.status === 409) {
        let reportedVersion = null;
        try {
          const conflictReceipt = parseJsonObject(response);
          if (
            Number.isSafeInteger(conflictReceipt.currentVersion) &&
            conflictReceipt.currentVersion >= 1
          ) {
            reportedVersion = conflictReceipt.currentVersion;
          }
        } catch (_failure) {
          reportedVersion = null;
        }
        await reconcileRevision(attempt, reportedVersion);
        return;
      }
      await reconcileRevision(attempt);
    } catch (failure) {
      if (revisionEpoch === attempt.epoch) {
        if (
          failure instanceof OutcomeResponseError &&
          failure.status === 200 &&
          failure.receiptInvalid
        ) {
          showRevisionUnknown(
            "服务端返回了 200，但响应在有界读取或回执校验前失败；页面未执行只读核对、不会自动重试，本地内容仍保留。",
          );
        } else {
          await reconcileRevision(attempt);
        }
      }
    } finally {
      if (revisionEpoch === attempt.epoch && revisionController === putController) {
        revisionController = null;
      }
    }
  });

  resetOutcomeCanvas();
  syncSourceFields();
})();
