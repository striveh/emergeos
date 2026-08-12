#!/bin/sh
set -eu

usage() {
  echo "usage: provision-pack010-postgres-roles.sh --check|--apply" >&2
  exit 2
}

[ "$#" -eq 1 ] || usage
mode=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
provision_sql="$script_dir/../adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles.sql"
audit_sql="$script_dir/../adapters/postgres/src/main/resources/db/provisioning/pack010_runtime_roles_check.sql"
psql_bin=${PSQL_BIN:-psql}

[ -r "$provision_sql" ] || {
  echo "Pack010 provisioning SQL is unavailable" >&2
  exit 3
}
[ -r "$audit_sql" ] || {
  echo "Pack010 provisioning audit SQL is unavailable" >&2
  exit 3
}

case "$mode" in
  --check)
    "$psql_bin" -X --set=ON_ERROR_STOP=1 --file="$audit_sql"
    ;;
  --apply)
    [ "${EMERGEOS_PROVISION_CONFIRM:-}" = "PACK010_ROLE_APPLY" ] || {
      echo "Pack010 provisioning is default-deny; confirmation is missing" >&2
      exit 4
    }
    "$psql_bin" -X --set=ON_ERROR_STOP=1 --single-transaction \
      --file="$provision_sql" --file="$audit_sql"
    ;;
  *)
    usage
    ;;
esac
