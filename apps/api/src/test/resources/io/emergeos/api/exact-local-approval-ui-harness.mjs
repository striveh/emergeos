import { createHash, webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const [
  htmlPath,
  scriptManifestPath,
  baseUrlArgument,
  expectedCaptureId,
  captureNonce,
  approvalNonce,
  captureContent,
  mode = "created",
] = process.argv.slice(2);

if (
  !htmlPath ||
  !scriptManifestPath ||
  !baseUrlArgument ||
  !expectedCaptureId ||
  !captureNonce ||
  !approvalNonce ||
  !captureContent
) {
  throw new Error("page, script, loopback URL, Capture identity, nonces, and content are required");
}

const nodeMajor = Number.parseInt(process.versions.node.split(".")[0], 10);
if (nodeMajor !== 22 || typeof globalThis.fetch !== "function") {
  throw new Error("Node 22 global fetch is required");
}

const appBaseUrl = new URL(baseUrlArgument);
if (
  appBaseUrl.protocol !== "http:" ||
  appBaseUrl.hostname !== "127.0.0.1" ||
  appBaseUrl.username ||
  appBaseUrl.password ||
  appBaseUrl.search ||
  appBaseUrl.hash
) {
  throw new Error("app base URL must be an uncredentialed 127.0.0.1 HTTP URL");
}

const html = await readFile(htmlPath, "utf8");
const scriptPaths = (await readFile(scriptManifestPath, "utf8"))
  .split(/\r?\n/)
  .map((value) => value.trim())
  .filter(Boolean);
if (scriptPaths.length === 0) {
  throw new Error("at least one same-origin served script is required");
}
const servedScripts = await Promise.all(scriptPaths.map((path) => readFile(path, "utf8")));
const nativeFetch = globalThis.fetch;
const listeners = new Map();
const documentListeners = new Map();
let ephemeralId = 0;
let activeElement = null;

class MinimalEvent {
  constructor(type, init = {}) {
    if (typeof type !== "string" || type.length === 0) {
      throw new TypeError("event type must be a non-empty string");
    }
    this.type = type;
    this.bubbles = Boolean(init.bubbles);
    this.cancelable = Boolean(init.cancelable);
    this.composed = Boolean(init.composed);
    this.defaultPrevented = false;
    this.target = null;
    this.currentTarget = null;
  }

  preventDefault() {
    if (this.cancelable) this.defaultPrevented = true;
  }
}

class MinimalCustomEvent extends MinimalEvent {
  constructor(type, init = {}) {
    super(type, init);
    this.detail = init.detail ?? null;
  }
}

class MinimalEventTarget {
  constructor(registry = new Map()) {
    this.registry = registry;
  }

  addEventListener(type, listener) {
    if (typeof listener !== "function") return;
    const registered = this.registry.get(type) ?? [];
    registered.push(listener);
    this.registry.set(type, registered);
  }

  removeEventListener(type, listener) {
    const registered = this.registry.get(type) ?? [];
    this.registry.set(
      type,
      registered.filter((candidate) => candidate !== listener),
    );
  }

  dispatchEvent(event, target = this) {
    if (!event || typeof event.type !== "string") {
      throw new TypeError("dispatchEvent requires an Event");
    }
    event.target = target;
    event.currentTarget = target;
    for (const listener of [...(this.registry.get(event.type) ?? [])]) {
      listener.call(target, event);
    }
    event.currentTarget = null;
    return !event.defaultPrevented;
  }
}

const documentEventTarget = new MinimalEventTarget(documentListeners);

const attribute = (source, name) => {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return source.match(new RegExp(`\\b${escaped}\\s*=\\s*(["'])(.*?)\\1`, "i"))?.[2] ?? null;
};

const visibleText = (source) =>
  source.replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/\s+/g, " ").trim();

