#!/usr/bin/env bash
# Unified Live E2E Test Orchestrator for VelocityPteroPower.
# Spins up real :latest Panels + real :latest Wings + real servers + Velocity 4.3+ + automated MC clients,
# and automatically generates versioned Markdown, JSON, and HTML test reports.
#
# Usage:
#   ./e2e/run-live-e2e.sh [pterodactyl|pelican|calagopus|all] [--skip-build] [--keep-up]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-pterodactyl}"
shift || true

SKIP_BUILD=false
KEEP_UP=false
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --keep-up)    KEEP_UP=true ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

# Detect container runtime & compose command
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  CONTAINER_CLI="docker"
  COMPOSE_CMD="docker compose"
  DOCKER_SOCKET_PATH="${DOCKER_SOCKET_PATH:-/var/run/docker.sock}"
elif command -v podman >/dev/null 2>&1; then
  CONTAINER_CLI="podman"
  if podman compose version >/dev/null 2>&1; then
    COMPOSE_CMD="podman compose"
  elif command -v podman-compose >/dev/null 2>&1; then
    COMPOSE_CMD="podman-compose"
  else
    echo "ERROR: Podman found but neither 'podman compose' nor 'podman-compose' is available." >&2
    exit 1
  fi
  DOCKER_SOCKET_PATH="${DOCKER_SOCKET_PATH:-/run/user/$(id -u)/podman/podman.sock}"
else
  echo "ERROR: No running Docker or Podman engine found. Start dockerd or 'systemctl --user start podman.socket'." >&2
  exit 1
fi

# Detect Maven & Java 25
MVN_BIN="${MVN_BIN:-$(command -v mvn || true)}"
if [ -z "${MVN_BIN}" ] && [ -x "/opt/intellij-idea-ultimate-edition/plugins/maven-plugin/lib/maven3/bin/mvn" ]; then
  MVN_BIN="/opt/intellij-idea-ultimate-edition/plugins/maven-plugin/lib/maven3/bin/mvn"
fi
if [ -z "${JAVA_HOME:-}" ] && [ -d "${HOME}/.jdks/temurin-25" ]; then
  export JAVA_HOME="${HOME}/.jdks/temurin-25"
elif [ -z "${JAVA_HOME:-}" ] && [ -n "${SUDO_USER:-}" ] && [ -d "/home/${SUDO_USER}/.jdks/temurin-25" ]; then
  export JAVA_HOME="/home/${SUDO_USER}/.jdks/temurin-25"
fi

