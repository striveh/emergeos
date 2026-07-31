import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const schemaDir = path.join(repoRoot, "contracts", "schemas", "v1");
const fixtureDir = path.join(repoRoot, "contracts", "fixtures", "v1");
const goldenDir = path.join(repoRoot, "contracts", "golden", "v1");
const taskPackDir = path.join(repoRoot, "evals", "task-packs", "synthetic");
const evalEnvironmentDir = path.join(repoRoot, "evals", "environments");
const frozenEvalEnvironments = new Map([
  [
    "evals/environments/openai-responses-synthetic-v1.json",
    {
      toolRegistryVersion: "agent-tools-v1",
      rawSha256: "f2ddb405f81ac4cf51c8f54479ddbb13fd00b62c9995bee2e52b43db85cccc6f"
    }
  ],
  [
    "evals/environments/openai-responses-synthetic-v2.json",
    {
      toolRegistryVersion: "agent-tools-v2",
      rawSha256: "440fe5ce81202d5083e33463849b19e310077c9906baab8d253c1f59fd7de968"
    }
  ]
]);

export function assertStrictJson(source, label) {
  let index = 0;

  function fail(message) {
    throw new Error(`${label}: ${message} at character ${index}`);
  }

  function skipWhitespace() {
    while (
      index < source.length
      && (
        source[index] === " "
        || source[index] === "\t"
        || source[index] === "\r"
        || source[index] === "\n"
      )
    ) {
      index += 1;
    }
  }

  function parseString() {
    if (source[index] !== "\"") {
      fail("expected a JSON string");
    }
    const start = index;
    index += 1;
    while (index < source.length) {
      const character = source[index];
      if (character === "\"") {
        index += 1;
        return JSON.parse(source.slice(start, index));
      }
      if (character === "\\") {
        index += 2;
      } else {
        index += 1;
      }
    }
    fail("unterminated JSON string");
  }

  function parseValue(pathLabel, depth) {
    if (depth > 256) {
      fail("JSON nesting exceeds the validation boundary");
    }
    skipWhitespace();
    if (source[index] === "{") {
      parseObject(pathLabel, depth + 1);
      return;
    }
    if (source[index] === "[") {
      parseArray(pathLabel, depth + 1);
      return;
    }
    if (source[index] === "\"") {
      parseString();
      return;
    }
    const start = index;
    while (
      index < source.length
      && ![" ", "\t", "\r", "\n", ",", "]", "}"].includes(source[index])
    ) {
      index += 1;
    }
    if (start === index) {
      fail("expected a JSON value");
    }
    JSON.parse(source.slice(start, index));
  }

  function parseObject(pathLabel, depth) {
    index += 1;
    skipWhitespace();
    const keys = new Set();
    if (source[index] === "}") {
      index += 1;
      return;
    }
    while (index < source.length) {
      skipWhitespace();
      const key = parseString();
      if (keys.has(key)) {
        fail(`${pathLabel} contains duplicate object key ${JSON.stringify(key)}`);
      }
      keys.add(key);
      skipWhitespace();
      if (source[index] !== ":") {
        fail("expected ':' after an object key");
      }
      index += 1;
      parseValue(`${pathLabel}.${key}`, depth);
      skipWhitespace();
      if (source[index] === "}") {
        index += 1;
        return;
      }
      if (source[index] !== ",") {
        fail("expected ',' or '}' in an object");
      }
      index += 1;
    }
    fail("unterminated JSON object");
  }

  function parseArray(pathLabel, depth) {
    index += 1;
    skipWhitespace();
    if (source[index] === "]") {
      index += 1;
      return;
    }
    let item = 0;
    while (index < source.length) {
      parseValue(`${pathLabel}[${item}]`, depth);
      item += 1;
      skipWhitespace();
      if (source[index] === "]") {
        index += 1;
        return;
      }
      if (source[index] !== ",") {
        fail("expected ',' or ']' in an array");
      }
      index += 1;
    }
    fail("unterminated JSON array");
  }

  parseValue("$", 0);
  skipWhitespace();
  if (index !== source.length) {
    fail("JSON contains trailing content");
  }
}

function verifyStrictJsonParserRegression() {
  assertStrictJson(
    "{\"reference\":\"capture://ok\",\"nested\":[{\"value\":1}]}",
    "strict JSON valid control"
  );
  const invalidCases = [
    ["plain duplicate", "{\"a\":1,\"a\":2}"],
    ["escaped duplicate", "{\"a\":1,\"\\u0061\":2}"],
    ["nested duplicate", "{\"outer\":{\"a\":1,\"a\":2}}"],
    ["array object duplicate", "[{\"a\":1,\"a\":2}]"],
    ["trailing comma", "{\"a\":1,}"],
    ["trailing content", "{\"a\":1} {}"],
    ["invalid escape", "{\"a\":\"\\x\"}"]
  ];
  for (const [label, source] of invalidCases) {
    let rejected = false;
    try {
      assertStrictJson(source, `strict JSON ${label}`);
    } catch {
      rejected = true;
    }
    if (!rejected) {
      throw new Error(`strict JSON parser accepted ${label}`);
    }
  }
}

verifyStrictJsonParserRegression();

function readJson(file) {
  try {
    const source = fs.readFileSync(file, "utf8");
    assertStrictJson(source, path.relative(repoRoot, file));
    return JSON.parse(source);
  } catch (error) {
    throw new Error(`${path.relative(repoRoot, file)} is not valid JSON: ${error.message}`);
  }
}

function jsonFiles(directory) {
  if (!fs.existsSync(directory)) {
    return [];
  }
  return fs.readdirSync(directory)
    .filter((file) => file.endsWith(".json"))
    .sort()
    .map((file) => path.join(directory, file));
}

function assertNonEmptyStrings(value, label) {
  if (!Array.isArray(value) || value.length === 0 || value.some((item) => typeof item !== "string" || item.trim() === "")) {
    throw new Error(`${label} must be a non-empty array of non-empty strings`);
  }
}

function assertExactObjectKeys(value, expected, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(
      `${label} fields mismatch: expected ${wanted.join(", ")}`
    );
  }
}

const INTEGRITY_PROFILE = "emergeos-length-prefixed-sha256-v1";
const MAX_SAFE_INTEGER = 9_007_199_254_740_991;
const MAX_DURATION_MS = 86_400_000;
const MAX_EXECUTION_LIMIT = 128;
const MAX_USD = 999_999.999_999;
const RESOURCE_ROLE_ORDER = [
  "EVIDENCE",
  "ARTIFACT",
  "RECEIPT",
  "VERIFICATION",
  "CHECKPOINT",
  "HANDOFF",
  "WORKER_RESULT"
];

function hasLoneSurrogate(value) {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xD800 && unit <= 0xDBFF) {
      if (index + 1 >= value.length) {
        return true;
      }
      const next = value.charCodeAt(index + 1);
      if (next < 0xDC00 || next > 0xDFFF) {
        return true;
      }
      index += 1;
    } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
      return true;
    }
  }
  return false;
}

function requireCanonicalString(value) {
  if (hasLoneSurrogate(value)) {
    throw new Error("canonical strings must not contain lone UTF-16 surrogates");
  }
}

function verifyUnicodeScalarTree(value, label) {
  if (typeof value === "string") {
    if (hasLoneSurrogate(value)) {
      throw new Error(`${label}: string contains a lone UTF-16 surrogate`);
    }
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) =>
      verifyUnicodeScalarTree(item, `${label}[${index}]`));
    return;
  }
  if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      requireCanonicalString(key);
      verifyUnicodeScalarTree(item, `${label}.${key}`);
    }
  }
}

function isFrozenWhitespace(codePoint) {
  return (codePoint >= 0x0009 && codePoint <= 0x000D)
    || codePoint === 0x0020
    || codePoint === 0x0085
    || codePoint === 0x00A0
    || codePoint === 0x1680
    || (codePoint >= 0x2000 && codePoint <= 0x200A)
    || codePoint === 0x2028
    || codePoint === 0x2029
    || codePoint === 0x202F
    || codePoint === 0x205F
    || codePoint === 0x3000;
}

function requireSafeText(value, label, maxCodePoints = 2048) {
  const codePoints = Array.from(value);
  if (
    value.includes("\0")
    || hasLoneSurrogate(value)
    || codePoints.length > maxCodePoints
    || !codePoints.some((character) => !isFrozenWhitespace(character.codePointAt(0)))
  ) {
    throw new Error(`${label}: value is outside the frozen SafeText domain`);
  }
}

function verifySafeContractTextTree(value, label) {
  if (typeof value === "string") {
    requireSafeText(value, label);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) =>
      verifySafeContractTextTree(item, `${label}[${index}]`));
    return;
  }
  if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      requireSafeText(key, `${label} property name`);
      verifySafeContractTextTree(item, `${label}.${key}`);
    }
  }
}

function isUsdDomain(value) {
  if (!Number.isFinite(value) || value < 0 || value > MAX_USD) {
    return false;
  }
  // JSON numbers are already IEEE-754 values here. Normalizing to six decimal
  // places lets us accept every value in the frozen micro-USD domain while
  // rejecting values that carry a representable seventh decimal place.
  return Number(value.toFixed(6)) === value;
}

function compareCodePoints(left, right) {
  const leftPoints = Array.from(left, (character) => character.codePointAt(0));
  const rightPoints = Array.from(right, (character) => character.codePointAt(0));
  const length = Math.min(leftPoints.length, rightPoints.length);
  for (let index = 0; index < length; index += 1) {
    if (leftPoints[index] !== rightPoints[index]) {
      return leftPoints[index] - rightPoints[index];
    }
  }
  return leftPoints.length - rightPoints.length;
}

function uint32(value) {
  const encoded = Buffer.alloc(4);
  encoded.writeUInt32BE(value);
  return encoded;
}

function taggedBytes(tag, value) {
  return Buffer.concat([Buffer.from([tag]), uint32(value.length), value]);
}

function normalizeNumber(value) {
  if (!Number.isFinite(value)) {
    throw new Error("canonical numbers must be finite");
  }
  if (Number.isInteger(value)) {
    if (!Number.isSafeInteger(value)) {
      throw new Error("canonical integers must stay within the JavaScript-safe domain");
    }
  } else if (!isUsdDomain(value)) {
    throw new Error(
      "canonical decimals must use the non-negative six-decimal contract domain"
    );
  }
  if (Object.is(value, -0) || value === 0) {
    return "0";
  }
  const raw = String(value);
  if (!/[eE]/.test(raw)) {
    return raw.includes(".") ? raw.replace(/0+$/, "").replace(/\.$/, "") : raw;
  }
  const [coefficient, exponentText] = raw.toLowerCase().split("e");
  const exponent = Number(exponentText);
  const negative = coefficient.startsWith("-");
  const digits = coefficient.replace("-", "").replace(".", "");
  const decimalIndex = coefficient.replace("-", "").indexOf(".");
  const originalIntegerDigits = decimalIndex === -1 ? digits.length : decimalIndex;
  const shifted = originalIntegerDigits + exponent;
  let plain;
  if (shifted <= 0) {
    plain = `0.${"0".repeat(-shifted)}${digits}`;
  } else if (shifted >= digits.length) {
    plain = `${digits}${"0".repeat(shifted - digits.length)}`;
  } else {
    plain = `${digits.slice(0, shifted)}.${digits.slice(shifted)}`;
  }
  plain = plain.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
  return negative ? `-${plain}` : plain;
}

function canonicalEncode(value) {
  if (value === null) {
    return Buffer.from([0]);
  }
  if (typeof value === "boolean") {
    return Buffer.from([1, value ? 1 : 0]);
  }
  if (typeof value === "string") {
    requireCanonicalString(value);
    return taggedBytes(2, Buffer.from(value, "utf8"));
  }
  if (typeof value === "number") {
    return taggedBytes(3, Buffer.from(normalizeNumber(value), "utf8"));
  }
  if (Array.isArray(value)) {
    return Buffer.concat([
      Buffer.from([4]),
      uint32(value.length),
      ...value.map(canonicalEncode)
    ]);
  }
  if (typeof value === "object") {
    const keys = Object.keys(value).sort(compareCodePoints);
    keys.forEach(requireCanonicalString);
    return Buffer.concat([
      Buffer.from([5]),
      uint32(keys.length),
      ...keys.flatMap((key) => [canonicalEncode(key), canonicalEncode(value[key])])
    ]);
  }
  throw new Error(`unsupported canonical value type: ${typeof value}`);
}

function domainHash(domain, canonical) {
  return crypto
    .createHash("sha256")
    .update(Buffer.from(domain, "utf8"))
    .update(Buffer.from([0]))
    .update(canonical)
    .digest("hex");
}

function emptyTraceRoot() {
  return crypto
    .createHash("sha256")
    .update(Buffer.from("emergeos.agent-trace.v1.empty", "utf8"))
    .digest("hex");
}

function nextTraceRoot(previousRootHash, eventHash) {
  return domainHash(
    "emergeos.agent-trace.v1",
    Buffer.concat([Buffer.from(previousRootHash, "hex"), Buffer.from(eventHash, "hex")])
  );
}

function verifyTraceIntegrity(instance, relative) {
  if (instance.eventCount !== instance.events.length) {
    throw new Error(`${relative}: eventCount does not match events`);
  }
  let root = emptyTraceRoot();
  instance.events.forEach((event, index) => {
    if (event.sequence !== index + 1 || event.previousRootHash !== root) {
      throw new Error(`${relative}: Trace sequence/root chain is not continuous`);
    }
    const body = {...event};
    delete body.eventHash;
    const expectedEventHash = domainHash(
      "emergeos.agent-trace-event.v1",
      canonicalEncode(body)
    );
    if (event.eventHash !== expectedEventHash) {
      throw new Error(`${relative}: Trace eventHash mismatch`);
    }
    root = nextTraceRoot(root, event.eventHash);
  });
  if (instance.rootHash !== root) {
    throw new Error(`${relative}: Trace rootHash mismatch`);
  }
}

function rehashTrace(instance) {
  let root = emptyTraceRoot();
  instance.events.forEach((event, index) => {
    event.sequence = index + 1;
    event.previousRootHash = root;
    const body = {...event};
    delete body.eventHash;
    event.eventHash = domainHash(
      "emergeos.agent-trace-event.v1",
      canonicalEncode(body)
    );
    root = nextTraceRoot(root, event.eventHash);
  });
  instance.eventCount = instance.events.length;
  instance.rootHash = root;
}

function verifyTrace(instance, relative) {
  verifyTraceIntegrity(instance, relative);
  instance.events.forEach((event) => {
    const toolEvent = event.type === "TOOL_REQUEST"
      || event.type === "TOOL_RESULT"
      || event.type === "TOOL_REJECTED";
    if (toolEvent !== (event.toolName !== null)) {
      throw new Error(`${relative}: only tool events must name a tool`);
    }
    const taskRef = `task://${instance.taskId}`;
    const allowed =
      (event.type === "MODEL_STEP"
        && ["COMPLETED", "FAILED"].includes(event.status)
        && event.reference === taskRef)
      || (event.type === "TOOL_REQUEST"
        && event.status === "REQUESTED"
        && event.reference !== null)
      || (event.type === "TOOL_RESULT"
        && event.status === "SUCCEEDED"
        && event.reference !== null)
      || (event.type === "TOOL_REJECTED"
        && [
          "BLOCKED",
          "LIMIT_EXHAUSTED",
          "FAILED",
          "MALFORMED_RESULT",
          "DEADLINE_EXCEEDED"
        ]
          .includes(event.status))
      || (event.type === "HANDOFF_REQUEST"
        && event.status === "REQUESTED"
        && /^agent-run:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/
          .test(event.reference))
      || (event.type === "HANDOFF_RESULT"
        && event.status === "SUCCEEDED"
        && /^agent-run:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/
          .test(event.reference))
      || (event.type === "HANDOFF_REJECTED"
        && [
          "BLOCKED",
          "FAILED",
          "CANCELLED_UNOBSERVED",
          "DEADLINE_EXHAUSTED",
          "DEADLINE_EXCEEDED_UNOBSERVED",
          "DEADLINE_EXCEEDED_AFTER_CHILD",
          "LIMIT_EXHAUSTED",
          "CHILD_FAILED",
          "CHILD_BLOCKED",
          "CHILD_NEEDS_INPUT",
          "CHILD_CANCELLED",
          "MALFORMED_RESULT"
        ].includes(event.status)
        && (
          event.reference === null
          || /^agent-run:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/
            .test(event.reference)
        ))
      || (event.type === "STRUCTURED_FINAL"
        && event.status === "PROPOSED"
        && (
          event.reference === taskRef
          || /^proposal:\/\/sha256:[a-f0-9]{64}$/.test(event.reference)
        ))
      || (event.type === "ARTIFACT_COMMITTED"
        && event.status === "SUCCEEDED"
        && /^artifact-version:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}\/[1-9][0-9]*$/
          .test(event.reference));
    if (!allowed) {
      throw new Error(`${relative}: Trace event metadata is outside the safe allowlist`);
    }
  });
  verifyTraceStateMachine(instance, relative);
}

