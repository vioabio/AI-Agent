# Source before Compose starts: `up` may fail while waiting for a dependency.
# Never dump container configuration/environment, which can contain credentials.
compose_smoke_cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "Compose smoke failed (exit $status); collecting diagnostics before cleanup"
    docker compose ps -a || true
    docker compose logs --no-color --tail=200 vio-ai-agent postgres || true
    backend_id=$(docker compose ps -a -q vio-ai-agent) || backend_id=''
    if [ -n "$backend_id" ]; then
      docker inspect --format '{{json .State.Health}}' "$backend_id" || true
    fi
  fi
  docker compose down -v || {
    cleanup_status=$?
    if [ "$status" -eq 0 ]; then status=$cleanup_status; fi
  }
  exit "$status"
}
trap compose_smoke_cleanup EXIT
