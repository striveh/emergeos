#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required for the PostgreSQL and Fake Provider evidence." >&2
  exit 1
}

docker info >/dev/null 2>&1 || {
  echo "Docker must be running before the Stage 1 operating demo." >&2
  exit 1
}

echo "Running the loopback-only Stage 1 packaged-process operating demo..."
echo "This uses synthetic data and an independent file-backed Fake Provider JVM."

./mvnw --batch-mode --no-transfer-progress \
  -pl apps/api -am verify \
  -Dit.test=RecoverableLocalActionHttpIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
