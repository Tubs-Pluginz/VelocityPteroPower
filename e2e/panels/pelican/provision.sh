#!/usr/bin/env bash
# Zero-touch headless provisioner for live Pelican Panel + live Pelican Wings (:latest).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
SHARED_DIR="${E2E_STATE_DIR}/shared"
WINGS_ETC="${E2E_STATE_DIR}/wings-etc"

mkdir -p "${SHARED_DIR}" "${WINGS_ETC}" \
  "${E2E_STATE_DIR}/wings-data" "${E2E_STATE_DIR}/wings-logs" "${E2E_STATE_DIR}/wings-tmp"

dc() {
  ${COMPOSE_CMD} -f "${COMPOSE_FILE}" "$@"
}

echo "[pelican-provision] Waiting for Pelican panel HTTP to come online..."
for _ in $(seq 1 90); do
  if dc exec -T panel curl -fsS http://panel/ >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "[pelican-provision] Ensuring migrations are applied..."
dc exec -T panel php artisan migrate --force --no-interaction || true

echo "[pelican-provision] Bootstrapping admin user, API keys, Node, Allocations, and PicoLimbo Egg..."
BOOT_RAW="$(dc exec -T -e "WINGS_DATA_DIR=${E2E_STATE_DIR}/wings-data" panel php /provision/bootstrap.php)"
BOOT_JSON="$(printf '%s\n' "${BOOT_RAW}" | grep -E '^\{' | tail -n1)"
if [ -z "${BOOT_JSON}" ]; then
  echo "[pelican-provision] ERROR: bootstrap.php produced no JSON output:" >&2
  printf '%s\n' "${BOOT_RAW}" >&2
  exit 1
fi

PANEL_VERSION="$(printf '%s' "${BOOT_JSON}" | jq -r '.panelVersion')"
APP_KEY="$(printf '%s' "${BOOT_JSON}" | jq -r '.appKey')"
CLIENT_KEY="$(printf '%s' "${BOOT_JSON}" | jq -r '.clientKey')"
USER_ID="$(printf '%s' "${BOOT_JSON}" | jq -r '.userId')"
EGG_ID="$(printf '%s' "${BOOT_JSON}" | jq -r '.eggId')"
DOCKER_IMAGE="$(printf '%s' "${BOOT_JSON}" | jq -r '.dockerImage')"
STARTUP="$(printf '%s' "${BOOT_JSON}" | jq -r '.startup')"

jq -n --arg pn "pelican" --arg pv "${PANEL_VERSION}" \
  '{panelName: $pn, panelVersion: $pv, wingsVersion: "starting...", picolimboVersion: "pending"}' \
  > "${SHARED_DIR}/panel-meta.json"

printf '%s\n' "${BOOT_JSON}" | jq -r '.wingsConfigYaml' > "${WINGS_ETC}/config.yml"
if ! grep -q '^token:' "${WINGS_ETC}/config.yml"; then
  echo "[pelican-provision] ERROR: Generated Wings config.yml is missing token:" >&2
  cat "${WINGS_ETC}/config.yml" >&2
  exit 1
fi
dc --profile wings up -d wings

echo "[pelican-provision] Waiting for live Pelican Wings daemon (http://wings:8080)..."
WINGS_UP=false
for _ in $(seq 1 45); do
  CODE="$(dc exec -T panel curl -s -o /dev/null -w '%{http_code}' http://wings:8080/api/system || echo "000")"
  if [ "${CODE}" = "200" ] || [ "${CODE}" = "401" ] || [ "${CODE}" = "403" ]; then
    WINGS_UP=true
    break
  fi
  if [ -z "$(dc --profile wings ps -q --status running wings 2>/dev/null)" ]; then
    echo "[pelican-provision] ERROR: Wings container exited prematurely:" >&2
    dc --profile wings logs wings >&2 || true
    exit 1
  fi
  sleep 2
done
if [ "${WINGS_UP}" != "true" ]; then
  echo "[pelican-provision] ERROR: Wings did not start listening on :8080" >&2
  dc --profile wings logs wings >&2 || true
  exit 1
fi

