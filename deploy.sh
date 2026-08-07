#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENV_FILE="$ROOT_DIR/.env"
ENV_EXAMPLE="$ROOT_DIR/.env.example"
COMPOSE_FILE="$ROOT_DIR/compose.yaml"
OFFLINE_COMPOSE_FILE="$ROOT_DIR/compose.offline.yaml"
NGINX_FILE="$ROOT_DIR/deploy/korilin.com.nginx.conf"
PUBLIC_URL="https://korilin.com/kt15/"
DIST_DIR="$ROOT_DIR/dist"
BACKEND_OFFLINE_IMAGE="kodee-battle-royale-backend:linux-amd64"
FRONTEND_OFFLINE_IMAGE="kodee-battle-royale-frontend:linux-amd64"
POSTGRES_OFFLINE_IMAGE="kodee-battle-royale-postgres:17-alpine-linux-amd64"

info() {
    printf '[INFO] %s\n' "$*"
}

warn() {
    printf '[WARN] %s\n' "$*" >&2
}

fail() {
    printf '[ERROR] %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Missing command: $1"
}

compose() {
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

compose_offline() {
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$OFFLINE_COMPOSE_FILE" "$@"
}

env_value() {
    local key=$1
    sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

check_platform() {
    if [ "$(uname -s)" != "Linux" ]; then
        warn "This Compose setup targets a Linux server. Docker Desktop host networking may require extra configuration."
    fi
    if [ "$(uname -m)" != "x86_64" ]; then
        warn "webrtc-java is bundled for linux-x86_64; other architectures will fall back to WebSocket."
    fi
}

check_docker() {
    require_command docker
    docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required (docker compose)."
    docker info >/dev/null 2>&1 || fail "Docker daemon is not running or the current user cannot access it."
}

validate_env() {
    [ -f "$ENV_FILE" ] || fail "Missing $ENV_FILE. Run: ./deploy.sh setup"

    local postgres_password jwt_secret
    postgres_password=$(env_value POSTGRES_PASSWORD)
    jwt_secret=$(env_value JWT_SECRET)

    [ -n "$postgres_password" ] || fail "POSTGRES_PASSWORD is empty in $ENV_FILE"
    [ -n "$jwt_secret" ] || fail "JWT_SECRET is empty in $ENV_FILE"
    case "$postgres_password" in
        replace_*) fail "POSTGRES_PASSWORD still contains the example value" ;;
    esac
    case "$jwt_secret" in
        replace_*) fail "JWT_SECRET still contains the example value" ;;
    esac
}

setup_env() {
    require_command openssl
    [ -f "$ENV_EXAMPLE" ] || fail "Missing template: $ENV_EXAMPLE"

    if [ -f "$ENV_FILE" ]; then
        info "Keeping existing configuration: $ENV_FILE"
        validate_env
        return
    fi

    local postgres_password jwt_secret temp_file
    postgres_password=$(openssl rand -hex 32)
    jwt_secret=$(openssl rand -hex 32)
    temp_file=$(mktemp "$ROOT_DIR/.env.tmp.XXXXXX")
    trap 'rm -f "$temp_file"' RETURN

    awk \
        -v postgres_password="$postgres_password" \
        -v jwt_secret="$jwt_secret" \
        '/^POSTGRES_PASSWORD=/ { print "POSTGRES_PASSWORD=" postgres_password; next }
         /^JWT_SECRET=/ { print "JWT_SECRET=" jwt_secret; next }
         { print }' \
        "$ENV_EXAMPLE" > "$temp_file"

    chmod 600 "$temp_file"
    mv "$temp_file" "$ENV_FILE"
    trap - RETURN
    info "Created $ENV_FILE with random PostgreSQL and JWT secrets (mode 600)."
}

show_status() {
    validate_env
    check_docker
    compose ps
}

check_url() {
    local label=$1 url=$2 required=$3
    if curl --fail --silent --show-error --max-time 5 "$url" >/dev/null; then
        printf '[OK]   %-16s %s\n' "$label" "$url"
        return 0
    fi

    if [ "$required" = "true" ]; then
        printf '[FAIL] %-16s %s\n' "$label" "$url" >&2
        return 1
    fi
    printf '[WAIT] %-16s %s (configure/reload host Nginx if needed)\n' "$label" "$url"
}

