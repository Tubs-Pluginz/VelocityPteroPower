#!/usr/bin/env bash
# Zero-touch headless provisioner for live Calagopus AIO (Panel + integrated Rust wings-rs, :aio latest).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
SHARED_DIR="${E2E_STATE_DIR}/shared"
COOKIE_JAR="${E2E_STATE_DIR}/calagopus.cookies"
BASE_URL="http://127.0.0.1:18084"

mkdir -p "${SHARED_DIR}" \
  "${E2E_STATE_DIR}/calagopus-data" \
  "${E2E_STATE_DIR}/calagopus-logs" \
  "${E2E_STATE_DIR}/calagopus-wings-etc" \
  "${E2E_STATE_DIR}/calagopus-wings-data" \
  "${E2E_STATE_DIR}/calagopus-wings-logs"

dc() {
  ${COMPOSE_CMD} -f "${COMPOSE_FILE}" "$@"
}

echo "[calagopus-provision] Waiting for Calagopus AIO HTTP API (${BASE_URL})..."
PANEL_UP=false
for _ in $(seq 1 90); do
  if curl -fsS "${BASE_URL}/api/settings" >/dev/null 2>&1; then
    PANEL_UP=true
    break
  fi
  if [ -z "$(dc ps -q --status running panel 2>/dev/null)" ]; then
    echo "[calagopus-provision] ERROR: Calagopus panel container exited prematurely:" >&2
    dc logs --tail 60 panel >&2 || true
    exit 1
  fi
  sleep 2
done
if [ "${PANEL_UP}" != "true" ]; then
  echo "[calagopus-provision] ERROR: Timed out waiting for Calagopus AIO HTTP API" >&2
  dc logs --tail 60 panel >&2 || true
  exit 1
fi

PANEL_VERSION="$(dc exec -T panel calagopus-panel version 2>&1 | head -n1 | tr -d '\r\n' || echo "unknown")"
echo "[calagopus-provision] Calagopus version: ${PANEL_VERSION}"

jq -n --arg pn "calagopus" --arg pv "${PANEL_VERSION}" --arg wv "integrated wings-rs (${PANEL_VERSION})" \
  '{panelName: $pn, panelVersion: $pv, wingsVersion: $wv, picolimboVersion: "installing..."}' \
  > "${SHARED_DIR}/panel-meta.json"

echo "[calagopus-provision] Finishing OOBE & creating headless admin user..."
dc exec -T panel calagopus-panel oobe finish || true
USER_JSON="$(dc exec -T panel calagopus-panel users create \
  --username admin \
  --email admin@vpp.test \
  --name-first VPP \
  --name-last Admin \
  --password 'VppE2eAdminPassword123!' \
  --admin true \
  --json 2>&1 | sed -n '/^{/,$p')"
OWNER_UUID="$(printf '%s' "${USER_JSON}" | jq -r '.uuid')"

echo "[calagopus-provision] Logging in to mint full c7sp_ API key..."
rm -f "${COOKIE_JAR}"
curl -fsSL -c "${COOKIE_JAR}" -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"user":"admin","password":"VppE2eAdminPassword123!"}' >/dev/null

# Dynamically discover all valid user/admin/server permissions from the live panel
PERMS_JSON="$(curl -fsSL -b "${COOKIE_JAR}" "${BASE_URL}/api/client/permissions")"
USER_PERMS="$(printf '%s' "${PERMS_JSON}" | jq '[.user_permissions // {} | to_entries[] | .key as $g | (.value.permissions // {} | keys[]) | "\($g).\(.)"]')"
ADMIN_PERMS="$(printf '%s' "${PERMS_JSON}" | jq '[.admin_permissions // {} | to_entries[] | .key as $g | (.value.permissions // {} | keys[]) | "\($g).\(.)"]')"
SERVER_PERMS="$(printf '%s' "${PERMS_JSON}" | jq '[.server_permissions // {} | to_entries[] | .key as $g | (.value.permissions // {} | keys[]) | "\($g).\(.)"]')"

KEY_PAYLOAD="$(jq -n \
  --argjson up "${USER_PERMS}" \
  --argjson ap "${ADMIN_PERMS}" \
  --argjson sp "${SERVER_PERMS}" \
  '{
    name: "vpp-e2e-key",
    allowed_ips: [],
    enabled: true,
    user_permissions: $up,
    admin_permissions: $ap,
    server_permissions: $sp
  }')"

KEY_RESP="$(curl -fsSL -b "${COOKIE_JAR}" -X POST "${BASE_URL}/api/client/account/api-keys" \
  -H "Content-Type: application/json" \
  -d "${KEY_PAYLOAD}")"
CLIENT_KEY="$(printf '%s' "${KEY_RESP}" | jq -r '.key')"

if [[ ! "${CLIENT_KEY}" == c7sp_* ]]; then
  echo "[calagopus-provision] ERROR: Expected c7sp_ API key, got: ${KEY_RESP}" >&2
  exit 1
fi

