#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

node - "$repo_root" <<'NODE'
const fs = require("node:fs");
const path = require("node:path");

const root = process.argv[2];
const ignoredDirectories = new Set([
  ".git",
  ".workbuddy",
  "node_modules",
  "target",
]);
const markdownFiles = [];

function walk(directory) {
  for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
    if (ignoredDirectories.has(entry.name)) continue;
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(absolute);
    else if (entry.isFile() && entry.name.endsWith(".md")) markdownFiles.push(absolute);
  }
}

walk(root);
const failures = [];
const linkPattern = /\]\(([^)]+)\)/g;

for (const file of markdownFiles) {
  const content = fs.readFileSync(file, "utf8");
  for (const match of content.matchAll(linkPattern)) {
    let target = match[1].trim();
    if (
      target.startsWith("http://") ||
      target.startsWith("https://") ||
      target.startsWith("mailto:") ||
      target.startsWith("#")
    ) {
      continue;
    }
    target = target.replace(/^<|>$/g, "").split("#", 1)[0];
    if (!target) continue;
    const resolved = path.resolve(path.dirname(file), target);
    if (!fs.existsSync(resolved)) {
      failures.push(`${path.relative(root, file)} -> ${target}`);
    }
  }
}

if (failures.length > 0) {
  throw new Error(`Broken local Markdown links:\n${failures.join("\n")}`);
}
process.stdout.write(`Validated local links in ${markdownFiles.length} Markdown files.\n`);
NODE
