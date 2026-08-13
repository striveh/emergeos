import { createHash, webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const [
  htmlPath,
  scriptPath,
  baseUrlArgument,
  expectedCaptureDigest,
  deterministicNonce = "outcome-canvas-seed-001",
  mode = "happy",
] = process.argv.slice(2);
if (!htmlPath || !scriptPath || !baseUrlArgument || !expectedCaptureDigest) {
  throw new Error("HTML, served script, loopback base URL, and capture digest are required");
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
const servedScript = await readFile(scriptPath, "utf8");
const nativeFetch = globalThis.fetch;
const listeners = new Map();

const makeElement = (id, initial = {}) => ({
  id,
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
  },
  getAttribute(name) {
    return this.attributes[name] ?? null;
  },
  addEventListener(type, listener) {
    const key = `${id}:${type}`;
    const registered = listeners.get(key) ?? [];
    registered.push(listener);
    listeners.set(key, registered);
  },
  focus() {
    this.focusCount += 1;
  },
  ...initial,
});

const elements = new Map();
for (const match of html.matchAll(/\bid\s*=\s*(["'])([^"']+)\1/gi)) {
  elements.set(match[2], makeElement(match[2]));
}
const elementText = (id) => {
  const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = html.match(
    new RegExp(`<[^>]+\\bid=["']${escaped}["'][^>]*>([\\s\\S]*?)<\\/[^>]+>`, "i"),
  );
  return match?.[1]?.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim() ?? "";
};

const content = makeElement("capture-content", {
  value: "synthetic outcome canvas seed",
});
const sourceRef = makeElement("source-ref", { value: "" });
const textChoice = makeElement("source-text", {
  value: "TEXT",
  checked: true,
});
const linkChoice = makeElement("source-link", {
  value: "LINK",
  checked: false,
});
const form = makeElement("quick-capture-form", {
  elements: {
    content,
    sourceType: { value: "TEXT" },
    sourceRef,
  },
  reportValidity: () => true,
});
elements.set("capture-content", content);
elements.set("source-ref", sourceRef);
elements.set("source-text", textChoice);
elements.set("source-link", linkChoice);
elements.set("quick-capture-form", form);

const triggerMatch = [...html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/gi)].find(
  (match) => match[2].replace(/<[^>]*>/g, "").replace(/\s+/g, "").includes("让它显现"),
);
const triggerIdMatch = triggerMatch?.[1].match(/\bid\s*=\s*(["'])([^"']+)\1/i);
const triggerId = triggerIdMatch?.[2] ?? null;
const triggerElement = triggerId ? elements.get(triggerId) : null;
const outcomeContent = elements.get("outcome-content") ?? elements.get("outcome-canvas-content") ?? null;
const outcomeSave = elements.get("outcome-save") ?? null;
const outcomeCanvas = elements.get("outcome-canvas") ?? null;
const outcomeBoundary = elements.get("outcome-canvas-boundary") ?? null;
if (outcomeBoundary) {
  outcomeBoundary.textContent = elementText("outcome-canvas-boundary");
}
if (outcomeCanvas) {
  outcomeCanvas.attributes["aria-live"] = "polite";
  outcomeCanvas.attributes.tabindex = "-1";
}

const document = {
  querySelector(selector) {
    if (selector === 'input[name="sourceType"]:checked') {
      return textChoice.checked ? textChoice : linkChoice;
    }
    return selector.startsWith("#") ? elements.get(selector.slice(1)) ?? null : null;
  },
  querySelectorAll(selector) {
    return selector === 'input[name="sourceType"]' ? [textChoice, linkChoice] : [];
  },
};

let captureReplayStatus = 0;
let captureIdMatch = 0;
let captureId = null;
let agentDraftPostCount = 0;
let agentDraftStatus = 0;
let agentDraftCacheNoStore = 0;
let agentDraftLocationValid = 0;
let agentDraftSucceeded = 0;
let artifactRefsExact = 0;
let evidenceRefsExact = 0;
let artifactLocation = null;
let artifactCurrentHash = null;
let revisionPutCount = 0;
let revisionPutStatus = 0;
let revisionRequestExact = 0;
let revisionV2Strict = 0;
let revisionRequestStable = 1;
let firstRevisionRequest = null;
let revisionUiGetCount = 0;
let revisionLocalPreserved = 0;
let revisionRemoteSeparate = 0;
let revisionExplicitRetry = 0;
const revisionContent = "synthetic edited Outcome Canvas version two";
const revisionV3Content = "synthetic continuously edited Outcome Canvas version three";
const competingRevisionContent = "synthetic competing server Outcome Canvas version two";
let revisionV2Hash = null;
let revisionV3RequestExact = 0;
let revisionV3PutCount = 0;
let revisionV3Strict = 0;
let artifactDurableV3 = 0;
const cryptoFacade = new Proxy(webcrypto, {
  get(target, property) {
    if (property === "randomUUID") {
      return () => deterministicNonce;
    }
    const value = Reflect.get(target, property, target);
    return typeof value === "function" ? value.bind(target) : value;
  },
});
const uiCaptureGetPaths = [];
const uiArtifactGetPaths = [];
let artifactV1Content = null;
let releasePendingDraft;
let pendingDraftStarted;
const pendingDraftStartedPromise = new Promise((resolve) => {
  pendingDraftStarted = resolve;
});
const jsonResponse = (value, status, headers = {}) =>
  new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "private, no-store",
      ...headers,
    },
  });
const recordRevisionResponse = async (response) => {
  revisionPutStatus = response.status;
  try {
    const revised = await response.clone().json();
    const v1 = revised?.versions?.[0];
    const v2 = revised?.versions?.[1];
    const candidateV2Strict =
      revised?.currentVersion === 2 &&
      typeof revised?.currentHash === "string" &&
      revised.currentHash === v2?.contentHash &&
      Array.isArray(revised?.versions) &&
      revised.versions.length === 2 &&
      v1?.version === 1 &&
      v1?.baseVersion === null &&
      v1?.baseHash === null &&
      v2?.version === 2 &&
      v2?.content === revisionContent &&
      v2?.baseVersion === 1 &&
      v2?.baseHash === v1?.contentHash
        ? 1
        : 0;
    if (revisionPutCount === 1) {
      revisionV2Strict = candidateV2Strict;
      if (revisionV2Strict === 1) {
        revisionV2Hash = revised.currentHash;
      }
    }
    const v3 = revised?.versions?.[2];
    if (revisionPutCount === 2) {
      revisionV3Strict =
        revised?.currentVersion === 3 &&
        revised?.currentHash === v3?.contentHash &&
        revised?.versions?.length === 3 &&
        v1?.content === artifactV1Content &&
        v1?.contentHash === artifactCurrentHash &&
        v2?.content === revisionContent &&
        v2?.contentHash === revisionV2Hash &&
        v2?.baseVersion === 1 &&
        v2?.baseHash === artifactCurrentHash &&
        v3?.version === 3 &&
        v3?.content === revisionV3Content &&
        v3?.contentHash === createHash("sha256").update(revisionV3Content).digest("hex") &&
        v3?.baseVersion === 2 &&
        v3?.baseHash === revisionV2Hash
          ? 1
          : 0;
    }
  } catch (_failure) {
    if (revisionPutCount === 1) {
      revisionV2Strict = 0;
    }
    if (revisionPutCount === 2) {
      revisionV3Strict = 0;
    }
  }
};
const context = vm.createContext({
  document,
  crypto: cryptoFacade,
  FormData: class FormData {
    constructor() {}
  },
  fetch: async (input, options = {}) => {
    if (typeof input !== "string" || !input.startsWith("/")) {
      throw new Error("served UI fetch must stay same-origin and relative");
    }
    const method = (options.method ?? "GET").toUpperCase();
    if (/^\/api\/v1\/captures\/[A-Za-z0-9._:-]+$/.test(input) && method === "GET") {
      uiCaptureGetPaths.push(input);
    }
    if (/^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) && method === "GET") {
      uiArtifactGetPaths.push(input);
    }
    if (input === "/api/v1/agent-drafts" && method === "POST") {
      agentDraftPostCount += 1;
    }
    if (/^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) && method === "PUT") {
      revisionPutCount += 1;
      try {
        const body = JSON.parse(options.body ?? "null");
        const canonicalBody = JSON.stringify(body);
        if (firstRevisionRequest === null) {
          firstRevisionRequest = canonicalBody;
        } else if (firstRevisionRequest !== canonicalBody) {
          revisionRequestStable = 0;
        }
        if (revisionPutCount === 1) {
          revisionRequestExact =
            body?.content === revisionContent &&
            body?.expectedBaseVersion === 1 &&
            typeof body?.expectedBaseHash === "string" &&
            body.expectedBaseHash === artifactCurrentHash
              ? 1
              : 0;
        }
        if (revisionPutCount === 2) {
          revisionV3PutCount += 1;
          revisionV3RequestExact =
            body?.content === revisionV3Content &&
            body?.expectedBaseVersion === 2 &&
            body?.expectedBaseHash === revisionV2Hash
              ? 1
              : 0;
        }
      } catch (_failure) {
        revisionRequestExact = 0;
        revisionRequestStable = 0;
      }
    }
    let response;
    if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-409-competing" &&
      revisionPutCount === 1
    ) {
      await nativeFetch(new URL(input, appBaseUrl), {
        ...options,
        body: JSON.stringify({
          content: competingRevisionContent,
          expectedBaseVersion: 1,
          expectedBaseHash: artifactCurrentHash,
        }),
      });
      response = await nativeFetch(new URL(input, appBaseUrl), options);
    } else if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-other-head" &&
      revisionPutCount === 1
    ) {
      await nativeFetch(new URL(input, appBaseUrl), {
        ...options,
        body: JSON.stringify({
          content: competingRevisionContent,
          expectedBaseVersion: 1,
          expectedBaseHash: artifactCurrentHash,
        }),
      });
      response = jsonResponse({ failure: "SYNTHETIC_BOUNDED_503" }, 503);
    } else if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-5xx-retry" &&
      revisionPutCount === 1
    ) {
      response = jsonResponse({ failure: "SYNTHETIC_BOUNDED_503" }, 503);
    } else if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-get-fail" &&
      revisionPutCount === 1
    ) {
      response = jsonResponse({ failure: "SYNTHETIC_BOUNDED_503" }, 503);
    } else if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-invalid-200" &&
      revisionPutCount === 1
    ) {
      response = jsonResponse({
        artifactId: artifactLocation?.slice(artifactLocation.lastIndexOf("/") + 1),
        captureId,
        currentVersion: 2,
        currentHash: "0".repeat(64),
        versions: [
          { version: 1, content: artifactV1Content, contentHash: artifactCurrentHash, baseVersion: null, baseHash: null },
          { version: 2, content: revisionContent, contentHash: "0".repeat(64), baseVersion: 999, baseHash: "f".repeat(64) },
        ],
      }, 200);
    } else if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-oversize-200" &&
      revisionPutCount === 1
    ) {
      const committed = await nativeFetch(new URL(input, appBaseUrl), options);
      response = new Response("x".repeat(262145), {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": committed.headers.get("cache-control") ?? "private, no-store",
          "Content-Length": "262145",
        },
      });
    } else if (
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      mode === "revision-stream-interrupted-200" &&
      revisionPutCount === 1
    ) {
      const committed = await nativeFetch(new URL(input, appBaseUrl), options);
      let readCount = 0;
      response = {
        status: 200,
        headers: committed.headers,
        clone: () => committed.clone(),
        body: {
          getReader: () => ({
            read: async () => {
              readCount += 1;
              if (readCount === 1) {
                return { done: false, value: new Uint8Array([0x7b]) };
              }
              throw new TypeError("synthetic revision 200 body stream interrupted");
            },
            cancel: async () => {},
          }),
        },
      };
    } else if (
      input === "/api/v1/agent-drafts" &&
      method === "POST" &&
      ["known-422", "malformed-422-cross-run", "malformed-422-schema"].includes(mode)
    ) {
      const runId = "bounded-failed-run-001";
      const runRef = `/api/v1/agent-runs/${runId}`;
      response = jsonResponse(
        {
          runId,
          runRef,
          bundleRef: `${runRef}/bundle`,
          result: {
            schemaVersion: mode === "malformed-422-schema" ? "2.0" : "1.0",
            runId:
              mode === "malformed-422-cross-run"
                ? "cross-bound-failed-run-002"
                : runId,
            status: "FAILED",
            traceRef: `${runRef}/trace`,
            failureReason: "SYNTHETIC_BOUNDED_FAILURE",
            resolvedModel: null,
            artifactRefs: [],
            receiptRefs: [],
          },
          trace: [],
        },
        422,
      );
    } else if (
      input === "/api/v1/agent-drafts" &&
      method === "POST" &&
      mode === "malformed-422"
    ) {
      response = jsonResponse(
        { result: { status: "FAILED", failureReason: "unsafe detail" } },
        422,
      );
    } else {
      response = await nativeFetch(new URL(input, appBaseUrl), options);
    }
    if (
      mode === "wrong-mime-201" &&
      input === "/api/v1/agent-drafts" &&
      method === "POST"
    ) {
      response = new Response(await response.clone().text(), {
        status: response.status,
        headers: {
          "Content-Type": "text/plain",
          "Cache-Control": response.headers.get("cache-control") ?? "",
          Location: response.headers.get("location") ?? "",
        },
      });
    }
    if (
      mode === "wrong-mime-200" &&
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      revisionPutCount === 1
    ) {
      response = new Response(await response.clone().text(), {
        status: response.status,
        headers: {
          "Content-Type": "text/plain",
          "Cache-Control": response.headers.get("cache-control") ?? "",
        },
      });
    }
    if (
      mode === "revision-v3-history-tamper" &&
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "PUT" &&
      revisionPutCount === 2
    ) {
      const artifact = await response.clone().json();
      response = jsonResponse({
        ...artifact,
        versions: artifact.versions.map((version, index) =>
          index === 0
            ? { ...version, content: `${version.content} [tampered history]` }
            : version),
      }, 200);
    }
    if (
      mode === "revision-get-fail" &&
      revisionPutCount > 0 &&
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "GET"
    ) {
      response = jsonResponse({ failure: "SYNTHETIC_BOUNDED_READBACK_503" }, 503);
    }
    if (input === "/api/v1/agent-drafts" && method === "POST") {
      if (mode === "draft-response-lost") {
        throw new TypeError("synthetic committed draft response loss");
      }
      if (mode === "pending-reset") {
        pendingDraftStarted();
        await new Promise((resolve) => {
          releasePendingDraft = resolve;
        });
      }
      if (["invalid-location", "crossrefs", "oversize-body"].includes(mode)) {
        const actualDraft = await response.clone().json();
        if (mode === "invalid-location") {
          response = jsonResponse(actualDraft, 201, { Location: "https://invalid.example/artifact" });
        } else if (mode === "crossrefs") {
          response = jsonResponse(
            {
              ...actualDraft,
              result: {
                ...actualDraft.result,
                artifactRefs: ["artifact-version://cross-boundary/1"],
                evidenceRefs: ["capture://cross-boundary"],
              },
            },
            201,
            { Location: response.headers.get("location") ?? "" },
          );
        } else {
          response = new Response("x".repeat(262145), {
            status: 201,
            headers: {
              "Content-Type": "application/json",
              "Cache-Control": "private, no-store",
              Location: response.headers.get("location") ?? "",
            },
          });
        }
      }
    }
    if (
      mode === "content-hash-mismatch" &&
      /^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) &&
      method === "GET"
    ) {
      const artifact = await response.clone().json();
      response = jsonResponse({
        ...artifact,
        versions: [
          {
            ...artifact.versions[0],
            content: `${artifact.versions[0].content} [tampered without rehash]`,
          },
        ],
      }, 200);
    }
    if (input === "/api/v1/captures" && method === "POST") {
      captureReplayStatus = response.status;
      const receipt = await response.clone().json();
      captureId = typeof receipt?.captureId === "string" ? receipt.captureId : null;
      const digest =
        captureId
          ? createHash("sha256").update(captureId).digest("hex")
          : "MISSING";
      captureIdMatch = digest === expectedCaptureDigest ? 1 : 0;
    }
    if (input === "/api/v1/agent-drafts" && method === "POST") {
      agentDraftStatus = response.status;
      agentDraftCacheNoStore =
        response.headers.get("cache-control") === "private, no-store" ? 1 : 0;
      const location = response.headers.get("location");
      if (/^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(location ?? "")) {
        artifactLocation = location;
        agentDraftLocationValid = 1;
      }
      try {
        const draft = await response.clone().json();
        const artifactId = artifactLocation?.slice(artifactLocation.lastIndexOf("/") + 1);
        agentDraftSucceeded = draft?.result?.status === "SUCCEEDED" ? 1 : 0;
        artifactRefsExact =
          artifactId &&
          JSON.stringify(draft?.result?.artifactRefs) ===
            JSON.stringify([`artifact-version://${artifactId}/1`])
            ? 1
            : 0;
        evidenceRefsExact =
          captureId &&
          JSON.stringify(draft?.result?.evidenceRefs) ===
            JSON.stringify([`capture://${captureId}`])
            ? 1
            : 0;
      } catch (_failure) {
        // The Java acceptance treats missing or malformed result fields as zero markers.
      }
    }
    if (/^\/api\/v1\/artifacts\/[A-Za-z0-9._:-]+$/.test(input) && method === "PUT") {
      await recordRevisionResponse(response);
      if (mode === "revision-response-lost" && response.status === 200) {
        throw new TypeError("synthetic committed revision response loss");
      }
    }
    return response;
  },
  console,
  AbortController: globalThis.AbortController,
  setTimeout,
  clearTimeout,
  URL,
  URLSearchParams,
});