const makeElement = (id, initial = {}) => ({
  id,
  tagName: "DIV",
  className: "",
  hidden: false,
  required: false,
  disabled: false,
  checked: false,
  value: "",
  textContent: "",
  dataset: {},
  attributes: {},
  focusCount: 0,
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === "hidden") this.hidden = true;
    if (name === "disabled") this.disabled = true;
    if (name.startsWith("data-")) {
      const key = name
        .slice(5)
        .replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase());
      this.dataset[key] = String(value);
    }
  },
  getAttribute(name) {
    if (name.startsWith("data-")) {
      const key = name
        .slice(5)
        .replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase());
      return this.dataset[key] ?? null;
    }
    return this.attributes[name] ?? null;
  },
  removeAttribute(name) {
    delete this.attributes[name];
    if (name === "hidden") this.hidden = false;
    if (name === "disabled") this.disabled = false;
  },
  addEventListener(type, listener) {
    const key = `${id}:${type}`;
    const registered = listeners.get(key) ?? [];
    registered.push(listener);
    listeners.set(key, registered);
  },
  removeEventListener(type, listener) {
    const key = `${id}:${type}`;
    listeners.set(
      key,
      (listeners.get(key) ?? []).filter((candidate) => candidate !== listener),
    );
  },
  dispatchEvent(event) {
    if (!event || typeof event.type !== "string") {
      throw new TypeError("dispatchEvent requires an Event");
    }
    event.target = this;
    event.currentTarget = this;
    for (const listener of [...(listeners.get(`${id}:${event.type}`) ?? [])]) {
      listener.call(this, event);
    }
    event.currentTarget = null;
    return !event.defaultPrevented;
  },
  append(...children) {
    this.textContent = children.map((child) => child?.textContent ?? "").join(" ").trim();
  },
  focus() {
    this.focusCount += 1;
    activeElement = this;
  },
  ...initial,
});

