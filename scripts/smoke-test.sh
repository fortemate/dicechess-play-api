#!/usr/bin/env bash
# Boot a built play-api image and check it actually serves — no database needed
# (state is in-memory). Used by CI before publishing :latest, and runnable locally:
#   docker build -t dc-play:smoke . && IMAGE=dc-play:smoke scripts/smoke-test.sh
set -euo pipefail

IMAGE="${IMAGE:-dicechess-play-api:smoke}"
PORT="${PORT:-8080}"
API="smoke-play-$$"

cleanup() { docker rm -f "$API" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$API" -p "$PORT:8080" "$IMAGE" >/dev/null

# 1) Liveness.
ok=
for _ in $(seq 1 30); do
  if curl -fsS "http://localhost:$PORT/health" | grep -q '"status":"ok"'; then
    ok=1; echo "smoke: GET /health OK"; break
  fi
  sleep 1
done
[ -n "$ok" ] || { echo "smoke: /health never served"; docker logs "$API" | tail -60; exit 1; }

# white/black must be UUIDs (dicechess-play-api#141 — Principal.guest rejects anything else with 400).
# white/black must be UUIDs (dicechess-play-api#14 — Principal.guest rejects anything else with 400).
body=$(curl -fsS -X POST "http://localhost:$PORT/games" \
  -H 'content-type: application/json' \
  -d '{"white":"77777777-7777-7777-7777-777777777777","black":"88888888-8888-8888-8888-888888888888"}')
echo "smoke: POST /games -> $body"
echo "$body" | grep -q '"gameId"' || { echo "smoke: POST /games missing gameId"; exit 1; }
echo "$body" | grep -q '"commit"' || { echo "smoke: POST /games missing dice commit"; exit 1; }

# 3) Bot API is wired (401 without a token, not 404).
code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/bot/account")
[ "$code" = "401" ] || { echo "smoke: GET /bot/account expected 401, got $code"; exit 1; }
echo "smoke: GET /bot/account -> 401 (auth wired)"

echo "smoke: PASS"
