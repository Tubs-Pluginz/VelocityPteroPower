# VelocityPteroPower automated tests

The tests cover VelocityPteroPower end to end on Pterodactyl, Pelican and Calagopus. Mc Server Soft is not covered. The live suite runs against `:latest` panel and Wings images, PicoLimbo servers that the panel creates, Velocity 4.3+ on Java 25 and headless Minecraft clients. Every run writes a versioned report.

## 1. Unit and panel contract tests (Maven, about 5s)

These run without Docker. They cover the Pterodactyl, Pelican and Calagopus wire protocols, the core services and the addons:

```bash
mvn test
```

## 2. Live panel E2E suite

### Prerequisites
- A running Docker Engine, or rootless Podman with `systemctl --user start podman.socket`
- `jq`, `curl` and `bash`. Node.js is optional: the test runner runs in a Docker image, and a local `node` is only used to generate the report.
- JDK 25 and Maven, or pass `--skip-build` if the JARs are already built

### Run locally

```bash
# One panel (pulls :latest Panel + :latest Wings + latest Velocity 4.x + latest PicoLimbo)
./e2e/run-live-e2e.sh pterodactyl
./e2e/run-live-e2e.sh pelican
./e2e/run-live-e2e.sh calagopus

# All three panels, one after another
./e2e/run-live-e2e.sh all

# Debugging options
./e2e/run-live-e2e.sh pterodactyl --skip-build --keep-up
```

Without an argument the script runs `pterodactyl`. `--keep-up` leaves the containers running after the suite finishes.

### What a live run does
1. Builds the Maven reactor and stages the shaded plugin (`velocitypteropower-*-shaded.jar`), the three official addons (webhooks, scheduler, move-history) and the test-only `vpp-test-bridge` plugin. The sample addon is skipped.
2. Pulls the panel and Wings images: `ghcr.io/pterodactyl/{panel,wings}:latest`, `ghcr.io/pelican/{panel,wings}:latest`, or `calagopus/panel:aio`, which runs Wings inside the panel container.
3. Sets up the panel with no manual steps. It runs DB migrations, creates an admin account, mints client API keys (`ptlc_`, `pacc_` or `c7sp_`), imports the PicoLimbo egg with Velocity modern forwarding (`e2e/eggs/egg-picolimbo-vpp-e2e.json`), configures the Wings node, and creates 6 servers (`limbo`, `lobby-1`, `lobby-2`, `survival`, `skywars`, `bedwars`) that each ship a `whitelist.json`.
4. Starts Velocity 4.3+ (Java 25) with VPP, the official addons, `vpp-test-bridge` and a local `webhook-sink`.
5. Connects headless Minecraft clients (`Alice`, `Bob`, `Carol`, `Dave`, `Admin`, `Stranger`) over the Minecraft protocol (`minecraft-protocol`) and runs 30 scenarios:
   - Panel auth, `PanelType` detection and the Wings WebSocket state cache
   - `alwaysOnline` limbo and lobby auto-start
   - Forced-host cold start, then limbo holding, then `HYBRID` readiness and the `startupJoinDelay` settle, then the transfer
   - Idle shutdown scheduling, cancel on rejoin, and the Wings container stopping
   - Several players starting the same server share one queue
   - Fetching the real `whitelist.json` from the panel, `/ptero whitelistReload` and `ptero.bypass`
   - Group starts (`skywars` + `bedwars`) and single-use `/ptero unlock` start passwords
   - `maxOnlineServers` queueing and auto-start when a slot frees up
   - Built-in `/ptero maintenance` and an external `MaintenanceProvider` blocking cold starts
   - Persistent queue resuming after a reconnect
   - Core `/ptero` commands and the official addons (`vpp-webhooks`, `vpp-scheduler`, `vpp-move-history`)
   - Admin power commands (`start`, `restart`, `stop`, `stopidle`), including a grouped peer start
   - Staff commands (`password`, `queue`, `api`, `apithreads`, `reload`, `history`), permission denial, and the unconfirmed `forcestopall` / `forcestartall` prompt
   - Global maintenance blocking a cold start
   - `ptero.maxcap.bypass` starting a server while `maxOnlineServers` is full
   - `playerStartCooldown` blocking a second cold start
   - Four players across lobby scale-up and two game servers
   - Group idle shutdown guard (a player on `skywars` keeps the empty `bedwars` online)
   - `RoutingProvider` sending a forced-host connect to limbo
   - An idle extra lobby stopping after `scaleDownIdleSeconds` while `minOnline` keeps `lobby-1` up
   - Config variants changed at runtime through the bridge `/file` endpoint and `/ptero reload`: `queueWhenMaxOnline=false`, `maxOnlineReservations`, `forcedHostOfflineBehavior` `DISCONNECT` and `LIMBO_ONLY`, `serverStatusCheckMethod` `PANEL_API` and `VELOCITY_PING`, `languageOverride: de_DE`
   - Tab completion (bridge `/suggest`), `password generate` with a player unlock, and the output of `/ptero queue` and `/ptero history`
   - Webhooks `format: auto` sending Discord embeds to a Discord-style URL and generic JSON elsewhere, plus `/ptero webhook test`
   - Addon manager `browse`, `disable`/`enable`, `uninstall` and `install` from the catalog (`webhook-sink` serves the freshly built JARs), and move-history keeping its data across an addon reload
   - The scheduler firing a `* * * * *` cron that starts a server
   - `resourcePrefetchEnabled` filling the resource cache for every server
   - Confirmed `forcestartall` / `forcestopall`, then `alwaysOnline` and `minOnline` recovery
   - Proxy shutdown (`shutdownOnProxyExit`) stopping servers through Wings
6. Writes a log and report on every run, whether it passes, fails or breaks during setup. They go to `e2e/reports/<timestamp>-<panel>/`, with symlinks to the newest run at `e2e/reports/latest-<panel>.{log,md,html}`:
   - `test-run.log`: one log file. It starts with the resolved `:latest` versions, OCI digests and the pass/fail summary, followed by the `provision`, `runner`, `velocity`, `panel`, `wings` and `webhook-sink` logs.
   - `report.md`, which is also appended to the GitHub Actions step summary
   - `report.json`, with the results and the exact component versions and image digests
   - `report.html`, a standalone page with collapsible logs
   - `logs/`, the separate `provision.log`, `runner.log`, `velocity.log`, `panel.log`, `wings.log` and `webhook-sink.log` files. Calagopus has no `wings.log` because Wings runs in the panel container.

## 3. GitHub Actions workflows
- `.github/workflows/test.yml` runs the unit and contract tests on pushes and pull requests to `main`, `dev` and `0.9.6-rework`.
- `.github/workflows/e2e-live-panels.yml` runs the live suite every night at 03:00 UTC, and on demand through `workflow_dispatch`, against Pterodactyl, Pelican and Calagopus. It writes the resolved versions and results to the workflow summary and uploads the reports as artifacts for 30 days.
