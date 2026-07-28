#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -d "$repo_root/node_modules/ajv" || ! -d "$repo_root/node_modules/ajv-formats" ]]; then
  echo "Contract tooling dependencies are missing. Run 'npm ci' from the repository root." >&2
  exit 1
fi

node "$repo_root/scripts/validate-contracts.mjs"
