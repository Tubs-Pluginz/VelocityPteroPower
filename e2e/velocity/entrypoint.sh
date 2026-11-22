#!/usr/bin/env bash
set -euo pipefail

SHARED_DIR="${SHARED_DIR:-/shared}"
PLUGINS_SRC="${PLUGINS_SRC:-/artifacts}"
SERVER_DIR="/server"

# Publish Velocity/Java version metadata immediately so even early-failure logs include it
mkdir -p "${SHARED_DIR}"
cp /opt/velocity/velocity-version.json "${SHARED_DIR}/velocity-version.json"

echo "[velocity-e2e] Waiting for panel provisioner (${SHARED_DIR}/ready)..."
for _ in $(seq 1 360); do
  if [ -f "${SHARED_DIR}/ready" ]; then
    break
  fi
  sleep 1
done

if [ ! -f "${SHARED_DIR}/ready" ]; then
  echo "[velocity-e2e] ERROR: Timed out waiting for ${SHARED_DIR}/ready" >&2
  exit 1
fi

VPP_DATA_DIR="${SERVER_DIR}/plugins/velocity-ptero-power"
mkdir -p "${VPP_DATA_DIR}/addons/configs"

# Copy provisioned Velocity & VPP configs
cp "${SHARED_DIR}/velocity.toml" "${SERVER_DIR}/velocity.toml"
cp "${SHARED_DIR}/forwarding.secret" "${SERVER_DIR}/forwarding.secret"
cp "${SHARED_DIR}/vpp-config.yml" "${VPP_DATA_DIR}/config.yml"

# Configure official addons for hermetic E2E testing
cat > "${VPP_DATA_DIR}/addons/configs/vpp-webhooks.yml" <<'EOF'
enabled: true
webhookUrl: "http://webhook-sink:19091/webhook"
webhookUrls: []
format: "generic"
events:
  power: true
  connect: true
  disconnect: true
  switch: true
skipCancelledPower: true
EOF

cat > "${VPP_DATA_DIR}/addons/configs/vpp-scheduler.yml" <<'EOF'
enabled: true
timezone: "UTC"
checkIntervalSeconds: 30
schedules:
  - name: "e2e-nightly-check"
    enabled: false
    cron: "0 4 * * *"
    action: "stop"
    servers:
      - survival
EOF

# Install freshly built shaded plugin + test bridge + official addons
rm -f "${SERVER_DIR}"/plugins/*.jar "${VPP_DATA_DIR}"/addons/*.jar
cp "${PLUGINS_SRC}"/velocitypteropower-*-shaded.jar "${SERVER_DIR}/plugins/"
cp "${PLUGINS_SRC}"/vpp-test-bridge-*.jar "${SERVER_DIR}/plugins/"

for addon_jar in "${PLUGINS_SRC}"/addons/*.jar; do
  if [ -f "$addon_jar" ]; then
    case "$(basename "$addon_jar")" in
      original-*) continue ;;
      *) cp "$addon_jar" "${VPP_DATA_DIR}/addons/" ;;
    esac
  fi
done

echo "[velocity-e2e] Installed plugins:"
ls -la "${SERVER_DIR}/plugins" "${VPP_DATA_DIR}/addons"

exec java -Xms256M -Xmx512M -jar /opt/velocity/velocity.jar