run_checks() {
    require_command curl
    validate_env

    local frontend_port failures=0
    frontend_port=$(env_value FRONTEND_PORT)
    frontend_port=${frontend_port:-18080}

    check_url "Ktor health" "http://127.0.0.1:8011/api/health" true || failures=$((failures + 1))
    check_url "Frontend" "http://127.0.0.1:${frontend_port}/" true || failures=$((failures + 1))
    check_url "Public site" "$PUBLIC_URL" false || true

    [ "$failures" -eq 0 ] || fail "$failures required health check(s) failed. Run: ./deploy.sh logs"
}

wait_for_services() {
    require_command curl
    local attempt
    for attempt in $(seq 1 45); do
        if curl --fail --silent --max-time 2 http://127.0.0.1:8011/api/health >/dev/null 2>&1; then
            info "Backend is healthy."
            return
        fi
        sleep 2
    done
    compose ps
    fail "Backend did not become healthy within 90 seconds. Run: ./deploy.sh logs"
}

deploy() {
    check_platform
    check_docker
    setup_env
    validate_env
    info "Building images and starting services..."
    compose up -d --build
    wait_for_services
    show_status
    run_checks
    show_info
}

start_services() {
    check_platform
    check_docker
    setup_env
    validate_env
    compose up -d --build
    wait_for_services
    show_status
}

package_images() {
    check_docker
    require_command gzip
    docker buildx version >/dev/null 2>&1 || fail "Docker Buildx is required (docker buildx)."

    local docker_hub_prefix stun_url postgres_source bundle temp_file
    docker_hub_prefix=${DOCKER_HUB_PREFIX:-m.daocloud.io/docker.io/library/}
    stun_url=${WEBRTC_STUN_URL:-}
    if [ -f "$ENV_FILE" ] && grep -q '^DOCKER_HUB_PREFIX=' "$ENV_FILE"; then
        docker_hub_prefix=$(env_value DOCKER_HUB_PREFIX)
    fi
    if [ -f "$ENV_FILE" ] && grep -q '^WEBRTC_STUN_URL=' "$ENV_FILE"; then
        stun_url=$(env_value WEBRTC_STUN_URL)
    fi
    postgres_source="${docker_hub_prefix}postgres:17-alpine"
    bundle="$DIST_DIR/kodee-battle-royale-linux-amd64.tar.gz"

    mkdir -p "$DIST_DIR"
    temp_file=$(mktemp "$DIST_DIR/.images.XXXXXX.tar.gz")
    trap 'rm -f "$temp_file"' RETURN

    info "Building backend image for linux/amd64..."
    docker buildx build --platform linux/amd64 --load \
        --build-arg "DOCKER_HUB_PREFIX=$docker_hub_prefix" \
        --tag "$BACKEND_OFFLINE_IMAGE" "$ROOT_DIR/server"

    info "Building frontend image for linux/amd64..."
    docker buildx build --platform linux/amd64 --load \
        --build-arg "DOCKER_HUB_PREFIX=$docker_hub_prefix" \
        --build-arg "VITE_API_BASE_URL=/kt15/api" \
        --build-arg "VITE_STUN_URL=$stun_url" \
        --tag "$FRONTEND_OFFLINE_IMAGE" "$ROOT_DIR/web"

    info "Fetching PostgreSQL image for linux/amd64..."
    docker pull --platform linux/amd64 "$postgres_source"
    docker tag "$postgres_source" "$POSTGRES_OFFLINE_IMAGE"

    info "Writing offline image bundle..."
    docker save "$BACKEND_OFFLINE_IMAGE" "$FRONTEND_OFFLINE_IMAGE" "$POSTGRES_OFFLINE_IMAGE" \
        | gzip -1 > "$temp_file"
    mv "$temp_file" "$bundle"
    trap - RETURN

    info "Offline bundle created: $bundle"
    info "Upload it to the server, then run: ./deploy.sh offline /path/to/$(basename "$bundle")"
}

start_offline() {
    local bundle=${1:-}
    [ -n "$bundle" ] || fail "Usage: ./deploy.sh offline /path/to/kodee-battle-royale-linux-amd64.tar.gz"
    [ -f "$bundle" ] || fail "Offline image bundle not found: $bundle"
    [ -f "$OFFLINE_COMPOSE_FILE" ] || fail "Missing offline Compose file: $OFFLINE_COMPOSE_FILE"

    check_platform
    check_docker
    setup_env
    validate_env
    info "Loading offline images (this does not access an image registry)..."
    docker load --input "$bundle"
    info "Starting services without pulling or building images..."
    compose_offline up -d --no-build --pull never
    wait_for_services
    compose_offline ps
    run_checks
    show_info
}