api_post() {
  local path="$1"
  local body="$2"
  local raw status resp
  raw="$(curl -sSL -w '\n%{http_code}' -X POST "${BASE_URL}${path}" \
    -H "Authorization: Bearer ${CLIENT_KEY}" \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    -d "${body}")"
  status="$(printf '%s\n' "${raw}" | tail -n1)"
  resp="$(printf '%s\n' "${raw}" | sed '$d')"
  if [ "${status}" -lt 200 ] || [ "${status}" -ge 300 ]; then
    echo "[calagopus-provision] ERROR: POST ${path} failed (HTTP ${status}): ${resp}" >&2
    return 1
  fi
  printf '%s' "${resp}"
}

api_get() {
  local path="$1"
  curl -fsSL "${BASE_URL}${path}" \
    -H "Authorization: Bearer ${CLIENT_KEY}" \
    -H "Accept: application/json"
}

echo "[calagopus-provision] Resolving or creating AIO Node & Allocations..."
NODES_JSON="$(api_get "/api/admin/nodes?page=1&per_page=10")"
NODE_UUID="$(printf '%s' "${NODES_JSON}" | jq -r '.nodes.data[0].uuid // empty')"

if [ -z "${NODE_UUID}" ]; then
  LOC_JSON="$(api_post "/api/admin/locations" '{"name":"e2e-local","description":"VPP Automated E2E Location"}')"
  LOC_UUID="$(printf '%s' "${LOC_JSON}" | jq -r '.location.uuid')"
  NODE_JSON="$(api_post "/api/admin/nodes" "$(jq -n --arg loc "${LOC_UUID}" '{
    location_uuid: $loc,
    name: "vpp-e2e-aio-node",
    description: "Calagopus AIO Wings Node",
    deployment_enabled: true,
    maintenance_enabled: false,
    url: "http://127.0.0.1:8080",
    public_url: "http://panel:8000",
    sftp_port: 2022,
    memory: 8192,
    disk: 32768
  }')")"
  NODE_UUID="$(printf '%s' "${NODE_JSON}" | jq -r '.node.uuid')"
fi

# Set Calagopus app.url = http://panel:8000 so /wings-proxy/{node_uuid} WebSocket URLs
# resolve to ws://panel:8000/... instead of ws://localhost:8000/... inside Docker.
SETTINGS_JSON="$(api_get "/api/admin/settings" || echo '{}')"
APP_PATCH="$(printf '%s' "${SETTINGS_JSON}" | jq '{app: ((.app // {}) + {url: "http://panel:8000"})}' 2>/dev/null || echo '{"app":{"url":"http://panel:8000"}}')"
SET_RES="$(curl -sS -w '\n%{http_code}' -X PUT "${BASE_URL}/api/admin/settings" \
  -H "Authorization: Bearer ${CLIENT_KEY}" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -d "${APP_PATCH}")"
SET_CODE="$(printf '%s\n' "${SET_RES}" | tail -n1)"
echo "[calagopus-provision] Updated Calagopus app.url -> http://panel:8000 (HTTP ${SET_CODE})"

# Point the AIO node's public_url at the panel's built-in /wings-proxy/{node_uuid} endpoint
# reachable from Velocity inside Docker (http://panel:8000) instead of localhost.
PATCH_RES="$(curl -sS -w '\n%{http_code}' -X PATCH "${BASE_URL}/api/admin/nodes/${NODE_UUID}" \
  -H "Authorization: Bearer ${CLIENT_KEY}" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg pub "http://panel:8000/wings-proxy/${NODE_UUID}" '{public_url: $pub}')")"
PATCH_CODE="$(printf '%s\n' "${PATCH_RES}" | tail -n1)"
echo "[calagopus-provision] Patched AIO node ${NODE_UUID} public_url -> http://panel:8000/wings-proxy/${NODE_UUID} (HTTP ${PATCH_CODE})"

# Create allocations 25601..25610 on 0.0.0.0
api_post "/api/admin/nodes/${NODE_UUID}/allocations" \
  '{"ip":"0.0.0.0","ip_alias":"host.docker.internal","ports":[25601,25602,25603,25604,25605,25606,25607,25608,25609,25610]}' >/dev/null || true

ALLOCS_JSON="$(api_get "/api/admin/nodes/${NODE_UUID}/allocations?page=1&per_page=50")"

echo "[calagopus-provision] Creating Nest & importing PicoLimbo VPP E2E Egg..."
NEST_JSON="$(api_post "/api/admin/nests" '{"author":"vpp-e2e@pluginz.dev","name":"VPP E2E","description":"Automated E2E test nest"}')"
NEST_UUID="$(printf '%s' "${NEST_JSON}" | jq -r '.nest.uuid')"

EGG_RESP="$(curl -fsSL -X POST "${BASE_URL}/api/admin/nests/${NEST_UUID}/eggs/import" \
  -H "Authorization: Bearer ${CLIENT_KEY}" \
  -H "Accept: application/json" \
  -H "Content-Type: application/json" \
  --data-binary "@${REPO_ROOT}/e2e/eggs/egg-picolimbo-vpp-e2e.json")"
EGG_UUID="$(printf '%s' "${EGG_RESP}" | jq -r '.egg.uuid')"