WINGS_VERSION="$(dc --profile wings exec -T wings /usr/bin/wings version 2>&1 | head -n1 | tr -d '\r\n' || echo "unknown")"
jq -n --arg pn "pelican" --arg pv "${PANEL_VERSION}" --arg wv "${WINGS_VERSION}" \
  '{panelName: $pn, panelVersion: $pv, wingsVersion: $wv, picolimboVersion: "installing..."}' \
  > "${SHARED_DIR}/panel-meta.json"

create_server() {
  local name="$1"
  local port="$2"
  local alloc_id
  alloc_id="$(printf '%s' "${BOOT_JSON}" | jq -r --arg p "${port}" '.allocations[$p]')"

  local payload
  payload="$(jq -n \
    --arg name "${name}" \
    --argjson user "${USER_ID}" \
    --argjson egg "${EGG_ID}" \
    --arg img "${DOCKER_IMAGE}" \
    --arg startup "${STARTUP}" \
    --argjson alloc "${alloc_id}" \
    '{
      name: $name,
      user: $user,
      egg: $egg,
      docker_image: $img,
      startup: $startup,
      environment: { VELOCITY_SECRET: "vpp-e2e-forwarding-secret" },
      limits: { memory: 256, swap: 0, disk: 512, io: 500, cpu: 100 },
      feature_limits: { databases: 0, backups: 0, allocations: 1 },
      allocation: { default: $alloc },
      start_on_completion: false
    }')"

  local raw status body
  raw="$(dc exec -T panel curl -sSL -w '\n%{http_code}' -X POST "http://panel/api/application/servers" \
    -H "Authorization: Bearer ${APP_KEY}" \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    -d "${payload}")"
  status="$(printf '%s\n' "${raw}" | tail -n1)"
  body="$(printf '%s\n' "${raw}" | sed '$d')"

  if [ "${status}" -lt 200 ] || [ "${status}" -ge 300 ]; then
    echo "[pelican-provision] ERROR: Failed to create server '${name}' (HTTP ${status}): ${body}" >&2
    return 1
  fi

  local ident num
  ident="$(printf '%s' "${body}" | jq -r '.attributes.identifier // empty')"
  num="$(printf '%s' "${body}" | jq -r '.attributes.id // empty')"
  if [ -z "${ident}" ] || [ -z "${num}" ]; then
    echo "[pelican-provision] ERROR: Unexpected create server response for '${name}': ${body}" >&2
    return 1
  fi
  printf '%s:%s\n' "${ident}" "${num}"
}

echo "[pelican-provision] Creating 6 real Minecraft servers on live Pelican + Wings..."
RES_LIMBO="$(create_server limbo 25601)"       || exit 1
RES_LOBBY1="$(create_server lobby-1 25602)"    || exit 1
RES_LOBBY2="$(create_server lobby-2 25603)"    || exit 1
RES_SURVIVAL="$(create_server survival 25604)" || exit 1
RES_SKYWARS="$(create_server skywars 25605)"   || exit 1
RES_BEDWARS="$(create_server bedwars 25606)"   || exit 1
IFS=: read -r ID_LIMBO    NUM_LIMBO    <<< "${RES_LIMBO}"
IFS=: read -r ID_LOBBY1   NUM_LOBBY1   <<< "${RES_LOBBY1}"
IFS=: read -r ID_LOBBY2   NUM_LOBBY2   <<< "${RES_LOBBY2}"
IFS=: read -r ID_SURVIVAL NUM_SURVIVAL <<< "${RES_SURVIVAL}"
IFS=: read -r ID_SKYWARS  NUM_SKYWARS  <<< "${RES_SKYWARS}"
IFS=: read -r ID_BEDWARS  NUM_BEDWARS  <<< "${RES_BEDWARS}"

