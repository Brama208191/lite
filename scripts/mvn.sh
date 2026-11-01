#!/usr/bin/env sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache/m2"
mkdir -p "$CACHE"
docker image inspect keel-mvn >/dev/null 2>&1 || docker build -q -t keel-mvn -f "$ROOT/scripts/mvn.Dockerfile" "$ROOT/scripts"
status=0
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$ROOT":/src -w /src \
  -v "$CACHE":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  keel-mvn mvn -B "$@" || status=$?
leftovers="$(docker ps -aq --filter label=org.testcontainers=true)"
[ -n "$leftovers" ] && docker rm -f $leftovers >/dev/null
exit $status
