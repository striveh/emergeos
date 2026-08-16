import { createHash, webcrypto } from "node:crypto";
import { AsyncLocalStorage } from "node:async_hooks";
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
  undoNonceArgument,
] = process.argv.slice(2);

const realLocalDraftboxMode = mode.startsWith("real-");
const logicalUndoMode = mode.startsWith("real-undo-");
const undoNonce = undoNonceArgument ?? "33333333-3333-4333-8333-000000000000";
const previewDriftField = mode.startsWith("real-preview-")
  ? mode.slice("real-preview-".length, -"-drift".length)
  : null;

if (
  !htmlPath ||
  !scriptManifestPath ||
  !baseUrlArgument ||
  !expectedCaptureId ||
  !captureNonce ||
  !approvalNonce ||
  !captureContent ||
  (logicalUndoMode && !undoNonceArgument)
) {
  throw new Error(
    "page, script, loopback URL, Capture identity, capture/approval/undo nonces, and content are required",
  );
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
  for (const name of [
    "role",
    "aria-live",
    "aria-label",
    "contenteditable",
    "tabindex",
    "data-context",
  ]) {
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
        if (uuidCalls === 1) return captureNonce;
        if (uuidCalls === 2) return approvalNonce;
        return logicalUndoMode ? undoNonce : approvalNonce;
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
let approvalScopePreview = null;
let approvalPostCount = 0;
let approvalStatus = 0;
let approvalRequestExact = 1;
let approvalRequestStable = 1;
let firstApprovalBody = null;
let approvalResponseStrict = 1;
let approvalFetchOptionsExact = 1;
let approvalAttempt = null;
let hangingSignalAborted = 0;
let approvalPostCountBeforeExplicitRetry = 0;
let outcomeFocusRetained = 0;
let outcomeContinuousInputExact = 0;
let approvalInvalidationNonAssertive = 0;
let actualProblemMimeExact = 0;
let actualProblemNoStore = 0;
let actualProblemTypeExact = 0;
let forbiddenActionCalls = 0;
let legacyActionCalls = 0;
let reconcileCalls = 0;
let providerSurfaceCalls = 0;
let executePostCount = 0;
let executePostCountBeforeSecondGesture = 0;
let executeGesturePresent = 0;
let executeClickDispatched = 0;
let executeRequestExact = 0;
let executeFetchOptionsExact = 0;
let executeStatus = 0;
let executeResponseStrict = 0;
let approvalReady = 0;
let approvalViaUi = 0;
let plannedFixtureUsedForRedLocalization = 0;
let executePostDuringSecondGesture = 0;
let unexpectedExecutePostCount = 0;
let executeSubmittingStateObserved = 0;
let quietBeforeSecondGesture = 0;
let quietAfterUnknown = 0;
let recoveryGetCount = 0;
let recoveryGetDuringExplicitGesture = 0;
let recoveryCanonicalExact = 0;
let recoveryGesturePresent = 0;
let recoveryClickDispatched = 0;
let executeCanonicalBodyText = null;
let firstExecuteRequestBody = null;
let replayCanonicalExact = 0;
let getCanonicalExact = 0;
let executeInvalidatedAfterEdit = 0;
let previewDriftHashRecomputed = 0;
let previewDriftUnknown = 0;
let terminalWaitReached = 0;
let executeTypedStaleProblem = 0;
let executeMalformedInjected = 0;
let executeUnknownBeforeRecovery = 0;
let timerCanaryObserved = 0;
let timerInheritedSecondGesture = 0;
let executingEditTriggered = 0;
let executingEditHistoricalUnknown = 0;
let executingEditGetOnly = 0;
let executingEditOldScopePostBlocked = 0;
let executingEditSameAttemptRecovery = 0;
let executingEditRecoveredSucceeded = 0;
let executingEditSaveClickDispatched = 0;
let executingEditV2BoundaryExact = 0;
let executingEditV2ScopeRecoveryIsolated = 0;
let executingEditV2HandlersForced = 0;
let executingEditV2ForcedHandlersNoPost = 0;
let executingEditSecondEditDispatched = 0;
let executingEditSecondDirtyRecoveryRetained = 0;
let executingEditSecondDirtyHandlersForced = 0;
let executingEditSecondDirtyForcedHandlersNoPost = 0;
let executingEditPostCountsStable = 0;
let executingEditNoHandleOverwrite = 0;
let readyRecoveryExecutingEditTriggered = 0;
let readyRecoveryHistoricalUnknown = 0;
let readyRecoverySaveClickDispatched = 0;
let readyRecoveryV2BoundaryExact = 0;
let readyRecoveryV2Isolated = 0;
let readyRecoveryPreRecoverySettled = 0;
let readyRecoveryPhaseCompleted = 0;
let readyRecoverySameOldAttemptGet = 0;
let readyRecoveryHistoricalResultExact = 0;
let readyRecoveryCurrentApprovalReopened = 0;
let readyRecoveryNoAutomaticPosts = 0;
let readyRecoveryTrigger = null;
let artifactRevisionPutCount = 0;
let terminalEditInputDispatched = 0;
let terminalSaveClickDispatched = 0;
let terminalNewArtifactSaved = 0;
let terminalHistoricalContextExact = 0;
let terminalHistoricalArtifactExact = 0;
let terminalNewReadyFactsExact = 0;
let terminalContextsNotMixed = 0;
let terminalNewArtifactNoApprovalOrExecute = 0;
let revisedArtifact = null;
let executedArtifactSnapshot = null;
let executedScopeSnapshot = null;
let executingAttemptId = null;
let creationAttemptSnapshot = null;
let plannedUndoContractExact = 0;
let logicalUndoEnvelopeBlocked = false;
let creationUndoContractExact = 0;
let undoEnvelopeCompatibilityExact = 0;
let undoPreTerminalSafe = 0;
let undoForcedPreTerminalNoPost = 0;
let quietBeforeThirdGesture = 0;
let undoGesturePresent = 0;
let undoExactButtonCopy = 0;
let undoClickDispatched = 0;
let undoPostCount = 0;
let undoPostDuringThirdGesture = 0;
let unexpectedUndoPostCount = 0;
let undoSubmittingStateObserved = 0;
let undoRequestExact = 0;
let undoFetchOptionsExact = 0;
let undoStatus = 0;
let undoResponseStrict = 0;
let undoCanonicalBodyText = null;
let undoResponseBody = null;
let undoNonceOnClick = 0;
let undoDoubleClickAtMostOnce = 0;
let undoReplayFixtureExact = 0;
let undoUnknownBeforeRecovery = 0;
let undoUnknownNoFalseSuccess = 0;
let undoQuietAfterUnknown = 0;
let undoRecoveryGesturePresent = 0;
let undoRecoveryClickDispatched = 0;
let undoRecoveryGetCount = 0;
let undoRecoveryGetDuringExplicitGesture = 0;
let undoRecoveryCanonicalExact = 0;
let undoHangingSignalAborted = 0;
let undoInflightEditTriggered = 0;
let undoInflightEditHistoricalUnknown = 0;
let undoInflightEditGetOnly = 0;
let undoUnknownSecondEditDispatched = 0;
let undoUnknownSecondEditRecoveryRetained = 0;
let undoEditSaveClickDispatched = 0;
let undoEditNewArtifactExact = 0;
let undoEditNewArtifactRecoveryIsolated = 0;
let undoEditQuietGetOnly = 0;
let undoEditExplicitGetOnly = 0;
let undoEditOldResultHistorical = 0;
let undoEditContextsNotMixed = 0;
let undoEditNoSecondPost = 0;
let forbiddenActionRouteCalls = 0;
let deleteCalls = 0;
const gestureContext = new AsyncLocalStorage();
const noGestureToken = "NO_GESTURE";
const secondGestureToken = `execute:${approvalNonce}`;
const recoveryGestureToken = `recover:${approvalNonce}`;
const thirdGestureToken = `undo:${undoNonce}`;
const undoRecoveryGestureToken = `undo-recover:${undoNonce}`;
let dropFirstApprovalResponse = mode === "response-loss";
let accelerateApprovalTimeout = false;

const canonicalScopeHash = (scope) => {
  const values = [
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
  const hash = createHash("sha256");
  hash.update(new TextEncoder().encode(scope.scopeSchema));
  hash.update(Uint8Array.of(0));
  for (const value of values) {
    const bytes = new TextEncoder().encode(value);
    const length = new Uint8Array(4);
    new DataView(length.buffer).setUint32(0, bytes.byteLength, false);
    hash.update(length);
    hash.update(bytes);
  }
  return hash.digest("hex");
};

const canonicalUndoScope = (creation) => {
  const scopeSchema = "emergeos.local-draft-undo-scope.v1";
  if (
    typeof creation?.approvalPrincipal?.configuredPrincipalId !== "string" ||
    creation.approvalPrincipal.configuredPrincipalId.length === 0 ||
    typeof creation?.localDraft?.draftId !== "string" ||
    creation.localDraft.draftId.length === 0 ||
    typeof creation?.attemptId !== "string" ||
    creation.attemptId.length === 0 ||
    typeof creation?.receipt?.receiptId !== "string" ||
    creation.receipt.receiptId.length === 0 ||
    typeof creation?.localDraft?.artifactId !== "string" ||
    creation.localDraft.artifactId.length === 0 ||
    !Number.isInteger(creation?.localDraft?.artifactVersion) ||
    creation.localDraft.artifactVersion < 1 ||
    !/^[0-9a-f]{64}$/.test(creation?.localDraft?.artifactHash ?? "")
  ) {
    return null;
  }
  const values = [
    "CONFIGURED_LOCAL_PRINCIPAL",
    creation?.approvalPrincipal?.configuredPrincipalId,
    "EXPLICIT_LOCAL_OWNER_INPUT",
    "LOCAL_DRAFTBOX_LOGICAL_UNDO_V1",
    "LOGICALLY_UNDO_LOCAL_DRAFT",
    `local://drafts/${creation?.localDraft?.draftId}`,
    creation?.localDraft?.draftId,
    creation?.attemptId,
    creation?.receipt?.receiptId,
    creation?.localDraft?.artifactId,
    String(creation?.localDraft?.artifactVersion),
    creation?.localDraft?.artifactHash,
    "ACTIVE",
    "CAPTURE_ARTIFACT_HISTORY_RETAINED",
    "local-draft-undo-v1",
    "emergeos.local-draftbox",
    "emergeos:local-draftbox",
    `local-draftbox:${creation?.approvalPrincipal?.configuredPrincipalId}`,
    undoNonce,
    "1",
  ];
  if (values.some((value) => typeof value !== "string" || value.length === 0)) {
    return null;
  }
  const hash = createHash("sha256");
  hash.update(new TextEncoder().encode(scopeSchema));
  hash.update(Uint8Array.of(0));
  for (const value of values) {
    const bytes = new TextEncoder().encode(value);
    const length = new Uint8Array(4);
    new DataView(length.buffer).setUint32(0, bytes.byteLength, false);
    hash.update(length);
    hash.update(bytes);
  }
  return Object.freeze({ scopeSchema, scopeHash: hash.digest("hex") });
};

const exactUndoRequest = (creation) => {
  const scope = canonicalUndoScope(creation);
  return scope
    ? Object.freeze({
        undoNonce,
        scopeSchema: scope.scopeSchema,
        scopeHash: scope.scopeHash,
      })
    : null;
};

const driftPreview = (source) => {
  const drifted = JSON.parse(JSON.stringify(source));
  if (previewDriftField === "policy") drifted.action.policyVersion = "local-action-v2-drift";
  if (previewDriftField === "connector") {
    drifted.capability.connector = "emergeos.local-draftbox.drift";
  }
  if (previewDriftField === "audience") {
    drifted.capability.audience = "emergeos:local-draftbox:drift";
  }
  if (previewDriftField === "account-ref") {
    drifted.capability.accountRef = "local-draftbox:other-local-owner";
  }
  if (previewDriftField === "max-calls") drifted.capability.maxCalls = 2;
  const originalHash = drifted.scopeHash;
  drifted.scopeHash = canonicalScopeHash(drifted);
  previewDriftHashRecomputed =
    /^[0-9a-f]{64}$/.test(drifted.scopeHash) && drifted.scopeHash !== originalHash ? 1 : 0;
  return drifted;
};

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
      body?.scopeSchema === request.approvedScopeSchema &&
      body?.scopeHash === request.approvedScopeHash &&
      body?.approvalPrincipal?.basis === "CONFIGURED_LOCAL_PRINCIPAL" &&
      body?.provenance?.approvalOrigin === "EXPLICIT_LOCAL_OWNER_INPUT" &&
      body?.provenance?.executionRoute === "LOCAL_DRAFTBOX_V2" &&
      body?.action?.policyVersion === "local-action-v2" &&
      body?.artifact?.artifactId === artifact?.artifactId &&
      body?.artifact?.artifactVersion === artifact?.currentVersion &&
      body?.artifact?.artifactHash === artifact?.currentHash &&
      body?.executionState === "NOT_EXECUTED" &&
      body?.capability?.connector === "emergeos.local-draftbox" &&
      body?.capability?.audience === "emergeos:local-draftbox" &&
      body?.capability?.accountRef ===
        `local-draftbox:${body?.approvalPrincipal?.configuredPrincipalId}` &&
      body?.capability?.usedCalls === 0 &&
      body?.capability?.maxCalls === 1 &&
      Array.isArray(body?.transitions) &&
      body.transitions.length === 1 &&
      transition?.sequence === 1 &&
      transition?.fromStatus === null &&
      transition?.toStatus === "PLANNED" &&
      body?.receipt === null &&
      body?.localDraft === null
    );
  } catch (_failure) {
    return false;
  }
};

const syntheticProblemResponse = () => {
  const problem = {
    type: "urn:emergeos:problem:approval-stale",
    title: "Action approval stale",
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
    const expectedStatus = mode === "stale-409" ? 412 : 409;
    const expectedType = mode === "stale-409"
      ? "urn:emergeos:problem:approval-stale"
      : "urn:emergeos:problem:action-idempotency-conflict";
    actualProblemTypeExact =
      response.status === expectedStatus &&
      problem?.status === expectedStatus &&
      problem?.type === expectedType
        ? 1
        : 0;
  } catch (_failure) {
    actualProblemTypeExact = 0;
  }
};

const allowedLogicalUndoMutationRequest = (path, method) => {
  if (
    mode === "real-undo-inflight-edit" &&
    method === "PUT" &&
    artifactPath !== null &&
    path === artifactPath
  ) {
    return true;
  }
  if (method !== "POST") return false;
  const approvalPath = artifact?.artifactId
    ? `/api/v1/artifacts/${encodeURIComponent(artifact.artifactId)}/action-approvals`
    : null;
  const executePath = approvalAttempt?.attemptId
    ? `/api/v1/action-approvals/${encodeURIComponent(approvalAttempt.attemptId)}/execute`
    : null;
  const undoPath = creationAttemptSnapshot?.attemptId
    ? `/api/v1/action-approvals/${encodeURIComponent(creationAttemptSnapshot.attemptId)}/undo`
    : null;
  return (
    path === "/api/v1/captures" ||
    path === "/api/v1/agent-drafts" ||
    (approvalPath !== null && path === approvalPath) ||
    (executePath !== null && path === executePath) ||
    (undoPath !== null && path === undoPath)
  );
};

