#!/usr/bin/env bash
# Post-promotion check against a LIVE deployment — the half `scripts/smoke-test.sh` cannot cover.
#
# That one boots the image with no database, so every persistence-backed path answers from the
# in-memory fallbacks and a broken query, a missing PLAY_DB_URL or an un-restarted container all
# still read as PASS. This one talks to a running deployment and exercises the path a visitor
# actually takes:
#
#   GET  /health            liveness
#   GET  /version           what is REALLY running (the promotion may not have restarted anything)
#   GET  /lobby/bots        persistence is configured at all — this route 404s without a database
#   POST /lobby/play-bot    start a real game against a catalog bot
#   GET  /games/{id}        the seat came back carrying its SETTLED RATING — the database read itself
#
# The last two steps are the point. `GameRegistry.createRoom` resolves both seats through
# `nicknamesByExternalId` and `settledRatingsByExternalId` on every game creation, and those two
# queries run nowhere else on a read path — no amount of GETting `/leaderboard` or `/lobby/bots`
# touches them. Starting a game is the only way to find out whether they work in production, which
# is why this script creates one rather than staying read-only.
#
# Read the final assertion precisely: a BOT seat's *name* is derived from its external id in pure
# code (`PublicPlayer.of`), so a resolved name proves the room was built and serves — not that any
# query ran. The seat's *rating* is the part that can only come from `settledRatingsByExternalId`,
# and it is attached only when the bot's rating has converged. A catalog of purely provisional bots
# therefore leaves that query uncovered, and this script says so out loud rather than passing
# quietly.
#
# It therefore has a real side effect: ONE casual game against a catalog bot, played by a synthetic
# guest id. Nobody moves, so it ends on its own clock (default 60s) and lands in `game_results` as
# a timeout loss for that guest. Rated is never requested and could not apply anyway — a guest seat
# is always casual (#279) — so nothing reaches a rating. Set PLAY_GAME=0 to stop after the
# read-only steps when even that row is unwanted; the check then no longer covers the resolvers,
# which is the whole reason it exists.
#
# Usage:
#   BASE_URL=https://play-api.jc.id.lv scripts/post-deploy-check.sh
#   BASE_URL=... EXPECT_VERSION=v0.16.9 scripts/post-deploy-check.sh   # also pin what must be live
#   BASE_URL=... PLAY_GAME=0 scripts/post-deploy-check.sh              # read-only subset
set -euo pipefail

BASE_URL="${BASE_URL:?set BASE_URL, e.g. BASE_URL=https://play-api.jc.id.lv scripts/post-deploy-check.sh}"
BASE_URL="${BASE_URL%/}"
EXPECT_VERSION="${EXPECT_VERSION:-}"
PLAY_GAME="${PLAY_GAME:-1}"
CLOCK_SECONDS="${CLOCK_SECONDS:-60}"

command -v jq >/dev/null || { echo "check: FAIL — jq is required (mise run setup installs it)" >&2; exit 1; }

say() { printf 'check: %s\n' "$*"; }
die() { printf 'check: FAIL — %s\n' "$*" >&2; exit 1; }

# `curl -f` would swallow the body on a 4xx, and the body is where this API puts its reason.
# Status and body are captured together so a failure can report both.
request() { # method path [json-body] -> sets HTTP_CODE and HTTP_BODY
  local method="$1" path="$2" body="${3:-}" raw
  if [ -n "$body" ]; then
    raw=$(curl -sS -X "$method" "$BASE_URL$path" -H 'content-type: application/json' \
      -d "$body" -w $'\n%{http_code}')
  else
    raw=$(curl -sS -X "$method" "$BASE_URL$path" -w $'\n%{http_code}')
  fi
  HTTP_CODE="${raw##*$'\n'}"
  HTTP_BODY="${raw%$'\n'*}"
}

uuid() {
  if command -v uuidgen >/dev/null; then uuidgen | tr '[:upper:]' '[:lower:]'
  else cat /proc/sys/kernel/random/uuid
  fi
}

say "target $BASE_URL"

# 1) Liveness.
request GET /health
[ "$HTTP_CODE" = "200" ] || die "GET /health -> $HTTP_CODE"
[ "$(jq -r '.status' <<<"$HTTP_BODY")" = "ok" ] || die "GET /health did not report ok: $HTTP_BODY"
say "GET /health ok"

# 2) What is actually serving. A promotion that pulled an image without recreating the container
# leaves the OLD build running and every other check here still passes — so this is worth pinning.
request GET /version
[ "$HTTP_CODE" = "200" ] || die "GET /version -> $HTTP_CODE"
# The endpoint answers a bare JSON string, not an object — `select(type == "string")` makes a shape
# change fail loudly here instead of silently comparing against an empty version below.
VERSION=$(jq -r 'select(type == "string")' <<<"$HTTP_BODY")
[ -n "$VERSION" ] || die "GET /version did not answer a version string: $HTTP_BODY"
if [ -n "$EXPECT_VERSION" ] && [ "$VERSION" != "$EXPECT_VERSION" ]; then
  die "live version is $VERSION, expected $EXPECT_VERSION — the promotion did not take"
