import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import vm from "node:vm";

const servedScriptPath = process.argv[2];
const appBaseUrlArgument = process.argv[3];
const mode = process.argv[4] ?? "create";
const deterministicNonce = process.argv[5] ?? "8b5e91b1-75de-4f1f-90af-1f58a79f4c31";
if (!servedScriptPath || !appBaseUrlArgument) {
  throw new Error("served Quick Capture script path and app base URL are required");
}

const nodeMajor = Number.parseInt(process.versions.node.split(".")[0], 10);
if (nodeMajor !== 22 || typeof globalThis.fetch !== "function") {
  throw new Error("Node 22 global fetch is required");
}

const appBaseUrl = new URL(appBaseUrlArgument);
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
const nativeFetch = globalThis.fetch;
const captureEndpoint = "/api/v1/captures";

const listeners = new Map();
let focusedElementId = "NONE";
const makeElement = (initial = {}) => ({
  hidden: false,
  required: false,
  disabled: false,
  dataset: {},
  textContent: "",
  value: "",
  checked: false,
  attributes: {},
  setAttribute(name, value) {
    this.attributes[name] = String(value);
  },
  getAttribute(name) {
    return this.attributes[name] ?? null;
  },
  focus() {
    focusedElementId = initial.id ?? "element";
  },
  addEventListener(type, listener) {
    const key = `${initial.id ?? "element"}:${type}`;
    const registered = listeners.get(key) ?? [];
    registered.push(listener);
    listeners.set(key, registered);
  },
  ...initial,
});

const content = makeElement({ id: "capture-content", value: "synthetic durable thought" });
const sourceRef = makeElement({ id: "source-ref", value: "" });
const textChoice = makeElement({ id: "source-text", value: "TEXT", checked: true });
const linkChoice = makeElement({ id: "source-link", value: "LINK", checked: false });
const form = makeElement({
  id: "quick-capture-form",
  elements: {
    content,
    sourceType: { value: "TEXT" },
    sourceRef,
  },
});
const statusElement = makeElement({
  id: "capture-status",
  attributes: { role: "status", "aria-live": "polite" },
});
const abandonRetryButton = makeElement({ id: "capture-abandon-retry", hidden: true });
const newEntryButton = makeElement({ id: "capture-new-entry", hidden: true });
const elements = new Map([
  ["#quick-capture-form", form],
  ["#link-source-field", makeElement({ id: "link-source-field" })],
  ["#source-ref", sourceRef],
  ["#capture-content", content],
  ["#capture-status", statusElement],
  ["#capture-abandon-retry", abandonRetryButton],
  ["#capture-new-entry", newEntryButton],
]);

const document = {
  querySelector(selector) {
    if (selector === 'input[name="sourceType"]:checked') {
      return textChoice.checked ? textChoice : linkChoice;
    }
    return elements.get(selector) ?? null;
  },
  querySelectorAll(selector) {
    return selector === 'input[name="sourceType"]'
      ? [textChoice, linkChoice]
      : [];
  },
};