show_info() {
    [ -f "$ENV_FILE" ] || warn "No .env file yet. Run: ./deploy.sh setup"

    local docker_hub_prefix frontend_port postgres_port rtc_min rtc_max stun_url
    docker_hub_prefix="m.daocloud.io/docker.io/library/"
    frontend_port=18080
    postgres_port=15432
    rtc_min=50000
    rtc_max=50100
    stun_url=""

    if [ -f "$ENV_FILE" ]; then
        if grep -q '^DOCKER_HUB_PREFIX=' "$ENV_FILE"; then
            docker_hub_prefix=$(env_value DOCKER_HUB_PREFIX)
        fi
        frontend_port=$(env_value FRONTEND_PORT)
        postgres_port=$(env_value POSTGRES_PORT)
        rtc_min=$(env_value WEBRTC_MIN_PORT)
        rtc_max=$(env_value WEBRTC_MAX_PORT)
        stun_url=$(env_value WEBRTC_STUN_URL)
        frontend_port=${frontend_port:-18080}
        postgres_port=${postgres_port:-15432}
        rtc_min=${rtc_min:-50000}
        rtc_max=${rtc_max:-50100}
    fi

    cat <<EOF

Deployment information
  Project directory : $ROOT_DIR
  Public URL        : $PUBLIC_URL
  Docker image source: ${docker_hub_prefix:-Docker Hub direct}
  Public health     : ${PUBLIC_URL}api/health
  Local frontend    : http://127.0.0.1:${frontend_port}/
  Local backend     : http://127.0.0.1:8011/api/health
  Local PostgreSQL  : 127.0.0.1:${postgres_port}
  WebRTC UDP ports  : ${rtc_min}-${rtc_max}
  WebRTC STUN       : ${stun_url:-not configured (WebSocket fallback remains available)}

Configuration files
  Secrets/runtime   : $ENV_FILE
  Compose           : $COMPOSE_FILE
  Host Nginx        : $NGINX_FILE
  Deployment guide  : $ROOT_DIR/DEPLOY.md

Secrets
  POSTGRES_PASSWORD : $([ -f "$ENV_FILE" ] && printf 'configured (hidden)' || printf 'not configured')
  JWT_SECRET        : $([ -f "$ENV_FILE" ] && printf 'configured (hidden)' || printf 'not configured')

Required firewall rule
  UDP ${rtc_min}:${rtc_max}

Next commands
  ./deploy.sh status
  ./deploy.sh logs
  ./deploy.sh check
  ./deploy.sh nginx
EOF
}

show_nginx() {
    [ -f "$NGINX_FILE" ] || fail "Missing Nginx config: $NGINX_FILE"
    cat "$NGINX_FILE"
}

show_logs() {
    local service=${1:-}
    validate_env
    check_docker
    if [ -n "$service" ]; then
        compose logs -f --tail=200 "$service"
    else
        compose logs -f --tail=200
    fi
}

stop_services() {
    validate_env
    check_docker
    compose down
    info "Services stopped. PostgreSQL data volume was preserved."
}

usage() {
    cat <<'EOF'
Usage: ./deploy.sh [command]

Commands:
  deploy          Configure, build, start, check, and show deployment info (default)
  package         Build a linux/amd64 offline image bundle on the local computer
  offline FILE    Load an offline image bundle and start without pulling or building
  setup           Create .env with random secrets; keep an existing .env unchanged
  up              Build and start all services
  restart         Restart all services without rebuilding images
  status          Show container status
  check           Check backend, frontend, and public endpoints
  info            Show deployment paths, ports, and redacted configuration
  nginx           Print the korilin.com Nginx location configuration
  logs [service]  Follow logs, optionally for backend/frontend/database
  down            Stop services without deleting PostgreSQL data
  help            Show this help
EOF
}

command=${1:-deploy}
case "$command" in
    deploy) deploy ;;
    package) package_images ;;
    offline) start_offline "${2:-}" ;;
    setup) setup_env; show_info ;;
    up) start_services ;;
    restart) validate_env; check_docker; compose restart; show_status ;;
    status) show_status ;;
    check) run_checks ;;
    info) show_info ;;
    nginx) show_nginx ;;
    logs) show_logs "${2:-}" ;;
    down) stop_services ;;
    help|-h|--help) usage ;;
    *) usage; fail "Unknown command: $command" ;;
esac
