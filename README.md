# VelocityPteroPower
![Static Badge](https://img.shields.io/badge/Velocity-green) <br>
[![forthebadge](https://forthebadge.com/images/badges/works-on-my-machine.svg)](https://forthebadge.com) <br> <br>
<a href="https://www.buymeacoffee.com/tubyoub" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300">
    </a>
    <br>
    <i>Please join the Discord if you have questions or for support!</i>
</p>

> [!NOTE]
> **About AI use in this project.** I built the automated test suite (the unit tests and the live panel E2E setup under `e2e/`) and wrote large parts of the documentation with the help of AI. I read and checked what went in, and the tests run against real Pterodactyl, Pelican and Calagopus panels. The plugin itself is still my project, and if something in the tests or docs looks wrong, please open an issue.

> The Agents Cursor and Clause only have this many lines committed because i used them to commit large chunks of code i wrote over the last few months and then didnt commit myself and well lost track of it.

VelocityPteroPower (VPP) connects your Velocity proxy to Pterodactyl, Pelican Panel, Calagopus or Mc Server Soft. It starts a game server when a player tries to join it and stops it again once nobody has played on it for a while.

You need **Velocity 4.3+ and Java 25** on the proxy. Velocity 3.x is not supported since 0.9.6.

The [docs page](https://docs.pluginz.dev/) covers configuration, commands and permissions in detail.

## Features

### Panels
- Pterodactyl (`ptlc_` keys), Pelican (`plcn_` / `pacc_`), Calagopus (`c7sp_`) and Mc Server Soft (any other key). VPP picks the panel type from the key prefix.
- Live server state over the panel WebSocket, so VPP doesn't have to poll the API for every check. You can turn it off with `panelWebSocketEnabled: false`.
- Respects the panel's rate limits. On an HTTP 429 VPP waits for `Retry-After` and pauses background requests first.
- Three ways to decide if a server is ready: `VELOCITY_PING`, `PANEL_API` or `HYBRID` (both). You can set this globally or per server.

### Starting and stopping
- A player connecting to an offline server starts it. They wait on a limbo or lobby and get moved over once it's up and `startupJoinDelay` has passed.
- Several players asking for the same server share one start and one queue.
- Idle servers stop after their `timeout`. Set `timeout: -1` to never stop a server. `idleStartShutdownTime` stops servers that were started but nobody actually joined.
- `alwaysOnline` servers are started at boot and restarted if they go down.
- `maxOnlineServers` caps how many managed servers can run at once. Players queue for a slot (or get refused with `queueWhenMaxOnline: false`). Reservations, exempt servers and `ptero.maxcap.bypass` are supported.
- Startup timeouts with a policy (`CANCEL` or `KEEP_STARTING`), a soft requeue if the backend kicks a player during startup, and a per-player start cooldown.
- A persistent queue: players who disconnect while waiting are put back in line when they reconnect, within `persistentQueueTtlSeconds`.
- `lifecycle.shutdownOnProxyExit` stops chosen servers (or `all`) when the proxy shuts down.

### Lobbies and limbos
- Several lobbies and limbos, balanced by `ROUND_ROBIN`, `LEAST_PLAYERS` or `LEAST_CPU`.
- Lobby auto scaling. VPP starts another lobby at `preStartThresholdPercent` of capacity, keeps `minOnline` running, and stops extra lobbies that sit empty for `scaleDownIdleSeconds`.
- What happens to a forced-host join on an offline server is configurable: `DISCONNECT`, `LOBBY_OR_LIMBO` or `LIMBO_ONLY`.

### Access control
- Fetch each server's `whitelist.json` from the panel and enforce it before a start. Reload with `/ptero whitelistReload`, skip with `ptero.bypass`.
- Single-use start passwords as an alternative to the whitelist (`startPassword`, `/ptero unlock`, `/ptero password`).
- Built-in maintenance mode, global or per server (`/ptero maintenance`). VPP also detects [kennytv/Maintenance](https://github.com/kennytv/Maintenance) and respects it.

### Server groups
- Servers in a group (or `linkedServers`) start together. Idle shutdown waits until the whole group is empty.

### Configuration
- Named `profiles` that servers can inherit from, and per-server overrides for timeouts, poll intervals and the status check method.
- Old config files are migrated automatically and keep your `servers` section.
- Messages in English and German (`languageOverride: auto | en_US | de_DE`).
- Update notifications from Modrinth (`checkUpdate`).

### Addons
- Drop-in addon JARs managed by VPP, with an in-game catalog (`/ptero addon browse`). Official addons cover webhooks, cron schedules and a persistent move audit.
- A developer API with `RoutingProvider` and `MaintenanceProvider` hooks so addons or other plugins can change where players go or block cold starts.

## Installation
1. Download the latest `.jar` from [Modrinth](https://modrinth.com/plugin/velocitypteropower).
2. Put it in your Velocity `plugins` folder.
3. Restart Velocity.
4. Edit `plugins/velocity-ptero-power/config.yml`. The [docs page](https://docs.pluginz.dev/) explains every option.

## Commands
- `/ptero start <server>`
- `/ptero stop <server>`
- `/ptero restart <server>`
- `/ptero list`
- `/ptero info <server>`
- `/ptero api` and `/ptero apithreads` show rate limit and thread pool status
- `/ptero queue` shows your own queue position, or every queue if you have `ptero.list` or `ptero.reload`
- `/ptero history [player|uuid]` shows recent server moves when `moveHistory.enabled` is on (kept in memory only)
- `/ptero unlock <server> <password>`
- `/ptero password <set|generate|clear|status> <server>`
- `/ptero maintenance [server|global] <on|off>` (turn the built-in mode off with `maintenance.builtinEnabled: false`)
- `/ptero addon [list|browse|info|install|uninstall|enable|disable|reload|update]`
- `/ptero reload`
- `/ptero whitelistReload`
- `/ptero stopidle`
- `/ptero forcestopall [confirm]`
- `/ptero forcestartall [confirm]`

Addon commands, when the addon is installed:

- `/ptero webhook test|reload` (webhooks)
- `/ptero schedule list|reload` (scheduler)
- `/ptero audit ...` (move-history, saved to disk)
- `/ptero greet`, `/ptero sampleinfo` and a few more (sample addon)

## Permissions
Core:

- `ptero.start`, `ptero.stop`, `ptero.restart`
- `ptero.list`
- `ptero.info` (also `/ptero api` and `/ptero apithreads`)
- `ptero.history`
- `ptero.maintenance`
- `ptero.password` (manage start passwords)
- `ptero.password.bypass` (start password-protected servers without unlocking)
- `ptero.addon` (view addons and the catalog)
- `ptero.addon.install` (install or update from the catalog)
- `ptero.addon.manage` (enable, disable, reload, uninstall)
- `ptero.whitelistReload`
- `ptero.reload`
- `ptero.stopIdle`
- `ptero.forcestopall`, `ptero.forcestartall`
- `ptero.bypass` (skip VPP whitelist checks, if `whitelistAllowBypass` is on)
- `ptero.maxcap.bypass` (start a server while `maxOnlineServers` is full, if `maxOnlineAllowBypass` is on)

`/ptero queue` and `/ptero unlock` need no permission.

Official addons:

- `vpp.webhooks.test`
- `vpp.scheduler.manage`
- `vpp.audit.use`, `vpp.audit.manage`
- `vpp.sample.*` (sample addon)

## Developer API and addons
The repo is a Maven multi-module build:

| Module | Artifact | Purpose |
|--------|----------|---------|
| `vpp-api` | `de.tubyoub:vpp-api` | Public API for addons |
| `vpp-plugin` | `de.tubyoub:velocitypteropower` | The Velocity plugin (use the `*-shaded.jar`) |
| `addons/sample` | `de.tubyoub:vpp-addon-sample` | Example addon |
| `addons/webhooks` | `de.tubyoub:vpp-addon-webhooks` | Discord and generic JSON webhooks |
| `addons/scheduler` | `de.tubyoub:vpp-addon-scheduler` | Cron-based start and stop |
| `addons/move-history` | `de.tubyoub:vpp-addon-move-history` | Persistent connect and switch audit (`/ptero audit`) |
| `vpp-test-bridge` | | Test-only Velocity plugin used by the E2E suite. Don't install it on a real proxy. |

Build:

```bash
mvn -pl vpp-plugin -am package          # main plugin
mvn -pl addons/sample -am package       # one addon
mvn package                             # plugin, all addons and the test bridge
```

Addons are JARs that VPP loads itself. They are not separate Velocity plugins. Implement `de.tubyoub.vpp.api.VPPAddon`, register it in `META-INF/services/de.tubyoub.vpp.api.VPPAddon`, depend on `vpp-api` with `provided` scope, and drop the JAR into `plugins/velocity-ptero-power/addons/`.

Each addon's config is `plugins/velocity-ptero-power/addons/configs/<addonId>.yml`, and its data goes in `addons/data/<addonId>/`. `/ptero addon reload` re-reads the config file, so hand edits survive.

```xml
<dependency>
  <groupId>de.tubyoub</groupId>
  <artifactId>vpp-api</artifactId>
  <version>0.9.6-beta</version>
  <scope>provided</scope>
</dependency>
```

The official catalog is [`catalog/addons.json`](catalog/addons.json), served from the `dev` branch. You can add your own with `addons.extraCatalogUrls` or `addons.extraEntries` in `config.yml`.

- `/ptero addon install ...` downloads from the rolling GitHub `nightly` prerelease. CI rebuilds that prerelease on every push.
- Webhooks, scheduler and move-history each have their own Modrinth project for browser downloads. The sample addon is only built locally.
- VPP scans the addons folder at startup. A JAR you drop in shows up as installed in `/ptero addon browse` after the next restart.

## Testing
`mvn test` runs the unit and panel contract tests in a few seconds, no Docker needed.

`e2e/run-live-e2e.sh <pterodactyl|pelican|calagopus|all>` spins up a real panel, Wings, six Minecraft servers, Velocity and bot players in Docker, then runs 30 scenarios against them. [`e2e/README.md`](e2e/README.md) has the details and the list of what's covered.

## Support
For bugs, ideas or questions, [open an issue on GitHub](https://github.com/Tubs-Pluginz/VelocityPteroPower/issues/new) or join the [Discord](https://discord.pluginz.dev).

## Contributing
Want to help? Talk to us on [Discord](https://discord.pluginz.dev) or send a pull request.

## License
MIT, see [LICENSE](LICENSE).

[![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com)
