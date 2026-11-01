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

**Manage your game servers with ease!** VelocityPteroPower connects your Velocity proxy to Pterodactyl, Pelican Panel, Calagopus, or Mc Server Soft, allowing for dynamic starting and stopping of your game servers.

**Requires Velocity 4.3+ and Java 25** on the proxy host (Velocity 3.x is not supported as of 0.9.6).

**For detailed information on configuration, commands, and permissions, please visit our [Docs Page](https://docs.pluginz.dev/).**

## Key Features
-   **Multi-Panel Support**: Works with Pterodactyl, Pelican Panel, Calagopus (`c7sp_` keys), and Mc Server Soft.
-   **Dynamic Server Management**:
    -   Automatically start servers when a player attempts to connect.
    -   Automatically stop idle servers after a configurable timeout.
    -   Manual control via commands (`/ptero start`, `/ptero stop`, `/ptero restart`).
-   **Whitelist Integration**:
    -   Fetch and enforce server whitelists from Pterodactyl/Pelican/Calagopus panels.
    -   Reload whitelists on the fly with `/ptero whitelistReload`.
    -   Optional single-use start passwords (`startPassword` / `/ptero unlock`) as a whitelist alternative.
-   **Server groups / linked starts**: start peers together and skip idle-shutdown while any group member has players.
-   **Efficient & Safe**:
    -   Respects panel API rate limits (Pterodactyl/Pelican/Calagopus/Mc Server Soft).
    -   Handles forced host connections, redirecting players to a limbo server.
-   **Update Notifications**: Stay informed about new plugin versions.
-   **Configurable**:
    -   Fine-tune server startup/shutdown behavior.
    -   Customize logging levels.
    -   Per-server settings for timeouts, join delays, and whitelist enforcement.
-   **VPP-managed addons**: drop-in JARs under `plugins/VelocityPteroPower/addons/` for webhooks, scheduling, audit history, and more.

## Developer API & Addons
This repo is a Maven multi-module reactor:

| Module | Artifact | Purpose |
|--------|----------|---------|
| `vpp-api` | `de.tubyoub:vpp-api` | Public API for addons |
| `vpp-plugin` | `de.tubyoub:velocitypteropower` | Main Velocity plugin (use `*-shaded.jar`) |
| `addons/sample` | `de.tubyoub:vpp-addon-sample` | Example VPP-managed addon |
| `addons/webhooks` | `de.tubyoub:vpp-addon-webhooks` | Discord / generic webhook notifications |
| `addons/scheduler` | `de.tubyoub:vpp-addon-scheduler` | Cron-based start/stop |
| `addons/move-history` | `de.tubyoub:vpp-addon-move-history` | Persistent connect/switch audit (`/ptero audit`) |

Build:

```bash
mvn -pl vpp-plugin -am package          # main plugin
mvn -pl addons/sample -am package       # one addon
mvn package                             # plugin + all addons
```

Addons are **VPP-managed JARs** (not separate Velocity plugins). Implement `de.tubyoub.vpp.api.VPPAddon`, register via `META-INF/services/de.tubyoub.vpp.api.VPPAddon`, depend on `vpp-api` with `provided` scope, and drop the JAR into `plugins/VelocityPteroPower/addons/`.

Addon YAML configs live in `plugins/VelocityPteroPower/addons/configs/{addonId}.yml` (separate from JAR drop folder and per-addon data under `addons/data/{addonId}/`).

Maven dependency:

```xml
<dependency>
  <groupId>de.tubyoub</groupId>
  <artifactId>vpp-api</artifactId>
  <version>0.9.6-alpha</version>
  <scope>provided</scope>
</dependency>
```

Official catalog: [`catalog/addons.json`](catalog/addons.json) (served from the `dev` branch by default). Operators can extend it with `addons.extraCatalogUrls` / `addons.extraEntries` in `config.yml`.

- In-game install (`/ptero addon install …`) downloads from GitHub Release assets (`releases/latest/download/vpp-addon-….jar`). CI uploads those on each GitHub Release.
- Browser downloads and stats live on separate Modrinth projects (webhooks, scheduler, move-history). The sample addon is build-local only.
- Dropping a JAR into `plugins/VelocityPteroPower/addons/` counts as installed in `/ptero addon browse` once it loads.

> Note: core `/ptero history` is in-memory only. The `move-history` addon provides persistent `/ptero audit`.

## Commands
-   `/ptero start <serverName>`
-   `/ptero stop <serverName>`
-   `/ptero restart <serverName>`
-   `/ptero list`
-   `/ptero info <serverName>`
-   `/ptero apithreads`
-   `/ptero api`
-   `/ptero queue`
-   `/ptero history [playerName|uuid]`
-   `/ptero maintenance [server|global] <on|off>` — built-in maintenance (disable via `maintenance.builtinEnabled`); external plugins can register `MaintenanceProvider`s instead
-   `/ptero addon [list|browse|info|install|uninstall|enable|disable|reload|update]`
-   `/ptero reload`
-   `/ptero whitelistReload`
-   `/ptero stopidle`
-   `/ptero forcestopall [confirm]`
-   `/ptero forcestartall [confirm]`

Addon commands (when the matching addon is installed):

-   `/ptero webhook test|reload` — webhooks addon
-   `/ptero schedule list|reload` — scheduler addon
-   `/ptero audit …` — move-history addon
-   `/ptero greet`, `/ptero sampleinfo`, … — sample addon

## Permissions
Core:

-   `ptero.start`
-   `ptero.stop`
-   `ptero.restart`
-   `ptero.list`
-   `ptero.info`
-   `ptero.queue` (queue status; also usable without staff for own position)
-   `ptero.history`
-   `ptero.maintenance`
-   `ptero.addon` (view addons / catalog)
-   `ptero.addon.install` (install / update from catalog)
-   `ptero.addon.manage` (enable / disable / reload / uninstall)
-   `ptero.whitelistReload`
-   `ptero.reload`
-   `ptero.stopIdle`
-   `ptero.forcestopall`
-   `ptero.forcestartall`
-   `ptero.bypass` (bypass VPP whitelist checks if enabled)
-   `ptero.maxcap.bypass` (bypass the maxOnlineServers cap if enabled)

Official addons:

-   `vpp.webhooks.test`
-   `vpp.scheduler.manage`
-   `vpp.audit.use` / `vpp.audit.manage`
-   `vpp.sample.*` (sample addon demos)

## Installation
1.  Download the latest `.jar` file from [Modrinth](https://modrinth.com/plugin/velocitypteropower).
2.  Place the `.jar` file into your Velocity server's `plugins` folder.
3.  Restart your Velocity server.
4.  Configure the plugin by editing `config.yml` in the `plugins/VelocityPteroPower` folder. **See the [Docs Page](https://docs.pluginz.dev/) for detailed configuration instructions.**

## Support
For issues, suggestions, or help, please [open an issue on GitHub](https://github.com/Tubs-Pluginz/VelocityPteroPower/issues/new) or join our [Discord Server](https://discord.pluginz.dev).

## Contributing
Interested in contributing? Join our [Discord](https://discord.pluginz.dev) to discuss your ideas or submit a pull request!

## License
This project is licensed under the [MIT License](LICENSE).

[![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com)
