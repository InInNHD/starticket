#!/usr/bin/env bash
set -Eeuo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state_dir=${STARTICKET_STATE_DIR:-"$project_dir/.deploy-state"}
current_file="$state_dir/current"
previous_file="$state_dir/previous"
requested=${1:-}

if [[ -z "$requested" ]]; then
  echo "用法：./deploy/deploy.sh <版本号|rollback>" >&2
  exit 2
fi

mkdir -p "$state_dir"
current=$(test -f "$current_file" && cat "$current_file" || true)
if [[ "$requested" == "rollback" ]]; then
  requested=$(test -f "$previous_file" && cat "$previous_file" || true)
  [[ -n "$requested" ]] || { echo "没有可回滚版本" >&2; exit 2; }
fi
[[ "$requested" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "版本号必须为 x.y.z" >&2; exit 2; }

cd "$project_dir"
export STARTICKET_VERSION="$requested"
compose=(docker compose -f docker-compose.yml -f deploy/compose.prod.yml)
"${compose[@]}" pull backend frontend
"${compose[@]}" up -d --no-build backend frontend

for attempt in {1..60}; do
  if curl --fail --silent http://127.0.0.1:${STARTICKET_FRONTEND_PORT:-8081}/healthz >/dev/null; then
    [[ -n "$current" && "$current" != "$requested" ]] && printf '%s\n' "$current" > "$previous_file"
    printf '%s\n' "$requested" > "$current_file"
    echo "StarTicket $requested 部署成功"
    exit 0
  fi
  sleep 2
done

echo "StarTicket $requested 健康检查失败" >&2
if [[ -n "$current" && "$current" != "$requested" ]]; then
  export STARTICKET_VERSION="$current"
  "${compose[@]}" up -d --no-build backend frontend
  echo "已回滚到 $current" >&2
fi
exit 1