function verifyTraceStateMachine(instance, relative) {
  let phase = "EXPECT_MODEL";
  let pendingTool = null;
  let pendingReference = null;
  let structuredFinal = false;
  for (const event of instance.events) {
    if (phase === "TERMINAL") {
      if (
        event.type !== "ARTIFACT_COMMITTED"
        || !structuredFinal
      ) {
        throw new Error(
          `${relative}: Trace continues after a terminal event`
        );
      }
      phase = "COMMITTED";
      continue;
    }
    if (phase === "COMMITTED") {
      if (event.type !== "ARTIFACT_COMMITTED") {
        throw new Error(
          `${relative}: Artifact commits must be a trailing suffix`
        );
      }
      continue;
    }
    if (event.type === "ARTIFACT_COMMITTED") {
      throw new Error(
        `${relative}: Artifact commit requires a structured final`
      );
    }
    if (phase === "EXPECT_MODEL") {
      if (event.type !== "MODEL_STEP") {
        throw new Error(`${relative}: Trace expected a MODEL_STEP`);
      }
      phase = event.status === "FAILED" ? "TERMINAL" : "AFTER_MODEL";
      continue;
    }
    if (phase === "AFTER_MODEL") {
      if (event.type === "STRUCTURED_FINAL") {
        structuredFinal = true;
        phase = "TERMINAL";
      } else if (event.type === "TOOL_REQUEST") {
        pendingTool = event.toolName;
        pendingReference = event.reference;
        phase = "EXPECT_TOOL_COMPLETION";
      } else if (event.type === "HANDOFF_REQUEST") {
        pendingReference = event.reference;
        phase = "EXPECT_HANDOFF_COMPLETION";
      } else if (
        event.type === "TOOL_REJECTED"
        && event.reference === null
      ) {
        phase = "TERMINAL";
      } else if (
        event.type === "HANDOFF_REJECTED"
        && event.reference === null
      ) {
        phase = "TERMINAL";
      } else {
        throw new Error(
          `${relative}: Trace expected a request, safe rejection, or structured final`
        );
      }
      continue;
    }
    if (phase === "EXPECT_TOOL_COMPLETION") {
      if (
        event.toolName !== pendingTool
        || event.reference !== pendingReference
        || !["TOOL_RESULT", "TOOL_REJECTED"].includes(event.type)
      ) {
        throw new Error(
          `${relative}: Tool completion must match its pending request`
        );
      }
      phase = event.type === "TOOL_RESULT" ? "EXPECT_MODEL" : "TERMINAL";
      pendingTool = null;
      pendingReference = null;
      continue;
    }
    if (phase === "EXPECT_HANDOFF_COMPLETION") {
      if (
        event.reference !== pendingReference
        || !["HANDOFF_RESULT", "HANDOFF_REJECTED"].includes(event.type)
      ) {
        throw new Error(
          `${relative}: Handoff completion must match its pending request`
        );
      }
      phase = event.type === "HANDOFF_RESULT"
        ? "EXPECT_MODEL"
        : "TERMINAL";
      pendingReference = null;
    }
  }
  if (
    phase === "EXPECT_TOOL_COMPLETION"
    || phase === "EXPECT_HANDOFF_COMPLETION"
  ) {
    throw new Error(`${relative}: Trace ends with an unmatched request`);
  }
}

function bindingRefs(instance, role) {
  const bindings = instance.resourceBindings
    .filter((binding) => binding.role === role)
    .sort((left, right) => left.ordinal - right.ordinal);
  bindings.forEach((binding, index) => {
    if (binding.ordinal !== index) {
      throw new Error(`${role} binding ordinals must be continuous`);
    }
  });
  return bindings.map((binding) => binding.ref);
}

function verifyResult(instance, relative) {
  if (!isUsdDomain(instance.costUsd)) {
    throw new Error(`${relative}: costUsd is outside the shared six-decimal USD domain`);
  }
  const expected = `/api/v1/agent-runs/${instance.runId}/trace`;
  if (instance.traceRef !== expected) {
    throw new Error(`${relative}: traceRef does not resolve to runId`);
  }
  if (instance.status === "SUCCEEDED") {
    if (instance.failureReason !== null || instance.resolvedModel === null) {
      throw new Error(
        `${relative}: a successful Result requires resolvedModel and cannot have failureReason`
      );
    }
  } else if (
    typeof instance.failureReason !== "string"
    || !/^[A-Z][A-Z0-9_]{0,127}$/.test(instance.failureReason)
  ) {
    throw new Error(
      `${relative}: a non-success Result requires a stable failureReason code`
    );
  }
}

function workerResultIntegrityHash(instance) {
  const preimage = JSON.parse(JSON.stringify(instance));
  delete preimage.integrityHash;
  return domainHash(
    "emergeos.worker-result-envelope.v1",
    canonicalEncode(preimage)
  );
}

function verifyWorkerResult(instance, relative) {
  const expectedRef = `worker-result://${instance.childRunId}`;
  if (instance.workerResultRef !== expectedRef) {
    throw new Error(`${relative}: workerResultRef does not resolve to childRunId`);
  }
  if (instance.content.length > 65_536) {
    throw new Error(
      `${relative}: content exceeds 65536 UTF-16 code units`
    );
  }
  const expectedContentHash = crypto
    .createHash("sha256")
    .update(Buffer.from(instance.content, "utf8"))
    .digest("hex");
  if (instance.contentHash !== expectedContentHash) {
    throw new Error(`${relative}: contentHash does not match UTF-8 content`);
  }
  if (instance.integrityProfile !== INTEGRITY_PROFILE) {
    throw new Error(`${relative}: unsupported integrityProfile`);
  }
  const expectedIntegrityHash = workerResultIntegrityHash(instance);
  if (instance.integrityHash !== expectedIntegrityHash) {
    throw new Error(
      `${relative}: integrityHash mismatch (expected ${expectedIntegrityHash})`
    );
  }
}

function verifyWorkerResultBoundaryRegression() {
  const candidate = (childRunId, content) => {
    const value = {
      schemaVersion: "1.0",
      workerResultRef: `worker-result://${childRunId}`,
      childRunId,
      childTaskId: `${childRunId}-task`,
      outputSchema:
        "urn:emergeos:schema:internal:agent-draft-proposal:v1",
      content,
      contentHash: crypto
        .createHash("sha256")
        .update(Buffer.from(content, "utf8"))
        .digest("hex"),
      evidenceRefs: ["capture://pack007-capture"],
      integrityProfile: INTEGRITY_PROFILE,
      integrityHash: null
    };
    value.integrityHash = workerResultIntegrityHash(value);
    return value;
  };
  for (const [name, content] of [
    ["exact-bmp", "文".repeat(65_536)],
    ["exact-astral", "😀".repeat(32_768)]
  ]) {
    verifyWorkerResult(
      candidate(`worker-result-${name}`, content),
      `WorkerResult UTF-16 boundary ${name}`
    );
  }
  for (const [name, content] of [
    ["over-bmp", "文".repeat(65_537)],
    ["over-astral", "😀".repeat(32_769)]
  ]) {
    let rejected = false;
    try {
      verifyWorkerResult(
        candidate(`worker-result-${name}`, content),
        `WorkerResult UTF-16 boundary ${name}`
      );
    } catch {
      rejected = true;
    }
    if (!rejected) {
      throw new Error(`WorkerResult UTF-16 boundary ${name} was accepted`);
    }
  }
}

verifyWorkerResultBoundaryRegression();

function verifyTask(instance, relative) {
  if (!isUsdDomain(instance.budgetUsd)) {
    throw new Error(`${relative}: budgetUsd is outside the shared six-decimal USD domain`);
  }
  if (
    instance.parentId === instance.id
    || (
      instance.parentId === null
        ? instance.delegationChain.length !== 0
        : !equalJson(instance.delegationChain, [instance.parentId])
    )
  ) {
    throw new Error(
      `${relative}: Task delegation must be root or exact non-self depth-one parent lineage`
    );
  }
  if (instance.schemaVersion === "1.0") {
    if (
      (instance.modelProvider !== undefined && instance.modelProvider !== null)
      || (instance.modelRequested !== undefined && instance.modelRequested !== null)
      || (instance.pricingProfile !== undefined && instance.pricingProfile !== null)
    ) {
      throw new Error(`${relative}: TaskEnvelope 1.0 cannot carry a model binding`);
    }
    return;
  }
  if (
    instance.schemaVersion !== "1.1"
    || typeof instance.modelProvider !== "string"
    || typeof instance.modelRequested !== "string"
    || typeof instance.pricingProfile !== "string"
    || typeof instance.idempotencyKey !== "string"
    || typeof instance.environmentSnapshotRef !== "string"
  ) {
    throw new Error(`${relative}: TaskEnvelope 1.1 requires a complete model binding`);
  }
}

function equalJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function permitsObservedLatency(task, outcome, latencyMs) {
  return outcome !== "SUCCEEDED" || latencyMs <= task.deadlineMs;
}

function permitsObservedFailureAttribution(
  task,
  outcome,
  latencyMs,
  failureAttribution
) {
  const toolDeadline =
    failureAttribution === "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH";
  const handoffDeadline =
    failureAttribution === "HANDOFF_DEADLINE_EXCEEDED_AFTER_DISPATCH";
  if (!toolDeadline && !handoffDeadline) {
    return true;
  }
  if (
    handoffDeadline
    && !equalJson(
      task.capabilityRefs,
      ["capability://worker-handoff/article-draft-read-v1"]
    )
  ) {
    return false;
  }
  return outcome === "FAILED" && latencyMs > task.deadlineMs;
}

function permitsObservedBudget(task, outcome, costUsd, failureAttribution) {
  const declaresModelBudget =
    task.schemaVersion === "1.1"
    && failureAttribution === "MODEL_BUDGET_EXHAUSTED";
  const declaresWorkerSubtreeBudget =
    failureAttribution === "HANDOFF_BUDGET_EXHAUSTED";
  const overBudget = costUsd > task.budgetUsd;
  if (declaresModelBudget) {
    return overBudget && outcome !== "SUCCEEDED";
  }
  if (declaresWorkerSubtreeBudget) {
    return (
      overBudget
      && outcome === "BLOCKED"
      && equalJson(
        task.capabilityRefs,
        ["capability://worker-handoff/article-draft-read-v1"]
      )
    );
  }
  return !overBudget;
}

for (const [outcome, latencyMs, expected] of [
  ["SUCCEEDED", 5, true],
  ["SUCCEEDED", 7, false],
  ["FAILED", 7, true],
  ["NEEDS_INPUT", 7, true],
  ["BLOCKED", 7, true],
  ["CANCELLED", 7, true]
]) {
  if (
    permitsObservedLatency({ deadlineMs: 5 }, outcome, latencyMs)
      !== expected
  ) {
    throw new Error(
      `Observed latency policy regression for ${outcome}/${latencyMs}ms`
    );
  }
}

for (const [outcome, latencyMs, failureAttribution, expected] of [
  ["FAILED", 7, "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH", true],
  ["FAILED", 5, "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH", false],
  ["BLOCKED", 7, "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH", false],
  ["FAILED", 5, "TOOL_EXECUTION_FAILED", true]
]) {
  if (
    permitsObservedFailureAttribution(
      { deadlineMs: 5 },
      outcome,
      latencyMs,
      failureAttribution
    ) !== expected
  ) {
    throw new Error(
      `Observed failure attribution regression for ${outcome}/${latencyMs}ms/${failureAttribution}`
    );
  }
}

function bundleIntegrityHash(instance) {
  const preimage = JSON.parse(JSON.stringify(instance));
  delete preimage.integrityHash;
  if (preimage.task.schemaVersion === "1.0") {
    delete preimage.task.modelProvider;
    delete preimage.task.modelRequested;
    delete preimage.task.pricingProfile;
  }
  return domainHash(
    "emergeos.harness-run-bundle.v1",
    canonicalEncode(preimage)
  );
}

function taskIntegrityHash(instance) {
  const preimage = JSON.parse(JSON.stringify(instance));
  if (preimage.schemaVersion === "1.0") {
    delete preimage.modelProvider;
    delete preimage.modelRequested;
    delete preimage.pricingProfile;
  }
  return domainHash(
    "emergeos.task-envelope.v1",
    canonicalEncode(preimage)
  );
}

function expectedDeadlineNegative(kind) {
  const baseline = readJson(
    path.join(
      fixtureDir,
      "harness-run-bundle",
      "valid-post-dispatch-deadline-observed-failure.json"
    )
  );
  const expected = JSON.parse(JSON.stringify(baseline));
  if (kind === "success-over-deadline") {
    expected.result.status = "SUCCEEDED";
    expected.result.failureReason = null;
    expected.failureAttribution = null;
    expected.outcome = "SUCCEEDED";
  } else if (kind === "deadline-at-boundary") {
    expected.result.latencyMs = expected.task.deadlineMs;
    expected.latencyMs = expected.task.deadlineMs;
  } else {
    throw new Error(`Unsupported deadline negative fixture kind ${kind}`);
  }
  expected.integrityHash = bundleIntegrityHash(expected);
  return expected;
}

function verifyBundle(instance, relative) {
  const result = instance.result;
  const task = instance.task;
  verifyTask(task, `${relative}.task`);
  verifyResult(result, `${relative}.result`);
  if (
    instance.schemaVersion !== task.schemaVersion
    ||
    instance.runId !== result.runId
    || instance.taskId !== task.id
    || instance.taskId !== result.taskId
    || instance.modelResolved !== result.resolvedModel
    || instance.environmentSnapshotRef !== task.environmentSnapshotRef
    || instance.toolRegistryVersion !== task.toolRegistryVersion
    || instance.traceRef !== result.traceRef
    || instance.failureAttribution !== result.failureReason
    || instance.outcome !== result.status
    || instance.costUsd !== result.costUsd
    || instance.tokenCount !== result.tokenCount
    || instance.latencyMs !== result.latencyMs
    || !permitsObservedBudget(
      task,
      instance.outcome,
      instance.costUsd,
      instance.failureAttribution
    )
    || !permitsObservedLatency(task, instance.outcome, instance.latencyMs)
    || !permitsObservedFailureAttribution(
      task,
      instance.outcome,
      instance.latencyMs,
      instance.failureAttribution
    )
  ) {
    throw new Error(`${relative}: Bundle fields do not match Task and Result`);
  }
  for (const binding of instance.resourceBindings) {
    if (
      binding.role === "EVIDENCE"
      && !/^capture:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,199}$/.test(binding.ref)
    ) {
      throw new Error(`${relative}: Evidence binding must name one Capture`);
    }
    if (
      binding.role === "ARTIFACT"
      && !/^artifact-version:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}\/[1-9][0-9]*$/
        .test(binding.ref)
    ) {
      throw new Error(`${relative}: Artifact binding must name one immutable version`);
    }
    if (
      binding.role === "HANDOFF"
      && (
        binding.ordinal !== 0
        || !/^agent-run:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/
          .test(binding.ref)
      )
    ) {
      throw new Error(
        `${relative}: Handoff binding must name exactly one child AgentRun`
      );
    }
    if (
      binding.role === "WORKER_RESULT"
      && (
        binding.ordinal !== 0
        || !/^worker-result:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/
          .test(binding.ref)
      )
    ) {
      throw new Error(
        `${relative}: Worker Result binding must name exactly one child result`
      );
    }
  }
  if (
    instance.componentVersions.agent !== result.agentVersion
    || instance.componentVersions.verifier !== result.verifierVersion
    || instance.componentVersions["trace-integrity"] !== instance.integrityProfile
    || (task.schemaVersion === "1.1"
      && typeof instance.componentVersions["model-adapter"] !== "string")
  ) {
    throw new Error(
      `${relative}: componentVersions do not match Result and integrity profiles`
    );
  }
  const canonicalBindings = [...instance.resourceBindings].sort((left, right) => {
    const roleDifference =
      RESOURCE_ROLE_ORDER.indexOf(left.role) - RESOURCE_ROLE_ORDER.indexOf(right.role);
    return roleDifference === 0 ? left.ordinal - right.ordinal : roleDifference;
  });
  if (!equalJson(canonicalBindings, instance.resourceBindings)) {
    throw new Error(
      `${relative}: resourceBindings are not in canonical global role/ordinal order`
    );
  }
  if (!equalJson(bindingRefs(instance, "EVIDENCE"), result.evidenceRefs)
      || !equalJson(bindingRefs(instance, "ARTIFACT"), result.artifactRefs)
      || !equalJson(bindingRefs(instance, "RECEIPT"), result.receiptRefs)
      || !equalJson(bindingRefs(instance, "HANDOFF"), instance.handoffRefs)
      || !equalJson(bindingRefs(instance, "CHECKPOINT"), instance.checkpointRefs)
      || !equalJson(
        bindingRefs(instance, "VERIFICATION"),
        instance.verificationRef === null ? [] : [instance.verificationRef]
      )) {
    throw new Error(`${relative}: resource bindings do not match Result refs`);
  }
  if (instance.outcome !== "SUCCEEDED" && result.artifactRefs.length !== 0) {
    throw new Error(`${relative}: a non-success Run cannot bind an Artifact`);
  }
  if (
    instance.outcome === "SUCCEEDED"
    && task.kind === "CREATE_ARTICLE_DRAFT"
    && result.artifactRefs.length !== 1
  ) {
    throw new Error(
      `${relative}: a successful CREATE_ARTICLE_DRAFT Run requires exactly one Artifact`
    );
  }
  const workerResultBindings = instance.resourceBindings
    .filter((binding) => binding.role === "WORKER_RESULT");
  const readOnlyWorker = task.kind === "PROPOSE_ARTICLE_DRAFT";
  if (readOnlyWorker) {
    if (
      result.artifactRefs.length !== 0
      || result.receiptRefs.length !== 0
      || instance.handoffRefs.length !== 0
      || instance.checkpointRefs.length !== 0
      || instance.verificationRef !== null
    ) {
      throw new Error(
        `${relative}: a read-only Worker cannot bind write-side or nested execution truth`
      );
    }
    if (instance.outcome === "SUCCEEDED") {
      if (
        workerResultBindings.length !== 1
        || workerResultBindings[0].ordinal !== 0
        || workerResultBindings[0].ref
          !== `worker-result://${instance.runId}`
      ) {
        throw new Error(
          `${relative}: a successful read-only Worker requires its exact durable Worker Result`
        );
      }
    } else if (workerResultBindings.length !== 0) {
      throw new Error(
        `${relative}: a non-success read-only Worker cannot bind a Worker Result`
      );
    }
  } else if (workerResultBindings.length !== 0) {
    throw new Error(
      `${relative}: only a read-only Worker Run can bind a Worker Result`
    );
  }
  if (instance.integrityProfile !== INTEGRITY_PROFILE) {
    throw new Error(`${relative}: unsupported integrityProfile`);
  }
  const expected = bundleIntegrityHash(instance);
  if (instance.integrityHash !== expected) {
    throw new Error(
      `${relative}: integrityHash mismatch (expected ${expected})`
    );
  }
}

