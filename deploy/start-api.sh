#!/usr/bin/env bash
set -euo pipefail
root=/opt/ai-recruitment
image=${1:?Usage: start-api.sh IMAGE_TAG}
test -f "$root/shared/hr.env"
test -f "$root/shared/batches.json"
if ! grep -Eq '^HR_REVIEW_DATABASE_URL=.+$' "$root/shared/hr.env"; then
  echo 'Configure HR_REVIEW_DATABASE_URL in shared/hr.env before starting.' >&2
  exit 1
fi
if docker container inspect ai-recruitment-api >/dev/null 2>&1; then
  echo 'ai-recruitment-api already exists; preserve it and plan an explicit update.' >&2
  exit 1
fi
docker run -d --name ai-recruitment-api --restart unless-stopped \
  --env-file "$root/shared/hr.env" \
  -p 127.0.0.1:18080:8000 \
  --mount type=bind,source="$root/shared/batches.json",target=/app/runtime/batches.json,readonly \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=32m \
  --cap-drop ALL --security-opt no-new-privileges \
  --memory 384m --cpus 1 --pids-limit 128 \
  --log-opt max-size=10m --log-opt max-file=3 \
  "$image"