# Stage compiled artifacts
ARTIFACTS_DIR="${REPO_ROOT}/e2e/.state/artifacts"
stage_artifacts() {
  if [ "${SKIP_BUILD}" != "true" ]; then
    if [ -z "${MVN_BIN}" ]; then
      echo "ERROR: Maven not found. Set MVN_BIN or run with --skip-build after building JARs." >&2
      exit 1
    fi
    echo "[vpp-e2e] Building full Maven reactor (Java 25)..."
    "${MVN_BIN}" -f "${REPO_ROOT}/pom.xml" -B -DskipTests package
  fi

  rm -rf "${ARTIFACTS_DIR}"
  mkdir -p "${ARTIFACTS_DIR}/addons"
  cp "${REPO_ROOT}"/vpp-plugin/target/velocitypteropower-*-shaded.jar "${ARTIFACTS_DIR}/"
  cp "${REPO_ROOT}"/vpp-test-bridge/target/vpp-test-bridge-*.jar "${ARTIFACTS_DIR}/"
  for addon_dir in "${REPO_ROOT}"/addons/*; do
    [ -d "${addon_dir}/target" ] || continue
    # Skip sample addon (registers a demo SimpleLobbyRouter targeting non-existent 'lobby')
    [ "$(basename "${addon_dir}")" = "sample" ] && continue
    local newest_jar=""
    newest_jar="$(find "${addon_dir}/target" -maxdepth 1 -type f -name 'vpp-addon-*.jar' ! -name 'original-*' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n1 | cut -d' ' -f2- || true)"
    [ -n "${newest_jar}" ] && cp "${newest_jar}" "${ARTIFACTS_DIR}/addons/"
  done
}

image_digest() {
  local img="$1"
  ${CONTAINER_CLI} image inspect --format '{{index .RepoDigests 0}}' "${img}" 2>/dev/null \
    || ${CONTAINER_CLI} image inspect --format '{{.Id}}' "${img}" 2>/dev/null \
    || echo "unknown"
}

run_one_panel() {
  local panel="$1"
  local panel_dir="${REPO_ROOT}/e2e/panels/${panel}"
  local compose_file="${panel_dir}/docker-compose.yml"

  if [ ! -f "${compose_file}" ]; then
    echo "ERROR: Unsupported panel '${panel}'. Choose pterodactyl, pelican, calagopus, or all." >&2
    exit 2
  fi

  local ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  local state_dir="${REPO_ROOT}/e2e/.state/${panel}-${ts}"
  local results_dir="${REPO_ROOT}/e2e/reports/${ts}-${panel}"
  local logs_dir="${results_dir}/logs"
  mkdir -p "${state_dir}/shared" "${logs_dir}"

  export REPO_ROOT COMPOSE_CMD DOCKER_SOCKET_PATH
  export E2E_STATE_DIR="${state_dir}"
  export E2E_ARTIFACTS_DIR="${ARTIFACTS_DIR}"
  export E2E_RESULTS_DIR="${results_dir}"

  local panel_img wings_img
  case "${panel}" in
    pterodactyl)
      panel_img="ghcr.io/pterodactyl/panel:latest"
      wings_img="ghcr.io/pterodactyl/wings:latest"
      ;;
    pelican)
      panel_img="ghcr.io/pelican/panel:latest"
      wings_img="ghcr.io/pelican/wings:latest"
      ;;
    calagopus)
      panel_img="calagopus/panel:aio"
      wings_img="calagopus/panel:aio"
      ;;
  esac

  echo "================================================================="
  echo "[vpp-e2e] Starting Live Panel E2E: ${panel}"
  echo "  Panel image : ${panel_img}"
  echo "  Wings image : ${wings_img}"
  echo "  Log & report: ${results_dir}/test-run.log"
  echo "================================================================="

  write_versions_json() {
    local vpp_ver git_sha git_branch git_dirty panel_digest wings_digest
    local container_ver host_os
    vpp_ver="$(sed -n 's/.*<version>\([^<]*\)<\/version>.*/\1/p' "${REPO_ROOT}/pom.xml" | head -n1)"
    git_sha="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
    git_branch="$(git -C "${REPO_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
    git_dirty="$(git -C "${REPO_ROOT}" diff --quiet 2>/dev/null && echo "false" || echo "true")"
    panel_digest="$(image_digest "${panel_img}")"
    wings_digest="$(image_digest "${wings_img}")"
    container_ver="$(${CONTAINER_CLI} --version 2>/dev/null | head -n1 || echo "${CONTAINER_CLI}")"
    host_os="$(uname -srmo 2>/dev/null || uname -a)"

    local panel_meta vel_meta
    panel_meta="$(cat "${state_dir}/shared/panel-meta.json" 2>/dev/null || jq -n --arg pn "${panel}" '{panelName: $pn, panelVersion: "unknown", wingsVersion: "unknown", picolimboVersion: "unknown"}')"
    vel_meta="$(cat "${state_dir}/shared/velocity-version.json" 2>/dev/null || echo '{}')"

    jq -n \
      --arg vppVer "${vpp_ver}" \
      --arg gitSha "${git_sha}" \
      --arg gitBranch "${git_branch}" \
      --argjson gitDirty "${git_dirty}" \
      --arg panelImg "${panel_img}" \
      --arg panelDigest "${panel_digest}" \
      --arg wingsImg "${wings_img}" \
      --arg wingsDigest "${wings_digest}" \
      --arg containerRuntime "${container_ver}" \
      --arg hostOs "${host_os}" \
      --argjson pmeta "${panel_meta}" \
      --argjson vmeta "${vel_meta}" \
      '{
        vpp: { version: $vppVer, gitCommit: $gitSha, gitBranch: $gitBranch, gitDirty: $gitDirty },
        host: { os: $hostOs, containerRuntime: $containerRuntime },
        panel: { name: ($pmeta.panelName // "unknown"), version: ($pmeta.panelVersion // "unknown"), image: $panelImg, digest: $panelDigest },
        wings: { version: ($pmeta.wingsVersion // "unknown"), image: $wingsImg, digest: $wingsDigest },
        velocity: $vmeta,
        backend: { name: "PicoLimbo", version: ($pmeta.picolimboVersion // "unknown") }
      }' > "${results_dir}/versions.json"
  }

  local finalized=false
  finalize_and_cleanup() {
    if [ "${finalized}" = "true" ]; then
      return
    fi
    finalized=true

    echo "[vpp-e2e] Collecting container logs & resolved versions into ${results_dir}..."
    write_versions_json || true

    # Always capture all container logs (pass, fail, or early provisioning failure)
    ${COMPOSE_CMD} -f "${compose_file}" logs --no-color panel        > "${logs_dir}/panel.log"        2>&1 || true
    ${COMPOSE_CMD} -f "${compose_file}" logs --no-color velocity     > "${logs_dir}/velocity.log"     2>&1 || true
    ${COMPOSE_CMD} -f "${compose_file}" logs --no-color webhook-sink > "${logs_dir}/webhook-sink.log" 2>&1 || true
    if [ "${panel}" != "calagopus" ]; then
      ${COMPOSE_CMD} -f "${compose_file}" --profile wings logs --no-color wings > "${logs_dir}/wings.log" 2>&1 || true
    fi

    # Always generate test-run.log (versions at top + all logs) plus report.{md,json,html}
    if command -v node >/dev/null 2>&1; then
      node "${REPO_ROOT}/e2e/runner/generate-report.mjs" "${results_dir}" || true
    else
      ${COMPOSE_CMD} -f "${compose_file}" --profile run run --rm runner generate-report.mjs /results || true
    fi

    # Convenience pointers to the newest run for this panel
    ln -sfn "${ts}-${panel}/test-run.log" "${REPO_ROOT}/e2e/reports/latest-${panel}.log"  2>/dev/null || true
    ln -sfn "${ts}-${panel}/report.md"    "${REPO_ROOT}/e2e/reports/latest-${panel}.md"   2>/dev/null || true
    ln -sfn "${ts}-${panel}/report.html"  "${REPO_ROOT}/e2e/reports/latest-${panel}.html" 2>/dev/null || true

    # Restore report ownership to the invoking user when run via sudo
    if [ -n "${SUDO_UID:-}" ] && [ -n "${SUDO_GID:-}" ]; then
      chown -R "${SUDO_UID}:${SUDO_GID}" "${results_dir}" 2>/dev/null || true
      chown -h "${SUDO_UID}:${SUDO_GID}" \
        "${REPO_ROOT}/e2e/reports/latest-${panel}.log" \
        "${REPO_ROOT}/e2e/reports/latest-${panel}.md" \
        "${REPO_ROOT}/e2e/reports/latest-${panel}.html" 2>/dev/null || true
    fi

    if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ -f "${results_dir}/report.md" ]; then
      cat "${results_dir}/report.md" >> "${GITHUB_STEP_SUMMARY}"
      echo "" >> "${GITHUB_STEP_SUMMARY}"
    fi

    echo "[vpp-e2e] Full versioned log : ${results_dir}/test-run.log (symlink: e2e/reports/latest-${panel}.log)"
    echo "[vpp-e2e] Reports            : ${results_dir}/report.{md,json,html}"

    if [ "${KEEP_UP}" != "true" ]; then
      echo "[vpp-e2e] Tearing down ${panel} stack..."
      cleanup_panel_stack
    else
      echo "[vpp-e2e] Keeping ${panel} stack up (--keep-up)."
    fi
  }

  cleanup_panel_stack() {
    ${COMPOSE_CMD} -f "${compose_file}" --profile wings --profile run down -v --remove-orphans >/dev/null 2>&1 || true
    # Remove any ephemeral game server containers & custom bridge created by live Wings
    local wings_net="vpp_${panel}_wings_nw"
    [ "${panel}" = "pterodactyl" ] && wings_net="vpp_ptero_wings_nw"
    [ "${panel}" = "calagopus" ] && wings_net="vpp_cala_nw"
    local cids
    cids="$(${CONTAINER_CLI} ps -aq --filter "network=${wings_net}" 2>/dev/null || true)"
    if [ -n "${cids}" ]; then
      ${CONTAINER_CLI} rm -f ${cids} >/dev/null 2>&1 || true
    fi
    ${CONTAINER_CLI} network rm "${wings_net}" >/dev/null 2>&1 || true
    if [ "${panel}" = "calagopus" ]; then
      rm -rf /tmp/vpp-e2e-calagopus-wings >/dev/null 2>&1 || true
    fi
  }
  trap finalize_and_cleanup RETURN

  # Always start from a clean slate (wipes stale DB volumes/containers left by any interrupted prior run)
  cleanup_panel_stack

  # Calagopus AIO mutates its base wings-config.yml on boot; copy into ephemeral state dir
  if [ "${panel}" = "calagopus" ]; then
    cp "${panel_dir}/wings-config.yml" "${state_dir}/wings-config.yml"
    mkdir -p /tmp/vpp-e2e-calagopus-wings
  fi

  # Always pull latest Panel & Wings images
  ${COMPOSE_CMD} -f "${compose_file}" pull panel || true
  if [ "${panel}" != "calagopus" ]; then
    ${COMPOSE_CMD} -f "${compose_file}" --profile wings pull wings || true
  fi

  # Build Velocity (latest 4.x) & MC bot runner images and bring up infrastructure
  ${COMPOSE_CMD} -f "${compose_file}" build velocity runner webhook-sink || return $?
  ${COMPOSE_CMD} -f "${compose_file}" up -d || return $?

  # Run zero-touch headless provisioning, teeing full output into logs/provision.log
  local prov_rc=0
  bash "${panel_dir}/provision.sh" 2>&1 | tee "${logs_dir}/provision.log" || prov_rc=${PIPESTATUS[0]}
  if [ "${prov_rc}" -ne 0 ]; then
    echo "[vpp-e2e] ERROR: Provisioning failed (exit=${prov_rc})" >&2
    return "${prov_rc}"
  fi

  # Export minted client API key for runner container
  export PANEL_API_KEY
  PANEL_API_KEY="$(jq -r '.apiKey' "${state_dir}/shared/panel-meta.json")"

  # Wait briefly for velocity-version.json written by velocity entrypoint
  for _ in $(seq 1 30); do
    [ -f "${state_dir}/shared/velocity-version.json" ] && break
    sleep 1
  done

  write_versions_json

  # Run automated headless Minecraft client E2E test suite, teeing full output into logs/runner.log
  local exit_code=0
  ${COMPOSE_CMD} -f "${compose_file}" --profile run run --rm runner 2>&1 | tee "${logs_dir}/runner.log" || exit_code=${PIPESTATUS[0]}

  return "${exit_code}"
}

stage_artifacts

OVERALL_RC=0
if [ "${TARGET}" = "all" ]; then
  for p in pterodactyl pelican calagopus; do
    if ! run_one_panel "${p}"; then
      OVERALL_RC=1
    fi
  done
else
  run_one_panel "${TARGET}" || OVERALL_RC=$?
fi

exit "${OVERALL_RC}"