vm.runInContext(servedScript, context, {
  filename: "served:/capture/quick-capture.js",
});

const submitListeners = listeners.get("quick-capture-form:submit") ?? [];
let submitDispatched = false;
for (const listener of submitListeners) {
  await listener({
    preventDefault() {
      submitDispatched = true;
    },
  });
}

const triggerListeners = triggerId ? listeners.get(`${triggerId}:click`) ?? [] : [];
let triggerDispatched = false;
const dispatchTrigger = async () => {
  for (const listener of triggerListeners) {
    await listener({
      preventDefault() {
        triggerDispatched = true;
      },
    });
    triggerDispatched = true;
  }
};
let pendingOldCompletionIsolated = 0;
if (mode === "pending-reset") {
  const pendingTrigger = dispatchTrigger();
  await pendingDraftStartedPromise;
  const newEntryListeners = listeners.get("capture-new-entry:click") ?? [];
  for (const listener of newEntryListeners) {
    await listener({ preventDefault() {} });
  }
  content.value = "synthetic replacement while old generation is pending";
  const inputListeners = listeners.get("quick-capture-form:input") ?? [];
  for (const listener of inputListeners) {
    await listener({});
  }
  await dispatchTrigger();
  releasePendingDraft();
  await pendingTrigger;
  pendingOldCompletionIsolated =
    agentDraftPostCount === 1 &&
    outcomeCanvas?.dataset.state !== "CANVAS_READY" &&
    outcomeCanvas?.dataset.state !== "GENERATION_UNKNOWN"
      ? 1
      : 0;
} else {
  await Promise.all([dispatchTrigger(), dispatchTrigger()]);
  await dispatchTrigger();
}

