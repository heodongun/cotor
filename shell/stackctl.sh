#!/usr/bin/env bash
set -euo pipefail

# stackctl: quick control for OpenClaw gateway + Symphony docker containers
#
# Usage:
#   ./shell/stackctl.sh status
#   ./shell/stackctl.sh stop
#   ./shell/stackctl.sh start
#   ./shell/stackctl.sh restart
#   ./shell/stackctl.sh kill
#   ./shell/stackctl.sh metrics
#
# Optional env:
#   SYMPHONY_PATTERN   (default: symphony)
#   SYMPHONY_COMPOSE   (path to docker compose file for start/restart)

ACTION="${1:-status}"
SYMPHONY_PATTERN="${SYMPHONY_PATTERN:-symphony}"
SYMPHONY_COMPOSE="${SYMPHONY_COMPOSE:-}"

have_cmd() { command -v "$1" >/dev/null 2>&1; }

echo_header() {
  printf "\n== %s ==\n" "$1"
}

openclaw_status() {
  if have_cmd openclaw; then
    openclaw gateway status || true
  else
    echo "openclaw CLI not found"
  fi
}

openclaw_start() {
  have_cmd openclaw && openclaw gateway start || echo "openclaw CLI not found"
}

openclaw_stop() {
  have_cmd openclaw && openclaw gateway stop || echo "openclaw CLI not found"
}

openclaw_kill() {
  pkill -KILL -f 'openclaw-gateway' >/dev/null 2>&1 || true
  echo "sent KILL to openclaw-gateway"
}

openclaw_restart() {
  have_cmd openclaw && openclaw gateway restart || echo "openclaw CLI not found"
}

docker_ok() {
  have_cmd docker && docker info >/dev/null 2>&1
}

symphony_container_ids() {
  docker ps -aq --filter "name=${SYMPHONY_PATTERN}" 2>/dev/null || true
}

symphony_status() {
  if ! docker_ok; then
    echo "Docker daemon not available"
    return 0
  fi

  local ids
  ids="$(symphony_container_ids)"
  if [[ -z "$ids" ]]; then
    echo "No containers matching pattern: ${SYMPHONY_PATTERN}"
    return 0
  fi

  docker ps -a --filter "name=${SYMPHONY_PATTERN}" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
}

symphony_stop() {
  if ! docker_ok; then
    echo "Docker daemon not available"
    return 0
  fi

  local ids
  ids="$(symphony_container_ids)"
  if [[ -z "$ids" ]]; then
    echo "No Symphony containers to stop"
    return 0
  fi

  echo "$ids" | xargs -n 20 docker stop
}

symphony_kill() {
  if ! docker_ok; then
    echo "Docker daemon not available"
    return 0
  fi

  local ids
  ids="$(symphony_container_ids)"
  if [[ -z "$ids" ]]; then
    echo "No Symphony containers to kill"
    return 0
  fi

  echo "$ids" | xargs -n 20 docker kill
}

symphony_start() {
  if ! docker_ok; then
    echo "Docker daemon not available"
    return 0
  fi

  if [[ -n "$SYMPHONY_COMPOSE" ]]; then
    docker compose -f "$SYMPHONY_COMPOSE" up -d
    return 0
  fi

  local ids
  ids="$(docker ps -aq --filter "name=${SYMPHONY_PATTERN}")"
  if [[ -n "$ids" ]]; then
    echo "$ids" | xargs -n 20 docker start
    return 0
  fi

  echo "No Symphony containers found. Set SYMPHONY_COMPOSE=/path/to/docker-compose.yml to create new ones."
}

show_metrics() {
  echo_header "System"
  top -l 1 -n 0 | head -n 12 || true

  echo_header "Top CPU"
  ps -axo pid,ppid,%cpu,%mem,rss,etime,command | sort -k3 -nr | head -n 12 || true

  echo_header "Top Memory"
  ps -axo pid,ppid,%cpu,%mem,rss,etime,command | sort -k4 -nr | head -n 12 || true
}

case "$ACTION" in
  status)
    echo_header "OpenClaw"
    openclaw_status
    echo_header "Symphony (pattern: ${SYMPHONY_PATTERN})"
    symphony_status
    show_metrics
    ;;
  stop|off)
    echo_header "Stopping OpenClaw"
    openclaw_stop
    echo_header "Stopping Symphony"
    symphony_stop
    ;;
  start|on)
    echo_header "Starting OpenClaw"
    openclaw_start
    echo_header "Starting Symphony"
    symphony_start
    ;;
  restart)
    echo_header "Restarting OpenClaw"
    openclaw_restart
    echo_header "Restarting Symphony"
    symphony_stop
    symphony_start
    ;;
  kill)
    echo_header "Killing OpenClaw"
    openclaw_kill
    echo_header "Killing Symphony"
    symphony_kill
    ;;
  metrics)
    show_metrics
    ;;
  *)
    echo "Unknown action: ${ACTION}"
    echo "Usage: $0 {status|start|stop|restart|kill|metrics}"
    exit 1
    ;;
esac