function verifySemantic(contractName, instance, relative) {
  verifyUnicodeScalarTree(instance, relative);
  if (contractName === "worker-result-envelope") {
    const metadata = {...instance};
    delete metadata.content;
    verifySafeContractTextTree(metadata, relative);
    requireSafeText(instance.content, `${relative}.content`, 65_536);
  } else {
    verifySafeContractTextTree(instance, relative);
  }
  if (contractName === "agent-trace-envelope") {
    verifyTrace(instance, relative);
  }
  if (contractName === "result-envelope") {
    verifyResult(instance, relative);
  }
  if (contractName === "worker-result-envelope") {
    verifyWorkerResult(instance, relative);
  }
  if (contractName === "task-envelope") {
    verifyTask(instance, relative);
  }
  if (contractName === "harness-run-bundle") {
    verifyBundle(instance, relative);
  }
}

const schemaFiles = jsonFiles(schemaDir);
if (schemaFiles.length === 0) {
  throw new Error("No contract schemas found");
}

const ajv = new Ajv2020({
  allErrors: true,
  strict: true,
  // Ajv 8.20 otherwise compares binary floating-point quotients exactly.
  // Precision 3 covers quotient error at the frozen USD maximum; the semantic
  // validator above independently enforces the exact six-decimal profile.
  multipleOfPrecision: 3
});
addFormats(ajv);

const schemas = schemaFiles.map((file) => {
  const schema = readJson(file);
  const relative = path.relative(repoRoot, file);
  if (schema.$schema !== "https://json-schema.org/draft/2020-12/schema") {
    throw new Error(`${relative}: unsupported or missing $schema`);
  }
  if (typeof schema.$id !== "string" || schema.$id === "" || typeof schema.title !== "string" || schema.title === "" || schema.type !== "object") {
    throw new Error(`${relative}: missing $id, title, or object type`);
  }
  return { file, schema };
});

for (const { schema } of schemas) {
  ajv.addSchema(schema);
}

let fixtureCount = 0;
for (const { file, schema } of schemas) {
  const validate = ajv.getSchema(schema.$id);
  if (!validate) {
    throw new Error(`${path.relative(repoRoot, file)} did not compile`);
  }

  const contractName = path.basename(file, ".schema.json");
  const contractFixtureDir = path.join(fixtureDir, contractName);
  const validFixtures = jsonFiles(contractFixtureDir).filter((fixture) => path.basename(fixture).startsWith("valid"));
  const invalidFixtures = jsonFiles(contractFixtureDir).filter((fixture) => path.basename(fixture).startsWith("invalid"));

  if (validFixtures.length === 0 || invalidFixtures.length === 0) {
    throw new Error(`${path.relative(repoRoot, contractFixtureDir)} must contain at least one valid*.json and one invalid*.json fixture`);
  }

  for (const fixture of validFixtures) {
    const instance = readJson(fixture);
    if (!validate(instance)) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} should be valid:\n${ajv.errorsText(validate.errors, { separator: "\n" })}`
      );
    }
    verifySemantic(contractName, instance, path.relative(repoRoot, fixture));
    fixtureCount += 1;
  }

  for (const fixture of invalidFixtures) {
    const instance = readJson(fixture);
    const schemaValid = validate(instance);
    let semanticValid = false;
    let semanticError = null;
    if (schemaValid) {
      try {
        verifySemantic(contractName, instance, path.relative(repoRoot, fixture));
        semanticValid = true;
      } catch (error) {
        semanticError = error;
        semanticValid = false;
      }
    }
    if (schemaValid && semanticValid) {
      throw new Error(`${path.relative(repoRoot, fixture)} should be invalid but passed`);
    }
    if (
      path.basename(fixture)
        === "invalid-v11-over-budget-wrong-failure-correct-hash.json"
      && (
        !schemaValid
        || semanticError === null
        || !semanticError.message.includes("Bundle fields do not match Task and Result")
      )
    ) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} must fail the budget/failure semantic check`
      );
    }
    if (
      path.basename(fixture)
        === "invalid-success-over-deadline-correct-hash.json"
      && (
        !schemaValid
        || semanticError === null
        || !semanticError.message.includes("Bundle fields do not match Task and Result")
        || !equalJson(
          instance,
          expectedDeadlineNegative("success-over-deadline")
        )
        || instance.integrityHash !== bundleIntegrityHash(instance)
      )
    ) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} must fail only the success/deadline semantic check and carry integrityHash ${bundleIntegrityHash(instance)}`
      );
    }
    if (
      path.basename(fixture)
        === "invalid-post-dispatch-deadline-at-boundary-correct-hash.json"
      && (
        !schemaValid
        || semanticError === null
        || !semanticError.message.includes("Bundle fields do not match Task and Result")
        || !equalJson(
          instance,
          expectedDeadlineNegative("deadline-at-boundary")
        )
        || instance.integrityHash !== bundleIntegrityHash(instance)
      )
    ) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} must fail only the typed deadline-attribution semantic check and carry integrityHash ${bundleIntegrityHash(instance)}`
      );
    }
    if (
      path.basename(fixture) === "invalid-content-tamper.json"
      && contractName === "worker-result-envelope"
      && (
        !schemaValid
        || semanticError === null
        || !semanticError.message.includes("contentHash does not match UTF-8 content")
        || instance.integrityHash !== workerResultIntegrityHash(instance)
      )
    ) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} must fail only the Worker Result contentHash check while carrying a correct integrityHash`
      );
    }
    if (
      path.basename(fixture)
        === "invalid-worker-result-ref-correct-integrity.json"
      && (
        !schemaValid
        || semanticError === null
        || !semanticError.message.includes(
          "workerResultRef does not resolve to childRunId"
        )
        || instance.integrityHash !== workerResultIntegrityHash(instance)
      )
    ) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} must fail only the typed Worker Result reference check while carrying a correct integrityHash`
      );
    }
    if (
      path.basename(fixture) === "invalid-integrity-hash.json"
      && contractName === "worker-result-envelope"
      && (
        !schemaValid
        || semanticError === null
        || !semanticError.message.includes("integrityHash mismatch")
        || instance.contentHash !== crypto
          .createHash("sha256")
          .update(Buffer.from(instance.content, "utf8"))
          .digest("hex")
        || instance.workerResultRef !== `worker-result://${instance.childRunId}`
        || instance.integrityHash === workerResultIntegrityHash(instance)
      )
    ) {
      throw new Error(
        `${path.relative(repoRoot, fixture)} must fail only the Worker Result integrityHash check`
      );
    }
    if (
      contractName === "agent-trace-envelope"
      && [
        "invalid-pack007-handoff-status-correct-hash.json",
        "invalid-pack007-proposal-ref-correct-hash.json"
      ].includes(path.basename(fixture))
    ) {
      if (schemaValid) {
        throw new Error(
          `${path.relative(repoRoot, fixture)} must be rejected by the typed Trace metadata schema`
        );
      }
      try {
        verifyTraceIntegrity(instance, path.relative(repoRoot, fixture));
      } catch (error) {
        throw new Error(
          `${path.relative(repoRoot, fixture)} must carry a correct Trace hash chain: ${error.message}`
        );
      }
    }
    fixtureCount += 1;
  }
}

const bundleValidator =
  ajv.getSchema("urn:emergeos:schema:v1:harness-run-bundle");
const pack007ChildBundle = readJson(
  path.join(
    fixtureDir,
    "harness-run-bundle",
    "valid-pack007-child.json"
  )
);
const pack007ParentBundle = readJson(
  path.join(
    fixtureDir,
    "harness-run-bundle",
    "valid-pack007-parent.json"
  )
);
const bundleSemanticNegatives = [
  {
    name: "worker-with-artifact",
    baseline: pack007ChildBundle,
    expected: "read-only Worker cannot bind write-side",
    mutate: (bundle) => {
      const artifactRef = "artifact-version://pack007-worker-artifact/1";
      bundle.result.artifactRefs = [artifactRef];
      bundle.resourceBindings.splice(1, 0, {
        role: "ARTIFACT",
        ordinal: 0,
        ref: artifactRef,
        contentHash:
          "e5b6493d7741cf43b65fb45c29d0351f3d038e470df31f964e6f1fa9cc8f767b"
      });
    }
  },
  {
    name: "successful-worker-missing-result",
    baseline: pack007ChildBundle,
    expected: "successful read-only Worker requires its exact durable Worker Result",
    mutate: (bundle) => {
      bundle.resourceBindings = bundle.resourceBindings
        .filter((binding) => binding.role !== "WORKER_RESULT");
    }
  },
  {
    name: "worker-result-wrong-run",
    baseline: pack007ChildBundle,
    expected: "successful read-only Worker requires its exact durable Worker Result",
    mutate: (bundle) => {
      bundle.resourceBindings
        .find((binding) => binding.role === "WORKER_RESULT")
        .ref = "worker-result://pack007-other-run";
    }
  },
  {
    name: "non-success-worker-with-result",
    baseline: pack007ChildBundle,
    expected: "non-success read-only Worker cannot bind a Worker Result",
    mutate: (bundle) => {
      bundle.result.status = "FAILED";
      bundle.result.failureReason = "AGENT_KERNEL_FAILED";
      bundle.failureAttribution = "AGENT_KERNEL_FAILED";
      bundle.outcome = "FAILED";
    }
  },
  {
    name: "non-worker-with-worker-result",
    baseline: pack007ParentBundle,
    expected: "only a read-only Worker Run can bind a Worker Result",
    mutate: (bundle) => {
      bundle.resourceBindings.push({
        role: "WORKER_RESULT",
        ordinal: 0,
        ref: "worker-result://pack007-parent-run",
        contentHash:
          "d69ecb08300f1f1cd378d826243add922c0deb75a15d9393a63d5d00d2a2f5a8"
      });
    }
  },
  {
    name: "handoff-ref-drift",
    baseline: pack007ParentBundle,
    expected: "resource bindings do not match Result refs",
    mutate: (bundle) => {
      bundle.resourceBindings
        .find((binding) => binding.role === "HANDOFF")
        .ref = "agent-run://pack007-other-child-run";
    }
  }
];
for (const negative of bundleSemanticNegatives) {
  const candidate = JSON.parse(JSON.stringify(negative.baseline));
  negative.mutate(candidate);
  candidate.integrityHash = bundleIntegrityHash(candidate);
  if (!bundleValidator(candidate)) {
    throw new Error(
      `Pack007 Bundle negative ${negative.name} must remain schema-valid:\n`
      + ajv.errorsText(bundleValidator.errors, {separator: "\n"})
    );
  }
  let semanticError = null;
  try {
    verifySemantic(
      "harness-run-bundle",
      candidate,
      `Pack007 Bundle negative ${negative.name}`
    );
  } catch (error) {
    semanticError = error;
  }
  if (
    semanticError === null
    || !semanticError.message.includes(negative.expected)
  ) {
    throw new Error(
      `Pack007 Bundle negative ${negative.name} must fail ${negative.expected}`
    );
  }
}

const pack007GraphManifestFile = path.join(
  fixtureDir,
  "worker-handoff",
  "valid-pack007.json"
);
const pack007GraphManifestRawSha256 =
  crypto.createHash("sha256")
    .update(fs.readFileSync(pack007GraphManifestFile))
    .digest("hex");
if (
  pack007GraphManifestRawSha256
  !== "888d0b66165560716cd3065d8b835a185040c4751d8e5db424275107a2527629"
) {
  throw new Error("Pack007 Handoff graph manifest raw SHA-256 drifted");
}
const pack007GraphManifest = readJson(pack007GraphManifestFile);
assertExactObjectKeys(
  pack007GraphManifest,
  ["schemaVersion", "graphId", "components", "receipts"],
  "Pack007 Handoff graph manifest"
);
if (
  pack007GraphManifest.schemaVersion !== "1.0"
  || pack007GraphManifest.graphId
    !== "pack007-offline-read-only-worker-handoff-v1"
) {
  throw new Error("Pack007 Handoff graph manifest identity is not frozen");
}
const graphComponentContracts = {
  parentTask: "task-envelope",
  childTask: "task-envelope",
  parentTrace: "agent-trace-envelope",
  childTrace: "agent-trace-envelope",
  parentBundle: "harness-run-bundle",
  childBundle: "harness-run-bundle",
  workerResult: "worker-result-envelope"
};
assertExactObjectKeys(
  pack007GraphManifest.components,
  Object.keys(graphComponentContracts),
  "Pack007 Handoff graph components"
);
assertExactObjectKeys(
  pack007GraphManifest.receipts,
  [
    "parentTaskHash",
    "childTaskHash",
    "parentTraceRoot",
    "childTraceRoot",
    "parentBundleHash",
    "childBundleHash",
    "workerResultIntegrityHash",
    "workerContentHash"
  ],
  "Pack007 Handoff graph receipts"
);
for (const [name, value] of Object.entries(pack007GraphManifest.receipts)) {
  if (!/^[a-f0-9]{64}$/.test(value)) {
    throw new Error(`Pack007 Handoff graph receipt ${name} is not a SHA-256 digest`);
  }
}
const pack007Graph = {};
for (const [name, contractName] of Object.entries(graphComponentContracts)) {
  const descriptor = pack007GraphManifest.components[name];
  assertExactObjectKeys(
    descriptor,
    ["path", "rawSha256"],
    `Pack007 Handoff graph component ${name}`
  );
  if (
    typeof descriptor.path !== "string"
    || !descriptor.path.startsWith("contracts/fixtures/v1/")
    || descriptor.path.includes("..")
    || !/^[a-f0-9]{64}$/.test(descriptor.rawSha256)
  ) {
    throw new Error(`Pack007 Handoff graph component ${name} is unsafe`);
  }
  const componentFile = path.resolve(repoRoot, descriptor.path);
  if (!componentFile.startsWith(`${repoRoot}${path.sep}`)) {
    throw new Error(`Pack007 Handoff graph component ${name} escapes the repository`);
  }
  const raw = fs.readFileSync(componentFile);
  const rawSha256 = crypto.createHash("sha256").update(raw).digest("hex");
  if (rawSha256 !== descriptor.rawSha256) {
    throw new Error(
      `Pack007 Handoff graph component ${name} raw SHA-256 mismatch`
    );
  }
  pack007Graph[name] = readJson(componentFile);
  pack007Graph[`${name}Contract`] = contractName;
}
const graphReceipts = pack007GraphManifest.receipts;
if (
  graphReceipts.parentTaskHash
    !== taskIntegrityHash(pack007Graph.parentTask)
  || graphReceipts.childTaskHash
    !== taskIntegrityHash(pack007Graph.childTask)
  || graphReceipts.parentTraceRoot !== pack007Graph.parentTrace.rootHash
  || graphReceipts.childTraceRoot !== pack007Graph.childTrace.rootHash
  || graphReceipts.parentBundleHash
    !== pack007Graph.parentBundle.integrityHash
  || graphReceipts.childBundleHash
    !== pack007Graph.childBundle.integrityHash
  || graphReceipts.workerResultIntegrityHash
    !== pack007Graph.workerResult.integrityHash
  || graphReceipts.workerContentHash
    !== pack007Graph.workerResult.contentHash
) {
  throw new Error("Pack007 Handoff graph receipts do not match its components");
}

function graphBindings(bundle, role) {
  return bundle.resourceBindings
    .filter((binding) => binding.role === role);
}

function requireGraphRelation(condition, label, code) {
  if (!condition) {
    throw new Error(`${label}: ${code}`);
  }
}

