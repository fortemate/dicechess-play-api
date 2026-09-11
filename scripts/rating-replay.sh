#!/usr/bin/env bash
# Runs the offline rating replay (#145): `mise run rating:replay -- corpus=<games.jsonl[.gz]> out=<dir> [option=value ...]`.
#
# A script rather than an inline mise template because the options must reach `runMain` as separate, unquoted words
# inside ONE sbt command string: mise's argument templating quotes each word, and sbt then hands the quotes to the
# program, which reads `'corpus` as the key. Options never contain spaces (paths and instants), so `$*` is exact.
# See `RatingReplayMain` for the option list; the replay reads files only and never touches a database.
set -euo pipefail
if [ "$#" -eq 0 ]; then
  echo "usage: mise run rating:replay -- corpus=<games.jsonl[.gz]> out=<dir> [participants=<participants.jsonl>] [option=value ...]" >&2
  exit 2
fi
exec sbt "runMain dicechess.play.rating.RatingReplayMain $*"
