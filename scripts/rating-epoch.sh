#!/usr/bin/env bash
# Explicit operator command; never called by application startup or deployment.
set -euo pipefail
if [ "$#" -eq 0 ]; then
  echo 'usage: mise run rating:epoch -- <plan|probe|pause|resume|stage|activate|rollback|status> [key=value ...]' >&2
  exit 2
fi
# sbt parses one command string. Refuse its separators/quoting rather than allowing an argument to add commands.
# Use paths without whitespace through this wrapper; direct JVM invocation can pass ordinary quoted arguments.
for arg in "$@"; do
  case "$arg" in
    *[[:space:]]*|*\;*|*\"*|*\'*|*\\*)
      echo 'rating:epoch arguments must not contain whitespace, quotes, backslashes or semicolons' >&2
      exit 2
      ;;
  esac
done
exec sbt "runMain dicechess.play.rating.migration.RatingEpochMigrationMain $*"