const elements = new Map();
for (const match of html.matchAll(/<([a-z][a-z0-9-]*)\b([^>]*\bid\s*=\s*(["'])([^"']+)\3[^>]*)>/gi)) {
  const tagName = match[1].toUpperCase();
  const attrs = match[2];
  const id = match[4];
  const closing = html.match(
    new RegExp(`<${match[1]}\\b[^>]*\\bid=["']${id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}["'][^>]*>([\\s\\S]*?)<\\/${match[1]}>`, "i"),
  );
  const dataState = attribute(attrs, "data-state");
  const element = makeElement(id, {
    tagName,
    className: attribute(attrs, "class") ?? "",
    hidden: /(?:^|\s)hidden(?:\s|$|=)/i.test(attrs),
    disabled: /(?:^|\s)disabled(?:\s|$|=)/i.test(attrs),
    textContent: visibleText(closing?.[1] ?? ""),
  });
  if (dataState !== null) element.dataset.state = dataState;
  for (const name of ["role", "aria-live", "aria-label", "contenteditable", "tabindex"]) {
    const value = attribute(attrs, name);
    if (value !== null) element.attributes[name] = value;
  }
  elements.set(id, element);
}

const content = elements.get("capture-content") ?? makeElement("capture-content");
content.value = captureContent;
const sourceRef = elements.get("source-ref") ?? makeElement("source-ref");
sourceRef.value = "";
const textChoice = elements.get("source-text") ?? makeElement("source-text");
textChoice.value = "TEXT";
textChoice.checked = true;
const linkChoice = elements.get("source-link") ?? makeElement("source-link");
linkChoice.value = "LINK";
linkChoice.checked = false;
const form = elements.get("quick-capture-form") ?? makeElement("quick-capture-form");
form.elements = {
  content,
  sourceType: { value: "TEXT" },
  sourceRef,
};
form.reportValidity = () => true;
elements.set("capture-content", content);
elements.set("source-ref", sourceRef);
elements.set("source-text", textChoice);
elements.set("source-link", linkChoice);
elements.set("quick-capture-form", form);

const document = {
  get activeElement() {
    return activeElement;
  },
  querySelector(selector) {
    if (selector === 'input[name="sourceType"]:checked') {
      return textChoice.checked ? textChoice : linkChoice;
    }
    return selector.startsWith("#") ? elements.get(selector.slice(1)) ?? null : null;
  },
  querySelectorAll(selector) {
    return selector === 'input[name="sourceType"]' ? [textChoice, linkChoice] : [];
  },
  createElement(tagName) {
    return makeElement(`ephemeral-${++ephemeralId}`, { tagName: tagName.toUpperCase() });
  },
  addEventListener(type, listener) {
    documentEventTarget.addEventListener(type, listener);
  },
  removeEventListener(type, listener) {
    documentEventTarget.removeEventListener(type, listener);
  },
  dispatchEvent(event) {
    return documentEventTarget.dispatchEvent(event, document);
  },
};

let uuidCalls = 0;
const cryptoFacade = new Proxy(webcrypto, {
  get(target, property) {
    if (property === "randomUUID") {
      return () => {
        uuidCalls += 1;
        return uuidCalls === 1 ? captureNonce : approvalNonce;
      };
    }
    const value = Reflect.get(target, property, target);
    return typeof value === "function" ? value.bind(target) : value;
  },
});

let captureReplayStatus = 0;
let captureIdMatch = 0;
let outcomeTriggerDispatched = 0;
let artifact = null;
let artifactPath = null;
let approvalPostCount = 0;
let approvalStatus = 0;
let approvalRequestExact = 1;
let approvalRequestStable = 1;
let firstApprovalBody = null;
let approvalResponseStrict = 1;
let approvalFetchOptionsExact = 1;
let hangingSignalAborted = 0;
let approvalPostCountBeforeExplicitRetry = 0;
let outcomeFocusRetained = 0;
let outcomeContinuousInputExact = 0;
let approvalInvalidationNonAssertive = 0;
let actualProblemMimeExact = 0;
let actualProblemNoStore = 0;
let actualProblemTypeExact = 0;
let forbiddenActionCalls = 0;
let reconcileCalls = 0;
let dropFirstApprovalResponse = mode === "response-loss";
let accelerateApprovalTimeout = false;

const exactApprovalResponse = async (response, request) => {
  try {
    const body = await response.clone().json();
    const location = response.headers.get("location");
    const cacheControl = response.headers.get("cache-control");
    const contentType = response.headers.get("content-type") ?? "";
    const transition = body?.transitions?.[0];
    return (
      [200, 201].includes(response.status) &&
      cacheControl === "private, no-store" &&
      /^application\/json(?:\s*;|$)/i.test(contentType) &&
      typeof body?.attemptId === "string" &&
      body.attemptId.length > 0 &&
      location === `/api/v1/action-approvals/${body.attemptId}` &&
      body?.status === "PLANNED" &&
      body?.plan?.artifactId === artifact?.artifactId &&
      body?.plan?.artifactVersion === artifact?.currentVersion &&
      body?.plan?.artifactHash === artifact?.currentHash &&
      body?.plan?.idempotencyKey === request.approvalNonce &&
      typeof body?.plan?.planId === "string" &&
      /^[0-9a-f]{64}$/.test(body?.plan?.planHash ?? "") &&
      body?.approval?.decision === "APPROVED" &&
      typeof body?.approval?.actor === "string" &&
      body.approval.actor.length > 0 &&
      body?.approval?.planId === body?.plan?.planId &&
      body?.approval?.planHash === body?.plan?.planHash &&
      body?.approval?.artifactHash === artifact?.currentHash &&
      body?.capability?.usedCalls === 0 &&
      Array.isArray(body?.transitions) &&
      body.transitions.length === 1 &&
      transition?.sequence === 1 &&
      transition?.fromStatus === null &&
      transition?.toStatus === "PLANNED" &&
      body?.receipt === null
    );
  } catch (_failure) {
    return false;
  }
};

const syntheticProblemResponse = () => {
  const problem = {
    type: "urn:emergeos:problem:approval-stale",
    title: "Approval stale",
    status: 412,
    detail: "The exact approved Artifact is no longer current.",
  };
  const headers = {
    "Content-Type": "application/problem+json",
    "Cache-Control": "private, no-store",
  };
  if (mode === "problem-wrong-mime") {
    headers["Content-Type"] = "application/json";
  }
  if (mode === "problem-missing-no-store") {
    delete headers["Cache-Control"];
  }
  if (mode === "problem-oversize") {
    headers["Content-Length"] = "262145";
  }
  if (mode === "problem-wrong-type") {
    problem.type = "urn:emergeos:problem:action-idempotency-conflict";
  }
  return new Response(JSON.stringify(problem), { status: 412, headers });
};

const captureActualProblemContract = async (response) => {
  if (!["stale-409", "nonce-conflict-409"].includes(mode)) return;
  actualProblemMimeExact = /^application\/problem\+json(?:\s*;|$)/i.test(
    response.headers.get("content-type") ?? "",
  )
    ? 1
    : 0;
  actualProblemNoStore = response.headers.get("cache-control") === "private, no-store" ? 1 : 0;
  try {
    const problem = await response.clone().json();
    const expectedType =
      mode === "stale-409"
        ? "urn:emergeos:problem:409"
        : "urn:emergeos:problem:action-idempotency-conflict";
    actualProblemTypeExact =
      response.status === 409 && problem?.status === 409 && problem?.type === expectedType ? 1 : 0;
  } catch (_failure) {
    actualProblemTypeExact = 0;
  }
};

const wrappedFetch = async (input, options = {}) => {
  if (typeof input !== "string" || !input.startsWith("/")) {
    throw new Error("served UI fetch must stay same-origin and relative");
  }
  const method = (options.method ?? "GET").toUpperCase();
  if (/^\/api\/v1\/artifacts\/[^/]+\/actions$/.test(input)) {
    forbiddenActionCalls += 1;
  }
  if (/^\/api\/v1\/actions\//.test(input)) {
    forbiddenActionCalls += 1;
    if (/\/reconcile$/.test(input)) reconcileCalls += 1;
  }

  if (/^\/api\/v1\/artifacts\/[^/]+\/action-approvals$/.test(input) && method === "POST") {
    approvalPostCount += 1;
    const bodyText = typeof options.body === "string" ? options.body : "";
    if (firstApprovalBody === null) {
      firstApprovalBody = bodyText;
    } else if (bodyText !== firstApprovalBody) {
      approvalRequestStable = 0;
    }
    let request = null;
    try {
      request = JSON.parse(bodyText);
    } catch (_failure) {
      approvalRequestExact = 0;
    }
    if (
      !request ||
      Object.keys(request).sort().join(",") !==
        "approvalNonce,approvedArtifactHash,approvedArtifactVersion" ||
      request.approvedArtifactVersion !== artifact?.currentVersion ||
      request.approvedArtifactHash !== artifact?.currentHash ||
      request.approvalNonce !== approvalNonce
    ) {
      approvalRequestExact = 0;
    }

    if (
      options.credentials !== "same-origin" ||
      options.cache !== "no-store" ||
      options.redirect !== "error" ||
      !options.signal ||
      typeof options.signal.addEventListener !== "function"
    ) {
      approvalFetchOptionsExact = 0;
    }

    if (mode === "hanging-fetch" && approvalPostCount === 1) {
      return new Promise((_resolve, reject) => {
        const rejectAborted = () => {
          hangingSignalAborted = options.signal?.aborted ? 1 : 0;
          reject(options.signal?.reason ?? new DOMException("synthetic approval timeout", "AbortError"));
        };
        if (options.signal?.aborted) {
          rejectAborted();
          return;
        }
        options.signal?.addEventListener("abort", rejectAborted, { once: true });
      });
    }

    if (
      [
        "strict-stale-412",
        "problem-wrong-mime",
        "problem-missing-no-store",
        "problem-oversize",
        "problem-wrong-type",
      ].includes(mode)
    ) {
      approvalStatus = 412;
      approvalResponseStrict = 0;
      return syntheticProblemResponse();
    }

    if (mode === "malformed-201") {
      approvalStatus = 201;
      approvalResponseStrict = 0;
      return new Response("{}", {
        status: 201,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "private, no-store",
          Location: "/api/v1/action-approvals/untrusted",
        },
      });
    }

    const response = await nativeFetch(new URL(input, appBaseUrl), options);
    approvalStatus = response.status;
    await captureActualProblemContract(response);
    if (!(await exactApprovalResponse(response, request))) {
      approvalResponseStrict = 0;
    }
    if (dropFirstApprovalResponse) {
      dropFirstApprovalResponse = false;
      throw new TypeError("synthetic committed response loss");
    }
    return response;
  }

  const response = await nativeFetch(new URL(input, appBaseUrl), options);
  if (input === "/api/v1/captures" && method === "POST") {
    captureReplayStatus = response.status;
    try {
      const receipt = await response.clone().json();
      captureIdMatch = receipt?.captureId === expectedCaptureId ? 1 : 0;
    } catch (_failure) {
      captureIdMatch = 0;
    }
  }
  if (/^\/api\/v1\/artifacts\/[^/]+$/.test(input) && method === "GET") {
    try {
      const candidate = await response.clone().json();
      if (candidate?.captureId === expectedCaptureId) {
        artifact = candidate;
        artifactPath = input;
      }
    } catch (_failure) {
      // The actual UI must reject an invalid Artifact response.
    }
  }
  return response;
};

const contextSetTimeout = (callback, delay, ...args) =>
  setTimeout(
    callback,
    accelerateApprovalTimeout && Number(delay) >= 10000 ? 25 : delay,
    ...args,
  );

const context = vm.createContext({
  document,
  crypto: cryptoFacade,
  fetch: wrappedFetch,
  FormData: class FormData {},
  AbortController,
  Response,
  Headers,
  Request,
  TextDecoder,
  TextEncoder,
  URL,
  DOMException,
  Event: MinimalEvent,
  CustomEvent: MinimalCustomEvent,
  EventTarget: MinimalEventTarget,
  setTimeout: contextSetTimeout,
  clearTimeout,
  console,
});

let scriptExecuted = 0;
try {
  for (let index = 0; index < servedScripts.length; index += 1) {
    vm.runInContext(servedScripts[index], context, {
      filename: `served-script-${index + 1}.js`,
    });
  }
  scriptExecuted = 1;
} catch (failure) {
  throw new Error(`actual served script failed: ${failure?.stack ?? failure}`);
}

const fire = async (element, type) => {
  if (!element) return 0;
  const registered = [...(listeners.get(`${element.id}:${type}`) ?? [])];
  if (registered.length === 0) return 0;
  for (const listener of registered) {
    await listener({
      target: element,
      preventDefault() {},
    });
  }
  return 1;
};

const waitFor = async (predicate, marker, milliseconds = 7000) => {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(marker);
};

await fire(form, "submit");
await waitFor(
  () => ["SAVED", "REPLAYED"].includes(elements.get("capture-status")?.dataset?.state),
  "Capture did not reach a durable replay state",
);

const outcomeTrigger = elements.get("capture-emerge");
outcomeTriggerDispatched = await fire(outcomeTrigger, "click");
await waitFor(
  () => elements.get("outcome-canvas")?.dataset?.state === "CANVAS_READY",
  "Outcome Canvas did not reach CANVAS_READY",
);

const artifactHead = artifact?.versions?.[artifact.currentVersion - 1];
const artifactCurrentExact =
  artifact &&
  artifact.currentVersion >= 1 &&
  artifact.currentHash === artifactHead?.contentHash &&
  artifact.currentHash === createHash("sha256").update(artifactHead?.content ?? "").digest("hex")
    ? 1
    : 0;

const approvalElements = () =>
  [...elements.values()].filter((element) => /approval|approve/i.test(element.id));
const approvalButtons = () =>
  [...elements.values()].filter(
    (element) =>
      element.tagName === "BUTTON" &&
      (/approval|approve/i.test(element.id) || /批准/.test(element.textContent)),
  );
const approvalTrigger = () =>
  approvalButtons().find((element) => /记录本次批准/.test(element.textContent)) ??
  approvalButtons()[0] ??
  null;
const approvalCopy = () =>
  approvalElements()
    .map((element) => element.textContent)
    .filter(Boolean)
    .join(" | ")
    .replace(/\s+/g, " ");
const approvalState = () =>
  approvalElements().map((element) => element.dataset?.state).find(Boolean) ?? "";

const trigger = approvalTrigger();
const approvalTriggerPresent = trigger ? 1 : 0;
const readyCopy = approvalCopy();
const approvalMarkupPosition = trigger ? html.indexOf(`id="${trigger.id}"`) : -1;
const outcomeStart = html.indexOf('id="outcome-canvas"');
const outcomeEnd = outcomeStart < 0 ? -1 : html.indexOf("</section>", outcomeStart);
const savePosition = html.indexOf('id="outcome-save"');
const triggerMarkup = trigger
  ? html.match(new RegExp(`<button\\b[^>]*\\bid=["']${trigger.id}["'][^>]*>`, "i"))?.[0] ?? ""
  : "";
const cardStructureExact =
  trigger &&
  approvalMarkupPosition > savePosition &&
  approvalMarkupPosition > outcomeStart &&
  (outcomeEnd < 0 || approvalMarkupPosition < outcomeEnd) &&
  !/role\s*=\s*["']dialog["']/i.test(triggerMarkup)
    ? 1
    : 0;
const cardTargetRiskExact =
  readyCopy.includes("Local Draftbox") &&
  readyCopy.includes("REVERSIBLE") &&
  readyCopy.includes("可撤销")
    ? 1
    : 0;
const cardArtifactExact =
  artifact && readyCopy.includes(`v${artifact.currentVersion}`) && readyCopy.includes(artifact.currentHash)
    ? 1
    : 0;
const cardBoundaryExact =
  readyCopy.includes("批准只记录授权，尚未执行，不产生Receipt") &&
  readyCopy.includes("当前为本机单用户原型，未验证真实身份")
    ? 1
    : 0;

let approvalClickDispatched = 0;
let explicitSameNonceRetry = 0;
let approvalInvalidated = 0;
let uuidCallsBeforeApproval = uuidCalls;

const directApproval = async () => {
  const response = await nativeFetch(new URL(`/api/v1/artifacts/${artifact.artifactId}/action-approvals`, appBaseUrl), {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({
      approvedArtifactVersion: artifact.currentVersion,
      approvedArtifactHash: artifact.currentHash,
      approvalNonce,
    }),
  });
  if (response.status !== 201 || !(await exactApprovalResponse(response, {
    approvedArtifactVersion: artifact.currentVersion,
    approvedArtifactHash: artifact.currentHash,
    approvalNonce,
  }))) {
    throw new Error("replay scenario could not seed one exact PLANNED approval");
  }
};

if (trigger) {
  if (mode === "artifact-edit") {
    const outcomeContent = elements.get("outcome-canvas-content");
    const originalContent = outcomeContent.textContent;
    outcomeContent.focus();
    outcomeContent.textContent += "甲";
    await fire(outcomeContent, "input");
    outcomeFocusRetained = document.activeElement === outcomeContent ? 1 : 0;
    outcomeContent.textContent += "乙";
    await fire(outcomeContent, "input");
    outcomeContinuousInputExact = outcomeContent.textContent === `${originalContent}甲乙` ? 1 : 0;
    const invalidationStatus = elements.get("approval-status");
    approvalInvalidationNonAssertive =
      invalidationStatus?.getAttribute("role") === "status" &&
      invalidationStatus?.getAttribute("aria-live") === "polite"
        ? 1
        : 0;
    const enabledOldApproval = approvalButtons().some((button) => !button.hidden && !button.disabled);
    approvalInvalidated =
      !enabledOldApproval &&
      (approvalElements().every((element) => element.hidden) ||
        /INVALIDATED|DIRTY/.test(approvalState()) ||
        /失效|重新保存/.test(approvalCopy()))
        ? 1
        : 0;
  } else {
    if (mode === "replay") {
      await directApproval();
    }
    if (mode === "nonce-conflict-409") {
      await directApproval();
      const revised = await nativeFetch(new URL(artifactPath, appBaseUrl), {
        method: "PUT",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({
          content: `${artifactHead.content} nonce-conflict durable revision`,
          expectedBaseVersion: artifact.currentVersion,
          expectedBaseHash: artifact.currentHash,
        }),
      });
      if (revised.status !== 200) throw new Error("nonce-conflict revision did not commit");
      artifact = await revised.json();
      document.dispatchEvent(
        new MinimalCustomEvent("emergeos:trusted-artifact", {
          detail: Object.freeze({
            artifactId: artifact.artifactId,
            artifactVersion: artifact.currentVersion,
            artifactHash: artifact.currentHash,
          }),
        }),
      );
    }
    if (mode === "stale-409") {
      const revisedContent = `${artifactHead.content} competing durable revision`;
      const revised = await nativeFetch(new URL(artifactPath, appBaseUrl), {
        method: "PUT",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({
          content: revisedContent,
          expectedBaseVersion: artifact.currentVersion,
          expectedBaseHash: artifact.currentHash,
        }),
      });
      if (revised.status !== 200) throw new Error("stale scenario revision did not commit");
    }

    if (mode === "hanging-fetch") {
      accelerateApprovalTimeout = true;
    }
    if (mode === "missing-abort-controller") {
      context.AbortController = undefined;
    }

    if (mode === "double-click") {
      const first = fire(trigger, "click");
      const second = fire(trigger, "click");
      approvalClickDispatched = 1;
      await Promise.all([first, second]);
    } else {
      approvalClickDispatched = await fire(trigger, "click");
    }

    if (["response-loss", "hanging-fetch"].includes(mode)) {
      await new Promise((resolve) => setTimeout(resolve, 50));
      approvalPostCountBeforeExplicitRetry = approvalPostCount;
      const retry = approvalButtons().find(
        (button) => !button.hidden && !button.disabled && /重试/.test(button.textContent),
      );
      explicitSameNonceRetry = retry && /同一/.test(`${retry.textContent} ${approvalCopy()}`) ? 1 : 0;
      if (retry) {
        accelerateApprovalTimeout = false;
        await fire(retry, "click");
      }
    }
  }
}

const finalCopy = approvalCopy();
const finalState = approvalState();
const approvalPlannedRendered =
  finalCopy.includes("批准已记录") &&
  finalCopy.includes("状态 PLANNED") &&
  finalCopy.includes("尚未执行") &&
  /Receipt[：:]无/.test(finalCopy) &&
  !/已完成|执行成功/.test(finalCopy)
    ? 1
    : 0;
const approvalStaleRendered =
  /STALE|INVALIDATED|CONFLICT/.test(finalState) || /版本已变化|已失效|已过期/.test(finalCopy)
    ? 1
    : 0;
const approvalUnknownRendered =
  /UNKNOWN/.test(finalState) || /无法确认|结果未知/.test(finalCopy) ? 1 : 0;
const completionClaimRendered = /已完成|行动完成|执行成功|已产生Receipt/.test(finalCopy) ? 1 : 0;
const approvalNonceOnClick =
  trigger && mode !== "artifact-edit" && uuidCallsBeforeApproval === 1 && uuidCalls === 2
    ? 1
    : 0;

const output = {
  NODE_MAJOR: nodeMajor,
  SCRIPT_EXECUTED: scriptExecuted,
  CAPTURE_REPLAY_STATUS: captureReplayStatus,
  CAPTURE_ID_MATCH: captureIdMatch,
  OUTCOME_TRIGGER_DISPATCHED: outcomeTriggerDispatched,
  CANVAS_STATE: elements.get("outcome-canvas")?.dataset?.state ?? "MISSING",
  ARTIFACT_CURRENT_EXACT: artifactCurrentExact,
  APPROVAL_TRIGGER_PRESENT: approvalTriggerPresent,
  CARD_STRUCTURE_EXACT: cardStructureExact,
  CARD_TARGET_RISK_EXACT: cardTargetRiskExact,
  CARD_ARTIFACT_EXACT: cardArtifactExact,
  CARD_BOUNDARY_EXACT: cardBoundaryExact,
  APPROVAL_CLICK_DISPATCHED: approvalClickDispatched,
  APPROVAL_POST_COUNT: approvalPostCount,
  APPROVAL_REQUEST_EXACT: approvalRequestExact,
  APPROVAL_REQUEST_STABLE: approvalRequestStable,
  APPROVAL_FETCH_OPTIONS_EXACT: approvalFetchOptionsExact,
  APPROVAL_NONCE_ON_CLICK: approvalNonceOnClick,
  APPROVAL_STATUS: approvalStatus,
  APPROVAL_RESPONSE_STRICT: approvalPostCount === 0 ? 0 : approvalResponseStrict,
  APPROVAL_PLANNED_RENDERED: approvalPlannedRendered,
  EXPLICIT_SAME_NONCE_RETRY: explicitSameNonceRetry,
  APPROVAL_POST_COUNT_BEFORE_EXPLICIT_RETRY: approvalPostCountBeforeExplicitRetry,
  HANGING_SIGNAL_ABORTED: hangingSignalAborted,
  APPROVAL_INVALIDATED: approvalInvalidated,
  OUTCOME_FOCUS_RETAINED: outcomeFocusRetained,
  OUTCOME_CONTINUOUS_INPUT_EXACT: outcomeContinuousInputExact,
  APPROVAL_INVALIDATION_NON_ASSERTIVE: approvalInvalidationNonAssertive,
  APPROVAL_STALE_RENDERED: approvalStaleRendered,
  APPROVAL_UNKNOWN_RENDERED: approvalUnknownRendered,
  ACTUAL_PROBLEM_MIME_EXACT: actualProblemMimeExact,
  ACTUAL_PROBLEM_NO_STORE: actualProblemNoStore,
  ACTUAL_PROBLEM_TYPE_EXACT: actualProblemTypeExact,
  FORBIDDEN_ACTION_CALLS: forbiddenActionCalls,
  RECONCILE_CALLS: reconcileCalls,
  COMPLETION_CLAIM_RENDERED: completionClaimRendered,
};

for (const [key, value] of Object.entries(output)) {
  process.stdout.write(`${key}=${value}\n`);
}