function assertLocallyValidPack007Graph(graph, label) {
  for (const name of Object.keys(graphComponentContracts)) {
    const contractName = graph[`${name}Contract`];
    const schema = schemas
      .find(({file}) =>
        path.basename(file, ".schema.json") === contractName)
      .schema;
    const validate = ajv.getSchema(schema.$id);
    if (!validate(graph[name])) {
      throw new Error(
        `${label}.${name} must remain locally schema-valid:\n`
        + ajv.errorsText(validate.errors, {separator: "\n"})
      );
    }
    verifySemantic(contractName, graph[name], `${label}.${name}`);
  }
}

function verifyPack007HandoffGraph(graph, label) {
  const {
    parentTask,
    childTask,
    parentTrace,
    childTrace,
    parentBundle,
    childBundle,
    workerResult
  } = graph;
  requireGraphRelation(
    equalJson(parentTask, parentBundle.task)
      && parentBundle.taskId === parentTask.id
      && parentBundle.result.taskId === parentTask.id,
    label,
    "PARENT_TASK_BINDING"
  );
  requireGraphRelation(
    equalJson(childTask, childBundle.task)
      && childBundle.taskId === childTask.id
      && childBundle.result.taskId === childTask.id,
    label,
    "CHILD_TASK_BINDING"
  );
  requireGraphRelation(
    parentTrace.runId === parentBundle.runId
      && parentTrace.taskId === parentTask.id
      && parentBundle.traceRootHash === parentTrace.rootHash,
    label,
    "PARENT_TRACE_BINDING"
  );
  requireGraphRelation(
    childTrace.runId === childBundle.runId
      && childTrace.taskId === childTask.id
      && childBundle.traceRootHash === childTrace.rootHash,
    label,
    "CHILD_TRACE_BINDING"
  );
  requireGraphRelation(
    parentTask.parentId === null
      && parentTask.delegationChain.length === 0
      && childTask.parentId === parentTask.id
      && equalJson(childTask.delegationChain, [parentTask.id]),
    label,
    "CHILD_LINEAGE"
  );
  requireGraphRelation(
    childTask.principalRef === parentTask.principalRef,
    label,
    "CHILD_PRINCIPAL"
  );
  requireGraphRelation(
    childTask.policyVersion === parentTask.policyVersion
      && childTask.stateVersion === parentTask.stateVersion
      && childTask.contextPolicyVersion === parentTask.contextPolicyVersion
      && childTask.toolRegistryVersion === parentTask.toolRegistryVersion
      && childTask.environmentSnapshotRef === parentTask.environmentSnapshotRef
      && childTask.dataClass === parentTask.dataClass
      && equalJson(childTask.modalities, parentTask.modalities)
      && childTask.latencyClass === parentTask.latencyClass
      && equalJson(
        childTask.unresolvedDecisions,
        parentTask.unresolvedDecisions
      ),
    label,
    "CHILD_POLICY_INHERITANCE"
  );
  requireGraphRelation(
    parentTask.requiredTools.length === 0
      && equalJson(childTask.inputRefs, parentTask.inputRefs)
      && childTask.evidenceRefs.length === 0
      && equalJson(childTask.requiredTools, ["capture.read"])
      && childTask.risk === "READ_ONLY"
      && childTask.allowParallel === false,
    label,
    "CHILD_INPUT_AUTHORITY"
  );
  requireGraphRelation(
    equalJson(
      parentTask.capabilityRefs,
      ["capability://worker-handoff/article-draft-read-v1"]
    )
      && childTask.capabilityRefs.length === 0,
    label,
    "CHILD_CAPABILITY_AUTHORITY"
  );
  requireGraphRelation(
    childTask.maxModelSteps <= parentTask.maxModelSteps
      && childTask.maxToolCalls <= parentTask.maxToolCalls
      && childTask.deadlineMs <= parentTask.deadlineMs
      && childTask.budgetUsd <= parentTask.budgetUsd
      && childTask.budgetUsd === 0,
    label,
    "CHILD_LIMIT_AUTHORITY"
  );
  requireGraphRelation(
    parentBundle.componentVersions["worker-registry"]
      === childBundle.componentVersions["worker-registry"]
      && parentBundle.componentVersions["worker-profile-fingerprint"]
        === childBundle.componentVersions["worker-profile-fingerprint"],
    label,
    "WORKER_PROFILE_BINDING"
  );
  const traceSignature = (trace) =>
    trace.events.map((event) => [
      event.type,
      event.toolName,
      event.status
    ]);
  requireGraphRelation(
    equalJson(
      traceSignature(parentTrace),
      [
        ["MODEL_STEP", null, "COMPLETED"],
        ["HANDOFF_REQUEST", null, "REQUESTED"],
        ["HANDOFF_RESULT", null, "SUCCEEDED"],
        ["MODEL_STEP", null, "COMPLETED"],
        ["STRUCTURED_FINAL", null, "PROPOSED"],
        ["ARTIFACT_COMMITTED", null, "SUCCEEDED"]
      ]
    )
      && equalJson(
        traceSignature(childTrace),
        [
          ["MODEL_STEP", null, "COMPLETED"],
          ["TOOL_REQUEST", "capture.read", "REQUESTED"],
          ["TOOL_RESULT", "capture.read", "SUCCEEDED"],
          ["MODEL_STEP", null, "COMPLETED"],
          ["STRUCTURED_FINAL", null, "PROPOSED"]
        ]
      ),
    label,
    "PACK007_TRACE_SEQUENCE"
  );
  requireGraphRelation(
    workerResult.childRunId === childBundle.runId
      && workerResult.workerResultRef
        === `worker-result://${childBundle.runId}`,
    label,
    "WORKER_RESULT_CHILD_RUN"
  );
  requireGraphRelation(
    workerResult.childTaskId === childTask.id,
    label,
    "WORKER_RESULT_CHILD_TASK"
  );
  requireGraphRelation(
    workerResult.outputSchema === childTask.outputSchema,
    label,
    "WORKER_RESULT_OUTPUT_SCHEMA"
  );
  const childRef = `agent-run://${childBundle.runId}`;
  const parentRequests = parentTrace.events
    .filter((event) => event.type === "HANDOFF_REQUEST");
  const parentCompletions = parentTrace.events
    .filter((event) =>
      event.type === "HANDOFF_RESULT"
      || event.type === "HANDOFF_REJECTED");
  requireGraphRelation(
    parentRequests.length === 1
      && parentRequests[0].reference === childRef
      && parentCompletions.length === 1
      && parentCompletions[0].type === "HANDOFF_RESULT"
      && parentCompletions[0].status === "SUCCEEDED"
      && parentCompletions[0].reference === childRef
      && !childTrace.events.some((event) =>
        event.type === "HANDOFF_REQUEST"
        || event.type === "HANDOFF_RESULT"
        || event.type === "HANDOFF_REJECTED"
        || event.type === "ARTIFACT_COMMITTED"),
    label,
    "PARENT_TRACE_CHILD_REF"
  );
  const handoffs = graphBindings(parentBundle, "HANDOFF");
  requireGraphRelation(
    equalJson(parentBundle.handoffRefs, [childRef])
      && handoffs.length === 1
      && handoffs[0].ref === childRef
      && handoffs[0].contentHash === childBundle.integrityHash,
    label,
    "PARENT_HANDOFF_BINDING"
  );
  const workerResults = graphBindings(childBundle, "WORKER_RESULT");
  requireGraphRelation(
    workerResults.length === 1
      && workerResults[0].ref === workerResult.workerResultRef
      && workerResults[0].contentHash === workerResult.integrityHash,
    label,
    "CHILD_WORKER_RESULT_BINDING"
  );
  const parentEvidence = graphBindings(parentBundle, "EVIDENCE");
  const childEvidence = graphBindings(childBundle, "EVIDENCE");
  requireGraphRelation(
    equalJson(parentEvidence, childEvidence)
      && equalJson(
        workerResult.evidenceRefs,
        childEvidence.map((binding) => binding.ref)
      )
      && equalJson(
        parentBundle.result.evidenceRefs,
        workerResult.evidenceRefs
      )
      && equalJson(
        childBundle.result.evidenceRefs,
        workerResult.evidenceRefs
      ),
    label,
    "EVIDENCE_BINDING"
  );
  const childProposals = childTrace.events
    .filter((event) => event.type === "STRUCTURED_FINAL");
  requireGraphRelation(
    childProposals.length === 1
      && childProposals[0].reference
        === `proposal://sha256:${workerResult.contentHash}`,
    label,
    "CHILD_PROPOSAL_BINDING"
  );
  const parentProposals = parentTrace.events
    .filter((event) => event.type === "STRUCTURED_FINAL");
  requireGraphRelation(
    parentProposals.length === 1
      && parentProposals[0].reference
        === `proposal://sha256:${workerResult.contentHash}`,
    label,
    "PARENT_PROPOSAL_BINDING"
  );
  const artifacts = graphBindings(parentBundle, "ARTIFACT");
  requireGraphRelation(
    artifacts.length === 1
      && artifacts[0].contentHash === workerResult.contentHash,
    label,
    "ARTIFACT_CONTENT_BINDING"
  );
  requireGraphRelation(
    parentBundle.costUsd >= childBundle.costUsd
      && parentBundle.tokenCount >= childBundle.tokenCount,
    label,
    "USAGE_AGGREGATION"
  );
}

function clonePack007Graph() {
  return JSON.parse(JSON.stringify(pack007Graph));
}

function verifyTraceStateMachineRegression() {
  const negatives = [
    {
      name: "child-result-before-request",
      trace: pack007Graph.childTrace,
      mutate: (candidate) => {
        const requestIndex = candidate.events
          .findIndex((event) => event.type === "TOOL_REQUEST");
        const resultIndex = candidate.events
          .findIndex((event) => event.type === "TOOL_RESULT");
        [candidate.events[requestIndex], candidate.events[resultIndex]] =
          [candidate.events[resultIndex], candidate.events[requestIndex]];
      }
    },
    {
      name: "parent-handoff-before-model",
      trace: pack007Graph.parentTrace,
      mutate: (candidate) => {
        const requestIndex = candidate.events
          .findIndex((event) => event.type === "HANDOFF_REQUEST");
        const [request] = candidate.events.splice(requestIndex, 1);
        candidate.events.unshift(request);
      }
    }
  ];
  for (const negative of negatives) {
    const candidate = JSON.parse(JSON.stringify(negative.trace));
    negative.mutate(candidate);
    rehashTrace(candidate);
    let rejected = false;
    try {
      verifyTrace(
        candidate,
        `Pack007 Trace state-machine negative ${negative.name}`
      );
    } catch {
      rejected = true;
    }
    if (!rejected) {
      throw new Error(
        `Pack007 Trace state-machine negative ${negative.name} was accepted`
      );
    }
  }
}

function cascadeChildBundle(graph) {
  graph.childBundle.integrityHash =
    bundleIntegrityHash(graph.childBundle);
  graph.parentBundle.resourceBindings
    .find((binding) => binding.role === "HANDOFF")
    .contentHash = graph.childBundle.integrityHash;
  graph.parentBundle.integrityHash =
    bundleIntegrityHash(graph.parentBundle);
}

function syncChildTaskAndCascade(graph) {
  graph.childBundle.task = JSON.parse(JSON.stringify(graph.childTask));
  cascadeChildBundle(graph);
}

function syncChildTraceAndCascade(graph) {
  graph.childBundle.traceRootHash = graph.childTrace.rootHash;
  cascadeChildBundle(graph);
}

function syncParentTraceAndRehash(graph) {
  graph.parentBundle.traceRootHash = graph.parentTrace.rootHash;
  graph.parentBundle.integrityHash =
    bundleIntegrityHash(graph.parentBundle);
}

const graphRelationNegatives = [
  {
    name: "parent-task-binding",
    expected: "PARENT_TASK_BINDING",
    mutate: (graph) => {
      graph.parentBundle.task.intent =
        "同一 ID 下被篡改、但局部仍然有效的 parent intent";
      graph.parentBundle.integrityHash =
        bundleIntegrityHash(graph.parentBundle);
    }
  },
  {
    name: "child-task-binding",
    expected: "CHILD_TASK_BINDING",
    mutate: (graph) => {
      graph.childBundle.task.intent =
        "同一 ID 下被篡改、但局部仍然有效的 child intent";
      cascadeChildBundle(graph);
    }
  },
  {
    name: "child-trace-binding",
    expected: "CHILD_TRACE_BINDING",
    mutate: (graph) => {
      graph.childBundle.traceRootHash = "9".repeat(64);
      cascadeChildBundle(graph);
    }
  },
  {
    name: "child-lineage",
    expected: "CHILD_LINEAGE",
    mutate: (graph) => {
      graph.childTask.parentId = "pack007-other-parent-task";
      graph.childTask.delegationChain = ["pack007-other-parent-task"];
      syncChildTaskAndCascade(graph);
    }
  },
  {
    name: "child-principal",
    expected: "CHILD_PRINCIPAL",
    mutate: (graph) => {
      graph.childTask.principalRef = "pack007-other-owner";
      syncChildTaskAndCascade(graph);
    }
  },
  {
    name: "child-context-policy",
    expected: "CHILD_POLICY_INHERITANCE",
    mutate: (graph) => {
      graph.childTask.contextPolicyVersion = "pack007-drifted-context-v2";
      syncChildTaskAndCascade(graph);
    }
  },
  {
    name: "child-input-expansion",
    expected: "CHILD_INPUT_AUTHORITY",
    mutate: (graph) => {
      graph.childTask.inputRefs.push("capture://pack007-extra-capture");
      syncChildTaskAndCascade(graph);
    }
  },
  {
    name: "child-capability-escalation",
    expected: "CHILD_CAPABILITY_AUTHORITY",
    mutate: (graph) => {
      graph.childTask.capabilityRefs = ["capability://external/write-v1"];
      syncChildTaskAndCascade(graph);
    }
  },
  {
    name: "child-limit-escalation",
    expected: "CHILD_LIMIT_AUTHORITY",
    mutate: (graph) => {
      graph.childTask.maxModelSteps =
        graph.parentTask.maxModelSteps + 1;
      syncChildTaskAndCascade(graph);
    }
  },
  {
    name: "worker-profile-drift",
    expected: "WORKER_PROFILE_BINDING",
    mutate: (graph) => {
      graph.childBundle.componentVersions["worker-profile-fingerprint"] =
        "pack007-drifted-worker-profile-v2";
      cascadeChildBundle(graph);
    }
  },
  {
    name: "worker-result-child-run",
    expected: "WORKER_RESULT_CHILD_RUN",
    mutate: (graph) => {
      graph.workerResult.childRunId = "pack007-other-child-run";
      graph.workerResult.workerResultRef =
        "worker-result://pack007-other-child-run";
      graph.workerResult.integrityHash =
        workerResultIntegrityHash(graph.workerResult);
    }
  },
  {
    name: "worker-result-child-task",
    expected: "WORKER_RESULT_CHILD_TASK",
    mutate: (graph) => {
      graph.workerResult.childTaskId = "pack007-other-child-task";
      graph.workerResult.integrityHash =
        workerResultIntegrityHash(graph.workerResult);
    }
  },
  {
    name: "worker-result-output-schema",
    expected: "WORKER_RESULT_OUTPUT_SCHEMA",
    mutate: (graph) => {
      graph.workerResult.outputSchema =
        "urn:emergeos:schema:internal:alternate-proposal:v1";
      graph.workerResult.integrityHash =
        workerResultIntegrityHash(graph.workerResult);
      graph.childBundle.resourceBindings
        .find((binding) => binding.role === "WORKER_RESULT")
        .contentHash = graph.workerResult.integrityHash;
      cascadeChildBundle(graph);
    }
  },
  {
    name: "pack007-trace-sequence",
    expected: "PACK007_TRACE_SEQUENCE",
    mutate: (graph) => {
      graph.childTrace.events = [
        graph.childTrace.events[0],
        graph.childTrace.events.at(-1)
      ];
      rehashTrace(graph.childTrace);
      syncChildTraceAndCascade(graph);
    }
  },
  {
    name: "parent-trace-child-ref",
    expected: "PARENT_TRACE_CHILD_REF",
    mutate: (graph) => {
      graph.parentTrace.events
        .filter((event) =>
          event.type === "HANDOFF_REQUEST"
          || event.type === "HANDOFF_RESULT")
        .forEach((event) => {
          event.reference = "agent-run://pack007-other-child-run";
        });
      rehashTrace(graph.parentTrace);
      syncParentTraceAndRehash(graph);
    }
  },
  {
    name: "parent-trace-root",
    expected: "PARENT_TRACE_BINDING",
    mutate: (graph) => {
      graph.parentBundle.traceRootHash = "a".repeat(64);
      graph.parentBundle.integrityHash =
        bundleIntegrityHash(graph.parentBundle);
    }
  },
  {
    name: "handoff-bundle-hash",
    expected: "PARENT_HANDOFF_BINDING",
    mutate: (graph) => {
      graph.parentBundle.resourceBindings
        .find((binding) => binding.role === "HANDOFF")
        .contentHash = "b".repeat(64);
      graph.parentBundle.integrityHash =
        bundleIntegrityHash(graph.parentBundle);
    }
  },
  {
    name: "worker-result-binding-hash",
    expected: "CHILD_WORKER_RESULT_BINDING",
    mutate: (graph) => {
      graph.childBundle.resourceBindings
        .find((binding) => binding.role === "WORKER_RESULT")
        .contentHash = "c".repeat(64);
      cascadeChildBundle(graph);
    }
  },
  {
    name: "evidence-hash",
    expected: "EVIDENCE_BINDING",
    mutate: (graph) => {
      graph.childBundle.resourceBindings
        .find((binding) => binding.role === "EVIDENCE")
        .contentHash = "d".repeat(64);
      cascadeChildBundle(graph);
    }
  },
  {
    name: "child-proposal",
    expected: "CHILD_PROPOSAL_BINDING",
    mutate: (graph) => {
      graph.childTrace.events
        .find((event) => event.type === "STRUCTURED_FINAL")
        .reference = `proposal://sha256:${"e".repeat(64)}`;
      rehashTrace(graph.childTrace);
      syncChildTraceAndCascade(graph);
    }
  },
  {
    name: "parent-proposal",
    expected: "PARENT_PROPOSAL_BINDING",
    mutate: (graph) => {
      graph.parentTrace.events
        .find((event) => event.type === "STRUCTURED_FINAL")
        .reference = `proposal://sha256:${"f".repeat(64)}`;
      rehashTrace(graph.parentTrace);
      syncParentTraceAndRehash(graph);
    }
  },
  {
    name: "artifact-content",
    expected: "ARTIFACT_CONTENT_BINDING",
    mutate: (graph) => {
      graph.parentBundle.resourceBindings
        .find((binding) => binding.role === "ARTIFACT")
        .contentHash = "0".repeat(64);
      graph.parentBundle.integrityHash =
        bundleIntegrityHash(graph.parentBundle);
    }
  },
  {
    name: "aggregate-usage",
    expected: "USAGE_AGGREGATION",
    mutate: (graph) => {
      graph.childBundle.result.tokenCount = 1;
      graph.childBundle.tokenCount = 1;
      cascadeChildBundle(graph);
    }
  }
];