const canvasStateAfterGeneration = outcomeCanvas?.dataset.state ?? "MISSING";
const canvasContentAfterGeneration = outcomeContent?.textContent ?? "";
const canvasFakeBoundaryAfterGeneration = outcomeBoundary?.textContent.includes("Fake") ? 1 : 0;

const uiCaptureGetCount = uiCaptureGetPaths.length;
const uiArtifactGetCount = uiArtifactGetPaths.length;
const uiCaptureGetPathExact =
  captureId &&
  uiCaptureGetPaths.length === 1 &&
  uiCaptureGetPaths[0] === `/api/v1/captures/${encodeURIComponent(captureId)}`
    ? 1
    : 0;
const uiArtifactGetPathExact =
  artifactLocation &&
  uiArtifactGetPaths.length === 1 &&
  uiArtifactGetPaths[0] === artifactLocation
    ? 1
    : 0;

let captureGetStatus = 0;
let artifactGetStatus = 0;
let artifactV1Match = 0;
if (captureId) {
  const captureResponse = await nativeFetch(
    new URL(`/api/v1/captures/${encodeURIComponent(captureId)}`, appBaseUrl),
    { headers: { Accept: "application/json" }, cache: "no-store", redirect: "error" },
  );
  captureGetStatus = captureResponse.status;
}
if (artifactLocation) {
  const artifactResponse = await nativeFetch(new URL(artifactLocation, appBaseUrl), {
    headers: { Accept: "application/json" },
    cache: "no-store",
    redirect: "error",
  });
  artifactGetStatus = artifactResponse.status;
  try {
    const artifact = await artifactResponse.json();
    artifactCurrentHash = artifact?.currentHash ?? null;
    artifactV1Content = artifact?.versions?.[0]?.content ?? null;
    artifactV1Match =
      artifact?.captureId === captureId &&
      artifact?.currentVersion === 1 &&
      Array.isArray(artifact?.versions) &&
      artifact.versions.length === 1 &&
      artifact.versions[0]?.version === 1
        ? 1
        : 0;
  } catch (_failure) {
    artifactV1Match = 0;
  }
}