const wrappedFetch = async (input, options = {}) => {
  if (typeof input !== "string" || !input.startsWith("/")) {
    throw new Error("served UI fetch must stay same-origin and relative");
  }
  const method = (options.method ?? "GET").toUpperCase();
  if (logicalUndoMode && method === "DELETE") {
    deleteCalls += 1;
    forbiddenActionRouteCalls += 1;
    forbiddenActionCalls += 1;
    throw new Error("logical undo UI must never issue DELETE");
  }
  if (
    logicalUndoMode &&
    method !== "GET" &&
    !allowedLogicalUndoMutationRequest(input, method)
  ) {
    forbiddenActionRouteCalls += 1;
    forbiddenActionCalls += 1;
    throw new Error(`logical undo UI called a non-allowlisted mutation route: ${method}`);
  }
  if (/^\/api\/v1\/artifacts\/[^/]+\/actions$/.test(input)) {
    forbiddenActionCalls += 1;
    legacyActionCalls += 1;
  }
  if (/^\/api\/v1\/actions\//.test(input)) {
    forbiddenActionCalls += 1;
    legacyActionCalls += 1;
    if (/\/reconcile$/.test(input)) reconcileCalls += 1;
  }
  if (/provider|simulated/i.test(input)) providerSurfaceCalls += 1;

  if (
    previewDriftField &&
    /^\/api\/v1\/artifacts\/[^/]+\/action-approval-scope\?/.test(input) &&
    method === "GET"
  ) {
    const actual = await nativeFetch(new URL(input, appBaseUrl), options);
    const drifted = driftPreview(await actual.clone().json());
    approvalScopePreview = drifted;
    const headers = new Headers(actual.headers);
    headers.delete("content-length");
    return new Response(JSON.stringify(drifted), {
      status: actual.status,
      statusText: actual.statusText,
      headers,
    });
  }

  const approvalGetMatch = input.match(/^\/api\/v1\/action-approvals\/([^/]+)$/);
  if (approvalGetMatch && method === "GET") {
    const resolvingUndo = logicalUndoMode && undoPostCount > 0;
    if (resolvingUndo) {
      undoRecoveryGetCount += 1;
      if (gestureContext.getStore() === undoRecoveryGestureToken) {
        undoRecoveryGetDuringExplicitGesture += 1;
      }
    } else {
      recoveryGetCount += 1;
      if (gestureContext.getStore() === recoveryGestureToken) {
        recoveryGetDuringExplicitGesture += 1;
      }
    }
    if (
      mode === "real-executing-edit" &&
      approvalGetMatch[1] === executingAttemptId
    ) {
      executingEditSameAttemptRecovery = 1;
    }
    if (
      mode === "real-executing-edit-ready-recovery" &&
      approvalGetMatch[1] === executingAttemptId
    ) {
      readyRecoverySameOldAttemptGet = 1;
    }
    const response = await nativeFetch(new URL(input, appBaseUrl), options);
    try {
      const bodyText = await response.clone().text();
      if (resolvingUndo) {
        undoRecoveryCanonicalExact =
          response.status === 200 &&
          response.headers.get("cache-control") === "private, no-store" &&
          approvalGetMatch[1] === creationAttemptSnapshot?.attemptId &&
          bodyText === undoCanonicalBodyText
            ? 1
            : 0;
      } else {
        recoveryCanonicalExact =
          response.status === 200 &&
          response.headers.get("cache-control") === "private, no-store" &&
          approvalGetMatch[1] === approvalAttempt?.attemptId &&
          bodyText === executeCanonicalBodyText
            ? 1
            : 0;
      }
    } catch (_failure) {
      if (resolvingUndo) {
        undoRecoveryCanonicalExact = 0;
      } else {
        recoveryCanonicalExact = 0;
      }
    }
    return response;
  }

  const undoMatch = input.match(/^\/api\/v1\/action-approvals\/([^/]+)\/undo$/);
  if (undoMatch && method === "POST") {
    undoPostCount += 1;
    if (gestureContext.getStore() === thirdGestureToken) {
      undoPostDuringThirdGesture += 1;
    } else {
      unexpectedUndoPostCount += 1;
    }
    undoSubmittingStateObserved =
      elements.get("approval-card")?.dataset?.state === "UNDOING" ? 1 : 0;
    const requestBody = typeof options.body === "string" ? options.body : "";
    let request = null;
    try {
      request = JSON.parse(requestBody);
    } catch (_failure) {
      request = null;
    }
    const expectedRequest = exactUndoRequest(creationAttemptSnapshot);
    undoRequestExact =
      request &&
      expectedRequest &&
      Object.keys(request).sort().join(",") === "scopeHash,scopeSchema,undoNonce" &&
      request.undoNonce === expectedRequest.undoNonce &&
      request.scopeSchema === expectedRequest.scopeSchema &&
      request.scopeHash === expectedRequest.scopeHash &&
      undoMatch[1] === creationAttemptSnapshot?.attemptId
        ? 1
        : 0;
    undoFetchOptionsExact =
      options.credentials === "same-origin" &&
      options.cache === "no-store" &&
      options.redirect === "error" &&
      options.signal &&
      typeof options.signal.addEventListener === "function"
        ? 1
        : 0;

    // The hang fault is injected only after the real loopback transaction and response have
    // completed. Do not let the accelerated UI timeout abort the evidence-producing request.
    const nativeUndoOptions =
      ["real-undo-hang", "real-undo-inflight-edit"].includes(mode)
        ? Object.fromEntries(
            Object.entries(options).filter(([key]) => key !== "signal"),
          )
        : options;
    const response = await nativeFetch(
      new URL(input, appBaseUrl),
      nativeUndoOptions,
    );
    undoStatus = response.status;
    try {
      const bodyText = await response.clone().text();
      const body = JSON.parse(bodyText);
      const topLevelKeys = Object.keys(body ?? {}).sort().join(",");
      const draftKeys = Object.keys(body?.localDraft ?? {}).sort().join(",");
      const undoReceiptKeys = Object.keys(body?.undoReceipt ?? {}).sort().join(",");
      const sameCreationTruth = [
        "action",
        "approval",
        "approvalPrincipal",
        "artifact",
        "attemptId",
        "capability",
        "executionState",
        "plan",
        "provenance",
        "receipt",
        "scopeHash",
        "scopeSchema",
        "status",
        "transitions",
      ].every(
        (key) =>
          JSON.stringify(body?.[key]) === JSON.stringify(creationAttemptSnapshot?.[key]),
      );
      undoResponseStrict =
        [200, 201].includes(response.status) &&
        response.headers.get("cache-control") === "private, no-store" &&
        response.headers.get("location") ===
          `/api/v1/action-approvals/${creationAttemptSnapshot?.attemptId}` &&
        topLevelKeys ===
          "action,approval,approvalPrincipal,artifact,attemptId,capability,executionState,localDraft,plan,provenance,receipt,scopeHash,scopeSchema,status,transitions,undoAvailable,undoReceipt" &&
        sameCreationTruth &&
        body?.status === "SUCCEEDED" &&
        body?.executionState === "EXECUTED" &&
        body?.capability?.usedCalls === 1 &&
        body?.capability?.maxCalls === 1 &&
        draftKeys === "artifactHash,artifactId,artifactVersion,createdAt,draftId,state" &&
        body?.localDraft?.draftId === creationAttemptSnapshot?.localDraft?.draftId &&
        body?.localDraft?.artifactId === creationAttemptSnapshot?.localDraft?.artifactId &&
        body?.localDraft?.artifactVersion ===
          creationAttemptSnapshot?.localDraft?.artifactVersion &&
        body?.localDraft?.artifactHash === creationAttemptSnapshot?.localDraft?.artifactHash &&
        body?.localDraft?.state === "LOGICALLY_UNDONE" &&
        body?.undoAvailable === false &&
        undoReceiptKeys ===
          "artifactHash,artifactId,artifactVersion,creationAttemptId,creationReceiptId,draftId,effect,occurredAt,outcome,receiptId,receiptType,retention,scopeHash,scopeSchema,simulated,undoNonce" &&
        body?.undoReceipt?.receiptType === "LOCAL_DRAFT_LOGICALLY_UNDONE_V1" &&
        typeof body?.undoReceipt?.receiptId === "string" &&
        body.undoReceipt.receiptId.length > 0 &&
        body?.undoReceipt?.creationAttemptId === creationAttemptSnapshot?.attemptId &&
        body?.undoReceipt?.draftId === creationAttemptSnapshot?.localDraft?.draftId &&
        body?.undoReceipt?.creationReceiptId ===
          creationAttemptSnapshot?.receipt?.receiptId &&
        body?.undoReceipt?.artifactId === creationAttemptSnapshot?.localDraft?.artifactId &&
        body?.undoReceipt?.artifactVersion ===
          creationAttemptSnapshot?.localDraft?.artifactVersion &&
        body?.undoReceipt?.artifactHash === creationAttemptSnapshot?.localDraft?.artifactHash &&
        body?.undoReceipt?.scopeSchema === expectedRequest?.scopeSchema &&
        body?.undoReceipt?.scopeHash === expectedRequest?.scopeHash &&
        body?.undoReceipt?.undoNonce === undoNonce &&
        body?.undoReceipt?.effect === "LOGICALLY_UNDONE" &&
        body?.undoReceipt?.retention === "CAPTURE_ARTIFACT_HISTORY_RETAINED" &&
        body?.undoReceipt?.outcome === "SUCCEEDED" &&
        body?.undoReceipt?.simulated === false &&
        Number.isFinite(Date.parse(body?.undoReceipt?.occurredAt ?? ""))
          ? 1
          : 0;
      undoCanonicalBodyText = bodyText;
      undoResponseBody = body;
      approvalAttempt = body;
    } catch (_failure) {
      undoResponseStrict = 0;
    }

    if (mode === "real-undo-inflight-edit" && undoInflightEditTriggered === 0) {
      const outcomeContent = elements.get("outcome-canvas-content");
      const undoingBeforeEdit =
        elements.get("approval-card")?.dataset?.state === "UNDOING";
      let dispatched = 0;
      if (outcomeContent) {
        outcomeContent.focus();
        outcomeContent.textContent = `${outcomeContent.textContent} undo inflight edit`;
        try {
          dispatched = await fire(outcomeContent, "input");
        } catch (_failure) {
          dispatched = 0;
        }
      }
      undoInflightEditTriggered =
        undoingBeforeEdit &&
        dispatched === 1 &&
        undoPostCount === 1 &&
        undoResponseStrict === 1 &&
        options.signal?.aborted
          ? 1
          : 0;
    }

    if (mode === "real-undo-response-loss") {
      throw new TypeError("synthetic committed logical undo response loss");
    }
    if (["real-undo-hang", "real-undo-inflight-edit"].includes(mode)) {
      return new Promise((_resolve, reject) => {
        const rejectAborted = () => {
          undoHangingSignalAborted = options.signal?.aborted ? 1 : 0;
          reject(
            options.signal?.reason ??
              new DOMException("synthetic logical undo timeout", "AbortError"),
          );
        };
        if (options.signal?.aborted) {
          rejectAborted();
          return;
        }
        options.signal?.addEventListener("abort", rejectAborted, { once: true });
      });
    }
    return response;
  }

  const executeMatch = input.match(/^\/api\/v1\/action-approvals\/([^/]+)\/execute$/);
  if (executeMatch && method === "POST") {
    executePostCount += 1;
    if (executingAttemptId === null) {
      executingAttemptId = approvalAttempt?.attemptId ?? null;
      executedScopeSnapshot = approvalScopePreview
        ? Object.freeze({
            scopeSchema: approvalScopePreview.scopeSchema,
            scopeHash: approvalScopePreview.scopeHash,
          })
        : null;
    }
    if (gestureContext.getStore() === secondGestureToken) {
      executePostDuringSecondGesture += 1;
    } else {
      unexpectedExecutePostCount += 1;
    }
    executeSubmittingStateObserved =
      elements.get("approval-card")?.dataset?.state === "EXECUTING" ? 1 : 0;
    if (!realLocalDraftboxMode) forbiddenActionCalls += 1;
    const requestBody = typeof options.body === "string" ? options.body : "";
    if (firstExecuteRequestBody === null) firstExecuteRequestBody = requestBody;
    let request = null;
    try {
      request = JSON.parse(requestBody);
    } catch (_failure) {
      request = null;
    }
    executeRequestExact =
      request &&
      Object.keys(request).sort().join(",") === "scopeHash,scopeSchema" &&
      request.scopeSchema === approvalScopePreview?.scopeSchema &&
      request.scopeHash === approvalScopePreview?.scopeHash &&
      executeMatch[1] === approvalAttempt?.attemptId
        ? 1
        : 0;
    executeFetchOptionsExact =
      options.credentials === "same-origin" &&
      options.cache === "no-store" &&
      options.redirect === "error" &&
      options.signal &&
      typeof options.signal.addEventListener === "function"
        ? 1
        : 0;

    if (mode === "real-malformed") {
      executeMalformedInjected = 1;
      executeStatus = 201;
      return new Response("{}", {
        status: 201,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "private, no-store",
          Location: `/api/v1/action-approvals/${approvalAttempt?.attemptId}`,
        },
      });
    }
    if (mode === "real-stale") {
      executeTypedStaleProblem = 1;
      executeStatus = 412;
      return new Response(
        JSON.stringify({
          type: "urn:emergeos:problem:approval-stale",
          title: "Action approval stale",
          status: 412,
          detail: "The approved action scope is not the current local scope.",
        }),
        {
          status: 412,
          headers: {
            "Content-Type": "application/problem+json",
            "Cache-Control": "private, no-store",
          },
        },
      );
    }

    // For the real-hang fault, first let the loopback transaction and its strict 201 body
    // evidence finish. Only the response returned to the served UI remains pending on the
    // original accelerated signal below.
    const nativeExecuteOptions =
      mode === "real-hang"
        ? Object.fromEntries(
            Object.entries(options).filter(([key]) => key !== "signal"),
          )
        : options;
    const response = await nativeFetch(
      new URL(input, appBaseUrl),
      nativeExecuteOptions,
    );
    executeStatus = response.status;
    try {
      const bodyText = await response.clone().text();
      const body = JSON.parse(bodyText);
      const topLevelKeys = Object.keys(body ?? {}).sort().join(",");
      const receiptKeys = Object.keys(body?.receipt ?? {}).sort().join(",");
      const draftKeys = Object.keys(body?.localDraft ?? {}).sort().join(",");
      executeResponseStrict =
        response.status === 201 &&
        response.headers.get("cache-control") === "private, no-store" &&
        response.headers.get("location") === `/api/v1/action-approvals/${approvalAttempt?.attemptId}` &&
        body?.attemptId === approvalAttempt?.attemptId &&
        body?.status === "SUCCEEDED" &&
        body?.executionState === "EXECUTED" &&
        body?.scopeSchema === approvalScopePreview?.scopeSchema &&
        body?.scopeHash === approvalScopePreview?.scopeHash &&
        body?.provenance?.executionRoute === "LOCAL_DRAFTBOX_V2" &&
        body?.action?.policyVersion === "local-action-v2" &&
        body?.capability?.connector === "emergeos.local-draftbox" &&
        body?.capability?.audience === "emergeos:local-draftbox" &&
        body?.capability?.accountRef ===
          `local-draftbox:${body?.approvalPrincipal?.configuredPrincipalId}` &&
        body?.capability?.usedCalls === 1 &&
        body?.capability?.maxCalls === 1 &&
        draftKeys === "artifactHash,artifactId,artifactVersion,createdAt,draftId,state" &&
        typeof body?.localDraft?.draftId === "string" &&
        body.localDraft.draftId.length > 0 &&
        body.localDraft.state === "ACTIVE" &&
        body.localDraft.artifactId === artifact?.artifactId &&
        body.localDraft.artifactVersion === artifact?.currentVersion &&
        body.localDraft.artifactHash === artifact?.currentHash &&
        receiptKeys === "attemptId,draftId,occurredAt,outcome,receiptId,receiptType,simulated" &&
        body?.receipt?.receiptType === "LOCAL_DRAFT_CREATED_V1" &&
        typeof body?.receipt?.receiptId === "string" &&
        body.receipt.receiptId.length > 0 &&
        body.receipt.attemptId === body.attemptId &&
        body.receipt.draftId === body.localDraft.draftId &&
        body.receipt.outcome === "SUCCEEDED" &&
        body.receipt.simulated === false
          ? 1
          : 0;
      creationUndoContractExact =
        logicalUndoMode &&
        topLevelKeys ===
          "action,approval,approvalPrincipal,artifact,attemptId,capability,executionState,localDraft,plan,provenance,receipt,scopeHash,scopeSchema,status,transitions,undoAvailable,undoReceipt" &&
        body?.undoReceipt === null &&
        body?.undoAvailable === true
          ? 1
          : 0;
      executeCanonicalBodyText = bodyText;
      creationAttemptSnapshot = JSON.parse(JSON.stringify(body));
      executedArtifactSnapshot = Object.freeze({
        artifactId: body?.localDraft?.artifactId,
        artifactVersion: body?.localDraft?.artifactVersion,
        artifactHash: body?.localDraft?.artifactHash,
      });
      approvalAttempt = body;
    } catch (_failure) {
      executeResponseStrict = 0;
    }
    if (
      mode === "real-executing-edit-ready-recovery" &&
      readyRecoveryExecutingEditTriggered === 0
    ) {
      const outcomeContent = elements.get("outcome-canvas-content");
      const executingBeforeEdit =
        elements.get("approval-card")?.dataset?.state === "EXECUTING";
      try {
        if (outcomeContent) {
          outcomeContent.focus();
          outcomeContent.textContent = `${outcomeContent.textContent} executing edit`;
          let dispatched = 0;
          try {
            dispatched = await fire(outcomeContent, "input");
          } catch (_failure) {
            dispatched = 0;
          }
          readyRecoveryExecutingEditTriggered =
            executingBeforeEdit && dispatched === 1 && executePostCount === 1 ? 1 : 0;
          const historicalUnknownCopy = [...elements.values()]
            .filter((element) => !element.hidden)
            .map((element) => element.textContent)
            .filter(Boolean)
            .join(" | ")
            .replace(/\s+/g, " ");
          const historicalStatus = elements.get("approval-status")?.textContent ?? "";
          const historicalRecoveryTrigger = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-check-execution" ||
                element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          readyRecoveryHistoricalUnknown =
            readyRecoveryExecutingEditTriggered === 1 &&
            elements.get("approval-card")?.dataset?.state === "UNKNOWN" &&
            /历史/.test(historicalStatus) &&
            /执行结果未知|结果未知/.test(historicalStatus) &&
            !/\bSUCCEEDED\b|\bEXECUTED\b|LOCAL_DRAFT_CREATED_V1|本地草稿已创建|执行成功/.test(
              historicalUnknownCopy,
            ) &&
            historicalRecoveryTrigger &&
            !historicalRecoveryTrigger.hidden &&
            !historicalRecoveryTrigger.disabled
              ? 1
              : 0;
          readyRecoveryTrigger = historicalRecoveryTrigger ?? null;
          const outcomeSave = elements.get("outcome-save");
          if (outcomeSave && !outcomeSave.hidden && !outcomeSave.disabled) {
            try {
              readyRecoverySaveClickDispatched = await fire(outcomeSave, "click");
            } catch (_failure) {
              readyRecoverySaveClickDispatched = 0;
            }
          }
        }
      } catch (_failure) {
        // Product state is sampled by markers after the bounded injected handlers return.
      } finally {
        readyRecoveryPhaseCompleted = 1;
      }
    }
    if (mode === "real-executing-edit" && executingEditTriggered === 0) {
      const outcomeContent = elements.get("outcome-canvas-content");
      const executingBeforeEdit =
        elements.get("approval-card")?.dataset?.state === "EXECUTING";
      if (outcomeContent) {
        outcomeContent.focus();
        outcomeContent.textContent = `${outcomeContent.textContent} executing edit`;
        const dispatched = await fire(outcomeContent, "input");
        executingEditTriggered =
          executingBeforeEdit && dispatched === 1 && executePostCount === 1 ? 1 : 0;
      }
    }
    if (mode === "real-response-loss") {
      throw new TypeError("synthetic committed execute response loss");
    }
    if (mode === "real-hang") {
      return new Promise((_resolve, reject) => {
        const rejectAborted = () => {
          hangingSignalAborted = options.signal?.aborted ? 1 : 0;
          reject(options.signal?.reason ?? new DOMException("synthetic execute timeout", "AbortError"));
        };
        if (options.signal?.aborted) {
          rejectAborted();
          return;
        }
        options.signal?.addEventListener("abort", rejectAborted, { once: true });
      });
    }
    return response;
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
    const currentApprovalCopy = approvalCopy();
    const requestScopeExact =
      request?.approvedScopeSchema === approvalScopePreview?.scopeSchema &&
      request?.approvedScopeHash === approvalScopePreview?.scopeHash &&
      currentApprovalCopy.includes(approvalScopePreview?.scopeSchema ?? "") &&
      currentApprovalCopy.includes(approvalScopePreview?.scopeHash ?? "");
    if (!requestScopeExact) {
      cardScopeExact = 0;
    }
    if (
      !request ||
      Object.keys(request).sort().join(",") !==
        "approvalNonce,approvedArtifactHash,approvedArtifactVersion,approvedScopeHash,approvedScopeSchema" ||
      request.approvedArtifactVersion !== artifact?.currentVersion ||
      request.approvedArtifactHash !== artifact?.currentHash ||
      request.approvedScopeSchema !== "emergeos.action-approval-scope.v1" ||
      !/^[0-9a-f]{64}$/.test(request.approvedScopeHash ?? "") ||
      !requestScopeExact ||
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
    const exactApprovalContract = await exactApprovalResponse(response, request);
    if (!exactApprovalContract) {
      approvalResponseStrict = 0;
    }
    try {
      approvalAttempt = await response.clone().json();
      plannedUndoContractExact =
        logicalUndoMode &&
        exactApprovalContract &&
        Object.keys(approvalAttempt ?? {}).sort().join(",") ===
          "action,approval,approvalPrincipal,artifact,attemptId,capability,executionState,localDraft,plan,provenance,receipt,scopeHash,scopeSchema,status,transitions,undoAvailable,undoReceipt" &&
        approvalAttempt?.status === "PLANNED" &&
        approvalAttempt?.executionState === "NOT_EXECUTED" &&
        approvalAttempt?.receipt === null &&
        approvalAttempt?.localDraft === null &&
        approvalAttempt?.undoReceipt === null &&
        approvalAttempt?.undoAvailable === false
          ? 1
          : 0;
    } catch (_failure) {
      approvalAttempt = null;
      plannedUndoContractExact = 0;
    }
    if (dropFirstApprovalResponse) {
      dropFirstApprovalResponse = false;
      throw new TypeError("synthetic committed response loss");
    }
    return response;
  }

  const response = await nativeFetch(new URL(input, appBaseUrl), options);
  if (
      [
        "real-executing-edit",
        "real-executing-edit-ready-recovery",
        "real-terminal-new-artifact",
        "real-undo-inflight-edit",
      ].includes(mode) &&
    artifactPath !== null &&
    input === artifactPath &&
    method === "PUT"
  ) {
    artifactRevisionPutCount += 1;
    try {
      revisedArtifact = await response.clone().json();
    } catch (_failure) {
      revisedArtifact = null;
    }
  }
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
  if (
    /^\/api\/v1\/artifacts\/[^/]+\/action-approval-scope\?/.test(input) &&
    method === "GET"
  ) {
    try {
      approvalScopePreview = await response.clone().json();
    } catch (_failure) {
      approvalScopePreview = null;
    }
  }
  return response;
};

