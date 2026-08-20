#!/usr/bin/env bash
# Regenerate the contributor-docs schema reference from the Flyway migrations.
#
# The point is that the reference cannot drift from the code: it is derived by applying the
# real migrations to a real Postgres and introspecting the result, never hand-maintained.
# CI runs this same script and fails if the committed page differs (see
# .github/workflows/deploy-contributor-docs.yaml), which is why it must behave identically
# on a laptop and on a runner — hence Docker for both Postgres and Flyway, rather than
# whatever happens to be installed.
#
# Requires: docker, tbls, node (all pinned in mise.toml).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT
readonly MIGRATIONS="${REPO_ROOT}/src/main/resources/db/migration"
readonly OUT="${REPO_ROOT}/contributor-docs/src/content/docs/reference/schema.md"

readonly PG_IMAGE="postgres:18-alpine" # matches the Testcontainers image the test suites use
readonly FLYWAY_IMAGE="flyway/flyway:11-alpine"
readonly CONTAINER="play-api-schema-docs-$$"
readonly NETWORK="play-api-schema-docs-net-$$"
readonly DB=playdocs
readonly USER=playdocs
readonly PASSWORD=playdocs # throwaway container on a private network; never a real credential

cleanup() {
	docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
	docker network rm "${NETWORK}" >/dev/null 2>&1 || true
	rm -f "${schema_json:-}"
}
trap cleanup EXIT

# A user-defined network rather than --network host for the Flyway container: host networking
# is off by default on Docker Desktop (opt-in since 4.34) and unsupported for Windows
# containers, so this is the only arrangement that behaves the same everywhere. Postgres also
# publishes a port, because tbls runs on the host and cannot reach the container network.
echo "==> creating network ${NETWORK}"
docker network create "${NETWORK}" >/dev/null

echo "==> starting throwaway ${PG_IMAGE}"
docker run -d --name "${CONTAINER}" \
	--network "${NETWORK}" \
	-e POSTGRES_DB="${DB}" \
	-e POSTGRES_USER="${USER}" \
	-e POSTGRES_PASSWORD="${PASSWORD}" \
	-P "${PG_IMAGE}" >/dev/null

port="$(docker port "${CONTAINER}" 5432/tcp | head -1 | sed 's/.*://')"
readonly port

echo "==> waiting for postgres on :${port}"
for _ in $(seq 1 60); do
	if docker exec "${CONTAINER}" pg_isready -U "${USER}" -d "${DB}" >/dev/null 2>&1; then
		break
	fi
	sleep 1
done
docker exec "${CONTAINER}" pg_isready -U "${USER}" -d "${DB}" >/dev/null

echo "==> applying Flyway migrations"
# On the shared network, so Postgres is reachable by container name — "localhost" inside the
# Flyway container is the Flyway container itself, not the database.
docker run --rm --network "${NETWORK}" \
	-v "${MIGRATIONS}:/flyway/sql:ro" \
	"${FLYWAY_IMAGE}" \
	-url="jdbc:postgresql://${CONTAINER}:5432/${DB}" \
	-user="${USER}" -password="${PASSWORD}" \
	-connectRetries=10 \
	migrate

echo "==> introspecting with tbls"
schema_json="$(mktemp -t play-api-schema.XXXXXX.json)"
tbls out -t json \
	-o "${schema_json}" \
	"postgres://${USER}:${PASSWORD}@localhost:${port}/${DB}?sslmode=disable"

echo "==> rendering ${OUT#"${REPO_ROOT}/"}"
node "${REPO_ROOT}/contributor-docs/scripts/render-schema-doc.mjs" "${schema_json}" "${OUT}"

echo "==> done"