echo "[pelican-provision] Waiting for live Pelican Wings to install PicoLimbo on all 6 servers..."
INSTALL_DEADLINE=$((SECONDS + 180))
SERVER_NUMS=("${NUM_LIMBO}" "${NUM_LOBBY1}" "${NUM_LOBBY2}" "${NUM_SURVIVAL}" "${NUM_SKYWARS}" "${NUM_BEDWARS}")
while true; do
  ALL_DONE=true
  for num in "${SERVER_NUMS[@]}"; do
    INFO="$(dc exec -T panel curl -fsSL "http://panel/api/application/servers/${num}" \
      -H "Authorization: Bearer ${APP_KEY}" \
      -H "Accept: application/json")"
    INSTALLED="$(printf '%s' "${INFO}" | jq -r '.attributes.container.installed')"
    STATUS="$(printf '%s' "${INFO}" | jq -r '.attributes.status // ""')"
    if [ "${STATUS}" = "install_failed" ]; then
      echo "[pelican-provision] ERROR: Server #${num} installation failed on Pelican Wings!" >&2
      dc --profile wings logs --tail 60 wings >&2 || true
      exit 1
    fi
    if [ "${INSTALLED}" != "1" ] && [ "${INSTALLED}" != "true" ]; then
      ALL_DONE=false
    fi
  done
  if [ "${ALL_DONE}" = "true" ]; then
    break
  fi
  if [ "${SECONDS}" -ge "${INSTALL_DEADLINE}" ]; then
    echo "[pelican-provision] ERROR: Timed out waiting for Pelican Wings server installations!" >&2
    dc --profile wings logs --tail 60 wings >&2 || true
    exit 1
  fi
  sleep 2
done

PICOLIMBO_VERSION="$(dc exec -T panel curl -fsSL \
  "http://panel/api/client/servers/${ID_LIMBO}/files/contents?file=picolimbo-version.txt" \
  -H "Authorization: Bearer ${CLIENT_KEY}" \
  -H "Accept: application/json" 2>/dev/null | tr -d '\r\n' || echo "unknown")"

WINGS_VERSION="$(dc --profile wings exec -T wings /usr/bin/wings version 2>&1 | head -n1 | tr -d '\r\n' || echo "unknown")"

SHARED_DIR="${SHARED_DIR}" \
TEMPLATE_DIR="${REPO_ROOT}/e2e/velocity/config" \
PANEL_URL="http://panel/" \
API_KEY="${CLIENT_KEY}" \
BACKEND_HOST="host.docker.internal" \
ID_LIMBO="${ID_LIMBO}" ID_LOBBY1="${ID_LOBBY1}" ID_LOBBY2="${ID_LOBBY2}" \
ID_SURVIVAL="${ID_SURVIVAL}" ID_SKYWARS="${ID_SKYWARS}" ID_BEDWARS="${ID_BEDWARS}" \
PORT_LIMBO=25601 PORT_LOBBY1=25602 PORT_LOBBY2=25603 \
PORT_SURVIVAL=25604 PORT_SKYWARS=25605 PORT_BEDWARS=25606 \
bash "${REPO_ROOT}/e2e/scripts/render-configs.sh"

jq -n \
  --arg panelName "pelican" \
  --arg panelVersion "${PANEL_VERSION}" \
  --arg wingsVersion "${WINGS_VERSION}" \
  --arg picoVersion "${PICOLIMBO_VERSION}" \
  --arg apiKey "${CLIENT_KEY}" \
  --arg limbo "${ID_LIMBO}" \
  --arg lobby1 "${ID_LOBBY1}" \
  --arg lobby2 "${ID_LOBBY2}" \
  --arg survival "${ID_SURVIVAL}" \
  --arg skywars "${ID_SKYWARS}" \
  --arg bedwars "${ID_BEDWARS}" \
  '{
    panelName: $panelName,
    panelVersion: $panelVersion,
    wingsVersion: $wingsVersion,
    picolimboVersion: $picoVersion,
    apiKey: $apiKey,
    servers: {
      limbo: $limbo,
      "lobby-1": $lobby1,
      "lobby-2": $lobby2,
      survival: $survival,
      skywars: $skywars,
      bedwars: $bedwars
    }
  }' > "${SHARED_DIR}/panel-meta.json"

touch "${SHARED_DIR}/ready"
echo "[pelican-provision] Ready! Panel=${PANEL_VERSION} Wings=${WINGS_VERSION} PicoLimbo=${PICOLIMBO_VERSION}"