verifyTraceStateMachineRegression();
assertLocallyValidPack007Graph(pack007Graph, "Pack007 Handoff graph");
verifyPack007HandoffGraph(pack007Graph, "Pack007 Handoff graph");
for (const negative of graphRelationNegatives) {
  const candidate = clonePack007Graph();
  negative.mutate(candidate);
  assertLocallyValidPack007Graph(
    candidate,
    `Pack007 Handoff relation negative ${negative.name}`
  );
  let relationError = null;
  try {
    verifyPack007HandoffGraph(
      candidate,
      `Pack007 Handoff relation negative ${negative.name}`
    );
  } catch (error) {
    relationError = error;
  }
  if (
    relationError === null
    || !relationError.message.endsWith(negative.expected)
  ) {
    throw new Error(
      `Pack007 Handoff relation negative ${negative.name} must fail ${negative.expected}`
    );
  }
}

process.stdout.write(`Compiled ${schemas.length} JSON Schema 2020-12 contracts and validated ${fixtureCount} fixtures.\n`);
process.stdout.write(
  `Validated ${bundleSemanticNegatives.length} locally well-hashed Pack007 Bundle semantic negatives.\n`
);
process.stdout.write(
  `Validated 1 Pack007 cross-run Handoff graph and ${graphRelationNegatives.length} locally valid relation negatives.\n`
);

const taskHashGoldenFile =
  path.join(goldenDir, "task-envelope-integrity-hashes.json");
const taskHashGolden = readJson(taskHashGoldenFile);
if (
  taskHashGolden.profile !== INTEGRITY_PROFILE
  || !Array.isArray(taskHashGolden.vectors)
  || taskHashGolden.vectors.length === 0
) {
  throw new Error(
    `${path.relative(repoRoot, taskHashGoldenFile)} has an unsupported or empty profile`
  );
}
for (const vector of taskHashGolden.vectors) {
  if (
    typeof vector.name !== "string"
    || typeof vector.taskHash !== "string"
    || !/^[a-f0-9]{64}$/.test(vector.taskHash)
    || vector.task === null
    || typeof vector.task !== "object"
  ) {
    throw new Error(
      `${path.relative(repoRoot, taskHashGoldenFile)} contains an invalid task hash vector`
    );
  }
  verifyTask(vector.task, `${vector.name}.task`);
  const expected = taskIntegrityHash(vector.task);
  if (vector.taskHash !== expected) {
    throw new Error(
      `${vector.name}: taskHash mismatch (expected ${expected})`
    );
  }
}
process.stdout.write(
  `Validated ${taskHashGolden.vectors.length} cross-language Task hash golden vector.\n`
);

const workerResultHashGoldenFile =
  path.join(goldenDir, "worker-result-envelope-integrity-hashes.json");
const workerResultHashGolden = readJson(workerResultHashGoldenFile);
if (
  workerResultHashGolden.profile !== INTEGRITY_PROFILE
  || !Array.isArray(workerResultHashGolden.vectors)
  || workerResultHashGolden.vectors.length === 0
) {
  throw new Error(
    `${path.relative(repoRoot, workerResultHashGoldenFile)} has an unsupported or empty profile`
  );
}
for (const vector of workerResultHashGolden.vectors) {
  assertExactObjectKeys(
    vector,
    ["name", "contentHash", "integrityHash", "workerResult"],
    `${path.relative(repoRoot, workerResultHashGoldenFile)} vector`
  );
  if (
    typeof vector.name !== "string"
    || !/^[a-f0-9]{64}$/.test(vector.contentHash)
    || !/^[a-f0-9]{64}$/.test(vector.integrityHash)
    || vector.workerResult === null
    || typeof vector.workerResult !== "object"
    || Array.isArray(vector.workerResult)
  ) {
    throw new Error(
      `${path.relative(repoRoot, workerResultHashGoldenFile)} contains an invalid Worker Result hash vector`
    );
  }
  const envelope = {
    ...JSON.parse(JSON.stringify(vector.workerResult)),
    integrityHash: vector.integrityHash
  };
  verifyUnicodeScalarTree(envelope, `${vector.name}.workerResult`);
  verifySafeContractTextTree(envelope, `${vector.name}.workerResult`);
  verifyWorkerResult(envelope, `${vector.name}.workerResult`);
  if (envelope.contentHash !== vector.contentHash) {
    throw new Error(`${vector.name}: contentHash golden mismatch`);
  }
}
process.stdout.write(
  `Validated ${workerResultHashGolden.vectors.length} cross-language Worker Result hash golden vector.\n`
);

const taskPackFiles = jsonFiles(taskPackDir);
if (taskPackFiles.length === 0) {
  throw new Error("No synthetic evaluation task packs found");
}

const seenTaskIds = new Set();
const allowedDataClasses = new Set(["PUBLIC", "PERSONAL", "SENSITIVE", "SECRET"]);
const allowedRisks = new Set(["READ_ONLY", "REVERSIBLE", "EXTERNAL", "IRREVERSIBLE"]);
const seenHarnessComparisonSuiteIds = new Set();
const seenHarnessComparisonExecutionIdentities = new Set();
let harnessComparisonPackCount = 0;
let deterministicAgentFaultPackCount = 0;
let postDispatchDeadlineFaultPackCount = 0;
let readOnlyWorkerHandoffPackCount = 0;

function verifyHarnessComparison(task, relative) {
  const comparison = task.harnessComparison;
  assertExactObjectKeys(
    comparison,
    [
      "suiteId",
      "variable",
      "repetitions",
      "pairedExecutionIdsSharedAcrossArms",
      "provenance",
      "frozen",
      "arms",
      "cases",
      "expectedTotals"
    ],
    `${relative}: harnessComparison`
  );
  if (
    task.schemaVersion !== "0.3"
    || task.seed.dataClass !== "PERSONAL"
    || task.risk !== "REVERSIBLE"
    || task.expectedArtifactKind !== "ARTICLE_DRAFT"
    || !task.seed.sourceRef.startsWith("synthetic://eval/")
    || !task.principalRef.startsWith("synthetic-")
  ) {
    throw new Error(
      `${relative}: offline Harness comparison must be a PERSONAL, REVERSIBLE, literal synthetic draft`
    );
  }
  if (
    task.modelEval !== undefined
    || task.syntheticProvenance !== undefined
    || task.offlineReplay !== undefined
    || task.expectedReplay !== undefined
    || task.replayVersions !== undefined
  ) {
    throw new Error(
      `${relative}: harnessComparison cannot also be a modelEval or offlineReplay pack`
    );
  }
  assertExactObjectKeys(
    task,
    [
      "schemaVersion",
      "taskId",
      "title",
      "principalRef",
      "seed",
      "requiredConstraints",
      "forbiddenActions",
      "acceptanceChecks",
      "humanReviewQuestions",
      "risk",
      "expectedArtifactKind",
      "faultPlan",
      "harnessComparison"
    ],
    relative
  );
  assertExactObjectKeys(
    task.seed,
    ["sourceType", "sourceRef", "dataClass", "content"],
    `${relative}: seed`
  );

  if (
    typeof comparison.suiteId !== "string"
    || !/^[a-z][a-z0-9._-]{0,199}$/.test(comparison.suiteId)
    || comparison.suiteId !== "offline-reference-grounding-verifier-v1"
    || comparison.variable !== "reference-grounding-verifier"
    || comparison.repetitions !== 3
    || comparison.pairedExecutionIdsSharedAcrossArms !== true
  ) {
    throw new Error(
      `${relative}: harnessComparison must freeze the S4-O1 suite, variable and three repetitions`
    );
  }
  if (seenHarnessComparisonSuiteIds.has(comparison.suiteId)) {
    throw new Error(
      `${relative}: duplicate Harness comparison suiteId ${comparison.suiteId}`
    );
  }
  seenHarnessComparisonSuiteIds.add(comparison.suiteId);

  assertExactObjectKeys(
    comparison.provenance,
    [
      "kind",
      "containsRealUserData",
      "containsRealAccount",
      "networkAllowed",
      "realModelAllowed",
      "connectorAllowed"
    ],
    `${relative}: harnessComparison.provenance`
  );
  if (
    comparison.provenance.kind !== "LITERAL_CHECKED_IN_SYNTHETIC"
    || comparison.provenance.containsRealUserData !== false
    || comparison.provenance.containsRealAccount !== false
    || comparison.provenance.networkAllowed !== false
    || comparison.provenance.realModelAllowed !== false
    || comparison.provenance.connectorAllowed !== false
  ) {
    throw new Error(
      `${relative}: harnessComparison provenance must be literal synthetic with model, network and Connector disabled`
    );
  }

  const frozen = comparison.frozen;
  assertExactObjectKeys(
    frozen,
    [
      "principalId",
      "captureId",
      "clientNonce",
      "sourceType",
      "sourceRef",
      "dataClass",
      "content",
      "intent",
      "frozenTime",
      "kernelLatencyMs",
      "maxModelSteps",
      "maxToolCalls",
      "taskDeadlineMs",
      "model",
      "harness",
      "agent",
      "policy",
      "state",
      "contextPolicy",
      "toolRegistry",
      "candidateGeneratorVersion",
      "traceIntegrity"
    ],
    `${relative}: harnessComparison.frozen`
  );
  for (const field of [
    "principalId",
    "captureId",
    "clientNonce",
    "sourceType",
    "sourceRef",
    "dataClass",
    "content",
    "intent",
    "frozenTime",
    "model",
    "harness",
    "agent",
    "policy",
    "state",
    "contextPolicy",
    "toolRegistry",
    "candidateGeneratorVersion",
    "traceIntegrity"
  ]) {
    if (typeof frozen[field] !== "string" || frozen[field].trim() === "") {
      throw new Error(
        `${relative}: harnessComparison.frozen.${field} must be a non-empty string`
      );
    }
  }
  for (const field of [
    "kernelLatencyMs",
    "maxModelSteps",
    "maxToolCalls",
    "taskDeadlineMs"
  ]) {
    if (!Number.isSafeInteger(frozen[field])) {
      throw new Error(
        `${relative}: harnessComparison.frozen.${field} must be an integer`
      );
    }
  }
  const frozenTimeMillis = Date.parse(frozen.frozenTime);
  const frozenTimeCanonical =
    Number.isNaN(frozenTimeMillis)
      ? null
      : new Date(frozenTimeMillis).toISOString().replace(".000Z", "Z");
  if (
    frozen.principalId !== task.principalRef
    || frozen.sourceType !== task.seed.sourceType
    || frozen.sourceRef !== task.seed.sourceRef
    || frozen.dataClass !== task.seed.dataClass
    || frozen.content !== task.seed.content
    || frozen.dataClass !== "PERSONAL"
    || frozen.sourceType !== "TEXT"
    || frozen.kernelLatencyMs < 0
    || frozen.kernelLatencyMs >= frozen.taskDeadlineMs
    || frozen.taskDeadlineMs < 1
    || frozen.taskDeadlineMs > MAX_DURATION_MS
    || frozen.maxModelSteps !== 2
    || frozen.maxToolCalls !== 1
    || frozen.model !== "scripted-fake-draft-v1"
    || frozen.harness !== "framework-free-agent-kernel-v1"
    || frozen.agent !== "agent-draft-service-v1"
    || frozen.policy !== "agent-draft-policy-v1"
    || frozen.state !== "stage2-s4-o1"
    || frozen.contextPolicy !== "ref-only-v1"
    || frozen.toolRegistry !== "agent-tools-v1"
    || frozen.candidateGeneratorVersion !== "literal-reference-candidate-fixture-v1"
    || frozen.traceIntegrity !== INTEGRITY_PROFILE
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(frozen.frozenTime)
    || frozenTimeCanonical !== frozen.frozenTime
  ) {
    throw new Error(
      `${relative}: harnessComparison frozen inputs or component versions drifted`
    );
  }
  for (const [field, value] of [
    ["captureId", frozen.captureId],
    ["clientNonce", frozen.clientNonce]
  ]) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/.test(value)) {
      throw new Error(
        `${relative}: harnessComparison.frozen.${field} has an invalid identifier`
      );
    }
  }

  if (!Array.isArray(comparison.arms) || comparison.arms.length !== 2) {
    throw new Error(`${relative}: harnessComparison requires exactly two arms`);
  }
  const expectedArms = [
    ["h0-schema-only", "schema-only-eval-v1"],
    ["h1-reference-grounding", "agent-draft-verifier-v1"]
  ];
  const seenArmIds = new Set();
  comparison.arms.forEach((arm, index) => {
    assertExactObjectKeys(
      arm,
      ["id", "verifier"],
      `${relative}: harnessComparison.arms[${index}]`
    );
    const [expectedId, expectedVerifier] = expectedArms[index];
    if (arm.id !== expectedId || arm.verifier !== expectedVerifier) {
      throw new Error(
        `${relative}: harnessComparison arms must freeze only the selected Verifier variable`
      );
    }
    if (seenArmIds.has(arm.id)) {
      throw new Error(`${relative}: duplicate Harness comparison arm id ${arm.id}`);
    }
    seenArmIds.add(arm.id);
  });

  if (!Array.isArray(comparison.cases) || comparison.cases.length !== 4) {
    throw new Error(`${relative}: harnessComparison requires exactly four cases`);
  }
  const expectedCases = [
    {
      id: "grounded",
      mode: "GROUNDED",
      fault: "NONE",
      h0Status: "SUCCEEDED",
      h0Reason: null,
      h1Status: "SUCCEEDED",
      h1Reason: null
    },
    {
      id: "claim-without-tool",
      mode: "CLAIM_WITHOUT_TOOL",
      fault: "CLAIM_WITHOUT_TOOL",
      h0Status: "SUCCEEDED",
      h0Reason: null,
      h1Status: "FAILED",
      h1Reason: "MISSING_REQUIRED_EVIDENCE"
    },
    {
      id: "omitted-claim",
      mode: "OMITTED_EVIDENCE_REF",
      fault: "OMITTED_EVIDENCE_REF",
      h0Status: "SUCCEEDED",
      h0Reason: null,
      h1Status: "FAILED",
      h1Reason: "INVALID_EVIDENCE_CLAIM"
    },
    {
      id: "extra-claim",
      mode: "EXTRA_EVIDENCE_REF",
      fault: "EXTRA_EVIDENCE_REF",
      h0Status: "SUCCEEDED",
      h0Reason: null,
      h1Status: "FAILED",
      h1Reason: "INVALID_EVIDENCE_CLAIM"
    }
  ];
  const seenCaseIds = new Set();
  const seenExecutionPrefixes = new Set();
  const generatedExecutionIds = new Set();
  comparison.cases.forEach((testCase, index) => {
    assertExactObjectKeys(
      testCase,
      [
        "id",
        "executionIdPrefix",
        "candidateMode",
        "faultClass",
        "expectedByArm"
      ],
      `${relative}: harnessComparison.cases[${index}]`
    );
    const expected = expectedCases[index];
    if (
      testCase.id !== expected.id
      || testCase.candidateMode !== expected.mode
      || testCase.faultClass !== expected.fault
      || typeof testCase.executionIdPrefix !== "string"
      || !/^[a-z][a-z0-9-]{0,80}$/.test(testCase.executionIdPrefix)
    ) {
      throw new Error(
        `${relative}: harnessComparison case ${index} drifted from the frozen S4-O1 fault matrix`
      );
    }
    if (seenCaseIds.has(testCase.id)) {
      throw new Error(`${relative}: duplicate Harness comparison case id ${testCase.id}`);
    }
    if (seenExecutionPrefixes.has(testCase.executionIdPrefix)) {
      throw new Error(
        `${relative}: duplicate Harness comparison executionIdPrefix ${testCase.executionIdPrefix}`
      );
    }
    seenCaseIds.add(testCase.id);
    seenExecutionPrefixes.add(testCase.executionIdPrefix);

    assertExactObjectKeys(
      testCase.expectedByArm,
      ["h0-schema-only", "h1-reference-grounding"],
      `${relative}: harnessComparison.cases[${index}].expectedByArm`
    );
    for (const [armId, expectedStatus, expectedReason] of [
      ["h0-schema-only", expected.h0Status, expected.h0Reason],
      ["h1-reference-grounding", expected.h1Status, expected.h1Reason]
    ]) {
      const verdict = testCase.expectedByArm[armId];
      assertExactObjectKeys(
        verdict,
        ["status", "failureReason"],
        `${relative}: harnessComparison.cases[${index}].expectedByArm.${armId}`
      );
      if (
        verdict.status !== expectedStatus
        || verdict.failureReason !== expectedReason
      ) {
        throw new Error(
          `${relative}: harnessComparison case ${testCase.id} has an invalid ${armId} verdict`
        );
      }
    }

    for (let repetition = 1; repetition <= comparison.repetitions; repetition += 1) {
      for (const kind of ["run", "task", "art"]) {
        const generated = `${kind}-${testCase.executionIdPrefix}-r${repetition}`;
        if (
          !/^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/.test(generated)
          || generatedExecutionIds.has(generated)
        ) {
          throw new Error(
            `${relative}: harnessComparison generated execution IDs must be unique and valid`
          );
        }
        generatedExecutionIds.add(generated);
        for (const arm of comparison.arms) {
          const compositeIdentity =
            `${comparison.suiteId}\0${arm.id}\0${generated}`;
          if (seenHarnessComparisonExecutionIdentities.has(compositeIdentity)) {
            throw new Error(
              `${relative}: Harness comparison composite execution identity is not unique`
            );
          }
          seenHarnessComparisonExecutionIdentities.add(compositeIdentity);
        }
      }
    }
  });

  assertExactObjectKeys(
    comparison.expectedTotals,
    [
      "pairedCandidates",
      "runs",
      "h0Accepted",
      "h0FaultAcceptances",
      "h1Accepted",
      "h1FaultRejections"
    ],
    `${relative}: harnessComparison.expectedTotals`
  );
  const expectedTotals = {
    pairedCandidates: 12,
    runs: 24,
    h0Accepted: 12,
    h0FaultAcceptances: 9,
    h1Accepted: 3,
    h1FaultRejections: 9
  };
  for (const [field, expected] of Object.entries(expectedTotals)) {
    if (comparison.expectedTotals[field] !== expected) {
      throw new Error(
        `${relative}: harnessComparison.expectedTotals.${field} must be ${expected}`
      );
    }
  }
}