let revisionDirtyObserved = 0;
let revisionSavePresent = outcomeSave ? 1 : 0;
let revisionSaveDispatched = 0;
if (outcomeContent) {
  outcomeContent.textContent = revisionContent;
  const inputListeners = listeners.get(`${outcomeContent.id}:input`) ?? [];
  for (const listener of inputListeners) {
    await listener({});
  }
  revisionDirtyObserved =
    outcomeContent.dataset.state === "DIRTY" ||
    elements.get("outcome-canvas")?.dataset.state === "DIRTY"
      ? 1
      : 0;
}
if (outcomeSave) {
  const saveListeners = listeners.get("outcome-save:click") ?? [];
  const dispatchSave = async () => {
    for (const listener of saveListeners) {
      await listener({ preventDefault() {} });
      revisionSaveDispatched = 1;
    }
  };
  if (mode === "revision-double-save") {
    await Promise.all([dispatchSave(), dispatchSave()]);
    await dispatchSave();
  } else {
    await dispatchSave();
    if (mode === "revision-response-lost") {
      await dispatchSave();
    }
    if (mode === "revision-5xx-retry") {
      revisionExplicitRetry =
        outcomeCanvas?.dataset.state === "DIRTY" && revisionPutCount === 1 ? 1 : 0;
      await dispatchSave();
    }
  }
}