create_server() {
  local name="$1"
  local port="$2"
  local alloc_uuid
  alloc_uuid="$(printf '%s' "${ALLOCS_JSON}" | jq -r --argjson p "${port}" '.allocations.data[] | select(.port == $p) | .uuid' | head -n1)"

  local payload
  payload="$(jq -n \
    --arg node "${NODE_UUID}" \
    --arg owner "${OWNER_UUID}" \
    --arg egg "${EGG_UUID}" \
    --arg alloc "${alloc_uuid}" \
    --arg name "${name}" \
    '{
      node_uuid: $node,
      owner_uuid: $owner,
      egg_uuid: $egg,
      allocation_uuid: $alloc,
      allocation_uuids: [],
      start_on_completion: false,
      skip_installer: false,
      name: $name,
      limits: { cpu: 100, memory: 256, memory_overhead: 0, swap: 0, disk: 512, io_weight: 500 },
      pinned_cpus: [],
      startup: "./start.sh",
      image: "ghcr.io/parkervcp/yolks:alpine",
      hugepages_passthrough_enabled: false,
      kvm_passthrough_enabled: false,
      feature_limits: { allocations: 1, databases: 0, backups: 0, schedules: 0 },
      variables: [{ env_variable: "VELOCITY_SECRET", value: "vpp-e2e-forwarding-secret" }]
    }')"

  api_post "/api/admin/servers" "${payload}" | jq -r '.server.uuid'
}

echo "[calagopus-provision] Creating 6 real Minecraft servers on live Calagopus AIO..."
ID_LIMBO="$(create_server limbo 25601)"       || exit 1
ID_LOBBY1="$(create_server lobby-1 25602)"    || exit 1
ID_LOBBY2="$(create_server lobby-2 25603)"    || exit 1
ID_SURVIVAL="$(create_server survival 25604)" || exit 1
ID_SKYWARS="$(create_server skywars 25605)"   || exit 1
ID_BEDWARS="$(create_server bedwars 25606)"   || exit 1

echo "[calagopus-provision] Waiting for Calagopus wings-rs to finish installing PicoLimbo on all 6 servers..."
INSTALL_DEADLINE=$((SECONDS + 180))
while true; do
  ALL_DONE=true
  for srv_uuid in "${ID_LIMBO}" "${ID_LOBBY1}" "${ID_LOBBY2}" "${ID_SURVIVAL}" "${ID_SKYWARS}" "${ID_BEDWARS}"; do
    ST="$(api_get "/api/admin/servers/${srv_uuid}" | jq -r '.server.status // "ready"')"
    if [ "${ST}" = "install_failed" ]; then
      echo "[calagopus-provision] ERROR: Server ${srv_uuid} installation failed on Calagopus wings-rs!" >&2
      exit 1
    fi
    if [ "${ST}" = "installing" ]; then
      ALL_DONE=false
    fi
  done
  if [ "${ALL_DONE}" = "true" ]; then
    break
  fi
  if [ "${SECONDS}" -ge "${INSTALL_DEADLINE}" ]; then
    echo "[calagopus-provision] ERROR: Timed out waiting for Calagopus server installations!" >&2
    exit 1
  fi
  sleep 2
done

WS_CHECK="$(api_get "/api/client/servers/${ID_LIMBO}/websocket" | jq -c 'if .token then .token="<redacted>" else . end' 2>/dev/null || echo "failed")"
echo "[calagopus-provision] Limbo websocket endpoint: ${WS_CHECK}"

PICOLIMBO_VERSION="$(curl -fsSL \
  "${BASE_URL}/api/client/servers/${ID_LIMBO}/files/contents?file=%2Fpicolimbo-version.txt" \
  -H "Authorization: Bearer ${CLIENT_KEY}" 2>/dev/null | tr -d '\r\n' || echo "unknown")"

SHARED_DIR="${SHARED_DIR}" \
TEMPLATE_DIR="${REPO_ROOT}/e2e/velocity/config" \
PANEL_URL="http://panel:8000/" \
API_KEY="${CLIENT_KEY}" \
BACKEND_HOST="host.docker.internal" \
ID_LIMBO="${ID_LIMBO}" ID_LOBBY1="${ID_LOBBY1}" ID_LOBBY2="${ID_LOBBY2}" \
ID_SURVIVAL="${ID_SURVIVAL}" ID_SKYWARS="${ID_SKYWARS}" ID_BEDWARS="${ID_BEDWARS}" \
PORT_LIMBO=25601 PORT_LOBBY1=25602 PORT_LOBBY2=25603 \
PORT_SURVIVAL=25604 PORT_SKYWARS=25605 PORT_BEDWARS=25606 \
bash "${REPO_ROOT}/e2e/scripts/render-configs.sh"

jq -n \
  --arg panelName "calagopus" \
  --arg panelVersion "${PANEL_VERSION}" \
  --arg wingsVersion "integrated wings-rs (${PANEL_VERSION})" \
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
echo "[calagopus-provision] Ready! Panel+Wings=${PANEL_VERSION} PicoLimbo=${PICOLIMBO_VERSION}"