fi
say "GET /version -> $VERSION"

# 3) Persistence. Mounted only with a database (same idiom as /leaderboard), so a 404 here says
# PLAY_DB_URL is missing — a deployment that is otherwise perfectly healthy and quietly useless.
request GET /lobby/bots
case "$HTTP_CODE" in
  200) ;;
  404) die "GET /lobby/bots -> 404: this build serves no catalog, i.e. PLAY_DB_URL is not configured" ;;
  *)   die "GET /lobby/bots -> $HTTP_CODE: $HTTP_BODY" ;;
esac
BOT_COUNT=$(jq '.bots | length' <<<"$HTTP_BODY")
[ "$BOT_COUNT" -gt 0 ] || die "the bot catalog is empty — nothing to play against"
say "GET /lobby/bots -> $BOT_COUNT bot(s)"

if [ "$PLAY_GAME" = "0" ]; then
  say "PLAY_GAME=0 — stopping before the game; the seat-face resolvers are NOT covered by this run"
  say "PASS (read-only subset)"
  exit 0
fi

# A settled bot is preferred: its rating must then come back ON the seat, which proves the ratings
# query returned a row rather than merely not throwing. `available` avoids a 409 from a bot already
# at its concurrent-game limit.
BOT=$(jq -c 'first(.bots[] | select(.available and (.provisional | not))) // first(.bots[] | select(.available)) // empty' <<<"$HTTP_BODY")
[ -n "$BOT" ] || die "every catalog bot is at its concurrent-game limit — retry shortly"
TEAM=$(jq -r '.team' <<<"$BOT")
NAME=$(jq -r '.name' <<<"$BOT")
PROVISIONAL=$(jq -r '.provisional' <<<"$BOT")
say "opponent $TEAM/$NAME (provisional=$PROVISIONAL)"

# 4) Start the game. `preferredColor` is pinned so the bot's seat is known without parsing for it;
# a fresh guest id every run keeps the one-active-game gate (409) out of the way.
GUEST=$(uuid)
request POST /lobby/play-bot "$(jq -nc \
  --arg guest "$GUEST" --arg team "$TEAM" --arg name "$NAME" --argjson secs "$CLOCK_SECONDS" \
  '{guestId: $guest, team: $team, name: $name, preferredColor: "White",
    timeControl: {Fischer: {initialSeconds: $secs, incrementSeconds: 0}}}')"
[ "$HTTP_CODE" = "201" ] || die "POST /lobby/play-bot -> $HTTP_CODE: $HTTP_BODY"
GAME_ID=$(jq -r '.gameId' <<<"$HTTP_BODY")
[ -n "$GAME_ID" ] && [ "$GAME_ID" != "null" ] || die "POST /lobby/play-bot returned no gameId: $HTTP_BODY"
say "POST /lobby/play-bot -> game $GAME_ID"

# 5) What the room actually serves. The name is cheap corroboration (pure code, no query); the
# rating below is the assertion this whole script exists for.
request GET "/games/$GAME_ID"
[ "$HTTP_CODE" = "200" ] || die "GET /games/$GAME_ID -> $HTTP_CODE: $HTTP_BODY"
BLACK_NAME=$(jq -r '.players.black.name // empty' <<<"$HTTP_BODY")
[ "$BLACK_NAME" = "$TEAM $NAME" ] || die "bot seat came back as '${BLACK_NAME:-<anonymous>}', expected '$TEAM $NAME'"
say "the room serves — black is '$BLACK_NAME'"

jq -e 'has("rated")' <<<"$HTTP_BODY" >/dev/null || die "the live state does not say whether the game is rated"

if [ "$PROVISIONAL" = "false" ]; then
  RATING=$(jq -r '.players.black.rating // empty' <<<"$HTTP_BODY")
  [ -n "$RATING" ] || die "a settled bot carried no rating — settledRatingsByExternalId returned nothing"
  say "settled seat rating resolved from the database — $RATING"
  say "the game ends on its own clock in ~${CLOCK_SECONDS}s; it is casual and reaches no rating"
  say "PASS"
else
  # Not a failure — a catalog can legitimately hold only freshly opened bots — but it must not read
  # as a full pass either: the query this check exists for did not run.
  say "the game ends on its own clock in ~${CLOCK_SECONDS}s; it is casual and reaches no rating"
  say "PASS (PARTIAL) — every available bot is provisional, so no seat rating was expected and"
  say "                settledRatingsByExternalId was NOT exercised by this run"
fi