revisionUiGetCount = uiArtifactGetPaths.length - uiArtifactGetCount;
revisionLocalPreserved = outcomeContent?.textContent === revisionContent ? 1 : 0;
revisionRemoteSeparate =
  elements.get("outcome-conflict-content")?.textContent === competingRevisionContent ? 1 : 0;
const revisionState = outcomeCanvas?.dataset.state ?? "MISSING";

let artifactDurableV2 = 0;
if (artifactLocation && revisionPutStatus === 200) {
  const durableResponse = await nativeFetch(new URL(artifactLocation, appBaseUrl), {
    headers: { Accept: "application/json" },
    cache: "no-store",
    redirect: "error",
  });
  try {
    const durable = await durableResponse.json();
    artifactDurableV2 =
      durableResponse.status === 200 &&
      durable?.currentVersion === 2 &&
      durable?.versions?.[1]?.content === revisionContent &&
      durable?.versions?.[1]?.baseVersion === 1 &&
      durable?.versions?.[1]?.baseHash === durable?.versions?.[0]?.contentHash
        ? 1
        : 0;
  } catch (_failure) {
    artifactDurableV2 = 0;
  }
}

let revisionV3DirtyObserved = 0;
let revisionV3State = "MISSING";
if (["happy", "revision-v3-history-tamper"].includes(mode) && outcomeContent && outcomeSave) {
  outcomeContent.textContent = revisionV3Content;
  const inputListeners = listeners.get(`${outcomeContent.id}:input`) ?? [];
  for (const listener of inputListeners) {
    await listener({});
  }
  revisionV3DirtyObserved = outcomeCanvas?.dataset.state === "DIRTY" ? 1 : 0;
  const saveListeners = listeners.get("outcome-save:click") ?? [];
  for (const listener of saveListeners) {
    await listener({ preventDefault() {} });
  }
  revisionV3State = outcomeCanvas?.dataset.state ?? "MISSING";
  if (artifactLocation) {
    const response = await nativeFetch(new URL(artifactLocation, appBaseUrl), {
      headers: { Accept: "application/json" },
      cache: "no-store",
      redirect: "error",
    });
    try {
      const artifact = await response.json();
      artifactDurableV3 =
        response.status === 200 &&
        artifact?.currentVersion === 3 &&
        artifact?.versions?.length === 3 &&
        artifact.versions[2]?.content === revisionV3Content &&
        artifact.versions[2]?.baseVersion === 2 &&
        artifact.versions[2]?.baseHash === artifact.versions[1]?.contentHash
          ? 1
          : 0;
    } catch (_failure) {
      artifactDurableV3 = 0;
    }
  }
}