function verifyDeterministicAgentFault(task, relative) {
  const expectedPath =
    "evals/task-packs/synthetic/005-offline-tool-arguments-schema-fault.json";
  const raw = fs.readFileSync(path.join(repoRoot, relative));
  const rawHash = crypto.createHash("sha256").update(raw).digest("hex");
  if (
    relative !== expectedPath
    || raw.length > 65_536
    || rawHash
      !== "64b7cf77942e444ee871d766c4dbcd38fa45fa1cdf0d2bf6e96d87f7b1211ece"
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault must use the frozen Pack 005 path, byte bound and raw SHA-256`
    );
  }
  const suite = task.deterministicAgentFault;
  assertExactObjectKeys(
    task,
    [
      "schemaVersion",
      "taskId",
      "title",
      "principalRef",
      "seed",
      "requiredConstraints",
      "forbiddenActions",
      "acceptanceChecks",
      "humanReviewQuestions",
      "risk",
      "expectedArtifactKind",
      "faultPlan",
      "deterministicAgentFault"
    ],
    relative
  );
  assertExactObjectKeys(
    task.seed,
    ["sourceType", "sourceRef", "dataClass", "content"],
    `${relative}: seed`
  );
  assertExactObjectKeys(
    suite,
    ["suiteId", "variable", "provenance", "frozen", "control", "fault"],
    `${relative}: deterministicAgentFault`
  );
  if (
    task.schemaVersion !== "0.4"
    || task.seed.dataClass !== "PUBLIC"
    || task.risk !== "REVERSIBLE"
    || task.expectedArtifactKind !== "ARTICLE_DRAFT"
    || !task.seed.sourceRef.startsWith("synthetic://eval/")
    || !task.principalRef.startsWith("synthetic-")
    || task.modelEval !== undefined
    || task.syntheticProvenance !== undefined
    || task.offlineReplay !== undefined
    || task.expectedReplay !== undefined
    || task.replayVersions !== undefined
    || task.harnessComparison !== undefined
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault must be an isolated PUBLIC synthetic reversible draft`
    );
  }
  if (
    suite.suiteId !== "offline-tool-arguments-schema-v1"
    || suite.variable !== "capture-read-extra-property"
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault suite or single variable drifted`
    );
  }
  assertExactObjectKeys(
    suite.provenance,
    [
      "kind",
      "containsRealUserData",
      "containsRealAccount",
      "networkAllowed",
      "realModelAllowed",
      "connectorAllowed"
    ],
    `${relative}: deterministicAgentFault.provenance`
  );
  if (
    suite.provenance.kind !== "LITERAL_CHECKED_IN_SYNTHETIC"
    || suite.provenance.containsRealUserData !== false
    || suite.provenance.containsRealAccount !== false
    || suite.provenance.networkAllowed !== false
    || suite.provenance.realModelAllowed !== false
    || suite.provenance.connectorAllowed !== false
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault provenance must disable real data, model, network and Connector`
    );
  }

  const frozen = suite.frozen;
  assertExactObjectKeys(
    frozen,
    [
      "principalId",
      "captureId",
      "clientNonce",
      "sourceType",
      "sourceRef",
      "dataClass",
      "content",
      "intent",
      "runId",
      "taskId",
      "artifactId",
      "frozenTime",
      "kernelLatencyMs",
      "taskSchemaVersion",
      "budgetUsd",
      "maxModelSteps",
      "maxToolCalls",
      "taskDeadlineMs",
      "executionProfile",
      "model",
      "harness",
      "agent",
      "verifier",
      "policy",
      "state",
      "contextPolicy",
      "toolRegistry",
      "traceIntegrity"
    ],
    `${relative}: deterministicAgentFault.frozen`
  );
  for (const field of [
    "principalId",
    "captureId",
    "clientNonce",
    "sourceType",
    "sourceRef",
    "dataClass",
    "content",
    "intent",
    "runId",
    "taskId",
    "artifactId",
    "frozenTime",
    "taskSchemaVersion",
    "budgetUsd",
    "executionProfile",
    "model",
    "harness",
    "agent",
    "verifier",
    "policy",
    "state",
    "contextPolicy",
    "toolRegistry",
    "traceIntegrity"
  ]) {
    if (typeof frozen[field] !== "string" || frozen[field].trim() === "") {
      throw new Error(
        `${relative}: deterministicAgentFault.frozen.${field} must be a non-empty string`
      );
    }
  }
  for (const field of [
    "kernelLatencyMs",
    "maxModelSteps",
    "maxToolCalls",
    "taskDeadlineMs"
  ]) {
    if (!Number.isSafeInteger(frozen[field])) {
      throw new Error(
        `${relative}: deterministicAgentFault.frozen.${field} must be an integer`
      );
    }
  }
  const frozenTimeMillis = Date.parse(frozen.frozenTime);
  const frozenTimeCanonical =
    Number.isNaN(frozenTimeMillis)
      ? null
      : new Date(frozenTimeMillis).toISOString().replace(".000Z", "Z");
  if (
    frozen.principalId !== task.principalRef
    || frozen.sourceType !== task.seed.sourceType
    || frozen.sourceRef !== task.seed.sourceRef
    || frozen.dataClass !== task.seed.dataClass
    || frozen.content !== task.seed.content
    || frozen.dataClass !== "PUBLIC"
    || frozen.sourceType !== "TEXT"
    || frozen.kernelLatencyMs !== 7
    || frozen.taskSchemaVersion !== "1.0"
    || frozen.budgetUsd !== "0.000000"
    || frozen.maxModelSteps !== 2
    || frozen.maxToolCalls !== 1
    || frozen.taskDeadlineMs !== 5000
    || frozen.executionProfile !== "offline-tool-arguments-fault-v1"
    || frozen.model !== "scripted-tool-arguments-fault-v1"
    || frozen.harness !== "framework-free-agent-kernel-v1"
    || frozen.agent !== "agent-draft-service-v1"
    || frozen.verifier !== "agent-draft-verifier-v1"
    || frozen.policy !== "agent-draft-policy-v1"
    || frozen.state !== "stage2-s4-f1"
    || frozen.contextPolicy !== "ref-only-v1"
    || frozen.toolRegistry !== "agent-tools-v2"
    || frozen.traceIntegrity !== INTEGRITY_PROFILE
    || frozenTimeCanonical !== frozen.frozenTime
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault frozen inputs or component versions drifted`
    );
  }
  for (const field of [
    "captureId",
    "clientNonce",
    "runId",
    "taskId",
    "artifactId"
  ]) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/.test(frozen[field])) {
      throw new Error(
        `${relative}: deterministicAgentFault.frozen.${field} has an invalid identifier`
      );
    }
  }

  const expectedReference = `capture://${frozen.captureId}`;
  const expectedCases = [
    {
      name: "control",
      id: "valid-control",
      argumentKeys: ["reference"],
      status: "SUCCEEDED",
      failureReason: null,
      traceTypes: [
        "MODEL_STEP",
        "TOOL_REQUEST",
        "TOOL_RESULT",
        "MODEL_STEP",
        "STRUCTURED_FINAL",
        "ARTIFACT_COMMITTED"
      ],
      traceStatuses: [
        "COMPLETED",
        "REQUESTED",
        "SUCCEEDED",
        "COMPLETED",
        "PROPOSED",
        "SUCCEEDED"
      ],
      modelCallCount: 2,
      validationCount: 1,
      toolExecuteCount: 1,
      totalFindOwnedCount: 2,
      toolBackedReadCount: 1,
      runStartCount: 1,
      runCompleteCount: 1,
      artifactCount: 1,
      consumedIdPrefixes: ["run", "task", "art"]
    },
    {
      name: "fault",
      id: "extra-property",
      argumentKeys: ["reference", "unexpected"],
      status: "FAILED",
      failureReason: "TOOL_ARGUMENTS_INVALID",
      traceTypes: ["MODEL_STEP", "TOOL_REJECTED"],
      traceStatuses: ["COMPLETED", "FAILED"],
      modelCallCount: 1,
      validationCount: 1,
      toolExecuteCount: 0,
      totalFindOwnedCount: 1,
      toolBackedReadCount: 0,
      runStartCount: 1,
      runCompleteCount: 1,
      artifactCount: 0,
      consumedIdPrefixes: ["run", "task"]
    }
  ];
  for (const expected of expectedCases) {
    const testCase = suite[expected.name];
    assertExactObjectKeys(
      testCase,
      ["id", "arguments", "expected"],
      `${relative}: deterministicAgentFault.${expected.name}`
    );
    if (testCase.id !== expected.id) {
      throw new Error(
        `${relative}: deterministicAgentFault.${expected.name}.id drifted`
      );
    }
    assertExactObjectKeys(
      testCase.arguments,
      expected.argumentKeys,
      `${relative}: deterministicAgentFault.${expected.name}.arguments`
    );
    if (testCase.arguments.reference !== expectedReference) {
      throw new Error(
        `${relative}: deterministic Agent fault reference must bind the frozen Capture`
      );
    }
    assertExactObjectKeys(
      testCase.expected,
      [
        "status",
        "failureReason",
        "traceTypes",
        "traceStatuses",
        "modelCallCount",
        "validationCount",
        "toolExecuteCount",
        "totalFindOwnedCount",
        "toolBackedReadCount",
        "runStartCount",
        "runCompleteCount",
        "artifactCount",
        "consumedIdPrefixes",
        "taskIntegrityHash",
        "traceRootHash",
        "bundleIntegrityHash"
      ],
      `${relative}: deterministicAgentFault.${expected.name}.expected`
    );
    if (
      testCase.expected.status !== expected.status
      || testCase.expected.failureReason !== expected.failureReason
      || JSON.stringify(testCase.expected.traceTypes)
        !== JSON.stringify(expected.traceTypes)
      || JSON.stringify(testCase.expected.traceStatuses)
        !== JSON.stringify(expected.traceStatuses)
      || testCase.expected.modelCallCount !== expected.modelCallCount
      || testCase.expected.validationCount !== expected.validationCount
      || testCase.expected.toolExecuteCount !== expected.toolExecuteCount
      || testCase.expected.totalFindOwnedCount !== expected.totalFindOwnedCount
      || testCase.expected.toolBackedReadCount !== expected.toolBackedReadCount
      || testCase.expected.runStartCount !== expected.runStartCount
      || testCase.expected.runCompleteCount !== expected.runCompleteCount
      || testCase.expected.artifactCount !== expected.artifactCount
      || JSON.stringify(testCase.expected.consumedIdPrefixes)
        !== JSON.stringify(expected.consumedIdPrefixes)
    ) {
      throw new Error(
        `${relative}: deterministicAgentFault.${expected.name} expected receipt drifted`
      );
    }
    for (const field of [
      "taskIntegrityHash",
      "traceRootHash",
      "bundleIntegrityHash"
    ]) {
      if (!/^[a-f0-9]{64}$/.test(testCase.expected[field])) {
        throw new Error(
          `${relative}: deterministicAgentFault.${expected.name}.expected.${field} must be a SHA-256 digest`
        );
      }
    }
  }
  if (
    suite.fault.arguments.unexpected
      !== "PUBLIC_SYNTHETIC_ARGUMENT_SENTINEL"
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault sentinel drifted`
    );
  }
  const faultWithoutSingleMutation = { ...suite.fault.arguments };
  delete faultWithoutSingleMutation.unexpected;
  if (
    JSON.stringify(faultWithoutSingleMutation)
      !== JSON.stringify(suite.control.arguments)
  ) {
    throw new Error(
      `${relative}: deterministic Agent fault must change exactly one extra argument`
    );
  }
}

function verifyPostDispatchDeadlineFault(task, relative) {
  const expectedPath =
    "evals/task-packs/synthetic/006-offline-read-only-tool-post-dispatch-deadline.json";
  const raw = fs.readFileSync(path.join(repoRoot, relative));
  const rawHash = crypto.createHash("sha256").update(raw).digest("hex");
  if (
    relative !== expectedPath
    || raw.length > 65_536
    || rawHash
      !== "ada80a0f05bffb907408cb9d877b039dbb46253ab09904404570f4c721b53f22"
  ) {
    throw new Error(
      `${relative}: post-dispatch deadline fault must use the frozen Pack 006 path, byte bound and raw SHA-256`
    );
  }
  const suite = task.postDispatchDeadlineFault;
  assertExactObjectKeys(
    task,
    [
      "schemaVersion",
      "taskId",
      "title",
      "principalRef",
      "seed",
      "requiredConstraints",
      "forbiddenActions",
      "acceptanceChecks",
      "humanReviewQuestions",
      "risk",
      "expectedArtifactKind",
      "faultPlan",
      "postDispatchDeadlineFault"
    ],
    relative
  );
  assertExactObjectKeys(
    task.seed,
    ["sourceType", "sourceRef", "dataClass", "content"],
    `${relative}: seed`
  );
  assertExactObjectKeys(
    suite,
    ["suiteId", "variable", "provenance", "frozen", "control", "fault"],
    `${relative}: postDispatchDeadlineFault`
  );
  if (
    task.schemaVersion !== "0.5"
    || task.taskId
      !== "synthetic-offline-read-only-tool-post-dispatch-deadline-006"
    || task.principalRef !== "synthetic-tool-deadline-owner"
    || task.seed.sourceType !== "TEXT"
    || task.seed.sourceRef !== "synthetic://eval/s4-f2-tool-deadline-006"
    || task.seed.dataClass !== "PUBLIC"
    || task.risk !== "REVERSIBLE"
    || task.expectedArtifactKind !== "ARTICLE_DRAFT"
    || suite.suiteId
      !== "offline-read-only-tool-post-dispatch-deadline-v1"
    || suite.variable !== "capture-read-completion-latency-ms"
    || task.modelEval !== undefined
    || task.syntheticProvenance !== undefined
    || task.offlineReplay !== undefined
    || task.expectedReplay !== undefined
    || task.replayVersions !== undefined
    || task.harnessComparison !== undefined
    || task.deterministicAgentFault !== undefined
  ) {
    throw new Error(
      `${relative}: post-dispatch deadline fault identity or isolation drifted`
    );
  }
  assertExactObjectKeys(
    suite.provenance,
    [
      "kind",
      "containsRealUserData",
      "containsRealAccount",
      "networkAllowed",
      "realModelAllowed",
      "connectorAllowed"
    ],
    `${relative}: postDispatchDeadlineFault.provenance`
  );
  if (
    suite.provenance.kind !== "LITERAL_CHECKED_IN_SYNTHETIC"
    || suite.provenance.containsRealUserData !== false
    || suite.provenance.containsRealAccount !== false
    || suite.provenance.networkAllowed !== false
    || suite.provenance.realModelAllowed !== false
    || suite.provenance.connectorAllowed !== false
  ) {
    throw new Error(
      `${relative}: post-dispatch deadline provenance must disable real data, model, network and Connector`
    );
  }

  const frozen = suite.frozen;
  const expectedFrozen = {
    principalId: "synthetic-tool-deadline-owner",
    captureId: "capture-tool-deadline-006",
    clientNonce: "tool-deadline-006",
    sourceType: "TEXT",
    sourceRef: "synthetic://eval/s4-f2-tool-deadline-006",
    dataClass: "PUBLIC",
    content:
      "这是公开、虚构、仅用于离线验证 post-dispatch deadline truth 的测试素材。",
    intent: "把公开的虚构测试素材整理成一段短文",
    runId: "run-tool-deadline-006",
    taskId: "task-tool-deadline-006",
    artifactId: "art-tool-deadline-006",
    frozenTime: "2026-07-31T00:00:00Z",
    taskSchemaVersion: "1.0",
    budgetUsd: "0.000000",
    maxModelSteps: 2,
    maxToolCalls: 1,
    taskDeadlineMs: 5,
    executionProfile: "offline-tool-deadline-fault-v1",
    model: "scripted-tool-deadline-fault-v1",
    harness: "framework-free-agent-kernel-v1",
    agent: "agent-draft-service-v1",
    verifier: "agent-draft-verifier-v1",
    policy: "agent-draft-policy-v1",
    state: "stage2-s4-f2",
    contextPolicy: "ref-only-v1",
    toolRegistry: "agent-tools-v2",
    traceIntegrity: INTEGRITY_PROFILE
  };
  assertExactObjectKeys(
    frozen,
    Object.keys(expectedFrozen),
    `${relative}: postDispatchDeadlineFault.frozen`
  );
  if (JSON.stringify(frozen) !== JSON.stringify(expectedFrozen)) {
    throw new Error(
      `${relative}: post-dispatch deadline frozen inputs or component versions drifted`
    );
  }

  const expectedKeys = [
    "status",
    "failureReason",
    "traceTypes",
    "traceStatuses",
    "modelCallCount",
    "validationCount",
    "toolExecuteCount",
    "totalFindOwnedCount",
    "toolBackedReadCount",
    "runStartCount",
    "runCompleteCount",
    "artifactCount",
    "evidenceRefCount",
    "resourceBindingCount",
    "uncertaintyCount",
    "consumedIdPrefixes",
    "taskIntegrityHash",
    "traceRootHash",
    "bundleIntegrityHash"
  ];
  const expectedCases = {
    control: {
      id: "within-deadline-control",
      completionLatencyMs: 4,
      expected: {
        status: "SUCCEEDED",
        failureReason: null,
        traceTypes: [
          "MODEL_STEP",
          "TOOL_REQUEST",
          "TOOL_RESULT",
          "MODEL_STEP",
          "STRUCTURED_FINAL",
          "ARTIFACT_COMMITTED"
        ],
        traceStatuses: [
          "COMPLETED",
          "REQUESTED",
          "SUCCEEDED",
          "COMPLETED",
          "PROPOSED",
          "SUCCEEDED"
        ],
        modelCallCount: 2,
        validationCount: 1,
        toolExecuteCount: 1,
        totalFindOwnedCount: 2,
        toolBackedReadCount: 1,
        runStartCount: 1,
        runCompleteCount: 1,
        artifactCount: 1,
        evidenceRefCount: 1,
        resourceBindingCount: 2,
        uncertaintyCount: 0,
        consumedIdPrefixes: ["run", "task", "art"],
        taskIntegrityHash:
          "da267ac640654eeae9e83e485a90f4cfd9496ea2ab95a4e3358f29b002696489",
        traceRootHash:
          "e3e72b87f00047a6a4c455b5ffe3698d75619d0174550359e88f62a7db8f18a9",
        bundleIntegrityHash:
          "ebb982e652777f176b3ae1786ca4a28d066eb58e82e21aaacfebbc08bdc45451"
      }
    },
    fault: {
      id: "deadline-exceeded-after-dispatch",
      completionLatencyMs: 7,
      expected: {
        status: "FAILED",
        failureReason: "TOOL_DEADLINE_EXCEEDED_AFTER_DISPATCH",
        traceTypes: ["MODEL_STEP", "TOOL_REQUEST", "TOOL_REJECTED"],
        traceStatuses: ["COMPLETED", "REQUESTED", "DEADLINE_EXCEEDED"],
        modelCallCount: 1,
        validationCount: 1,
        toolExecuteCount: 1,
        totalFindOwnedCount: 2,
        toolBackedReadCount: 1,
        runStartCount: 1,
        runCompleteCount: 1,
        artifactCount: 0,
        evidenceRefCount: 0,
        resourceBindingCount: 0,
        uncertaintyCount: 0,
        consumedIdPrefixes: ["run", "task"],
        taskIntegrityHash:
          "da267ac640654eeae9e83e485a90f4cfd9496ea2ab95a4e3358f29b002696489",
        traceRootHash:
          "d081324187b38e962ca14876a1b4fa9da762cdbb0823e90d44dc061763edadd0",
        bundleIntegrityHash:
          "71ce0d367459bbe5a55f1da713d9311c71ba06f33815f05b8f5017b3b9438f3a"
      }
    }
  };
  for (const [name, expectedCase] of Object.entries(expectedCases)) {
    const actual = suite[name];
    assertExactObjectKeys(
      actual,
      ["id", "completionLatencyMs", "expected"],
      `${relative}: postDispatchDeadlineFault.${name}`
    );
    assertExactObjectKeys(
      actual.expected,
      expectedKeys,
      `${relative}: postDispatchDeadlineFault.${name}.expected`
    );
    if (JSON.stringify(actual) !== JSON.stringify(expectedCase)) {
      throw new Error(
        `${relative}: post-dispatch deadline ${name} receipt drifted`
      );
    }
  }
  if (
    suite.control.completionLatencyMs >= frozen.taskDeadlineMs
    || suite.fault.completionLatencyMs <= frozen.taskDeadlineMs
  ) {
    throw new Error(
      `${relative}: control and fault must straddle the frozen Task deadline`
    );
  }
}

function verifyReadOnlyWorkerHandoff(task, relative) {
  const expectedPath =
    "evals/task-packs/synthetic/007-offline-read-only-worker-handoff-context-drift.json";
  const raw = fs.readFileSync(path.join(repoRoot, relative));
  const rawHash = crypto.createHash("sha256").update(raw).digest("hex");
  if (
    relative !== expectedPath
    || raw.length !== 8_443
    || raw.length > 65_536
    || rawHash
      !== "808661ba78fc1cd43aa0b54c6271fccf7002399b9d24690cfbb55b97ebbb831c"
  ) {
    throw new Error(
      `${relative}: read-only Worker Handoff must use the frozen Pack 007 path, byte length and raw SHA-256`
    );
  }

  assertExactObjectKeys(
    task,
    [
      "schemaVersion",
      "taskId",
      "title",
      "principalRef",
      "seed",
      "requiredConstraints",
      "forbiddenActions",
      "acceptanceChecks",
      "humanReviewQuestions",
      "risk",
      "expectedArtifactKind",
      "faultPlan",
      "readOnlyWorkerHandoff"
    ],
    relative
  );
  assertExactObjectKeys(
    task.seed,
    ["sourceType", "sourceRef", "dataClass", "content"],
    `${relative}: seed`
  );
  const suite = task.readOnlyWorkerHandoff;
  assertExactObjectKeys(
    suite,
    ["suiteId", "variable", "provenance", "frozen", "control", "fault"],
    `${relative}: readOnlyWorkerHandoff`
  );
  if (
    task.schemaVersion !== "0.6"
    || task.taskId
      !== "synthetic-offline-read-only-worker-handoff-context-drift-007"
    || task.principalRef !== "pack007-owner"
    || task.seed.sourceType !== "TEXT"
    || task.seed.sourceRef !== "synthetic-pack-007"
    || task.seed.dataClass !== "PUBLIC"
    || task.seed.content
      !== "Prompt 工程是在为概率程序构造运行时状态。"
    || task.risk !== "REVERSIBLE"
    || task.expectedArtifactKind !== "ARTICLE_DRAFT"
    || suite.suiteId
      !== "offline-read-only-worker-handoff-context-drift-v1"
    || suite.variable
      !== "registered-worker-context-policy-version"
    || task.modelEval !== undefined
    || task.syntheticProvenance !== undefined
    || task.offlineReplay !== undefined
    || task.expectedReplay !== undefined
    || task.replayVersions !== undefined
    || task.harnessComparison !== undefined
    || task.deterministicAgentFault !== undefined
    || task.postDispatchDeadlineFault !== undefined
  ) {
    throw new Error(
      `${relative}: read-only Worker Handoff identity or isolation drifted`
    );
  }

  assertExactObjectKeys(
    suite.provenance,
    [
      "kind",
      "containsRealUserData",
      "containsRealAccount",
      "networkAllowed",
      "realModelAllowed",
      "connectorAllowed"
    ],
    `${relative}: readOnlyWorkerHandoff.provenance`
  );
  if (
    suite.provenance.kind !== "LITERAL_CHECKED_IN_SYNTHETIC"
    || suite.provenance.containsRealUserData !== false
    || suite.provenance.containsRealAccount !== false
    || suite.provenance.networkAllowed !== false
    || suite.provenance.realModelAllowed !== false
    || suite.provenance.connectorAllowed !== false
  ) {
    throw new Error(
      `${relative}: Pack 007 provenance must disable real data, account, model, network and Connector`
    );
  }

  const expectedFrozen = {
    taskSchemaVersion: "1.0",
    principalId: "pack007-owner",
    captureId: "pack007-capture",
    captureNonce: "pack007-nonce",
    sourceType: "TEXT",
    sourceRef: "synthetic-pack-007",
    dataClass: "PUBLIC",
    content: "Prompt 工程是在为概率程序构造运行时状态。",
    intent: "把这个想法整理成一篇有证据的短文",
    parentRunId: "pack007-parent-run",
    childRunId: "pack007-child-run",
    parentTaskId: "pack007-parent-task",
    childTaskId: "pack007-child-task",
    artifactId: "pack007-parent-artifact",
    frozenTime: "2026-07-31T03:30:00Z",
    parentExecutionProfile: "api-fake-worker-agent-draft-v1",
    parentModel: "fake-model-v1",
    workerProfile: "article-draft-read-worker-v1",
    workerRegistry: "agent-workers-v1",
    workerName: "article-draft.read-v1",
    workerModel: "fake-worker-model-v1",
    workerProfileFingerprint:
      "e5f705bcc49d3e0302ab0a159f18ad597df01199fc7c0594e375b40011d519ac",
    harness: "framework-free-agent-kernel-v1",
    parentAgent: "agent-draft-service-v1",
    workerAgent: "agent-draft-worker-v1",
    verifier: "agent-draft-verifier-v1",
    policy: "agent-draft-policy-v1",
    state: "stage2-s2",
    parentContextPolicyVersion: "ref-only-v1",
    toolRegistry: "agent-tools-v2",
    traceIntegrity: INTEGRITY_PROFILE
  };
  assertExactObjectKeys(
    suite.frozen,
    Object.keys(expectedFrozen),
    `${relative}: readOnlyWorkerHandoff.frozen`
  );
  if (
    JSON.stringify(suite.frozen) !== JSON.stringify(expectedFrozen)
    || suite.frozen.principalId !== task.principalRef
    || suite.frozen.sourceType !== task.seed.sourceType
    || suite.frozen.sourceRef !== task.seed.sourceRef
    || suite.frozen.dataClass !== task.seed.dataClass
    || suite.frozen.content !== task.seed.content
  ) {
    throw new Error(
      `${relative}: Pack 007 frozen inputs, component versions or seed binding drifted`
    );
  }

  const receiptKeys = [
    "parentStatus",
    "parentFailureReason",
    "childStatus",
    "parentTraceTypes",
    "parentTraceStatuses",
    "childTraceTypes",
    "childTraceStatuses",
    "runStartCount",
    "runCompleteCount",
    "artifactCount",
    "workerResultCount",
    "workerVerifiedReadCount",
    "pairVerificationCount",
    "parentModelCallCount",
    "childModelCallCount",
    "childToolExecuteCount",
    "captureReadCount",
    "workerPreparationCount",
    "parentTaskHash",
    "childTaskHash",
    "parentTraceRoot",
    "childTraceRoot",
    "parentBundleHash",
    "childBundleHash",
    "workerResultIntegrityHash",
    "workerContentHash",
    "artifactContentHash"
  ];
  const expectedCases = {
    control: {
      id: "matching-context-policy-control",
      registeredWorkerContextPolicyVersion: "ref-only-v1",
      expected: {
        parentStatus: "SUCCEEDED",
        parentFailureReason: null,
        childStatus: "SUCCEEDED",
        parentTraceTypes: [
          "MODEL_STEP",
          "HANDOFF_REQUEST",
          "HANDOFF_RESULT",
          "MODEL_STEP",
          "STRUCTURED_FINAL",
          "ARTIFACT_COMMITTED"
        ],
        parentTraceStatuses: [
          "COMPLETED",
          "REQUESTED",
          "SUCCEEDED",
          "COMPLETED",
          "PROPOSED",
          "SUCCEEDED"
        ],
        childTraceTypes: [
          "MODEL_STEP",
          "TOOL_REQUEST",
          "TOOL_RESULT",
          "MODEL_STEP",
          "STRUCTURED_FINAL"
        ],
        childTraceStatuses: [
          "COMPLETED",
          "REQUESTED",
          "SUCCEEDED",
          "COMPLETED",
          "PROPOSED"
        ],
        runStartCount: 2,
        runCompleteCount: 2,
        artifactCount: 1,
        workerResultCount: 1,
        workerVerifiedReadCount: 1,
        pairVerificationCount: 1,
        parentModelCallCount: 2,
        childModelCallCount: 2,
        childToolExecuteCount: 1,
        captureReadCount: 3,
        workerPreparationCount: 1,
        parentTaskHash: graphReceipts.parentTaskHash,
        childTaskHash: graphReceipts.childTaskHash,
        parentTraceRoot: graphReceipts.parentTraceRoot,
        childTraceRoot: graphReceipts.childTraceRoot,
        parentBundleHash: graphReceipts.parentBundleHash,
        childBundleHash: graphReceipts.childBundleHash,
        workerResultIntegrityHash:
          graphReceipts.workerResultIntegrityHash,
        workerContentHash: graphReceipts.workerContentHash,
        artifactContentHash: graphReceipts.workerContentHash
      }
    },
    fault: {
      id: "registered-context-policy-drift",
      registeredWorkerContextPolicyVersion: "ref-only-v2",
      expected: {
        parentStatus: "BLOCKED",
        parentFailureReason: "HANDOFF_CONTEXT_POLICY_DRIFT",
        childStatus: null,
        parentTraceTypes: ["MODEL_STEP", "HANDOFF_REJECTED"],
        parentTraceStatuses: ["COMPLETED", "BLOCKED"],
        childTraceTypes: [],
        childTraceStatuses: [],
        runStartCount: 1,
        runCompleteCount: 1,
        artifactCount: 0,
        workerResultCount: 0,
        workerVerifiedReadCount: 0,
        pairVerificationCount: 0,
        parentModelCallCount: 1,
        childModelCallCount: 0,
        childToolExecuteCount: 0,
        captureReadCount: 1,
        workerPreparationCount: 1,
        parentTaskHash: graphReceipts.parentTaskHash,
        childTaskHash: null,
        parentTraceRoot:
          "b43c9c149a15e1e0cd7b96a29ad2f7b7c9f2b1e1422f84c41f316368e59bc2d9",
        childTraceRoot: null,
        parentBundleHash:
          "594793dc386abaef416390e4a4ae76e32fb94d19130c92a1ba0e2c39ad3f1e6e",
        childBundleHash: null,
        workerResultIntegrityHash: null,
        workerContentHash: null,
        artifactContentHash: null
      }
    }
  };
  for (const [name, expectedCase] of Object.entries(expectedCases)) {
    const actual = suite[name];
    assertExactObjectKeys(
      actual,
      ["id", "registeredWorkerContextPolicyVersion", "expected"],
      `${relative}: readOnlyWorkerHandoff.${name}`
    );
    assertExactObjectKeys(
      actual.expected,
      receiptKeys,
      `${relative}: readOnlyWorkerHandoff.${name}.expected`
    );
    if (JSON.stringify(actual) !== JSON.stringify(expectedCase)) {
      throw new Error(
        `${relative}: Pack 007 ${name} deterministic receipt drifted`
      );
    }
  }

  for (const [name, testCase] of Object.entries(expectedCases)) {
    for (const field of [
      "parentTaskHash",
      "parentTraceRoot",
      "parentBundleHash"
    ]) {
      if (!/^[a-f0-9]{64}$/.test(testCase.expected[field])) {
        throw new Error(
          `${relative}: Pack 007 ${name}.${field} must be a SHA-256 digest`
        );
      }
    }
    for (const field of [
      "childTaskHash",
      "childTraceRoot",
      "childBundleHash",
      "workerResultIntegrityHash",
      "workerContentHash",
      "artifactContentHash"
    ]) {
      const value = testCase.expected[field];
      if (value !== null && !/^[a-f0-9]{64}$/.test(value)) {
        throw new Error(
          `${relative}: Pack 007 ${name}.${field} must be null or a SHA-256 digest`
        );
      }
    }
  }
  if (
    suite.control.registeredWorkerContextPolicyVersion
      !== suite.frozen.parentContextPolicyVersion
    || suite.fault.registeredWorkerContextPolicyVersion
      === suite.frozen.parentContextPolicyVersion
    || suite.control.expected.parentTaskHash
      !== suite.fault.expected.parentTaskHash
  ) {
    throw new Error(
      `${relative}: Pack 007 control and fault must change only the registered Worker context policy`
    );
  }
}

for (const file of taskPackFiles) {
  const task = readJson(file);
  const relative = path.relative(repoRoot, file);
  verifyUnicodeScalarTree(task, relative);

  for (const field of ["schemaVersion", "taskId", "title", "principalRef", "expectedArtifactKind"]) {
    if (typeof task[field] !== "string" || task[field].trim() === "") {
      throw new Error(`${relative}: ${field} must be a non-empty string`);
    }
  }
  if (!/^\d+\.\d+$/.test(task.schemaVersion)) {
    throw new Error(`${relative}: schemaVersion must use major.minor format`);
  }
  if (seenTaskIds.has(task.taskId)) {
    throw new Error(`${relative}: duplicate taskId ${task.taskId}`);
  }
  seenTaskIds.add(task.taskId);

  if (!task.seed || typeof task.seed !== "object" || Array.isArray(task.seed)) {
    throw new Error(`${relative}: seed must be an object`);
  }
  for (const field of ["sourceType", "sourceRef", "content"]) {
    if (typeof task.seed[field] !== "string" || task.seed[field].trim() === "") {
      throw new Error(`${relative}: seed.${field} must be a non-empty string`);
    }
  }
  if (!allowedDataClasses.has(task.seed.dataClass)) {
    throw new Error(`${relative}: seed.dataClass is not supported`);
  }
  if (!allowedRisks.has(task.risk)) {
    throw new Error(`${relative}: risk is not supported`);
  }

  for (const field of [
    "requiredConstraints",
    "forbiddenActions",
    "acceptanceChecks",
    "humanReviewQuestions",
    "faultPlan"
  ]) {
    assertNonEmptyStrings(task[field], `${relative}: ${field}`);
  }

  if (task.harnessComparison !== undefined) {
    verifyHarnessComparison(task, relative);
    harnessComparisonPackCount += 1;
  }

  if (task.deterministicAgentFault !== undefined) {
    verifyDeterministicAgentFault(task, relative);
    deterministicAgentFaultPackCount += 1;
  }

  if (task.postDispatchDeadlineFault !== undefined) {
    verifyPostDispatchDeadlineFault(task, relative);
    postDispatchDeadlineFaultPackCount += 1;
  }

  if (task.readOnlyWorkerHandoff !== undefined) {
    verifyReadOnlyWorkerHandoff(task, relative);
    readOnlyWorkerHandoffPackCount += 1;
  }

  if (task.offlineReplay !== undefined || task.expectedReplay !== undefined) {
    if (!task.offlineReplay || typeof task.offlineReplay !== "object" || Array.isArray(task.offlineReplay)) {
      throw new Error(`${relative}: offlineReplay must be an object`);
    }
    if (!task.expectedReplay || typeof task.expectedReplay !== "object" || Array.isArray(task.expectedReplay)) {
      throw new Error(`${relative}: expectedReplay must be an object`);
    }
    const replay = task.offlineReplay;
    for (const field of [
      "principalId",
      "captureId",
      "clientNonce",
      "sourceType",
      "sourceRef",
      "dataClass",
      "content",
      "intent",
      "runId",
      "taskId",
      "artifactId",
      "frozenTime"
    ]) {
      if (typeof replay[field] !== "string" || replay[field].trim() === "") {
        throw new Error(`${relative}: offlineReplay.${field} must be a non-empty string`);
      }
    }
    for (const field of ["kernelLatencyMs", "maxModelSteps", "maxToolCalls", "taskDeadlineMs"]) {
      if (!Number.isSafeInteger(replay[field])) {
        throw new Error(`${relative}: offlineReplay.${field} must be an integer`);
      }
    }
    if (replay.kernelLatencyMs < 0
        || replay.kernelLatencyMs > MAX_DURATION_MS
        || replay.taskDeadlineMs < 1
        || replay.taskDeadlineMs > MAX_DURATION_MS
        || replay.kernelLatencyMs >= replay.taskDeadlineMs
        || replay.maxModelSteps < 1
        || replay.maxModelSteps > MAX_EXECUTION_LIMIT
        || replay.maxToolCalls < 1
        || replay.maxToolCalls > MAX_EXECUTION_LIMIT) {
      throw new Error(`${relative}: offlineReplay execution limits are invalid`);
    }
    if (
      replay.principalId !== task.principalRef
      || replay.sourceType !== task.seed.sourceType
      || replay.sourceRef !== task.seed.sourceRef
      || replay.dataClass !== task.seed.dataClass
      || replay.content !== task.seed.content
    ) {
      throw new Error(`${relative}: offlineReplay must bind the declared principal and seed exactly`);
    }
    if (replay.dataClass !== "PERSONAL") {
      throw new Error(`${relative}: S2 offline golden replay requires PERSONAL dataClass`);
    }
    if (!Number.isSafeInteger(task.expectedReplay.traceEventCount)
        || task.expectedReplay.traceEventCount < 1) {
      throw new Error(`${relative}: expectedReplay.traceEventCount must be a positive integer`);
    }
    if (task.expectedReplay.status !== "SUCCEEDED") {
      throw new Error(`${relative}: expectedReplay.status must be SUCCEEDED`);
    }
    for (const field of [
      "captureRequestHash",
      "traceRootHash",
      "artifactContentHash",
      "bundleIntegrityHash"
    ]) {
      if (typeof task.expectedReplay[field] !== "string"
          || !/^[a-f0-9]{64}$/.test(task.expectedReplay[field])) {
        throw new Error(`${relative}: expectedReplay.${field} must be a lowercase SHA-256 digest`);
      }
    }
    if (!task.replayVersions
        || typeof task.replayVersions !== "object"
        || Array.isArray(task.replayVersions)) {
      throw new Error(`${relative}: replayVersions must be an object`);
    }
    for (const field of [
      "model",
      "harness",
      "agent",
      "verifier",
      "policy",
      "state",
      "contextPolicy",
      "toolRegistry",
      "traceIntegrity"
    ]) {
      if (typeof task.replayVersions[field] !== "string"
          || task.replayVersions[field].trim() === "") {
        throw new Error(`${relative}: replayVersions.${field} must be a non-empty string`);
      }
    }
  }

  if (task.modelEval !== undefined || task.syntheticProvenance !== undefined) {
    assertExactObjectKeys(
      task.syntheticProvenance,
      ["kind", "containsRealUserData", "containsRealAccount"],
      `${relative}: syntheticProvenance`
    );
    if (
      task.syntheticProvenance.kind !== "LITERAL_CHECKED_IN_SYNTHETIC"
      || task.syntheticProvenance.containsRealUserData !== false
      || task.syntheticProvenance.containsRealAccount !== false
    ) {
      throw new Error(
        `${relative}: model egress requires exact literal synthetic provenance`
      );
    }
    assertExactObjectKeys(
      task.modelEval,
      [
        "caseId",
        "captureId",
        "clientNonce",
        "runId",
        "taskId",
        "artifactId",
        "intent",
        "requiredTools",
        "maximumProviderRequests"
      ],
      `${relative}: modelEval`
    );
    for (const field of [
      "caseId",
      "captureId",
      "clientNonce",
      "runId",
      "taskId",
      "artifactId",
      "intent"
    ]) {
      if (
        typeof task.modelEval[field] !== "string"
        || task.modelEval[field].trim() === ""
      ) {
        throw new Error(
          `${relative}: modelEval.${field} must be a non-empty string`
        );
      }
    }
    if (
      task.schemaVersion !== "0.2"
      || task.seed.dataClass !== "PUBLIC"
      || task.risk !== "EXTERNAL"
      || !task.seed.sourceRef.startsWith("synthetic://eval/")
      || !task.principalRef.startsWith("synthetic-")
      || JSON.stringify(task.modelEval.requiredTools)
        !== JSON.stringify(["capture.read"])
      || task.modelEval.maximumProviderRequests !== 2
    ) {
      throw new Error(
        `${relative}: modelEval must be the bounded PUBLIC synthetic two-call route`
      );
    }
    if (task.offlineReplay !== undefined || task.expectedReplay !== undefined) {
      throw new Error(
        `${relative}: modelEval cannot also be an offlineReplay pack`
      );
    }
  }
}

if (harnessComparisonPackCount === 0) {
  throw new Error("No offline Harness comparison Task Pack found");
}
if (deterministicAgentFaultPackCount !== 1) {
  throw new Error("Exactly one deterministic Agent fault Task Pack is required");
}
if (postDispatchDeadlineFaultPackCount !== 1) {
  throw new Error("Exactly one post-dispatch deadline fault Task Pack is required");
}
if (readOnlyWorkerHandoffPackCount !== 1) {
  throw new Error("Exactly one read-only Worker Handoff Task Pack is required");
}

process.stdout.write(`Validated ${taskPackFiles.length} synthetic evaluation task packs with unique task IDs.\n`);

const evalEnvironmentFiles = jsonFiles(evalEnvironmentDir);
if (evalEnvironmentFiles.length === 0) {
  throw new Error("No synthetic evaluation environment manifests found");
}
const relativeEvalEnvironmentFiles =
  evalEnvironmentFiles.map((file) => path.relative(repoRoot, file));
if (
  JSON.stringify(relativeEvalEnvironmentFiles)
  !== JSON.stringify([...frozenEvalEnvironments.keys()].sort())
) {
  throw new Error(
    "Synthetic evaluation environment manifests must exactly match the frozen identity set"
  );
}
const evalEnvironmentsByRelative = new Map();
for (const file of evalEnvironmentFiles) {
  const relative = path.relative(repoRoot, file);
  const frozenIdentity = frozenEvalEnvironments.get(relative);
  const raw = fs.readFileSync(file);
  const rawHash = crypto.createHash("sha256").update(raw).digest("hex");
  if (
    frozenIdentity === undefined
    || rawHash !== frozenIdentity.rawSha256
  ) {
    throw new Error(
      `${relative}: evaluation environment path and raw SHA-256 must match a frozen identity`
    );
  }
  const environment = readJson(file);
  if (environment.toolRegistryVersion !== frozenIdentity.toolRegistryVersion) {
    throw new Error(
      `${relative}: toolRegistryVersion does not match its frozen environment identity`
    );
  }
  evalEnvironmentsByRelative.set(relative, environment);
  verifyUnicodeScalarTree(environment, relative);
  assertExactObjectKeys(
    environment,
    [
      "schemaVersion",
      "reviewedAt",
      "javaRelease",
      "openaiJavaVersion",
      "protocolVersion",
      "harnessVersion",
      "toolRegistryVersion",
      "tools",
      "providerApi",
      "model",
      "requestPolicy",
      "pricing",
      "inputTokenUpperBound",
      "promptCachePolicy",
      "operatorGate"
    ],
    relative
  );
  if (
    environment.schemaVersion !== "0.1"
    || !/^\d{4}-\d{2}-\d{2}$/.test(environment.reviewedAt)
    || environment.javaRelease !== 21
    || environment.providerApi !== "responses"
    || JSON.stringify(environment.tools) !== JSON.stringify(["capture.read"])
  ) {
    throw new Error(`${relative}: unsupported evaluation environment identity`);
  }
  assertExactObjectKeys(
    environment.model,
    [
      "requested",
      "pricingFamily",
      "maxInputTokens",
      "maxOutputTokens",
      "reference"
    ],
    `${relative}: model`
  );
  assertExactObjectKeys(
    environment.requestPolicy,
    [
      "store",
      "parallelToolCalls",
      "serviceTier",
      "maxRetries",
      "productionBaseUrl",
      "productionBaseUrlOverrideAllowed",
      "ambientProxyAllowed",
      "sdkLogLevel",
      "maximumProviderRequests",
      "maximumInputTokensPerRequest",
      "maximumOutputTokensPerRequest"
    ],
    `${relative}: requestPolicy`
  );
  assertExactObjectKeys(
    environment.pricing,
    [
      "currency",
      "unit",
      "uncachedInput",
      "cachedInput",
      "output",
      "fullRunReservationUsd",
      "reference"
    ],
    `${relative}: pricing`
  );
  assertExactObjectKeys(
    environment.inputTokenUpperBound,
    ["method", "tokens", "reference"],
    `${relative}: inputTokenUpperBound`
  );
  assertExactObjectKeys(
    environment.promptCachePolicy,
    ["mode", "cacheWriteFeeIncluded", "reference"],
    `${relative}: promptCachePolicy`
  );
  assertExactObjectKeys(
    environment.operatorGate,
    [
      "posixOneShotMarkerRequired",
      "realTtyChallengeRequired",
      "credentialReadAfterPermit",
      "durableRunRecordRequired",
      "attemptJournalRequired",
      "atomicFinalPublishRequired"
    ],
    `${relative}: operatorGate`
  );
  if (
    typeof environment.model.requested !== "string"
    || typeof environment.model.pricingFamily !== "string"
    || !Number.isSafeInteger(environment.model.maxInputTokens)
    || environment.model.maxInputTokens < 1
    || environment.model.maxInputTokens > 272_000
    || !Number.isSafeInteger(environment.model.maxOutputTokens)
    || environment.model.maxOutputTokens < 1
    || environment.requestPolicy.store !== false
    || environment.requestPolicy.parallelToolCalls !== false
    || environment.requestPolicy.serviceTier !== "default"
    || environment.requestPolicy.maxRetries !== 0
    || environment.requestPolicy.productionBaseUrl
      !== "https://api.openai.com/v1"
    || environment.requestPolicy.productionBaseUrlOverrideAllowed !== false
    || environment.requestPolicy.ambientProxyAllowed !== false
    || environment.requestPolicy.sdkLogLevel !== "OFF"
    || environment.requestPolicy.maximumProviderRequests !== 2
    || environment.requestPolicy.maximumInputTokensPerRequest
      !== environment.inputTokenUpperBound.tokens
    || environment.requestPolicy.maximumInputTokensPerRequest
      !== environment.model.maxInputTokens
    || environment.requestPolicy.maximumOutputTokensPerRequest
      > environment.model.maxOutputTokens
    || environment.inputTokenUpperBound.method
      !== "OFFICIAL_MODEL_MAX_INPUT"
    || environment.promptCachePolicy.cacheWriteFeeIncluded !== false
    || environment.operatorGate.posixOneShotMarkerRequired !== true
    || environment.operatorGate.realTtyChallengeRequired !== true
    || environment.operatorGate.credentialReadAfterPermit !== true
    || environment.operatorGate.durableRunRecordRequired !== true
    || environment.operatorGate.attemptJournalRequired !== true
    || environment.operatorGate.atomicFinalPublishRequired !== true
  ) {
    throw new Error(`${relative}: unsafe synthetic evaluation environment`);
  }
  for (const rate of [
    environment.pricing.uncachedInput,
    environment.pricing.cachedInput,
    environment.pricing.output
  ]) {
    if (!Number.isSafeInteger(rate) || rate < 0) {
      throw new Error(`${relative}: pricing rates must be non-negative integers`);
    }
  }
  for (const reference of [
    environment.model.reference,
    environment.pricing.reference,
    environment.inputTokenUpperBound.reference,
    environment.promptCachePolicy.reference
  ]) {
    if (
      typeof reference !== "string"
      || !reference.startsWith("https://developers.openai.com/")
    ) {
      throw new Error(`${relative}: references must use official OpenAI docs`);
    }
  }
}
const historicalEnvironment = evalEnvironmentsByRelative.get(
  "evals/environments/openai-responses-synthetic-v1.json"
);
const activeEnvironment = evalEnvironmentsByRelative.get(
  "evals/environments/openai-responses-synthetic-v2.json"
);
if (
  JSON.stringify({
    ...historicalEnvironment,
    toolRegistryVersion: "agent-tools-v2"
  }) !== JSON.stringify(activeEnvironment)
) {
  throw new Error(
    "Synthetic evaluation environment v2 must differ from historical v1 only by toolRegistryVersion"
  );
}
process.stdout.write(
  `Validated ${evalEnvironmentFiles.length} synthetic evaluation environment manifest.\n`
);
