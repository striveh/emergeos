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

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
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
  "HANDOFF"
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

function verifyTrace(instance, relative) {
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
        && ["BLOCKED", "LIMIT_EXHAUSTED", "FAILED", "MALFORMED_RESULT"]
          .includes(event.status))
      || (event.type === "STRUCTURED_FINAL"
        && event.status === "PROPOSED"
        && event.reference === taskRef)
      || (event.type === "ARTIFACT_COMMITTED"
        && event.status === "SUCCEEDED"
        && /^artifact-version:\/\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}\/[1-9][0-9]*$/
          .test(event.reference));
    if (!allowed) {
      throw new Error(`${relative}: Trace event metadata is outside the safe allowlist`);
    }
    root = nextTraceRoot(root, event.eventHash);
  });
  if (instance.rootHash !== root) {
    throw new Error(`${relative}: Trace rootHash mismatch`);
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

function verifyTask(instance, relative) {
  if (!isUsdDomain(instance.budgetUsd)) {
    throw new Error(`${relative}: budgetUsd is outside the shared six-decimal USD domain`);
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
    || (instance.costUsd > task.budgetUsd
      && !(instance.schemaVersion === "1.1"
        && instance.outcome !== "SUCCEEDED"
        && instance.failureAttribution === "MODEL_BUDGET_EXHAUSTED"))
    || instance.latencyMs > task.deadlineMs
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
  if (instance.integrityProfile !== INTEGRITY_PROFILE) {
    throw new Error(`${relative}: unsupported integrityProfile`);
  }
  const preimage = JSON.parse(JSON.stringify(instance));
  delete preimage.integrityHash;
  if (preimage.task.schemaVersion === "1.0") {
    delete preimage.task.modelProvider;
    delete preimage.task.modelRequested;
    delete preimage.task.pricingProfile;
  }
  const expected = domainHash(
    "emergeos.harness-run-bundle.v1",
    canonicalEncode(preimage)
  );
  if (instance.integrityHash !== expected) {
    throw new Error(
      `${relative}: integrityHash mismatch (expected ${expected})`
    );
  }
}

function verifySemantic(contractName, instance, relative) {
  verifyUnicodeScalarTree(instance, relative);
  verifySafeContractTextTree(instance, relative);
  if (contractName === "agent-trace-envelope") {
    verifyTrace(instance, relative);
  }
  if (contractName === "result-envelope") {
    verifyResult(instance, relative);
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
    fixtureCount += 1;
  }
}

process.stdout.write(`Compiled ${schemas.length} JSON Schema 2020-12 contracts and validated ${fixtureCount} fixtures.\n`);

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
  const preimage = JSON.parse(JSON.stringify(vector.task));
  if (preimage.schemaVersion === "1.0") {
    delete preimage.modelProvider;
    delete preimage.modelRequested;
    delete preimage.pricingProfile;
  }
  const expected = domainHash(
    "emergeos.task-envelope.v1",
    canonicalEncode(preimage)
  );
  if (vector.taskHash !== expected) {
    throw new Error(
      `${vector.name}: taskHash mismatch (expected ${expected})`
    );
  }
}
process.stdout.write(
  `Validated ${taskHashGolden.vectors.length} cross-language Task hash golden vector.\n`
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

process.stdout.write(`Validated ${taskPackFiles.length} synthetic evaluation task packs with unique task IDs.\n`);

const evalEnvironmentFiles = jsonFiles(evalEnvironmentDir);
if (evalEnvironmentFiles.length === 0) {
  throw new Error("No synthetic evaluation environment manifests found");
}
for (const file of evalEnvironmentFiles) {
  const environment = readJson(file);
  const relative = path.relative(repoRoot, file);
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
process.stdout.write(
  `Validated ${evalEnvironmentFiles.length} synthetic evaluation environment manifest.\n`
);
