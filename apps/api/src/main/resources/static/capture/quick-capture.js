(() => {
  const CAPTURE_ENDPOINT = "/api/v1/captures";
  const PERSONAL_DATA_CLASS = "PERSONAL";

  const form = document.querySelector("#quick-capture-form");
  const linkField = document.querySelector("#link-source-field");
  const sourceRef = document.querySelector("#source-ref");
  const content = document.querySelector("#capture-content");
  const status = document.querySelector("#capture-status");
  const submitButton = document.querySelector("#capture-submit");
  const abandonRetryButton = document.querySelector("#capture-abandon-retry");
  const newEntryButton = document.querySelector("#capture-new-entry");
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

  const markEditable = () => {
    if (submitting || retryRequired) {
      return;
    }
    frozenAttempt = null;
    explicitNewEntry = false;
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
  const CAPTURED_AT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

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
    syncSourceFields();
    setFormLocked(false);
    setBusy(false);
    setStatus("READY", "新条目已就绪，可以写下新的想法。");
    content.focus?.();
  });

  syncSourceFields();
})();
