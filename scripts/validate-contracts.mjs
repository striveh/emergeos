import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const schemaDir = path.join(repoRoot, "contracts", "schemas", "v1");
const fixtureDir = path.join(repoRoot, "contracts", "fixtures", "v1");
const taskPackDir = path.join(repoRoot, "evals", "task-packs", "synthetic");

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

const schemaFiles = jsonFiles(schemaDir);
if (schemaFiles.length === 0) {
  throw new Error("No contract schemas found");
}

const ajv = new Ajv2020({
  allErrors: true,
  strict: true
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
    fixtureCount += 1;
  }

  for (const fixture of invalidFixtures) {
    const instance = readJson(fixture);
    if (validate(instance)) {
      throw new Error(`${path.relative(repoRoot, fixture)} should be invalid but passed`);
    }
    fixtureCount += 1;
  }
}

process.stdout.write(`Compiled ${schemas.length} JSON Schema 2020-12 contracts and validated ${fixtureCount} fixtures.\n`);

const taskPackFiles = jsonFiles(taskPackDir);
if (taskPackFiles.length === 0) {
  throw new Error("No synthetic evaluation task packs found");
}

const seenTaskIds = new Set();
const allowedDataClasses = new Set(["PUBLIC", "PERSONAL", "SENSITIVE", "SECRET"]);
const allowedRisks = new Set(["READ_ONLY", "REVERSIBLE", "EXTERNAL", "IRREVERSIBLE"]);

for (const file of taskPackFiles) {
  const task = readJson(file);
  const relative = path.relative(repoRoot, file);

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

  for (const field of ["requiredConstraints", "forbiddenActions", "acceptanceChecks", "faultPlan"]) {
    assertNonEmptyStrings(task[field], `${relative}: ${field}`);
  }
}

process.stdout.write(`Validated ${taskPackFiles.length} synthetic evaluation task packs with unique task IDs.\n`);
