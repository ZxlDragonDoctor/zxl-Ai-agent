#!/bin/sh
set -eu
case "${1:-}" in
 bootstrap)
  [ -n "${MIGRATOR_DB_PASSWORD:-}" ] && [ -n "${RUNTIME_DB_PASSWORD:-}" ] || { echo ROLE_PASSWORD_REQUIRED >&2; exit 1; }
  [ "$MIGRATOR_DB_PASSWORD" != "$RUNTIME_DB_PASSWORD" ] && [ "$MIGRATOR_DB_PASSWORD" != "${PGPASSWORD:-}" ] && [ "$RUNTIME_DB_PASSWORD" != "${PGPASSWORD:-}" ] || { echo ROLE_PASSWORD_POLICY >&2; exit 1; }
  file=/deployment/runtime-bootstrap.sql ;;
 grant) file=/deployment/runtime-grants.sql ;;
 *) echo ADMIN_TASK_INVALID >&2; exit 1 ;;
esac
[ -n "${PGHOST:-}" ] && [ -n "${PGUSER:-}" ] && [ -n "${PGPASSWORD:-}" ] || { echo ADMIN_CONNECTION_REQUIRED >&2; exit 1; }
exec psql -Xq -v ON_ERROR_STOP=1 -f "$file"