const outcomeA11yExact =
  outcomeCanvas?.attributes.role === "alert" &&
  outcomeCanvas?.attributes["aria-live"] === "assertive" &&
  outcomeCanvas?.focusCount >= 2
    ? 1
    : 0;

console.log(`NODE_MAJOR=${nodeMajor}`);
console.log("SCRIPT_EXECUTED=1");
console.log(`CAPTURE_SUBMIT_DISPATCHED=${submitDispatched ? 1 : 0}`);
console.log(`CAPTURE_REPLAY_STATUS=${captureReplayStatus}`);
console.log(`CAPTURE_ID_MATCH=${captureIdMatch}`);
console.log(`TRIGGER_PRESENT=${triggerElement ? 1 : 0}`);
console.log(`TRIGGER_DISPATCHED=${triggerDispatched ? 1 : 0}`);
console.log(`AGENT_DRAFT_POST_COUNT=${agentDraftPostCount}`);
console.log(`AGENT_DRAFT_STATUS=${agentDraftStatus}`);
console.log(`AGENT_DRAFT_CACHE_NO_STORE=${agentDraftCacheNoStore}`);
console.log(`AGENT_DRAFT_LOCATION_VALID=${agentDraftLocationValid}`);
console.log(`AGENT_DRAFT_SUCCEEDED=${agentDraftSucceeded}`);
console.log(`ARTIFACT_REFS_EXACT=${artifactRefsExact}`);
console.log(`EVIDENCE_REFS_EXACT=${evidenceRefsExact}`);
console.log(`CAPTURE_GET_STATUS=${captureGetStatus}`);
console.log(`ARTIFACT_GET_STATUS=${artifactGetStatus}`);
console.log(`ARTIFACT_V1_MATCH=${artifactV1Match}`);
console.log(`CANVAS_STATE=${canvasStateAfterGeneration}`);
console.log(`CANVAS_V1_CONTENT_RENDERED=${artifactV1Content && canvasContentAfterGeneration === artifactV1Content ? 1 : 0}`);
console.log(`CANVAS_FAKE_BOUNDARY_RENDERED=${canvasFakeBoundaryAfterGeneration}`);
console.log(`UI_CAPTURE_GET_COUNT=${uiCaptureGetCount}`);
console.log(`UI_ARTIFACT_GET_COUNT=${uiArtifactGetCount}`);
console.log(`UI_CAPTURE_GET_PATH_EXACT=${uiCaptureGetPathExact}`);
console.log(`UI_ARTIFACT_GET_PATH_EXACT=${uiArtifactGetPathExact}`);
console.log(`PENDING_OLD_COMPLETION_ISOLATED=${pendingOldCompletionIsolated}`);
console.log(`REVISION_SAVE_PRESENT=${revisionSavePresent}`);
console.log(`REVISION_DIRTY_OBSERVED=${revisionDirtyObserved}`);
console.log(`REVISION_SAVE_DISPATCHED=${revisionSaveDispatched}`);
console.log(`REVISION_PUT_COUNT=${revisionPutCount}`);
console.log(`REVISION_REQUEST_EXACT=${revisionRequestExact}`);
console.log(`REVISION_REQUEST_STABLE=${revisionRequestStable}`);
console.log(`REVISION_PUT_STATUS=${revisionPutStatus}`);
console.log(`REVISION_V2_STRICT=${revisionV2Strict}`);
console.log(`ARTIFACT_DURABLE_V2=${artifactDurableV2}`);
console.log(`REVISION_STATE=${revisionState}`);
console.log(`REVISION_UI_GET_COUNT=${revisionUiGetCount}`);
console.log(`REVISION_LOCAL_PRESERVED=${revisionLocalPreserved}`);
console.log(`REVISION_REMOTE_SEPARATE=${revisionRemoteSeparate}`);
console.log(`REVISION_EXPLICIT_RETRY=${revisionExplicitRetry}`);
console.log(`ARTIFACT_PATH=${artifactLocation ?? "MISSING"}`);
console.log(`REVISION_V3_DIRTY_OBSERVED=${revisionV3DirtyObserved}`);
console.log(`REVISION_V3_PUT_COUNT=${revisionV3PutCount}`);
console.log(`REVISION_V3_REQUEST_EXACT=${revisionV3RequestExact}`);
console.log(`REVISION_V3_STRICT=${revisionV3Strict}`);
console.log(`REVISION_V3_STATE=${revisionV3State}`);
console.log(`ARTIFACT_DURABLE_V3=${artifactDurableV3}`);
console.log(`OUTCOME_A11Y_EXACT=${outcomeA11yExact}`);