const contextSetTimeout = (callback, delay, ...args) => {
  return setTimeout(
    () => gestureContext.run(noGestureToken, callback, ...args),
    accelerateApprovalTimeout && Number(delay) >= 10000 ? 25 : delay,
  );
};

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
  URLSearchParams,
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

const fire = async (element, type, gestureToken = null) => {
  if (!element) return 0;
  const registered = [...(listeners.get(`${element.id}:${type}`) ?? [])];
  if (registered.length === 0) return 0;
  return gestureContext.run(gestureToken, async () => {
    if (mode === "real-timer-spoof" && gestureToken === secondGestureToken) {
      await new Promise((resolve) => {
        contextSetTimeout(() => {
          timerCanaryObserved = 1;
          timerInheritedSecondGesture =
            gestureContext.getStore() === secondGestureToken ? 1 : 0;
          resolve();
        }, 0);
      });
    }
    for (const listener of registered) {
      await listener({
        target: element,
        preventDefault() {},
      });
    }
    return 1;
  });
};

const waitFor = async (predicate, marker, milliseconds = 7000) => {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(marker);
};

const quietWindow = async (snapshot, milliseconds = 200) => {
  const deadline = Date.now() + milliseconds;
  while (Date.now() < deadline) {
    if (!snapshot()) return 0;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return snapshot() ? 1 : 0;
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

try {
  await waitFor(
    () => elements.get("approval-card")?.dataset?.state === "READY",
    "Approval scope preview did not reach READY",
    realLocalDraftboxMode ? 400 : 7000,
  );
  approvalReady = 1;
} catch (failure) {
  if (!realLocalDraftboxMode) throw failure;
}

const approvalElements = () =>
  [...elements.values()].filter((element) => /approval|approve/i.test(element.id));
const approvalButtons = () =>
  [...elements.values()].filter(
    (element) =>
      element.tagName === "BUTTON" &&
      (/approval|approve/i.test(element.id) || /批准/.test(element.textContent)),
  );
const undoButtons = () =>
  [...elements.values()].filter(
    (element) =>
      element.tagName === "BUTTON" &&
      (/undo/i.test(element.id) || /Undo|撤销/.test(element.textContent)),
  );
const undoTrigger = () =>
  undoButtons().find(
    (element) => element.textContent.trim() === "逻辑撤销本地草稿（保留明文与历史）",
  ) ??
  elements.get("approval-undo") ??
  null;
const undoRecoveryTrigger = () =>
  undoButtons().find(
    (element) =>
      element.id === "approval-check-undo" ||
      element.id === "approval-undo-recover" ||
      /查询.*撤销.*结果|确认.*撤销.*结果/.test(element.textContent),
  ) ?? null;
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
const readyRiskCopy = elements.get("approval-risk")?.textContent?.trim() ?? "";
const expectedReadyRiskCopy =
  "REVERSIBLE（风险分类；撤销暂未开放） · Policy local-action-v2";
const cardTargetRiskExact =
  approvalScopePreview?.action?.targetRef === "local://drafts" &&
  approvalScopePreview?.action?.risk === "REVERSIBLE" &&
  approvalScopePreview?.action?.policyVersion === "local-action-v2" &&
  readyCopy.includes("Local Draftbox") &&
  readyCopy.includes(approvalScopePreview.action.targetRef) &&
  readyRiskCopy === expectedReadyRiskCopy &&
  !readyCopy.includes("REVERSIBLE（可撤销）")
    ? 1
    : 0;
const cardArtifactExact =
  artifact && readyCopy.includes(`v${artifact.currentVersion}`) && readyCopy.includes(artifact.currentHash)
    ? 1
    : 0;
const cardBoundaryExact =
  readyCopy.includes("批准只记录授权") &&
  readyCopy.includes("尚未执行") &&
  /不产生\s*Receipt/.test(readyCopy) &&
  readyCopy.includes("当前为本机单用户原型") &&
  readyCopy.includes("未验证真实身份")
    ? 1
    : 0;
let cardScopeExact =
  approvalScopePreview?.scopeSchema === "emergeos.action-approval-scope.v1" &&
  /^[0-9a-f]{64}$/.test(approvalScopePreview?.scopeHash ?? "") &&
  readyCopy.includes(approvalScopePreview.scopeSchema) &&
  readyCopy.includes(approvalScopePreview.scopeHash)
    ? 1
    : 0;
const cardSensitiveRefsHidden =
  typeof approvalScopePreview?.approvalPrincipal?.configuredPrincipalId === "string" &&
  approvalScopePreview.approvalPrincipal.configuredPrincipalId.length > 0 &&
  typeof approvalScopePreview?.capability?.accountRef === "string" &&
  approvalScopePreview.capability.accountRef.length > 0 &&
  !readyCopy.includes(approvalScopePreview.approvalPrincipal.configuredPrincipalId) &&
  !readyCopy.includes(approvalScopePreview.capability.accountRef)
    ? 1
    : 0;

let approvalClickDispatched = 0;
let explicitSameNonceRetry = 0;
let approvalInvalidated = 0;
let uuidCallsBeforeApproval = uuidCalls;

const directApproval = async () => {
  const preview = await nativeFetch(
    new URL(
      `/api/v1/artifacts/${artifact.artifactId}/action-approval-scope?artifactVersion=${artifact.currentVersion}&artifactHash=${artifact.currentHash}`,
      appBaseUrl,
    ),
    { headers: { Accept: "application/json" }, cache: "no-store" },
  );
  if (preview.status !== 200) {
    throw new Error("replay scenario could not preview the exact approval scope");
  }
  const scope = await preview.json();
  const request = {
    approvedArtifactVersion: artifact.currentVersion,
    approvedArtifactHash: artifact.currentHash,
    approvalNonce,
    approvedScopeSchema: scope.scopeSchema,
    approvedScopeHash: scope.scopeHash,
  };
  const response = await nativeFetch(new URL(`/api/v1/artifacts/${artifact.artifactId}/action-approvals`, appBaseUrl), {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  if (response.status !== 201 || !(await exactApprovalResponse(response, request))) {
    throw new Error("replay scenario could not seed one exact PLANNED approval");
  }
  return { scope, body: await response.json() };
};

if (trigger) {
  if (realLocalDraftboxMode) {
    if (previewDriftField) {
      previewDriftUnknown =
        !approvalReady &&
        elements.get("approval-card")?.dataset?.state === "UNKNOWN" &&
        trigger.hidden &&
        trigger.disabled
          ? 1
          : 0;
      quietBeforeSecondGesture = await quietWindow(
        () => approvalPostCount === 0 && executePostCount === 0,
      );
    } else if (approvalReady) {
      approvalClickDispatched = await fire(trigger, "click");
      try {
        await waitFor(
          () => elements.get("approval-card")?.dataset?.state === "PLANNED",
          "real local draftbox approval did not reach PLANNED",
          logicalUndoMode ? 1000 : 7000,
        );
        approvalViaUi =
          approvalPostCount === 1 && approvalAttempt?.status === "PLANNED" ? 1 : 0;
      } catch (failure) {
        const currentState = elements.get("approval-card")?.dataset?.state;
        if (
          !logicalUndoMode ||
          plannedUndoContractExact !== 1 ||
          approvalPostCount !== 1 ||
          currentState !== "UNKNOWN"
        ) {
          throw failure;
        }
        // The real backend returned the frozen 17-key envelope, while the currently served
        // UI still rejected it with its old 15-key validator. This is the first bounded Red;
        // do not seed a fixture or bypass the product UI.
        logicalUndoEnvelopeBlocked = true;
      }
    } else {
      const seeded = await directApproval();
      approvalScopePreview = seeded.scope;
      approvalAttempt = seeded.body;
      plannedFixtureUsedForRedLocalization = 1;
    }

    if (!previewDriftField && !logicalUndoEnvelopeBlocked) {
      if (logicalUndoMode) {
        const visibleBeforeTerminal = [...elements.values()]
          .filter((element) => !element.hidden)
          .map((element) => element.textContent)
          .filter(Boolean)
          .join(" | ")
          .replace(/\s+/g, " ");
        undoPreTerminalSafe =
          undoButtons().every((button) => button.hidden) &&
          !/LOGICALLY_UNDONE|LOCAL_DRAFT_LOGICALLY_UNDONE_V1|撤销成功|已逻辑撤销/.test(
            visibleBeforeTerminal,
          )
            ? 1
            : 0;
        const undoPostsBeforeForcedPreTerminal = undoPostCount;
        const uuidCallsBeforeForcedPreTerminal = uuidCalls;
        const prematureUndo = undoTrigger();
        if (prematureUndo) {
          await fire(prematureUndo, "click", thirdGestureToken);
        }
        undoForcedPreTerminalNoPost = await quietWindow(
          () =>
            undoPostCount === undoPostsBeforeForcedPreTerminal &&
            uuidCalls === uuidCallsBeforeForcedPreTerminal,
        );
      }
      executePostCountBeforeSecondGesture = executePostCount;
      quietBeforeSecondGesture = await quietWindow(() => executePostCount === 0);
      const executeTrigger = [...elements.values()].find(
        (element) =>
          element.tagName === "BUTTON" &&
          (element.id === "approval-execute" || /写入本地草稿|明确执行/.test(element.textContent)),
      );
      executeGesturePresent =
        executeTrigger && !executeTrigger.hidden && !executeTrigger.disabled ? 1 : 0;

      if (mode === "real-planned-edit") {
        const outcomeContent = elements.get("outcome-canvas-content");
        const originalContent = outcomeContent?.textContent ?? "";
        if (outcomeContent) {
          outcomeContent.focus();
          outcomeContent.textContent = `${originalContent} 已编辑`;
          await fire(outcomeContent, "input");
        }
        executeInvalidatedAfterEdit =
          elements.get("approval-card")?.dataset?.state === "INVALIDATED" &&
          (!executeTrigger || executeTrigger.hidden || executeTrigger.disabled)
            ? 1
            : 0;
        if (executeTrigger) {
          executeClickDispatched = await fire(
            executeTrigger,
            "click",
            secondGestureToken,
          );
        }
        quietAfterUnknown = await quietWindow(() => executePostCount === 0);
      } else if (executeGesturePresent) {
        if (mode === "real-hang") accelerateApprovalTimeout = true;
        if (mode === "real-double-click") {
          const first = fire(executeTrigger, "click", secondGestureToken);
          const second = fire(executeTrigger, "click", secondGestureToken);
          executeClickDispatched = 1;
          await Promise.all([first, second]);
        } else {
          executeClickDispatched = await fire(
            executeTrigger,
            "click",
            secondGestureToken,
          );
        }
        if (executePostCount > 0) {
          if (mode === "real-executing-edit-ready-recovery") {
            await waitFor(
              () => readyRecoveryPhaseCompleted === 1,
              "ready-recovery injected request phase did not complete",
            );
          } else {
            await waitFor(
              () => {
                const cardState = elements.get("approval-card")?.dataset?.state;
                return (
                  ["SUCCEEDED", "UNKNOWN", "STALE"].includes(cardState) ||
                  (mode === "real-executing-edit" && cardState === "INVALIDATED")
                );
              },
              "real local draftbox execute did not reach a bounded terminal UI state",
            );
          }
          terminalWaitReached = 1;
        }

        if (logicalUndoMode && executePostCount > 0) {
          const preUndoDraftElement = elements.get("approval-local-draft");
          const preUndoReceiptElement = elements.get("approval-receipt");
          const preUndoDraftCopy =
            preUndoDraftElement && !preUndoDraftElement.hidden
              ? preUndoDraftElement.textContent
              : "";
          const preUndoReceiptCopy =
            preUndoReceiptElement && !preUndoReceiptElement.hidden
              ? preUndoReceiptElement.textContent
              : "";
          undoEnvelopeCompatibilityExact =
            plannedUndoContractExact === 1 &&
            creationUndoContractExact === 1 &&
            elements.get("approval-card")?.dataset?.state === "SUCCEEDED" &&
            preUndoDraftCopy.includes("ACTIVE") &&
            preUndoDraftCopy.includes(
              creationAttemptSnapshot?.localDraft?.draftId ?? "__missing_creation_draft__",
            ) &&
            preUndoReceiptCopy.includes("LOCAL_DRAFT_CREATED_V1") &&
            preUndoReceiptCopy.includes(
              creationAttemptSnapshot?.receipt?.receiptId ??
                "__missing_creation_receipt__",
            )
              ? 1
              : 0;
          const logicalUndoTrigger = undoTrigger();
          undoGesturePresent =
            logicalUndoTrigger &&
            !logicalUndoTrigger.hidden &&
            !logicalUndoTrigger.disabled &&
            creationAttemptSnapshot?.status === "SUCCEEDED" &&
            creationAttemptSnapshot?.executionState === "EXECUTED" &&
            creationAttemptSnapshot?.receipt?.receiptType === "LOCAL_DRAFT_CREATED_V1" &&
            creationAttemptSnapshot?.undoAvailable === true
              ? 1
              : 0;
          undoExactButtonCopy =
            logicalUndoTrigger?.textContent.trim() ===
            "逻辑撤销本地草稿（保留明文与历史）"
              ? 1
              : 0;
          quietBeforeThirdGesture = await quietWindow(
            () =>
              undoPostCount === 0 &&
              undoRecoveryGetCount === 0 &&
              recoveryGetCount === 0,
          );

          if (undoGesturePresent) {
            if (mode === "real-undo-double-click") {
              const request = exactUndoRequest(creationAttemptSnapshot);
              const replaySeed = await nativeFetch(
                new URL(
                  `/api/v1/action-approvals/${creationAttemptSnapshot.attemptId}/undo`,
                  appBaseUrl,
                ),
                {
                  method: "POST",
                  headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json",
                  },
                  body: JSON.stringify(request),
                  cache: "no-store",
                },
              );
              let replaySeedBody = null;
              try {
                replaySeedBody = await replaySeed.clone().json();
              } catch (_failure) {
                replaySeedBody = null;
              }
              undoReplayFixtureExact =
                replaySeed.status === 201 &&
                replaySeed.headers.get("cache-control") === "private, no-store" &&
                replaySeed.headers.get("location") ===
                  `/api/v1/action-approvals/${creationAttemptSnapshot.attemptId}` &&
                replaySeedBody?.localDraft?.state === "LOGICALLY_UNDONE" &&
                replaySeedBody?.undoAvailable === false &&
                replaySeedBody?.undoReceipt?.undoNonce === undoNonce &&
                replaySeedBody?.undoReceipt?.scopeSchema === request?.scopeSchema &&
                replaySeedBody?.undoReceipt?.scopeHash === request?.scopeHash
                  ? 1
                  : 0;
            }

            if (mode === "real-undo-hang") accelerateApprovalTimeout = true;
            const uuidCallsBeforeUndo = uuidCalls;
            if (mode === "real-undo-double-click") {
              const first = fire(logicalUndoTrigger, "click", thirdGestureToken);
              const second = fire(logicalUndoTrigger, "click", thirdGestureToken);
              const dispatched = await Promise.all([first, second]);
              undoClickDispatched = dispatched[0] === 1 && dispatched[1] === 1 ? 1 : 0;
            } else {
              undoClickDispatched = await fire(
                logicalUndoTrigger,
                "click",
                thirdGestureToken,
              );
            }
            undoNonceOnClick =
              uuidCallsBeforeUndo === 2 && uuidCalls === 3 ? 1 : 0;
            if (mode === "real-undo-inflight-edit") {
              await waitFor(
                () => undoInflightEditTriggered === 1,
                "logical undo did not commit before the injected inflight edit",
              );
            } else {
              await waitFor(
                () =>
                  ["LOGICALLY_UNDONE", "UNDO_UNKNOWN"].includes(
                    elements.get("approval-card")?.dataset?.state,
                  ),
                "logical undo did not reach a bounded terminal UI state",
              );
            }
            undoDoubleClickAtMostOnce = undoPostCount === 1 ? 1 : 0;
          }

          if (["real-undo-response-loss", "real-undo-hang"].includes(mode)) {
            const unknownVisibleCopy = [...elements.values()]
              .filter((element) => !element.hidden)
              .map((element) => element.textContent)
              .filter(Boolean)
              .join(" | ")
              .replace(/\s+/g, " ");
            undoUnknownBeforeRecovery =
              elements.get("approval-card")?.dataset?.state === "UNDO_UNKNOWN"
                ? 1
                : 0;
            undoUnknownNoFalseSuccess =
              !/LOGICALLY_UNDONE|LOCAL_DRAFT_LOGICALLY_UNDONE_V1|撤销成功|已逻辑撤销/.test(
                unknownVisibleCopy,
              )
                ? 1
                : 0;
            const postsAtUnknown = undoPostCount;
            undoQuietAfterUnknown = await quietWindow(
              () =>
                undoPostCount === postsAtUnknown && undoRecoveryGetCount === 0,
            );
            const recoveryControl = undoRecoveryTrigger();
            const enabledControls = [
              ...new Set([...approvalButtons(), ...undoButtons()]),
            ].filter((button) => !button.hidden && !button.disabled);
            undoRecoveryGesturePresent =
              recoveryControl &&
              !recoveryControl.hidden &&
              !recoveryControl.disabled &&
              enabledControls.length === 1 &&
              enabledControls[0] === recoveryControl
                ? 1
                : 0;
            if (undoRecoveryGesturePresent) {
              accelerateApprovalTimeout = false;
              undoRecoveryClickDispatched = await fire(
                recoveryControl,
                "click",
                undoRecoveryGestureToken,
              );
              await waitFor(
                () =>
                  elements.get("approval-card")?.dataset?.state ===
                  "LOGICALLY_UNDONE",
                "explicit logical undo GET recovery did not reach LOGICALLY_UNDONE",
              );
            }
          }

          if (mode === "real-undo-inflight-edit") {
            const historicalLabel = "历史执行结果；不授权当前 Artifact";
            const historicalLabelVisible = () =>
              [...elements.values()].some(
                (element) =>
                  !element.hidden && element.textContent.trim() === historicalLabel,
              );
            const enabledApprovalAndUndoControls = () => [
              ...new Set([...approvalButtons(), ...undoButtons()]),
            ].filter((button) => !button.hidden && !button.disabled);
            const recoveryIsSoleEnabled = (control) => {
              const enabled = enabledApprovalAndUndoControls();
              return (
                control &&
                !control.hidden &&
                !control.disabled &&
                enabled.length === 1 &&
                enabled[0] === control
              );
            };
            const unknownVisibleCopy = [...elements.values()]
              .filter((element) => !element.hidden)
              .map((element) => element.textContent)
              .filter(Boolean)
              .join(" | ")
              .replace(/\s+/g, " ");
            const firstRecoveryControl = undoRecoveryTrigger();
            const firstUnknownStatus =
              elements.get("approval-status")?.textContent ?? "";
            undoInflightEditHistoricalUnknown =
              undoInflightEditTriggered === 1 &&
              elements.get("approval-card")?.dataset?.state === "UNDO_UNKNOWN" &&
              elements.get("approval-result")?.getAttribute("data-context") ===
                "HISTORICAL" &&
              historicalLabelVisible() &&
              /历史/.test(firstUnknownStatus) &&
              /撤销结果未知|逻辑撤销结果未知|结果未知/.test(firstUnknownStatus) &&
              !/LOGICALLY_UNDONE|LOCAL_DRAFT_LOGICALLY_UNDONE_V1|撤销成功|已逻辑撤销/.test(
                unknownVisibleCopy,
              )
                ? 1
                : 0;
            undoInflightEditGetOnly = recoveryIsSoleEnabled(firstRecoveryControl)
              ? 1
              : 0;
            undoUnknownBeforeRecovery = undoInflightEditHistoricalUnknown;
            undoUnknownNoFalseSuccess =
              !/LOGICALLY_UNDONE|LOCAL_DRAFT_LOGICALLY_UNDONE_V1|撤销成功|已逻辑撤销/.test(
                unknownVisibleCopy,
              )
                ? 1
                : 0;

            const outcomeContent = elements.get("outcome-canvas-content");
            if (outcomeContent) {
              outcomeContent.focus();
              outcomeContent.textContent = `${outcomeContent.textContent} undo unknown second edit`;
              undoUnknownSecondEditDispatched = await fire(outcomeContent, "input");
            }
            const secondRecoveryControl = undoRecoveryTrigger();
            const secondUnknownStatus =
              elements.get("approval-status")?.textContent ?? "";
            undoUnknownSecondEditRecoveryRetained =
              undoUnknownSecondEditDispatched === 1 &&
              elements.get("approval-card")?.dataset?.state === "UNDO_UNKNOWN" &&
              elements.get("approval-result")?.getAttribute("data-context") ===
                "HISTORICAL" &&
              historicalLabelVisible() &&
              /历史/.test(secondUnknownStatus) &&
              /撤销结果未知|逻辑撤销结果未知|结果未知/.test(secondUnknownStatus) &&
              secondRecoveryControl === firstRecoveryControl &&
              recoveryIsSoleEnabled(secondRecoveryControl)
                ? 1
                : 0;

            const outcomeSave = elements.get("outcome-save");
            if (outcomeSave && !outcomeSave.hidden && !outcomeSave.disabled) {
              undoEditSaveClickDispatched = await fire(outcomeSave, "click");
              try {
                await waitFor(
                  () =>
                    artifactRevisionPutCount === 1 &&
                    elements.get("outcome-canvas")?.dataset?.state ===
                      "REVISION_SAVED" &&
                    approvalScopePreview?.artifact?.artifactVersion ===
                      revisedArtifact?.currentVersion &&
                    approvalScopePreview?.artifact?.artifactHash ===
                      revisedArtifact?.currentHash &&
                    elements.get("approval-card")?.getAttribute("aria-busy") ===
                      "false",
                  "undo inflight edit revision did not reach a trusted v+1 scope preview",
                );
              } catch (_downstreamNotSettled) {
                // Preserve zero-valued downstream markers so the ordered Java assertions
                // localize the first broken undo-recovery invariant.
              }
            }
            const currentArtifactBeforeRecovery =
              elements.get("approval-artifact")?.textContent ?? "";
            const currentScopeBeforeRecovery =
              elements.get("approval-scope")?.textContent ?? "";
            undoEditNewArtifactExact =
              undoEditSaveClickDispatched === 1 &&
              artifactRevisionPutCount === 1 &&
              revisedArtifact?.artifactId === executedArtifactSnapshot?.artifactId &&
              revisedArtifact?.currentVersion ===
                executedArtifactSnapshot?.artifactVersion + 1 &&
              /^[0-9a-f]{64}$/.test(revisedArtifact?.currentHash ?? "") &&
              revisedArtifact?.currentHash !== executedArtifactSnapshot?.artifactHash &&
              approvalScopePreview?.artifact?.artifactId === revisedArtifact?.artifactId &&
              approvalScopePreview?.artifact?.artifactVersion ===
                revisedArtifact?.currentVersion &&
              approvalScopePreview?.artifact?.artifactHash === revisedArtifact?.currentHash &&
              approvalScopePreview?.scopeHash !== executedScopeSnapshot?.scopeHash &&
              currentArtifactBeforeRecovery.includes(
                `v${revisedArtifact?.currentVersion}`,
              ) &&
              currentArtifactBeforeRecovery.includes(
                revisedArtifact?.currentHash ?? "__missing_new_hash__",
              ) &&
              !currentArtifactBeforeRecovery.includes(
                executedArtifactSnapshot?.artifactHash ?? "__missing_old_hash__",
              ) &&
              currentScopeBeforeRecovery.includes(
                approvalScopePreview?.scopeHash ?? "__missing_new_scope__",
              ) &&
              !currentScopeBeforeRecovery.includes(
                executedScopeSnapshot?.scopeHash ?? "__missing_old_scope__",
              )
                ? 1
                : 0;
            const recoveryAfterSave = undoRecoveryTrigger();
            undoEditNewArtifactRecoveryIsolated =
              undoEditNewArtifactExact === 1 &&
              elements.get("approval-result")?.getAttribute("data-context") ===
                "HISTORICAL" &&
              historicalLabelVisible() &&
              recoveryAfterSave === firstRecoveryControl &&
              recoveryIsSoleEnabled(recoveryAfterSave)
                ? 1
                : 0;

            const undoPostsBeforeForcedOldGesture = undoPostCount;
            await fire(undoTrigger(), "click", thirdGestureToken);
            undoEditQuietGetOnly = await quietWindow(
              () =>
                undoPostCount === undoPostsBeforeForcedOldGesture &&
                undoPostCount === 1 &&
                undoRecoveryGetCount === 0,
            );
            undoEditNoSecondPost =
              undoEditQuietGetOnly === 1 &&
              undoPostsBeforeForcedOldGesture === 1 &&
              undoPostCount === 1 &&
              unexpectedUndoPostCount === 0
                ? 1
                : 0;
            undoQuietAfterUnknown = undoEditQuietGetOnly;
            undoRecoveryGesturePresent =
              undoInflightEditGetOnly === 1 &&
              undoUnknownSecondEditRecoveryRetained === 1 &&
              undoEditNewArtifactRecoveryIsolated === 1 &&
              undoEditNoSecondPost === 1 &&
              recoveryIsSoleEnabled(recoveryAfterSave)
                ? 1
                : 0;
            if (undoRecoveryGesturePresent) {
              undoRecoveryClickDispatched = await fire(
                recoveryAfterSave,
                "click",
                undoRecoveryGestureToken,
              );
              await waitFor(
                () =>
                  undoRecoveryGetCount === 1 &&
                  undoRecoveryCanonicalExact === 1,
                "explicit historical logical Undo GET did not recover the old attempt",
              );
            }
            undoEditExplicitGetOnly =
              undoRecoveryClickDispatched === 1 &&
              undoRecoveryGetCount === 1 &&
              undoRecoveryGetDuringExplicitGesture === 1 &&
              undoRecoveryCanonicalExact === 1 &&
              undoPostCount === 1
                ? 1
                : 0;

            const historicalCopy = [
              elements.get("approval-local-draft"),
              elements.get("approval-draft-summary"),
              elements.get("approval-receipt"),
              elements.get("approval-receipt-summary"),
              elements.get("approval-undo-receipt"),
              elements.get("approval-undo-receipt-summary"),
              elements.get("approval-undo-boundary"),
            ]
              .filter((element) => element && !element.hidden)
              .map((element) => element.textContent)
              .filter(Boolean)
              .join(" | ")
              .replace(/\s+/g, " ");
            const currentArtifactAfterRecovery =
              elements.get("approval-artifact")?.textContent ?? "";
            const currentScopeAfterRecovery =
              elements.get("approval-scope")?.textContent ?? "";
            undoEditOldResultHistorical =
              undoEditExplicitGetOnly === 1 &&
              undoResponseBody?.localDraft?.state === "LOGICALLY_UNDONE" &&
              undoResponseBody?.undoReceipt?.receiptType ===
                "LOCAL_DRAFT_LOGICALLY_UNDONE_V1" &&
              elements.get("approval-result")?.getAttribute("data-context") ===
                "HISTORICAL" &&
              historicalLabelVisible() &&
              historicalCopy.includes(
                `Artifact v${executedArtifactSnapshot?.artifactVersion}`,
              ) &&
              historicalCopy.includes(
                executedArtifactSnapshot?.artifactHash ?? "__missing_old_hash__",
              ) &&
              historicalCopy.includes("LOCAL_DRAFT_CREATED_V1") &&
              historicalCopy.includes("LOCAL_DRAFT_LOGICALLY_UNDONE_V1") &&
              historicalCopy.includes("LOGICALLY_UNDONE") &&
              !historicalCopy.includes(
                revisedArtifact?.currentHash ?? "__missing_new_hash__",
              )
                ? 1
                : 0;
            undoEditContextsNotMixed =
              undoEditOldResultHistorical === 1 &&
              currentArtifactAfterRecovery.includes(
                `v${revisedArtifact?.currentVersion}`,
              ) &&
              currentArtifactAfterRecovery.includes(
                revisedArtifact?.currentHash ?? "__missing_new_hash__",
              ) &&
              !currentArtifactAfterRecovery.includes(
                executedArtifactSnapshot?.artifactHash ?? "__missing_old_hash__",
              ) &&
              currentScopeAfterRecovery.includes(
                approvalScopePreview?.scopeHash ?? "__missing_new_scope__",
              ) &&
              !currentScopeAfterRecovery.includes(
                executedScopeSnapshot?.scopeHash ?? "__missing_old_scope__",
              )
                ? 1
                : 0;
          }
        }

        if (mode === "real-executing-edit-ready-recovery") {
          const approvalPostsBeforeRecovery = approvalPostCount;
          const executePostsBeforeRecovery = executePostCount;
          const recoveryControl = readyRecoveryTrigger;
          const v2FactsExact = () => {
            const currentArtifactCopy =
              elements.get("approval-artifact")?.textContent ?? "";
            const currentScopeCopy = elements.get("approval-scope")?.textContent ?? "";
            return (
              readyRecoverySaveClickDispatched === 1 &&
              artifactRevisionPutCount === 1 &&
              revisedArtifact?.artifactId === executedArtifactSnapshot?.artifactId &&
              revisedArtifact?.currentVersion ===
                executedArtifactSnapshot?.artifactVersion + 1 &&
              /^[0-9a-f]{64}$/.test(revisedArtifact?.currentHash ?? "") &&
              revisedArtifact?.currentHash !== executedArtifactSnapshot?.artifactHash &&
              approvalScopePreview?.artifact?.artifactId === revisedArtifact?.artifactId &&
              approvalScopePreview?.artifact?.artifactVersion ===
                revisedArtifact?.currentVersion &&
              approvalScopePreview?.artifact?.artifactHash === revisedArtifact?.currentHash &&
              approvalScopePreview?.scopeHash !== executedScopeSnapshot?.scopeHash &&
              currentArtifactCopy.includes(`v${revisedArtifact?.currentVersion}`) &&
              currentArtifactCopy.includes(
                revisedArtifact?.currentHash ?? "__missing_v2_hash__",
              ) &&
              !currentArtifactCopy.includes(
                executedArtifactSnapshot?.artifactHash ?? "__missing_v1_hash__",
              ) &&
              currentScopeCopy.includes(
                approvalScopePreview?.scopeHash ?? "__missing_v2_scope__",
              ) &&
              !currentScopeCopy.includes(
                executedScopeSnapshot?.scopeHash ?? "__missing_v1_scope__",
              )
            );
          };
          const oldRecoveryIsSoleEnabled = () => {
            const enabled = approvalButtons().filter(
              (button) => !button.hidden && !button.disabled,
            );
            return (
              recoveryControl &&
              !recoveryControl.hidden &&
              !recoveryControl.disabled &&
              enabled.length === 1 &&
              enabled[0] === recoveryControl
            );
          };
          await waitFor(
            () =>
              v2FactsExact() &&
              elements.get("approval-card")?.dataset?.state === "READY" &&
              elements.get("approval-card")?.getAttribute("aria-busy") === "false" &&
              oldRecoveryIsSoleEnabled() &&
              recoveryGetCount === 0,
            "ready-recovery v+1 preview did not settle with the old GET-only control",
          );
          readyRecoveryV2BoundaryExact = v2FactsExact() ? 1 : 0;
          const enabledPreRecovery = approvalButtons().filter(
            (button) => !button.hidden && !button.disabled,
          );
          readyRecoveryV2Isolated =
            readyRecoveryHistoricalUnknown === 1 &&
            readyRecoveryV2BoundaryExact === 1 &&
            elements.get("approval-card")?.dataset?.state === "READY" &&
            elements.get("approval-card")?.getAttribute("aria-busy") === "false" &&
            oldRecoveryIsSoleEnabled() &&
            (trigger.hidden || trigger.disabled) &&
            (executeTrigger.hidden || executeTrigger.disabled) &&
            approvalPostsBeforeRecovery === 1 &&
            executePostsBeforeRecovery === 1 &&
            approvalPostCount === approvalPostsBeforeRecovery &&
            executePostCount === executePostsBeforeRecovery &&
            recoveryGetCount === 0
              ? 1
              : 0;
          readyRecoveryPreRecoverySettled =
            readyRecoveryV2Isolated === 1 &&
            elements.get("approval-card")?.dataset?.state === "READY" &&
            elements.get("approval-card")?.getAttribute("aria-busy") === "false" &&
            recoveryGetCount === 0
              ? 1
              : 0;
          recoveryGesturePresent =
            readyRecoveryPreRecoverySettled === 1 &&
            recoveryControl &&
            !recoveryControl.hidden &&
            !recoveryControl.disabled &&
            enabledPreRecovery.length === 1 &&
            enabledPreRecovery[0] === recoveryControl
              ? 1
              : 0;
          if (recoveryGesturePresent) {
            try {
              recoveryClickDispatched = await fire(
                recoveryControl,
                "click",
                recoveryGestureToken,
              );
            } catch (_failure) {
              recoveryClickDispatched = 0;
            }
          }
          const historicalLabel = "历史执行结果；不授权当前 Artifact";
          const historicalLabelExact = [...elements.values()].some(
            (element) =>
              !element.hidden && element.textContent.trim() === historicalLabel,
          );
          const historicalCopy = [
            elements.get("approval-local-draft"),
            elements.get("approval-draft-summary"),
            elements.get("approval-receipt"),
            elements.get("approval-receipt-summary"),
          ]
            .filter((element) => element && !element.hidden)
            .map((element) => element.textContent)
            .filter(Boolean)
            .join(" | ")
            .replace(/\s+/g, " ");
          let recoveredCanonical = null;
          try {
            recoveredCanonical = JSON.parse(executeCanonicalBodyText);
          } catch (_failure) {
            recoveredCanonical = null;
          }
          readyRecoveryHistoricalResultExact =
            recoveryClickDispatched === 1 &&
            recoveryGetCount === 1 &&
            recoveryGetDuringExplicitGesture === 1 &&
            recoveryCanonicalExact === 1 &&
            readyRecoverySameOldAttemptGet === 1 &&
            recoveredCanonical?.attemptId === executingAttemptId &&
            recoveredCanonical?.localDraft?.artifactId ===
              executedArtifactSnapshot?.artifactId &&
            recoveredCanonical?.localDraft?.artifactVersion ===
              executedArtifactSnapshot?.artifactVersion &&
            recoveredCanonical?.localDraft?.artifactHash ===
              executedArtifactSnapshot?.artifactHash &&
            elements.get("approval-result")?.getAttribute("data-context") ===
              "HISTORICAL" &&
            historicalLabelExact &&
            historicalCopy.includes(
              `Artifact v${executedArtifactSnapshot?.artifactVersion}`,
            ) &&
            historicalCopy.includes(
              executedArtifactSnapshot?.artifactHash ?? "__missing_v1_hash__",
            ) &&
            !historicalCopy.includes(revisedArtifact?.currentHash ?? "__missing_v2_hash__") &&
            historicalCopy.includes("LOCAL_DRAFT_CREATED_V1")
              ? 1
              : 0;
          const currentArtifactCopyAfterRecovery =
            elements.get("approval-artifact")?.textContent ?? "";
          const currentScopeCopyAfterRecovery =
            elements.get("approval-scope")?.textContent ?? "";
          const retryTrigger = elements.get("approval-retry");
          const checkExecutionTrigger = elements.get("approval-check-execution");
          readyRecoveryCurrentApprovalReopened =
            readyRecoveryPreRecoverySettled === 1 &&
            readyRecoveryHistoricalResultExact === 1 &&
            elements.get("approval-card")?.dataset?.state === "READY" &&
            elements.get("approval-card")?.getAttribute("aria-busy") === "false" &&
            currentArtifactCopyAfterRecovery.includes(
              `v${revisedArtifact?.currentVersion}`,
            ) &&
            currentArtifactCopyAfterRecovery.includes(
              revisedArtifact?.currentHash ?? "__missing_v2_hash__",
            ) &&
            !currentArtifactCopyAfterRecovery.includes(
              executedArtifactSnapshot?.artifactHash ?? "__missing_v1_hash__",
            ) &&
            currentScopeCopyAfterRecovery.includes(
              approvalScopePreview?.scopeHash ?? "__missing_v2_scope__",
            ) &&
            trigger &&
            !trigger.hidden &&
            !trigger.disabled &&
            (executeTrigger.hidden || executeTrigger.disabled) &&
            (!retryTrigger || retryTrigger.hidden || retryTrigger.disabled) &&
            (!checkExecutionTrigger ||
              checkExecutionTrigger.hidden ||
              checkExecutionTrigger.disabled)
              ? 1
              : 0;
          quietAfterUnknown = await quietWindow(
            () =>
              approvalPostCount === approvalPostsBeforeRecovery &&
              executePostCount === executePostsBeforeRecovery &&
              recoveryGetCount === 1,
          );
          readyRecoveryNoAutomaticPosts =
            quietAfterUnknown === 1 &&
            approvalPostsBeforeRecovery === 1 &&
            executePostsBeforeRecovery === 1 &&
            approvalPostCount === 1 &&
            executePostCount === 1
              ? 1
              : 0;
        }

        if (mode === "real-executing-edit") {
          const historicalUnknownCopy = [...elements.values()]
            .filter((element) => !element.hidden)
            .map((element) => element.textContent)
            .filter(Boolean)
            .join(" | ")
            .replace(/\s+/g, " ");
          const historicalStatus = elements.get("approval-status")?.textContent ?? "";
          executingEditHistoricalUnknown =
            elements.get("approval-card")?.dataset?.state === "UNKNOWN" &&
            /历史/.test(historicalStatus) &&
            /执行结果未知|结果未知/.test(historicalStatus) &&
            !/\bSUCCEEDED\b|\bEXECUTED\b|LOCAL_DRAFT_CREATED_V1|本地草稿已创建|执行成功/.test(
              historicalUnknownCopy,
            )
              ? 1
              : 0;
          const recoveryTrigger = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-check-execution" ||
                element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          const enabledApprovalControls = approvalButtons().filter(
            (button) => !button.hidden && !button.disabled,
          );
          executingEditGetOnly =
            recoveryTrigger &&
            !recoveryTrigger.hidden &&
            !recoveryTrigger.disabled &&
            enabledApprovalControls.length === 1 &&
            enabledApprovalControls[0] === recoveryTrigger
              ? 1
              : 0;
          const approvalPostsAfterCommittedEdit = approvalPostCount;
          const executePostsAfterCommittedEdit = executePostCount;
          if (executeTrigger) {
            await fire(executeTrigger, "click", secondGestureToken);
          }
          const outcomeContent = elements.get("outcome-canvas-content");
          const outcomeSave = elements.get("outcome-save");
          if (outcomeSave && !outcomeSave.hidden && !outcomeSave.disabled) {
            executingEditSaveClickDispatched = await fire(outcomeSave, "click");
            await waitFor(
              () =>
                artifactRevisionPutCount === 1 &&
                elements.get("outcome-canvas")?.dataset?.state === "REVISION_SAVED" &&
                revisedArtifact?.currentHash ===
                  approvalScopePreview?.artifact?.artifactHash,
              "executing-edit revision did not reach a trusted v+1 scope preview",
            );
          }
          const currentArtifactCopyAfterSave =
            elements.get("approval-artifact")?.textContent ?? "";
          const currentScopeCopyAfterSave =
            elements.get("approval-scope")?.textContent ?? "";
          executingEditV2BoundaryExact =
            executingEditSaveClickDispatched === 1 &&
            artifactRevisionPutCount === 1 &&
            revisedArtifact?.artifactId === executedArtifactSnapshot?.artifactId &&
            revisedArtifact?.currentVersion ===
              executedArtifactSnapshot?.artifactVersion + 1 &&
            /^[0-9a-f]{64}$/.test(revisedArtifact?.currentHash ?? "") &&
            revisedArtifact?.currentHash !== executedArtifactSnapshot?.artifactHash &&
            approvalScopePreview?.artifact?.artifactId === revisedArtifact?.artifactId &&
            approvalScopePreview?.artifact?.artifactVersion ===
              revisedArtifact?.currentVersion &&
            approvalScopePreview?.artifact?.artifactHash === revisedArtifact?.currentHash &&
            approvalScopePreview?.scopeHash !== executedScopeSnapshot?.scopeHash &&
            currentArtifactCopyAfterSave.includes(`v${revisedArtifact?.currentVersion}`) &&
            currentArtifactCopyAfterSave.includes(revisedArtifact?.currentHash ?? "__missing_v2_hash__") &&
            !currentArtifactCopyAfterSave.includes(
              executedArtifactSnapshot?.artifactHash ?? "__missing_v1_hash__",
            ) &&
            currentScopeCopyAfterSave.includes(
              approvalScopePreview?.scopeHash ?? "__missing_v2_scope__",
            ) &&
            !currentScopeCopyAfterSave.includes(
              executedScopeSnapshot?.scopeHash ?? "__missing_v1_scope__",
            )
              ? 1
              : 0;
          const recoveryAfterSave = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-check-execution" ||
                element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          const enabledAfterSave = approvalButtons().filter(
            (button) => !button.hidden && !button.disabled,
          );
          executingEditV2ScopeRecoveryIsolated =
            executingEditV2BoundaryExact === 1 &&
            recoveryAfterSave === recoveryTrigger &&
            recoveryAfterSave &&
            !recoveryAfterSave.hidden &&
            !recoveryAfterSave.disabled &&
            enabledAfterSave.length === 1 &&
            enabledAfterSave[0] === recoveryAfterSave &&
            approvalPostCount === approvalPostsAfterCommittedEdit &&
            executePostCount === executePostsAfterCommittedEdit
              ? 1
              : 0;
          const v2ApprovalPostsBeforeForcedHandlers = approvalPostCount;
          const v2ExecutePostsBeforeForcedHandlers = executePostCount;
          const forcedV2ApprovalDispatch = await fire(trigger, "click");
          const forcedV2ExecuteDispatch = await fire(executeTrigger, "click");
          executingEditV2HandlersForced =
            forcedV2ApprovalDispatch === 1 && forcedV2ExecuteDispatch === 1 ? 1 : 0;
          const v2ForcedHandlersQuiet = await quietWindow(
            () =>
              approvalPostCount === v2ApprovalPostsBeforeForcedHandlers &&
              executePostCount === v2ExecutePostsBeforeForcedHandlers &&
              recoveryGetCount === 0,
          );
          const recoveryAfterV2ForcedHandlers = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-check-execution" ||
                element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          const enabledAfterV2ForcedHandlers = approvalButtons().filter(
            (button) => !button.hidden && !button.disabled,
          );
          executingEditV2ForcedHandlersNoPost =
            executingEditV2HandlersForced === 1 &&
            v2ForcedHandlersQuiet === 1 &&
            v2ApprovalPostsBeforeForcedHandlers === 1 &&
            v2ExecutePostsBeforeForcedHandlers === 1 &&
            approvalPostCount === 1 &&
            executePostCount === 1 &&
            approvalAttempt?.attemptId === executingAttemptId &&
            recoveryAfterV2ForcedHandlers === recoveryTrigger &&
            recoveryAfterV2ForcedHandlers &&
            !recoveryAfterV2ForcedHandlers.hidden &&
            !recoveryAfterV2ForcedHandlers.disabled &&
            enabledAfterV2ForcedHandlers.length === 1 &&
            enabledAfterV2ForcedHandlers[0] === recoveryAfterV2ForcedHandlers
              ? 1
              : 0;
          if (
            outcomeContent &&
            elements.get("outcome-canvas")?.dataset?.state === "REVISION_SAVED"
          ) {
            outcomeContent.focus();
            outcomeContent.textContent = `${outcomeContent.textContent} second unsaved edit`;
            executingEditSecondEditDispatched = await fire(outcomeContent, "input");
          }
          const recoveryAfterSecondEdit = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-check-execution" ||
                element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          const enabledAfterSecondEdit = approvalButtons().filter(
            (button) => !button.hidden && !button.disabled,
          );
          const secondEditStatus = elements.get("approval-status")?.textContent ?? "";
          executingEditSecondDirtyRecoveryRetained =
            executingEditSecondEditDispatched === 1 &&
            elements.get("outcome-canvas")?.dataset?.state === "DIRTY" &&
            elements.get("approval-card")?.dataset?.state === "UNKNOWN" &&
            /历史/.test(secondEditStatus) &&
            /执行结果未知|结果未知/.test(secondEditStatus) &&
            recoveryAfterSecondEdit === recoveryTrigger &&
            recoveryAfterSecondEdit &&
            !recoveryAfterSecondEdit.hidden &&
            !recoveryAfterSecondEdit.disabled &&
            enabledAfterSecondEdit.length === 1 &&
            enabledAfterSecondEdit[0] === recoveryAfterSecondEdit
              ? 1
              : 0;
          const secondDirtyApprovalPostsBeforeForcedHandlers = approvalPostCount;
          const secondDirtyExecutePostsBeforeForcedHandlers = executePostCount;
          const forcedSecondDirtyApprovalDispatch = await fire(trigger, "click");
          const forcedSecondDirtyExecuteDispatch = await fire(executeTrigger, "click");
          executingEditSecondDirtyHandlersForced =
            forcedSecondDirtyApprovalDispatch === 1 &&
            forcedSecondDirtyExecuteDispatch === 1
              ? 1
              : 0;
          const secondDirtyForcedHandlersQuiet = await quietWindow(
            () =>
              approvalPostCount === secondDirtyApprovalPostsBeforeForcedHandlers &&
              executePostCount === secondDirtyExecutePostsBeforeForcedHandlers &&
              recoveryGetCount === 0,
          );
          const recoveryAfterSecondDirtyForcedHandlers = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-check-execution" ||
                element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          const enabledAfterSecondDirtyForcedHandlers = approvalButtons().filter(
            (button) => !button.hidden && !button.disabled,
          );
          executingEditSecondDirtyForcedHandlersNoPost =
            executingEditSecondDirtyHandlersForced === 1 &&
            secondDirtyForcedHandlersQuiet === 1 &&
            secondDirtyApprovalPostsBeforeForcedHandlers === 1 &&
            secondDirtyExecutePostsBeforeForcedHandlers === 1 &&
            approvalPostCount === 1 &&
            executePostCount === 1 &&
            approvalAttempt?.attemptId === executingAttemptId &&
            recoveryAfterSecondDirtyForcedHandlers === recoveryTrigger &&
            recoveryAfterSecondDirtyForcedHandlers &&
            !recoveryAfterSecondDirtyForcedHandlers.hidden &&
            !recoveryAfterSecondDirtyForcedHandlers.disabled &&
            enabledAfterSecondDirtyForcedHandlers.length === 1 &&
            enabledAfterSecondDirtyForcedHandlers[0] ===
              recoveryAfterSecondDirtyForcedHandlers
              ? 1
              : 0;
          quietAfterUnknown = secondDirtyForcedHandlersQuiet;
          executingEditPostCountsStable =
            quietAfterUnknown === 1 &&
            approvalPostsAfterCommittedEdit === 1 &&
            executePostsAfterCommittedEdit === 1 &&
            approvalPostCount === 1 &&
            executePostCount === 1
              ? 1
              : 0;
          executingEditOldScopePostBlocked = executingEditPostCountsStable;
          recoveryGesturePresent =
            executingEditSecondDirtyRecoveryRetained === 1 &&
            executingEditSecondDirtyForcedHandlersNoPost === 1
              ? 1
              : 0;
          if (recoveryGesturePresent) {
            recoveryClickDispatched = await fire(
              recoveryAfterSecondDirtyForcedHandlers,
              "click",
              recoveryGestureToken,
            );
            await waitFor(
              () => elements.get("approval-card")?.dataset?.state === "SUCCEEDED",
              "historical UNKNOWN recovery did not reach SUCCEEDED",
            );
            executingEditRecoveredSucceeded =
              recoveryClickDispatched === 1 &&
              recoveryGetCount === 1 &&
              recoveryCanonicalExact === 1 &&
              executingEditSameAttemptRecovery === 1 &&
              executePostCount === 1
                ? 1
                : 0;
            let recoveredCanonical = null;
            try {
              recoveredCanonical = JSON.parse(executeCanonicalBodyText);
            } catch (_failure) {
              recoveredCanonical = null;
            }
            executingEditNoHandleOverwrite =
              executingEditRecoveredSucceeded === 1 &&
              recoveredCanonical?.attemptId === executingAttemptId &&
              recoveredCanonical?.localDraft?.artifactId ===
                executedArtifactSnapshot?.artifactId &&
              recoveredCanonical?.localDraft?.artifactVersion ===
                executedArtifactSnapshot?.artifactVersion &&
              recoveredCanonical?.localDraft?.artifactHash ===
                executedArtifactSnapshot?.artifactHash &&
              approvalScopePreview?.artifact?.artifactVersion ===
                revisedArtifact?.currentVersion &&
              approvalScopePreview?.artifact?.artifactHash === revisedArtifact?.currentHash &&
              revisedArtifact?.currentHash !== executedArtifactSnapshot?.artifactHash
                ? 1
                : 0;
          }
        }

        if (["real-response-loss", "real-hang"].includes(mode)) {
          const unknownVisibleCopy = [...elements.values()]
            .filter((element) => !element.hidden)
            .map((element) => element.textContent)
            .filter(Boolean)
            .join(" | ")
            .replace(/\s+/g, " ");
          executeUnknownBeforeRecovery =
            elements.get("approval-card")?.dataset?.state === "UNKNOWN" &&
            !/\bSUCCEEDED\b|\bEXECUTED\b|LOCAL_DRAFT_CREATED_V1|本地草稿已创建|执行成功|Receipt\s*(?:已产生|已创建|ID|created|succeeded|success)|local\s+draft\s+(?:created|succeeded|success)|回执已产生/i.test(
              unknownVisibleCopy,
            )
              ? 1
              : 0;
          const postsAtUnknown = executePostCount;
          quietAfterUnknown = await quietWindow(
            () => executePostCount === postsAtUnknown && recoveryGetCount === 0,
          );
          const recoveryTrigger = [...elements.values()].find(
            (element) =>
              element.tagName === "BUTTON" &&
              (element.id === "approval-execute-recover" ||
                /查询.*结果|恢复.*状态|确认.*结果/.test(element.textContent)),
          );
          recoveryGesturePresent =
            recoveryTrigger && !recoveryTrigger.hidden && !recoveryTrigger.disabled ? 1 : 0;
          if (recoveryGesturePresent) {
            accelerateApprovalTimeout = false;
            recoveryClickDispatched = await fire(
              recoveryTrigger,
              "click",
              recoveryGestureToken,
            );
            await waitFor(
              () => elements.get("approval-card")?.dataset?.state === "SUCCEEDED",
              "explicit GET recovery did not reach SUCCEEDED",
            );
          }
        }
        if (["real-malformed", "real-stale"].includes(mode)) {
          const postsAtFailure = executePostCount;
          quietAfterUnknown = await quietWindow(
            () => executePostCount === postsAtFailure && recoveryGetCount === 0,
          );
        }

        if (mode === "real-terminal-new-artifact") {
          const approvalPostsBeforeRevision = approvalPostCount;
          const executePostsBeforeRevision = executePostCount;
          const outcomeContent = elements.get("outcome-canvas-content");
          const outcomeSave = elements.get("outcome-save");
          if (outcomeContent) {
            outcomeContent.focus();
            outcomeContent.textContent = `${outcomeContent.textContent} terminal new artifact`;
            terminalEditInputDispatched = await fire(outcomeContent, "input");
          }
          if (outcomeSave && !outcomeSave.hidden && !outcomeSave.disabled) {
            terminalSaveClickDispatched = await fire(outcomeSave, "click");
            await waitFor(
              () =>
                elements.get("outcome-canvas")?.dataset?.state === "REVISION_SAVED" &&
                elements.get("approval-card")?.dataset?.state === "READY",
              "terminal result revision did not reach a new READY Artifact",
            );
          }
          const noNewActionQuiet = await quietWindow(
            () =>
              approvalPostCount === approvalPostsBeforeRevision &&
              executePostCount === executePostsBeforeRevision,
          );
          terminalNewArtifactSaved =
            terminalEditInputDispatched === 1 &&
            terminalSaveClickDispatched === 1 &&
            artifactRevisionPutCount === 1 &&
            revisedArtifact?.artifactId === executedArtifactSnapshot?.artifactId &&
            revisedArtifact?.currentVersion ===
              executedArtifactSnapshot?.artifactVersion + 1 &&
            /^[0-9a-f]{64}$/.test(revisedArtifact?.currentHash ?? "") &&
            revisedArtifact?.currentHash !== executedArtifactSnapshot?.artifactHash
              ? 1
              : 0;
          const historicalLabel = "历史执行结果；不授权当前 Artifact";
          const resultElement = elements.get("approval-result");
          const historicalLabelExact = [...elements.values()].some(
            (element) =>
              !element.hidden && element.textContent.trim() === historicalLabel,
          );
          const historicalCopy = [
            elements.get("approval-local-draft"),
            elements.get("approval-draft-summary"),
            elements.get("approval-receipt"),
            elements.get("approval-receipt-summary"),
            elements.get("approval-undo-boundary"),
          ]
            .filter((element) => element && !element.hidden)
            .map((element) => element.textContent)
            .filter(Boolean)
            .join(" | ")
            .replace(/\s+/g, " ");
          const currentArtifactCopy = elements.get("approval-artifact")?.textContent ?? "";
          const currentScopeCopy = elements.get("approval-scope")?.textContent ?? "";
          terminalHistoricalContextExact =
            resultElement &&
            !resultElement.hidden &&
            resultElement.getAttribute("data-context") === "HISTORICAL" &&
            historicalLabelExact
              ? 1
              : 0;
          terminalHistoricalArtifactExact =
            historicalCopy.includes(
              `Artifact v${executedArtifactSnapshot?.artifactVersion}`,
            ) &&
            historicalCopy.includes(executedArtifactSnapshot?.artifactHash ?? "__missing_old_hash__") &&
            historicalCopy.includes("LOCAL_DRAFT_CREATED_V1")
              ? 1
              : 0;
          terminalNewReadyFactsExact =
            terminalNewArtifactSaved === 1 &&
            elements.get("approval-card")?.dataset?.state === "READY" &&
            currentArtifactCopy.includes(`v${revisedArtifact?.currentVersion}`) &&
            currentArtifactCopy.includes(revisedArtifact?.currentHash ?? "__missing_new_hash__") &&
            approvalScopePreview?.artifact?.artifactVersion === revisedArtifact?.currentVersion &&
            approvalScopePreview?.artifact?.artifactHash === revisedArtifact?.currentHash &&
            currentScopeCopy.includes(approvalScopePreview?.scopeHash ?? "__missing_new_scope__")
              ? 1
              : 0;
          terminalContextsNotMixed =
            terminalHistoricalContextExact === 1 &&
            terminalHistoricalArtifactExact === 1 &&
            !historicalCopy.includes(revisedArtifact?.currentHash ?? "__missing_new_hash__") &&
            !currentArtifactCopy.includes(
              executedArtifactSnapshot?.artifactHash ?? "__missing_old_hash__",
            ) &&
            !currentScopeCopy.includes(
              executedScopeSnapshot?.scopeHash ?? "__missing_old_scope__",
            )
              ? 1
              : 0;
          terminalNewArtifactNoApprovalOrExecute =
            noNewActionQuiet === 1 &&
            approvalPostsBeforeRevision === 1 &&
            executePostsBeforeRevision === 1 &&
            approvalPostCount === 1 &&
            executePostCount === 1
              ? 1
              : 0;
        }

        if (mode === "real-created" && executeCanonicalBodyText && firstExecuteRequestBody) {
          const attemptId = approvalAttempt?.attemptId;
          const path = `/api/v1/action-approvals/${attemptId}`;
          const replay = await nativeFetch(new URL(`${path}/execute`, appBaseUrl), {
            method: "POST",
            headers: { Accept: "application/json", "Content-Type": "application/json" },
            body: firstExecuteRequestBody,
            cache: "no-store",
          });
          const replayBody = await replay.text();
          replayCanonicalExact =
            replay.status === 200 &&
            replay.headers.get("cache-control") === "private, no-store" &&
            replay.headers.get("location") === path &&
            replayBody === executeCanonicalBodyText
              ? 1
              : 0;
          const persisted = await nativeFetch(new URL(path, appBaseUrl), {
            headers: { Accept: "application/json" },
            cache: "no-store",
          });
          getCanonicalExact =
            persisted.status === 200 &&
            persisted.headers.get("cache-control") === "private, no-store" &&
            (await persisted.text()) === executeCanonicalBodyText
              ? 1
              : 0;
        }
      }
    }
  } else if (mode === "artifact-edit") {
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
      await waitFor(
        () => elements.get("approval-card")?.dataset?.state === "READY",
        "revised approval scope preview did not reach READY",
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
const visibleElements = [...elements.values()].filter((element) => !element.hidden);
const structuralTags = new Set(["MAIN", "FORM", "FIELDSET", "SECTION", "ASIDE", "DL"]);
const visibleTextElements = visibleElements.filter(
  (element) => !structuralTags.has(element.tagName),
);
const visibleDomCopy = visibleTextElements
  .map((element) => element.textContent)
  .filter(Boolean)
  .join(" | ")
  .replace(/\s+/g, " ");
const allDomCopy = [...elements.values()]
  .map((element) => element.textContent)
  .filter(Boolean)
  .join(" | ")
  .replace(/\s+/g, " ");
const localDraftElement = elements.get("approval-local-draft");
const receiptElement = elements.get("approval-receipt");
const localDraftCopy =
  localDraftElement && !localDraftElement.hidden ? localDraftElement.textContent : "";
const receiptCopy = receiptElement && !receiptElement.hidden ? receiptElement.textContent : "";
const localDraftRendered =
  localDraftCopy.includes("本地草稿已创建") &&
  localDraftCopy.includes("ACTIVE") &&
  localDraftCopy.includes(approvalAttempt?.localDraft?.draftId ?? "__missing_draft__")
    ? 1
    : 0;
const typedReceiptRendered =
  receiptCopy.includes("LOCAL_DRAFT_CREATED_V1") &&
  receiptCopy.includes(approvalAttempt?.receipt?.receiptId ?? "__missing_receipt__") &&
  receiptCopy.includes("SUCCEEDED") &&
  /simulated\s*[=:：]\s*false/i.test(receiptCopy)
    ? 1
    : 0;
const executionSensitiveRefsHidden =
  !/providerRequestId|rawResponseRef|externalId|configuredPrincipalId|accountRef/i.test(
    visibleDomCopy,
  ) &&
  !/provider/i.test(visibleDomCopy) &&
  !(approvalScopePreview?.approvalPrincipal?.configuredPrincipalId &&
    visibleDomCopy.includes(approvalScopePreview.approvalPrincipal.configuredPrincipalId)) &&
  !(approvalScopePreview?.capability?.accountRef &&
    visibleDomCopy.includes(approvalScopePreview.capability.accountRef))
    ? 1
    : 0;
const undoUnavailableCopy = "撤销暂未开放；本版本不会提供 Undo 操作。";
const undoBoundaryOccurrences = visibleDomCopy.split(undoUnavailableCopy).length - 1;
const visibleDomWithoutUndoBoundary = visibleDomCopy.replace(undoUnavailableCopy, "");
const verifiedCreationUndoControl =
  creationAttemptSnapshot?.status === "SUCCEEDED" &&
  creationAttemptSnapshot?.executionState === "EXECUTED" &&
  creationAttemptSnapshot?.receipt?.receiptType === "LOCAL_DRAFT_CREATED_V1" &&
  creationAttemptSnapshot?.localDraft?.state === "ACTIVE" &&
  creationAttemptSnapshot?.undoReceipt === null &&
  creationAttemptSnapshot?.undoAvailable === true &&
  undoTrigger()?.textContent.trim() === "逻辑撤销本地草稿（保留明文与历史）";
const undoBoundaryExplicit =
  (undoBoundaryOccurrences === 1 && !/Undo|撤销/i.test(visibleDomWithoutUndoBoundary)) ||
  verifiedCreationUndoControl
    ? 1
    : 0;
const undoPositiveClaim =
  /Undo\s+(?:is\s+)?(?:available|enabled|ready)|Undo\s*(?:已开放|可用|已启用|已就绪|已支持)|撤销(?:功能|操作)?(?:现已|已经|已)?(?:可用|支持|开放|启用|就绪)|(?:现在|现已)可以撤销|可立即撤销|已撤销|撤销成功/i;
const verifiedLogicalUndoClaim =
  logicalUndoMode &&
  undoResponseStrict === 1 &&
  undoResponseBody?.localDraft?.state === "LOGICALLY_UNDONE" &&
  undoResponseBody?.undoReceipt?.effect === "LOGICALLY_UNDONE" &&
  (elements.get("approval-card")?.dataset?.state === "LOGICALLY_UNDONE" ||
    (mode === "real-undo-inflight-edit" && undoEditOldResultHistorical === 1));
const noFalseUndoClaim =
  !undoPositiveClaim.test(visibleDomCopy) || verifiedLogicalUndoClaim ? 1 : 0;
const undoControlsSafe = [...elements.values()]
  .filter(
    (element) =>
      element.tagName === "BUTTON" &&
      (/undo/i.test(element.id) || /Undo|撤销/.test(element.textContent)),
  )
  .every(
    (element) =>
      element.hidden ||
      element.disabled ||
      (element === undoTrigger() && verifiedCreationUndoControl),
  )
    ? 1
    : 0;
const undoReceiptElement = elements.get("approval-undo-receipt");
const undoReceiptSummaryElement = elements.get("approval-undo-receipt-summary");
const undoReceiptCopy = [undoReceiptElement, undoReceiptSummaryElement]
  .filter((element) => element && !element.hidden)
  .map((element) => element.textContent)
  .filter(Boolean)
  .join(" | ")
  .replace(/\s+/g, " ");
const undoEffectiveStateRendered =
  localDraftElement &&
  !localDraftElement.hidden &&
  localDraftCopy.includes("LOGICALLY_UNDONE") &&
  localDraftCopy.includes(undoResponseBody?.localDraft?.draftId ?? "__missing_undo_draft__")
    ? 1
    : 0;
const creationReceiptRetained =
  receiptElement &&
  !receiptElement.hidden &&
  receiptCopy.includes("LOCAL_DRAFT_CREATED_V1") &&
  receiptCopy.includes(
    creationAttemptSnapshot?.receipt?.receiptId ?? "__missing_creation_receipt__",
  ) &&
  receiptCopy.includes("SUCCEEDED") &&
  /simulated\s*[=:：]\s*false/i.test(receiptCopy)
    ? 1
    : 0;
const undoReceiptRendered =
  undoReceiptCopy.includes("LOCAL_DRAFT_LOGICALLY_UNDONE_V1") &&
  undoReceiptCopy.includes(
    undoResponseBody?.undoReceipt?.receiptId ?? "__missing_undo_receipt__",
  ) &&
  undoReceiptCopy.includes("LOGICALLY_UNDONE") &&
  undoReceiptCopy.includes("CAPTURE_ARTIFACT_HISTORY_RETAINED") &&
  undoReceiptCopy.includes("SUCCEEDED") &&
  /simulated\s*[=:：]\s*false/i.test(undoReceiptCopy)
    ? 1
    : 0;
const undoUnavailableAfterSuccess =
  undoResponseBody?.undoAvailable === false &&
  undoTrigger() &&
  !undoTrigger().hidden &&
  undoTrigger().disabled &&
  (!undoRecoveryTrigger() ||
    undoRecoveryTrigger().hidden ||
    undoRecoveryTrigger().disabled)
    ? 1
    : 0;
const destructiveUndoClaim =
  /永久(?:清空|删除|擦除|抹除)|彻底(?:清空|删除|擦除|抹除)|物理删除|清空|删除|擦除|抹除|忘记|遗忘|移除(?:明文|内容|历史)?|清除(?:明文|内容|历史)?|销毁(?:明文|内容|历史)?|恢复|重做|\b(?:delete|deleted|deletion|forget|forgotten|erase|erased|wipe|wiped|purge|purged|remove plaintext|plaintext removed|restore|restored|redo)\b/i;
const forbiddenUndoElement = [...elements.values()].some(
  (element) =>
    /(?:delete|forget|erase|wipe|purge|restore|redo)/i.test(element.id) ||
    destructiveUndoClaim.test(element.textContent ?? ""),
);
const noDestructiveUndoClaim =
  destructiveUndoClaim.test(allDomCopy) || forbiddenUndoElement ? 0 : 1;
const authorityLiveChangedClaim =
  /\bAuthority\b|\bLive\b|生产(?:就绪|可用)|真实外部执行(?:已)?成功|外部\s*(?:provider|模型|connector)\s*(?:已)?\s*(?:调用|执行|成功)/i;
const exactLocalPrincipal = approvalScopePreview?.approvalPrincipal?.configuredPrincipalId;
const creationLocalOnlyBoundary =
  creationAttemptSnapshot?.provenance?.executionRoute === "LOCAL_DRAFTBOX_V2" &&
  creationAttemptSnapshot?.action?.targetRef === "local://drafts" &&
  creationAttemptSnapshot?.action?.policyVersion === "local-action-v2" &&
  creationAttemptSnapshot?.capability?.connector === "emergeos.local-draftbox" &&
  creationAttemptSnapshot?.capability?.audience === "emergeos:local-draftbox" &&
  creationAttemptSnapshot?.capability?.accountRef ===
    `local-draftbox:${exactLocalPrincipal}` &&
  creationAttemptSnapshot?.capability?.maxCalls === 1 &&
  creationAttemptSnapshot?.capability?.usedCalls === 1;
const undoLocalOnlyBoundary =
  undoResponseBody === null ||
  (undoResponseBody?.provenance?.executionRoute === "LOCAL_DRAFTBOX_V2" &&
    undoResponseBody?.capability?.connector === "emergeos.local-draftbox" &&
    undoResponseBody?.capability?.audience === "emergeos:local-draftbox" &&
    undoResponseBody?.undoReceipt?.receiptType ===
      "LOCAL_DRAFT_LOGICALLY_UNDONE_V1" &&
    undoResponseBody?.undoReceipt?.effect === "LOGICALLY_UNDONE" &&
    undoResponseBody?.undoReceipt?.retention ===
      "CAPTURE_ARTIFACT_HISTORY_RETAINED" &&
    undoResponseBody?.undoReceipt?.simulated === false);
const exactLocalOnlyBoundary =
  elements.get("outcome-canvas-boundary")?.textContent.trim() ===
    "本地演示生成（Fake，无外部模型）" &&
  approvalScopePreview?.provenance?.executionRoute === "LOCAL_DRAFTBOX_V2" &&
  approvalScopePreview?.action?.targetRef === "local://drafts" &&
  approvalScopePreview?.capability?.connector === "emergeos.local-draftbox" &&
  approvalScopePreview?.capability?.audience === "emergeos:local-draftbox" &&
  approvalScopePreview?.capability?.accountRef ===
    `local-draftbox:${exactLocalPrincipal}` &&
  approvalScopePreview?.capability?.maxCalls === 1 &&
  approvalScopePreview?.capability?.usedCalls === 0 &&
  creationLocalOnlyBoundary &&
  undoLocalOnlyBoundary;
const authorityLiveDenyComplete =
  !authorityLiveChangedClaim.test(allDomCopy) &&
  forbiddenActionCalls === 0 &&
  providerSurfaceCalls === 0 &&
  forbiddenActionRouteCalls === 0 &&
  deleteCalls === 0 &&
  legacyActionCalls === 0 &&
  reconcileCalls === 0;
const authorityLiveUnchanged =
  exactLocalOnlyBoundary && authorityLiveDenyComplete
    ? 1
    : 0;
const failClosedNoSuccess = !/本地草稿已创建|执行成功|状态\s*SUCCEEDED/.test(visibleDomCopy)
  ? 1
  : 0;
const previewV2AuthorityExact =
  approvalScopePreview?.provenance?.executionRoute === "LOCAL_DRAFTBOX_V2" &&
  approvalScopePreview?.action?.policyVersion === "local-action-v2" &&
  approvalScopePreview?.capability?.connector === "emergeos.local-draftbox" &&
  approvalScopePreview?.capability?.audience === "emergeos:local-draftbox" &&
  approvalScopePreview?.capability?.accountRef ===
    `local-draftbox:${approvalScopePreview?.approvalPrincipal?.configuredPrincipalId}` &&
  approvalScopePreview?.capability?.maxCalls === 1 &&
  approvalScopePreview?.capability?.usedCalls === 0
    ? 1
    : 0;
const approvalNonceOnClick =
  trigger && mode !== "artifact-edit" && uuidCallsBeforeApproval === 1 && uuidCalls === 2
    ? 1
    : 0;

const output = {
  SCENARIO_MODE: mode,
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
  CARD_SCOPE_EXACT: cardScopeExact,
  CARD_SENSITIVE_REFS_HIDDEN: cardSensitiveRefsHidden,
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
  LEGACY_ACTION_CALLS: legacyActionCalls,
  PROVIDER_SURFACE_CALLS: providerSurfaceCalls,
  EXECUTE_POST_COUNT: executePostCount,
  EXECUTE_POST_COUNT_BEFORE_SECOND_GESTURE: executePostCountBeforeSecondGesture,
  EXECUTE_GESTURE_PRESENT: executeGesturePresent,
  EXECUTE_CLICK_DISPATCHED: executeClickDispatched,
  EXECUTE_REQUEST_EXACT: executeRequestExact,
  EXECUTE_FETCH_OPTIONS_EXACT: executeFetchOptionsExact,
  EXECUTE_STATUS: executeStatus,
  EXECUTE_RESPONSE_STRICT: executeResponseStrict,
  EXECUTE_POST_DURING_SECOND_GESTURE: executePostDuringSecondGesture,
  UNEXPECTED_EXECUTE_POST_COUNT: unexpectedExecutePostCount,
  EXECUTE_SUBMITTING_STATE_OBSERVED: executeSubmittingStateObserved,
  QUIET_BEFORE_SECOND_GESTURE: quietBeforeSecondGesture,
  QUIET_AFTER_UNKNOWN: quietAfterUnknown,
  TERMINAL_WAIT_REACHED: terminalWaitReached,
  RECOVERY_GET_COUNT: recoveryGetCount,
  RECOVERY_GET_DURING_EXPLICIT_GESTURE: recoveryGetDuringExplicitGesture,
  RECOVERY_CANONICAL_EXACT: recoveryCanonicalExact,
  RECOVERY_GESTURE_PRESENT: recoveryGesturePresent,
  RECOVERY_CLICK_DISPATCHED: recoveryClickDispatched,
  REPLAY_CANONICAL_EXACT: replayCanonicalExact,
  GET_CANONICAL_EXACT: getCanonicalExact,
  EXECUTE_INVALIDATED_AFTER_EDIT: executeInvalidatedAfterEdit,
  EXECUTE_TYPED_STALE_PROBLEM: executeTypedStaleProblem,
  EXECUTE_MALFORMED_INJECTED: executeMalformedInjected,
  EXECUTE_UNKNOWN_BEFORE_RECOVERY: executeUnknownBeforeRecovery,
  TIMER_CANARY_OBSERVED: timerCanaryObserved,
  TIMER_INHERITED_SECOND_GESTURE: timerInheritedSecondGesture,
  EXECUTING_EDIT_TRIGGERED: executingEditTriggered,
  EXECUTING_EDIT_HISTORICAL_UNKNOWN: executingEditHistoricalUnknown,
  EXECUTING_EDIT_GET_ONLY: executingEditGetOnly,
  EXECUTING_EDIT_OLD_SCOPE_POST_BLOCKED: executingEditOldScopePostBlocked,
  EXECUTING_EDIT_SAME_ATTEMPT_RECOVERY: executingEditSameAttemptRecovery,
  EXECUTING_EDIT_RECOVERED_SUCCEEDED: executingEditRecoveredSucceeded,
  EXECUTING_EDIT_SAVE_CLICK_DISPATCHED: executingEditSaveClickDispatched,
  EXECUTING_EDIT_V2_BOUNDARY_EXACT: executingEditV2BoundaryExact,
  EXECUTING_EDIT_V2_SCOPE_RECOVERY_ISOLATED: executingEditV2ScopeRecoveryIsolated,
  EXECUTING_EDIT_V2_HANDLERS_FORCED: executingEditV2HandlersForced,
  EXECUTING_EDIT_V2_FORCED_HANDLERS_NO_POST: executingEditV2ForcedHandlersNoPost,
  EXECUTING_EDIT_SECOND_EDIT_DISPATCHED: executingEditSecondEditDispatched,
  EXECUTING_EDIT_SECOND_DIRTY_RECOVERY_RETAINED:
    executingEditSecondDirtyRecoveryRetained,
  EXECUTING_EDIT_SECOND_DIRTY_HANDLERS_FORCED:
    executingEditSecondDirtyHandlersForced,
  EXECUTING_EDIT_SECOND_DIRTY_FORCED_HANDLERS_NO_POST:
    executingEditSecondDirtyForcedHandlersNoPost,
  EXECUTING_EDIT_POST_COUNTS_STABLE: executingEditPostCountsStable,
  EXECUTING_EDIT_NO_HANDLE_OVERWRITE: executingEditNoHandleOverwrite,
  READY_RECOVERY_EXECUTING_EDIT_TRIGGERED: readyRecoveryExecutingEditTriggered,
  READY_RECOVERY_HISTORICAL_UNKNOWN: readyRecoveryHistoricalUnknown,
  READY_RECOVERY_SAVE_CLICK_DISPATCHED: readyRecoverySaveClickDispatched,
  READY_RECOVERY_V2_BOUNDARY_EXACT: readyRecoveryV2BoundaryExact,
  READY_RECOVERY_V2_ISOLATED: readyRecoveryV2Isolated,
  READY_RECOVERY_PRE_RECOVERY_SETTLED: readyRecoveryPreRecoverySettled,
  READY_RECOVERY_PHASE_COMPLETED: readyRecoveryPhaseCompleted,
  READY_RECOVERY_SAME_OLD_ATTEMPT_GET: readyRecoverySameOldAttemptGet,
  READY_RECOVERY_HISTORICAL_RESULT_EXACT: readyRecoveryHistoricalResultExact,
  READY_RECOVERY_CURRENT_APPROVAL_REOPENED: readyRecoveryCurrentApprovalReopened,
  READY_RECOVERY_NO_AUTOMATIC_POSTS: readyRecoveryNoAutomaticPosts,
  ARTIFACT_REVISION_PUT_COUNT: artifactRevisionPutCount,
  TERMINAL_EDIT_INPUT_DISPATCHED: terminalEditInputDispatched,
  TERMINAL_SAVE_CLICK_DISPATCHED: terminalSaveClickDispatched,
  TERMINAL_NEW_ARTIFACT_SAVED: terminalNewArtifactSaved,
  TERMINAL_HISTORICAL_CONTEXT_EXACT: terminalHistoricalContextExact,
  TERMINAL_HISTORICAL_ARTIFACT_EXACT: terminalHistoricalArtifactExact,
  TERMINAL_NEW_READY_FACTS_EXACT: terminalNewReadyFactsExact,
  TERMINAL_CONTEXTS_NOT_MIXED: terminalContextsNotMixed,
  TERMINAL_NEW_ARTIFACT_NO_APPROVAL_OR_EXECUTE: terminalNewArtifactNoApprovalOrExecute,
  APPROVAL_READY: approvalReady,
  APPROVAL_VIA_UI: approvalViaUi,
  PLANNED_FIXTURE_USED_FOR_RED_LOCALIZATION: plannedFixtureUsedForRedLocalization,
  LOCAL_DRAFT_RENDERED: localDraftRendered,
  TYPED_RECEIPT_RENDERED: typedReceiptRendered,
  EXECUTION_SENSITIVE_REFS_HIDDEN: executionSensitiveRefsHidden,
  NO_FALSE_UNDO_CLAIM: noFalseUndoClaim,
  UNDO_BOUNDARY_EXPLICIT: undoBoundaryExplicit,
  UNDO_CONTROLS_SAFE: undoControlsSafe,
  CREATION_UNDO_CONTRACT_EXACT: creationUndoContractExact,
  UNDO_ENVELOPE_COMPATIBILITY_EXACT: undoEnvelopeCompatibilityExact,
  UNDO_PRE_TERMINAL_SAFE: undoPreTerminalSafe,
  UNDO_FORCED_PRE_TERMINAL_NO_POST: undoForcedPreTerminalNoPost,
  QUIET_BEFORE_THIRD_GESTURE: quietBeforeThirdGesture,
  UNDO_GESTURE_PRESENT: undoGesturePresent,
  UNDO_EXACT_BUTTON_COPY: undoExactButtonCopy,
  UNDO_CLICK_DISPATCHED: undoClickDispatched,
  UNDO_NONCE_ON_CLICK: undoNonceOnClick,
  UNDO_POST_COUNT: undoPostCount,
  UNDO_POST_DURING_THIRD_GESTURE: undoPostDuringThirdGesture,
  UNEXPECTED_UNDO_POST_COUNT: unexpectedUndoPostCount,
  UNDO_SUBMITTING_STATE_OBSERVED: undoSubmittingStateObserved,
  UNDO_REQUEST_EXACT: undoRequestExact,
  UNDO_FETCH_OPTIONS_EXACT: undoFetchOptionsExact,
  UNDO_STATUS: undoStatus,
  UNDO_RESPONSE_STRICT: undoResponseStrict,
  UNDO_DOUBLE_CLICK_AT_MOST_ONCE: undoDoubleClickAtMostOnce,
  UNDO_REPLAY_FIXTURE_EXACT: undoReplayFixtureExact,
  UNDO_UNKNOWN_BEFORE_RECOVERY: undoUnknownBeforeRecovery,
  UNDO_UNKNOWN_NO_FALSE_SUCCESS: undoUnknownNoFalseSuccess,
  UNDO_QUIET_AFTER_UNKNOWN: undoQuietAfterUnknown,
  UNDO_RECOVERY_GESTURE_PRESENT: undoRecoveryGesturePresent,
  UNDO_RECOVERY_CLICK_DISPATCHED: undoRecoveryClickDispatched,
  UNDO_RECOVERY_GET_COUNT: undoRecoveryGetCount,
  UNDO_RECOVERY_GET_DURING_EXPLICIT_GESTURE:
    undoRecoveryGetDuringExplicitGesture,
  UNDO_RECOVERY_CANONICAL_EXACT: undoRecoveryCanonicalExact,
  UNDO_HANGING_SIGNAL_ABORTED: undoHangingSignalAborted,
  UNDO_INFLIGHT_EDIT_TRIGGERED: undoInflightEditTriggered,
  UNDO_INFLIGHT_EDIT_HISTORICAL_UNKNOWN: undoInflightEditHistoricalUnknown,
  UNDO_INFLIGHT_EDIT_GET_ONLY: undoInflightEditGetOnly,
  UNDO_UNKNOWN_SECOND_EDIT_DISPATCHED: undoUnknownSecondEditDispatched,
  UNDO_UNKNOWN_SECOND_EDIT_RECOVERY_RETAINED:
    undoUnknownSecondEditRecoveryRetained,
  UNDO_EDIT_SAVE_CLICK_DISPATCHED: undoEditSaveClickDispatched,
  UNDO_EDIT_NEW_ARTIFACT_EXACT: undoEditNewArtifactExact,
  UNDO_EDIT_NEW_ARTIFACT_RECOVERY_ISOLATED:
    undoEditNewArtifactRecoveryIsolated,
  UNDO_EDIT_QUIET_GET_ONLY: undoEditQuietGetOnly,
  UNDO_EDIT_EXPLICIT_GET_ONLY: undoEditExplicitGetOnly,
  UNDO_EDIT_OLD_RESULT_HISTORICAL: undoEditOldResultHistorical,
  UNDO_EDIT_CONTEXTS_NOT_MIXED: undoEditContextsNotMixed,
  UNDO_EDIT_NO_SECOND_POST: undoEditNoSecondPost,
  UNDO_EFFECTIVE_STATE_RENDERED: undoEffectiveStateRendered,
  CREATION_RECEIPT_RETAINED: creationReceiptRetained,
  UNDO_RECEIPT_RENDERED: undoReceiptRendered,
  UNDO_UNAVAILABLE_AFTER_SUCCESS: undoUnavailableAfterSuccess,
  NO_DESTRUCTIVE_UNDO_CLAIM: noDestructiveUndoClaim,
  AUTHORITY_LIVE_ALLOWED_BOUNDARY_EXACT: exactLocalOnlyBoundary ? 1 : 0,
  AUTHORITY_LIVE_DENY_COMPLETE: authorityLiveDenyComplete ? 1 : 0,
  AUTHORITY_LIVE_UNCHANGED: authorityLiveUnchanged,
  FORBIDDEN_ACTION_ROUTE_CALLS: forbiddenActionRouteCalls,
  DELETE_CALLS: deleteCalls,
  FAIL_CLOSED_NO_SUCCESS: failClosedNoSuccess,
  PREVIEW_V2_AUTHORITY_EXACT: previewV2AuthorityExact,
  PREVIEW_DRIFT_FIELD: previewDriftField ?? "NONE",
  PREVIEW_DRIFT_HASH_RECOMPUTED: previewDriftHashRecomputed,
  PREVIEW_DRIFT_UNKNOWN: previewDriftUnknown,
  APPROVAL_FINAL_STATE: finalState || "MISSING",
  RECONCILE_CALLS: reconcileCalls,
  COMPLETION_CLAIM_RENDERED: completionClaimRendered,
};

for (const [key, value] of Object.entries(output)) {
  process.stdout.write(`${key}=${value}\n`);
}
