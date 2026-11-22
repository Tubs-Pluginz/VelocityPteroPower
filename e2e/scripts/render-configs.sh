#!/usr/bin/env bash
# Shared helper to render velocity.toml, forwarding.secret, and vpp-config.yml into $SHARED_DIR.
set -euo pipefail

: "${SHARED_DIR:?SHARED_DIR must be set}"
: "${PANEL_URL:?PANEL_URL must be set}"
: "${API_KEY:?API_KEY must be set}"
: "${BACKEND_HOST:?BACKEND_HOST must be set}"
: "${ID_LIMBO:?}"
: "${ID_LOBBY1:?}"
: "${ID_LOBBY2:?}"
: "${ID_SURVIVAL:?}"
: "${ID_SKYWARS:?}"
: "${ID_BEDWARS:?}"
: "${PORT_LIMBO:?}"
: "${PORT_LOBBY1:?}"
: "${PORT_LOBBY2:?}"
: "${PORT_SURVIVAL:?}"
: "${PORT_SKYWARS:?}"
: "${PORT_BEDWARS:?}"

TEMPLATE_DIR="${TEMPLATE_DIR:-/templates}"
VELOCITY_SECRET="${VELOCITY_SECRET:-vpp-e2e-forwarding-secret}"

mkdir -p "${SHARED_DIR}"
printf '%s' "${VELOCITY_SECRET}" > "${SHARED_DIR}/forwarding.secret"

sed \
  -e "s|__BACKEND_HOST__|${BACKEND_HOST}|g" \
  -e "s|__PORT_LIMBO__|${PORT_LIMBO}|g" \
  -e "s|__PORT_LOBBY1__|${PORT_LOBBY1}|g" \
  -e "s|__PORT_LOBBY2__|${PORT_LOBBY2}|g" \
  -e "s|__PORT_SURVIVAL__|${PORT_SURVIVAL}|g" \
  -e "s|__PORT_SKYWARS__|${PORT_SKYWARS}|g" \
  -e "s|__PORT_BEDWARS__|${PORT_BEDWARS}|g" \
  "${TEMPLATE_DIR}/velocity.template.toml" > "${SHARED_DIR}/velocity.toml"

sed \
  -e "s|__PANEL_URL__|${PANEL_URL}|g" \
  -e "s|__API_KEY__|${API_KEY}|g" \
  -e "s|__ID_LIMBO__|${ID_LIMBO}|g" \
  -e "s|__ID_LOBBY1__|${ID_LOBBY1}|g" \
  -e "s|__ID_LOBBY2__|${ID_LOBBY2}|g" \
  -e "s|__ID_SURVIVAL__|${ID_SURVIVAL}|g" \
  -e "s|__ID_SKYWARS__|${ID_SKYWARS}|g" \
  -e "s|__ID_BEDWARS__|${ID_BEDWARS}|g" \
  "${TEMPLATE_DIR}/vpp-config.template.yml" > "${SHARED_DIR}/vpp-config.yml"

echo "[render-configs] Wrote velocity.toml, forwarding.secret, and vpp-config.yml to ${SHARED_DIR}"