let postCount = 0;
const postBodies = [];
const postStatuses = [];
const captureIdDigests = [];
let uuidCount = 0;
let postStatus = 0;
let postCacheNoStore = 1;
let captureId = null;
let receiptSourceType = "MISSING";
let receiptDataClass = "MISSING";
let receiptCapturedAt = "MISSING";
const unsafeReceiptValues = [];
let unsafeReceiptValueDisplayed = 0;
const context = vm.createContext({
  document,
  crypto:
    mode === "crypto-missing"
      ? {}
      : {
          randomUUID: () => {
            uuidCount += 1;
            return mode === "success-new-entry" && uuidCount > 1
              ? `${deterministicNonce}-new`
              : deterministicNonce;
          },
        },
  FormData: class FormData {
    constructor() {}
  },
  fetch: async (input, options = {}) => {
    if (typeof input !== "string" || input !== captureEndpoint) {
      throw new Error("served script fetch must use only relative /api/v1/captures");
    }

    const method = (options.method ?? "GET").toUpperCase();
    if (method === "POST") {
      postCount += 1;
      postBodies.push(options.body ?? "");
    }

    const forwardedOptions =
      mode === "invalid-415"
        ? {
            ...options,
            headers: { ...options.headers, "Content-Type": "text/plain" },
          }
        : options;
    if (mode === "timeout-retry" && method === "POST" && postCount === 1) {
      throw new DOMException("synthetic bounded abort", "AbortError");
    }

    let response;
    if (mode === "server-5xx-retry" && method === "POST" && postCount === 1) {
      response = new Response("{}", {
        status: 503,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "private, no-store",
        },
      });
    } else {
      response = await nativeFetch(new URL(input, appBaseUrl), forwardedOptions);
    }
    if (method === "POST" && mode.startsWith("receipt-")) {
      const actualReceipt = await response.clone().json();
      const boundedReceipt =
        mode.includes("malformed")
          ? { ...actualReceipt, capturedAt: "" }
          : { ...actualReceipt, sourceType: "LINK", dataClass: "PUBLIC" };
      response = new Response(JSON.stringify(boundedReceipt), {
        status: response.status,
        headers: response.headers,
      });
    }
    if (method === "POST") {
      postStatus = response.status;
      postStatuses.push(response.status);
      if (response.headers.get("cache-control") !== "private, no-store") {
        postCacheNoStore = 0;
      }
      try {
        const receipt = await response.clone().json();
        captureId =
          typeof receipt?.captureId === "string" && receipt.captureId.trim()
            ? receipt.captureId.trim()
            : null;
        receiptSourceType =
          typeof receipt?.sourceType === "string" ? receipt.sourceType : "MISSING";
        receiptDataClass =
          typeof receipt?.dataClass === "string" ? receipt.dataClass : "MISSING";
        receiptCapturedAt =
          typeof receipt?.capturedAt === "string" ? receipt.capturedAt : "MISSING";
        for (const field of [
          "requestHash",
          "principalId",
          "content",
          "sourceRef",
          "clientNonce",
        ]) {
          if (typeof receipt?.[field] === "string") {
            unsafeReceiptValues.push(receipt[field]);
          }
        }
        captureIdDigests.push(
          captureId
            ? createHash("sha256").update(captureId).digest("hex")
            : "MISSING",
        );
      } catch (_failure) {
        captureId = null;
        receiptSourceType = "MISSING";
        receiptDataClass = "MISSING";
      }
      if (["lost", "unknown-only", "lost-abandon-restore"].includes(mode) && postCount === 1) {
        throw new TypeError("synthetic response loss after durable HTTP response");
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

const servedScript = await readFile(servedScriptPath, "utf8");
vm.runInContext(servedScript, context, {
  filename: "served:/capture/quick-capture.js",
});

const submitListeners = listeners.get("quick-capture-form:submit") ?? [];
if (submitListeners.length === 0) {
  throw new Error("served script did not register a submit listener");
}

let defaultPrevented = false;
let unknownObserved = false;
let newEntryCleared = false;
let newEntryTextDefault = false;
let newEntryClickPostDelta = 0;
let newEntryClickUuidDelta = 0;
let emptySubmitState = "MISSING";
let emptySubmitPostDelta = 0;
let emptySubmitUuidDelta = 0;
let newContentPostDelta = 0;
let newContentUuidDelta = 0;
let restoredSnapshotPostDelta = 0;
let restoredSnapshotUuidDelta = 0;
let restoredSnapshotState = "MISSING";
const submit = () => Promise.all(submitListeners.map((listener) => listener({
    preventDefault() {
      defaultPrevented = true;
    },
  })));
if (mode === "double") {
  await Promise.all([submit(), submit()]);
} else if (mode === "lost") {
  await submit();
  unknownObserved = statusElement.dataset.state === "UNKNOWN";
  await submit();
} else if (mode === "conflict-repeat") {
  await submit();
  await submit();
} else if (mode === "lost-abandon-restore") {
  await submit();
  unknownObserved = statusElement.dataset.state === "UNKNOWN";
  const abandonListeners = listeners.get("capture-abandon-retry:click") ?? [];
  for (const listener of abandonListeners) {
    await listener({ preventDefault() {} });
  }
  const inputListeners = listeners.get("quick-capture-form:input") ?? [];
  content.value = "synthetic edited thought";
  for (const listener of inputListeners) {
    await listener({});
  }
  content.value = "synthetic durable thought";
  for (const listener of inputListeners) {
    await listener({});
  }
  const postsBeforeRestore = postCount;
  const uuidsBeforeRestore = uuidCount;
  await submit();
  restoredSnapshotPostDelta = postCount - postsBeforeRestore;
  restoredSnapshotUuidDelta = uuidCount - uuidsBeforeRestore;
  restoredSnapshotState = statusElement.dataset.state ?? "MISSING";
} else if (mode === "conflict-edit-restore") {
  await submit();
  const inputListeners = listeners.get("quick-capture-form:input") ?? [];
  content.value = "synthetic edited thought";
  for (const listener of inputListeners) {
    await listener({});
  }
  content.value = "synthetic durable thought";
  for (const listener of inputListeners) {
    await listener({});
  }
  const postsBeforeRestore = postCount;
  const uuidsBeforeRestore = uuidCount;
  await submit();
  restoredSnapshotPostDelta = postCount - postsBeforeRestore;
  restoredSnapshotUuidDelta = uuidCount - uuidsBeforeRestore;
  restoredSnapshotState = statusElement.dataset.state ?? "MISSING";
} else if (["server-5xx-retry", "timeout-retry"].includes(mode)) {
  await submit();
  unknownObserved = statusElement.dataset.state === "UNKNOWN";
  await submit();
} else if (mode === "success-new-entry") {
  await submit();
  await submit();
  const postsBeforeClick = postCount;
  const uuidsBeforeClick = uuidCount;
  const newEntryListeners = listeners.get("capture-new-entry:click") ?? [];
  for (const listener of newEntryListeners) {
    await listener({ preventDefault() {} });
  }
  newEntryCleared = content.value === "" && sourceRef.value === "";
  newEntryTextDefault = textChoice.checked && !linkChoice.checked;
  newEntryClickPostDelta = postCount - postsBeforeClick;
  newEntryClickUuidDelta = uuidCount - uuidsBeforeClick;

  const postsBeforeEmpty = postCount;
  const uuidsBeforeEmpty = uuidCount;
  await submit();
  emptySubmitState = statusElement.dataset.state ?? "MISSING";
  emptySubmitPostDelta = postCount - postsBeforeEmpty;
  emptySubmitUuidDelta = uuidCount - uuidsBeforeEmpty;

  content.value = "synthetic second durable thought";
  const inputListeners = listeners.get("quick-capture-form:input") ?? [];
  for (const listener of inputListeners) {
    await listener({});
  }
  const postsBeforeNewContent = postCount;
  const uuidsBeforeNewContent = uuidCount;
  await submit();
  newContentPostDelta = postCount - postsBeforeNewContent;
  newContentUuidDelta = uuidCount - uuidsBeforeNewContent;
} else {
  await submit();
}
await new Promise((resolve) => setTimeout(resolve, 0));

const inputRetained = content.value === "synthetic durable thought";
const safeCryptoHint = statusElement.textContent.includes("安全请求标识");
const serverDetailLeaked =
  /clientNonce|application\/problem|exception|stack trace/i.test(statusElement.textContent);
const receiptCaptureIdDisplayed = captureId && statusElement.textContent.includes(captureId);
const receiptCapturedAtDisplayed =
  receiptCapturedAt !== "MISSING" && statusElement.textContent.includes(receiptCapturedAt);
const receiptSourceTypeDisplayed =
  receiptSourceType !== "MISSING" && statusElement.textContent.includes(receiptSourceType);
const receiptDataClassDisplayed =
  receiptDataClass !== "MISSING" && statusElement.textContent.includes(receiptDataClass);
for (const body of postBodies) {
  const payload = JSON.parse(body);
  if (
    [payload.content, payload.sourceRef, payload.clientNonce].some(
      (value) => typeof value === "string" && statusElement.textContent.includes(value),
    )
  ) {
    unsafeReceiptValueDisplayed = 1;
  }
}
if (
  unsafeReceiptValues.some(
    (value) => value && statusElement.textContent.includes(value),
  )
) {
  unsafeReceiptValueDisplayed = 1;
}

let getStatus = 0;
let getNoStore = 0;
let getCaptureIdMatch = 0;
if (captureId) {
  const getResponse = await nativeFetch(
    new URL(`${captureEndpoint}/${encodeURIComponent(captureId)}`, appBaseUrl),
    {
      method: "GET",
      headers: { Accept: "application/json" },
      cache: "no-store",
      redirect: "error",
    },
  );
  getStatus = getResponse.status;
  getNoStore = getResponse.headers.get("cache-control") === "private, no-store" ? 1 : 0;
  try {
    const storedCapture = await getResponse.json();
    getCaptureIdMatch = storedCapture?.captureId === captureId ? 1 : 0;
  } catch (_failure) {
    getCaptureIdMatch = 0;
  }
}

console.log(`NODE_MAJOR=${nodeMajor}`);
console.log("SCRIPT_EXECUTED=1");
console.log(`SUBMIT_DISPATCHED=${defaultPrevented ? 1 : 0}`);
console.log(`POST_COUNT=${postCount}`);
console.log(`POST_BODY_STABLE=${postBodies.every((body) => body === postBodies[0]) ? 1 : 0}`);
console.log(`FIRST_POST_STATUS=${postStatuses[0] ?? 0}`);
console.log(`POST_STATUS=${postStatus}`);
console.log(`POST_CACHE_CONTROL_NO_STORE=${postCacheNoStore}`);
console.log(`UUID_COUNT=${uuidCount}`);
console.log(`UNKNOWN_OBSERVED=${unknownObserved ? 1 : 0}`);
console.log(`STATUS_STATE=${statusElement.dataset.state ?? "MISSING"}`);
console.log(`INPUT_RETAINED=${inputRetained ? 1 : 0}`);
console.log(`SAFE_CRYPTO_HINT=${safeCryptoHint ? 1 : 0}`);
console.log(`SERVER_DETAIL_LEAKED=${serverDetailLeaked ? 1 : 0}`);
console.log(`STATUS_ROLE=${statusElement.getAttribute("role") ?? "MISSING"}`);
console.log(`FOCUSED_ELEMENT=${focusedElementId}`);
console.log(`RECEIPT_CAPTURE_ID_DISPLAYED=${receiptCaptureIdDisplayed ? 1 : 0}`);
console.log(`RECEIPT_CAPTURED_AT_DISPLAYED=${receiptCapturedAtDisplayed ? 1 : 0}`);
console.log(`RECEIPT_SOURCE_TYPE_DISPLAYED=${receiptSourceTypeDisplayed ? 1 : 0}`);
console.log(`RECEIPT_DATA_CLASS_DISPLAYED=${receiptDataClassDisplayed ? 1 : 0}`);
console.log(`UNSAFE_RECEIPT_VALUE_DISPLAYED=${unsafeReceiptValueDisplayed}`);
console.log(`NEW_ENTRY_CLEARED=${newEntryCleared ? 1 : 0}`);
console.log(`NEW_ENTRY_TEXT_DEFAULT=${newEntryTextDefault ? 1 : 0}`);
console.log(`NEW_ENTRY_CLICK_POST_DELTA=${newEntryClickPostDelta}`);
console.log(`NEW_ENTRY_CLICK_UUID_DELTA=${newEntryClickUuidDelta}`);
console.log(`EMPTY_SUBMIT_STATE=${emptySubmitState}`);
console.log(`EMPTY_SUBMIT_POST_DELTA=${emptySubmitPostDelta}`);
console.log(`EMPTY_SUBMIT_UUID_DELTA=${emptySubmitUuidDelta}`);
console.log(`NEW_CONTENT_POST_DELTA=${newContentPostDelta}`);
console.log(`NEW_CONTENT_UUID_DELTA=${newContentUuidDelta}`);
console.log(`RESTORED_SNAPSHOT_POST_DELTA=${restoredSnapshotPostDelta}`);
console.log(`RESTORED_SNAPSHOT_UUID_DELTA=${restoredSnapshotUuidDelta}`);
console.log(`RESTORED_SNAPSHOT_STATE=${restoredSnapshotState}`);
console.log(`CAPTURE_ID_DIGEST=${captureIdDigests.at(-1) ?? "MISSING"}`);
console.log(`FIRST_CAPTURE_ID_DIGEST=${captureIdDigests[0] ?? "MISSING"}`);
console.log(`CAPTURE_ID_PRESENT=${captureId ? 1 : 0}`);
console.log(`RECEIPT_SOURCE_TYPE=${receiptSourceType}`);
console.log(`RECEIPT_DATA_CLASS=${receiptDataClass}`);
console.log(`GET_STATUS=${getStatus}`);
console.log(`GET_CACHE_CONTROL_NO_STORE=${getNoStore}`);
console.log(`GET_CAPTURE_ID_MATCH=${getCaptureIdMatch}`);
