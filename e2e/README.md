# VelocityPteroPower Automated Testing Suite

This testing suite covers VelocityPteroPower end-to-end across **Pterodactyl**, **Pelican**, and **Calagopus** (McServerSoft is intentionally excluded), using **live `:latest` Panels**, **live `:latest` Wings daemons**, **real dynamically created Minecraft servers**, **real Velocity 4.3+ (Java 25)**, and **automated headless Minecraft clients**—with an automatic versioned report generated on every run.

## 1. Fast Unit & 3-Panel Contract Tests (Maven, ~5s)

Runs without Docker against Pterodactyl, Pelican, and Calagopus wire protocols plus all core services and addons:

```bash
mvn test
```

## 2. Live Panel + Live Wings + Automated MC Client E2E Suite

### Prerequisites
- Running Docker Engine (or rootless Podman with `systemctl --user start podman.socket`)
- `jq`, `curl`, `bash` (Node.js optional — the runner builds in Docker automatically)
- JDK 25 + Maven (or pass `--skip-build` if JARs are already packaged)

### Run Locally

```bash
# Single live panel (pulls :latest Panel + :latest Wings + latest Velocity 4.x + latest PicoLimbo)
./e2e/run-live-e2e.sh pterodactyl
./e2e/run-live-e2e.sh pelican
./e2e/run-live-e2e.sh calagopus

# Run all 3 live panels back-to-back
./e2e/run-live-e2e.sh all

# Debugging options
./e2e/run-live-e2e.sh pterodactyl --skip-build --keep-up
```

### What Each Live Run Does Automatically
1. **Builds** the shaded VPP plugin (`velocitypteropower-*-shaded.jar`), all 4 official addons, and the test-only `vpp-test-bridge` plugin.
2. **Pulls `:latest`** Panel and Wings images (`ghcr.io/pterodactyl/{panel,wings}:latest`, `ghcr.io/pelican/{panel,wings}:latest`, or `calagopus/panel:aio`).
3. **Zero-touch provisions** the live Panel: runs DB migrations, creates an admin account, mints real API keys (`ptlc_` / `pacc_` / `c7sp_`), imports the lightweight `PicoLimbo` Velocity Modern Forwarding egg (`e2e/eggs/egg-picolimbo-vpp-e2e.json`), configures the live Wings node, and **creates 6 real servers** (`limbo`, `lobby-1`, `lobby-2`, `survival`, `skywars`, `bedwars`) with a real `whitelist.json`.
4. **Boots real Velocity 4.3+ (Java 25)** with VPP + all official addons + `vpp-test-bridge` + local `webhook-sink`.
5. **Connects automated headless Minecraft clients** (`Alice`, `Bob`, `Admin`, `Stranger`) over real Minecraft TCP protocol (`minecraft-protocol`) to test:
   - Panel auth, `PanelType` detection, and live Wings WebSocket state cache
   - `alwaysOnline` limbo & lobby auto-start on live Wings
   - Forced-host cold start → limbo holding → `HYBRID` readiness + `startupJoinDelay` settle → auto-transfer
   - Idle shutdown scheduling, cancel-on-rejoin race, and live Wings container stop
   - Concurrent multi-player start queue deduplication
   - Real `whitelist.json` fetch from live Panel/Wings, `/ptero whitelistReload`, and `ptero.bypass`
   - Server groups linked starts (`skywars` + `bedwars`) and single-use `/ptero unlock` start passwords
   - `maxOnlineServers` cap queueing & auto-start when a slot frees
   - Built-in `/ptero maintenance` and external `MaintenanceProvider` SPI cold-start blocking
   - Persistent queue reconnect resume
   - Core `/ptero` commands and official addons (`vpp-webhooks`, `vpp-scheduler`, `vpp-move-history`)
   - Graceful proxy shutdown (`shutdownOnProxyExit`) stopping servers on live Wings
6. **Generates a Versioned Test Log & Report automatically on every run (pass, fail, or early setup error)** under `e2e/reports/<timestamp>-<panel>/` (with latest shortcuts `e2e/reports/latest-<panel>.{log,md,html}`):
   - `test-run.log` — single self-contained log file with **all resolved `:latest` versions, OCI digests, and pass/fail summary right at the top**, followed by full `provision`, `runner`, `velocity`, `panel`, `wings`, and `webhook-sink` logs
   - `report.md` (published automatically to GitHub Actions Step Summary)
   - `report.json` (structured results + exact component versions & OCI image digests)
   - `report.html` (standalone visual report with collapsible logs)
   - `logs/` (individual `provision.log`, `runner.log`, `velocity.log`, `panel.log`, `wings.log`, `webhook-sink.log` files)

## 3. GitHub Actions Workflows
- `.github/workflows/test.yml` — Runs fast Maven unit & 3-panel contract tests on every push and PR.
- `.github/workflows/e2e-live-panels.yml` — Runs nightly (and via manual `workflow_dispatch`) against live `:latest` Pterodactyl, Pelican, and Calagopus + live Wings, publishing the exact resolved versions and test results into the workflow summary and uploading report artifacts.
