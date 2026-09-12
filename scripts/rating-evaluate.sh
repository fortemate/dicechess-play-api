#!/usr/bin/env bash
# Runs the offline chronological rating-model evaluation (#148):
#   mise run rating:evaluate -- corpus=<games.jsonl[.gz]> out=<dir> [models=grid|baseline] [model=<spec> ...] [option=value ...]
#
# A script rather than an inline mise template for the same reason as `rating-replay.sh`: the options must reach
# `runMain` as separate, unquoted words inside ONE sbt command string. See `RatingEvaluationMain` for the option list;
# the evaluation reads files only and never touches a database. The test window is reported only when
# `report-windows` names it — keep that for the single preregistered look.
set -euo pipefail
if [ "$#" -eq 0 ]; then
  echo "usage: mise run rating:evaluate -- corpus=<games.jsonl[.gz]> out=<dir> [models=grid|baseline] [model=<spec> ...] [option=value ...]" >&2
  exit 2
fi
exec sbt "runMain dicechess.play.rating.RatingEvaluationMain $*"
