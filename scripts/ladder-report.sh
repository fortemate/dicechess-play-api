#!/usr/bin/env bash
# Runs the owner-facing strength report (#120, #208):
#   mise run ladder:report [elo0 elo1 alpha beta]
#   mise run ladder:report -- [out=path.md] [format=markdown|text] [category=blitz] [iterations=1000]
#
# A script rather than an inline mise template for the same reason as rating-replay.sh:
# options must reach runMain as unquoted words inside ONE sbt command string.
set -euo pipefail
exec sbt "runMain dicechess.play.rating.LadderReportMain $*"
