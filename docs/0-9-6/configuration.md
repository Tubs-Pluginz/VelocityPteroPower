---
title: VPP config
sidebar: true
---
# Configuration guide

VPP writes `plugins/velocity-ptero-power/config.yml` on first start.

::: tip Back up first
Copy `config.yml` before large edits. `/ptero reload` applies most keys. `apiThreads` and `pterodactyl.url` need a proxy restart.
:::

Defaults to know about in 0.9.6: `serverStatusCheckMethod` is `HYBRID`, the startup poll and timeout settings are new, and `sendToLimboOnStart` turns on when you list limbos. API keys can start with `ptlc_`, `plcn_`/`pacc_`, or `c7sp_` (Calagopus).

When a plugin update adds keys, VPP adds them to your `config.yml` on the next start and keeps your values. Your `servers`, `profiles` and `serverGroups` sections stay as you wrote them.

The defaults below are the values in the shipped `config.yml`. Where VPP uses a different value for a key that is missing from the file, the entry says so.

---

## General settings

### `fileversion`
VPP uses this number to update the config structure between plugin versions. Don't change it.

Default: set by the plugin (`17` in 0.9.6).

### `checkUpdate`
If `true`, VPP checks Modrinth for a newer version on startup and logs a message to the console when it finds one.

Type: `Boolean`. Default: `true`.

### `languageOverride`
Sets the language for plugin messages. Use a locale such as `"en_US"` or `"de_DE"`, or `"auto"` to follow the environment. See [Messages and localization](#messages-and-localization).

Type: `String`. Default: `auto`.

```yaml
languageOverride: en_US
```

### `loggerLevel`
Sets how much VPP logs to the Velocity console.

Type: `Integer`. Default: `20`.

| Value | Level | Use |
| --- | --- | --- |
| `10` | DEBUG | Troubleshooting. Logs the most detail. |
| `20` | INFO | Normal operation. |
| `30` | WARNING | Possible problems and non-critical errors. |
| `40` | ERROR | Only errors that stop something from working. |

::: tip Troubleshooting
Set `loggerLevel: 10` before you collect logs for a bug report. Set it back to `20` afterwards, or the console fills up.
:::

### `apiThreads`
Sets the number of threads for HTTP requests to the panel. VPP computes a baseline of 2 threads per managed server, clamped between 4 and 64, and uses whichever is larger: the baseline or `apiThreads`.

Type: `Integer`. Default: `10`.

```text
baseline = clamp(servers * 2, 4, 64)
final    = max(apiThreads, baseline)
```

| Servers | Baseline | Threads used |
| --- | --- | --- |
| 3 | 6 | `max(apiThreads, 6)` |
| 20 | 40 | `max(apiThreads, 40)` |
| 40 | 64 (clamped) | `max(apiThreads, 64)` |
| any, with `apiThreads: 100` | | at least `100` |

VPP sizes the pool when it creates the API client. A reload only recreates the client when the panel type or API key changes, so restart the proxy after you change `apiThreads`. Run `/ptero apithreads` (permission `ptero.info`) to see the configured value, the server count, the baseline and the thread count in use.

### `printRateLimit`
If `true`, VPP logs the panel's rate limit and remaining requests after each API call. Turn it on when you suspect the rate limit is slowing VPP down.

Type: `Boolean`. Default: `false`.

---

## Server status and startup

### `serverStatusCheckMethod`
Sets how VPP decides that a server is online and ready. You can override it per server or per profile.

Type: `String`. Default: `"HYBRID"`.

| Value | How it works | Trade-off |
| --- | --- | --- |
| `"VELOCITY_PING"` | Pings the server through Velocity. | Costs no API requests. The server must be registered in `velocity.toml` and reachable from the proxy. It can report online before backend plugins finish loading, and some minimal limbo servers don't answer pings. |
| `"PANEL_API"` | Asks the panel for the process state. | Matches the panel's "running" state, but every check uses an API request. |
| `"HYBRID"` | Needs both: the panel reports running and the ping succeeds. | Avoids sending players to a half-started server. |

### `pingTimeout`
How long VPP waits for a ping answer before it treats the server as offline, in milliseconds. It applies to `VELOCITY_PING` and to the ping half of `HYBRID`.

Type: `Integer`. Default: `1000`.

### `startupInitialCheckDelay`
Seconds to wait after sending a start signal before the first online check. A lightweight limbo that starts fast can use a lower value.

Type: `Integer`. Default: `10`.

### `startupPollInterval`
How often, in seconds, VPP checks whether a starting server is online. You can override it per server or per profile. The minimum is `1`.

Type: `Integer`. Default: `5`.

### `startupTimeoutSeconds`
The longest VPP waits for a server to come online after a start signal. When the time runs out, VPP applies `startupTimeoutPolicy`. You can override it per server or per profile. Values below `30` count as `30`.

Type: `Integer`. Default: `180`.

### `startupTimeoutPolicy`
What VPP does when `startupTimeoutSeconds` runs out.

Type: `String`. Default: `CANCEL`. An invalid value falls back to `CANCEL`.

| Value | Effect |
| --- | --- |
| `CANCEL` | Clears the waiting players and the server's starting state. |
| `KEEP_STARTING` | Clears the waiting players but keeps the starting state, so players can queue again. |

::: info Join delay and poll interval
The per-server `startupJoinDelay` is how long VPP waits after the server is online before it moves players there. It is not a poll interval. The poll interval is `startupPollInterval`.
:::

### `softRequeueGraceSeconds`
After VPP moves a waiting player to the started server, a kick with `SERVER_DISCONNECTED` within this many seconds puts the player back in the queue instead of cancelling.

Type: `Integer`. Default: `30`.

### `softRequeueMaxAttempts`
How many times VPP requeues a player this way before it gives up.

Type: `Integer`. Default: `3`.

### `persistentQueueEnabled`
If `true`, VPP saves the start-wait queues to `startup-queue.json`, so a player who disconnects can pick up their place when they reconnect.

Type: `Boolean`. Default: `true`.

### `persistentQueueTtlSeconds`
How long a disconnected player's queue entry stays valid, in seconds. The minimum is `60`.

Type: `Integer`. Default: `900` (15 minutes).

---

## Panel API usage

### `rateLimitBackgroundPauseThreshold`
VPP pauses background prefetching when the remaining API requests drop to this number, or while players are waiting for a start.

Type: `Integer`. Default: `5`.

### `rateLimitProbeFallbackSeconds`
If the panel never sends an `X-RateLimit-Reset` header, VPP waits this many seconds before it tries again once the remaining requests reach 0. The minimum is `10`.

Type: `Integer`. Default: `60`.

### `panelWebSocketEnabled`
If `true`, VPP subscribes to the Wings websockets of Pterodactyl, Pelican and Calagopus to get live server state. This cuts down on polling.

Type: `Boolean`. Default: `true`.

### `panelWebSocketRefreshSeconds`
How often VPP checks and renews the websocket subscriptions, in seconds. The minimum is `30`.

Type: `Integer`. Default: `120`.

### `serverStateCacheTtlSeconds`
How long VPP trusts a server state it got from the websocket, in seconds. The minimum is `5`.

Type: `Integer`. Default: `30`.

### `resourceCacheSeconds`
How long VPP caches resource usage from the panel, in seconds. `0` turns the cache off.

Type: `Integer`. Default: `10`.

### `resourcePrefetchEnabled`
If `true`, VPP fetches resource usage for all managed servers every `resourceCacheSeconds`, so `/ptero list` and `/ptero info` answer from the cache.

Type: `Boolean`. Default: `true`.

---

## Shutdown

### `shutdownRetries`
If VPP sends a stop signal and the server doesn't shut down, VPP sends it again up to this many times.

Type: `Integer`. Default: `3`.

### `shutdownRetryDelay`
Seconds between shutdown retries.

Type: `Integer`. Default: `300` (5 minutes). If the key is missing, VPP uses `30`.

### `idleStartShutdownTime`
If VPP starts a server and nobody joins it within this many seconds, VPP stops it again. This applies whether a player connection or a command started the server. `-1` turns it off.

Type: `Integer`. Default: `300` (5 minutes).

### `idleShutdownCheckInterval`
A backup sweep, in seconds. VPP checks all online managed servers and schedules an idle shutdown for each empty one, following `alwaysOnline` and the per-server `timeout`. It catches shutdowns that a missed event would otherwise skip. `0` or a negative value turns it off.

Type: `Integer`. Default: `60`.

### `stopIdleIgnore`
Servers that `/ptero stopidle` leaves running, even when they are empty. Use the server names from `velocity.toml` and the `servers` section.

Type: `List` of `String`. Default: `["hub", "lobby", "limbo"]`.

```yaml
stopIdleIgnore:
  - lobby1
  - lobby2
  - hub
```

::: warning Only for /ptero stopidle
This list has no effect on the automatic shutdown from `timeout` or `idleStartShutdownTime`, and none on `/ptero forcestopall`.
:::

### `lifecycle.shutdownOnProxyExit`
Servers VPP stops when the Velocity proxy shuts down. This overrides `alwaysOnline` during shutdown. Use `all` to stop every managed server, or list server names. An empty list turns it off.

Type: `List` of `String`. Default: `[]`.

```yaml
lifecycle:
  shutdownOnProxyExit:
    - lobby
    - survival
```

To stop everything:

```yaml
lifecycle:
  shutdownOnProxyExit:
    - all
```

---

## Players and starts

### `playerStartCooldown`
Seconds a player has to wait before they can start another server by connecting to it. It doesn't apply to `/ptero start`.

Type: `Integer`. Default: `10`.

### `serverNotFoundMessage`
If `true`, a player who connects to a server that is in `velocity.toml` but not in the VPP `servers` section gets a "server not found in configuration" message. VPP always logs this to the console.

Type: `Boolean`. Default: `false`.

### `alwaysOnline`
Servers VPP keeps running. VPP starts them once when the proxy starts, then checks every `alwaysOnlineCheckInterval` seconds and starts any that are offline. These servers are never shut down for being idle, whatever their `timeout` says.

Type: `List` of `String`. Default: `["limbo"]`.

```yaml
alwaysOnline:
  - limbo
```

### `alwaysOnlineCheckInterval`
How often VPP checks the `alwaysOnline` list, in seconds.

Type: `Integer`. Default: `60`.

---

## Online server cap

### `maxOnlineServers`
The most managed servers that can be online at once. `0` means no limit. Only servers under `servers` count.

Type: `Integer`. Default: `0`.

### `maxOnlineReservations`
Keeps this many slots under the cap free for always-online servers, limbos and lobbies, so player starts can't fill the whole cap. Player starts are limited to `maxOnlineServers - maxOnlineReservations`. It only applies when `maxOnlineServers` is above `0`.

Type: `Integer`. Default: `0`.

### `queueWhenMaxOnline`
If `true`, a player who connects while the cap is reached waits in the queue for the server. If `false`, VPP denies the start.

Type: `Boolean`. Default: `true`.

### `maxOnlineAllowBypass`
If `true`, players with `ptero.maxcap.bypass` can start servers past the cap.

Type: `Boolean`. Default: `true`.

### `maxOnlineExempt`
Servers that don't count toward the cap and can always start, by Velocity server name.

Type: `List` of `String`. Default: `["hub", "lobby", "limbo"]`.

### `countLobbiesInMaxOnline`
If `true`, the servers in `lobbyBalancer.lobbies` count toward `maxOnlineServers`. If `false`, VPP leaves them out. When `lobbyBalancer.lobbiesToUse` is above `0`, this only covers the first N lobbies.

Type: `Boolean`. Default: `false`.

### `countLimbosInMaxOnline`
If `true`, the servers in `lobbyBalancer.limbos` count toward `maxOnlineServers`. If `false`, VPP leaves them out.

Type: `Boolean`. Default: `false`.

---

## Whitelist

### `whitelistAllowBypass`
If `true`, players with `ptero.bypass` skip VPP's whitelist check on servers with `whitelist: true`.

Type: `Boolean`. Default: `true`.

::: danger The backend whitelist still applies
This only skips VPP's check before the connection. The Minecraft server still enforces its own `whitelist.json`, and a player who isn't on it can't join.
:::

### `whitelistCheckInterval`
How often, in minutes, VPP fetches `whitelist.json` again from servers with `whitelist: true`. `0` or a negative value turns off the periodic fetch. VPP still fetches the whitelists on startup, on `/ptero reload` and on `/ptero whitelistReload`.

Type: `Integer`. Default: `10`.

::: warning Not available on MC Server Soft
VPP can't fetch files from MC Server Soft, so whitelists don't work there.
:::

---

## Move history

### `moveHistory.enabled`
If `true`, VPP keeps each player's recent server moves and disconnects in memory for `/ptero history`. Nothing is written to disk, and the history is cleared when the proxy restarts.

Type: `Boolean`. Default: `false`.

### `moveHistory.maxEntriesPerPlayer`
How many moves VPP keeps per player, newest first. The minimum is `1`.

Type: `Integer`. Default: `50`.

```yaml
moveHistory:
  enabled: true
  maxEntriesPerPlayer: 50
```

---

## Maintenance

VPP has its own maintenance mode, controlled with `/ptero maintenance`. It can also read maintenance state from kennytv's Maintenance plugin and from addons that register a `MaintenanceProvider`. While any enabled source reports maintenance for a server, VPP doesn't start it.

### `maintenance.builtinEnabled`
Turns VPP's own maintenance mode on or off. While it is `false`, `/ptero maintenance` refuses to run.

Type: `Boolean`. Default: `true`.

### `maintenance.externalProvidersEnabled`
If `true`, VPP detects kennytv's Maintenance plugin and uses providers that addons register through `VPPApi.registerMaintenanceProvider(...)`.

Type: `Boolean`. Default: `true`.

```yaml
maintenance:
  builtinEnabled: true
  externalProvidersEnabled: true
```

---

## Panel connection (`pterodactyl`)

::: info
The section is still called `pterodactyl` for compatibility, even if you use Pelican, Calagopus or MC Server Soft. VPP detects the panel type from the API key prefix.
:::

### `pterodactyl.url`
The base URL of your panel, including `http://` or `https://`. VPP adds a trailing `/` if it is missing.

Type: `String`. Required.

```yaml
url: "https://panel.yourdomain.com/"
```

::: tip Cloudflare and proxies
If Cloudflare, a reverse proxy or a tunnel sits in front of your panel, point the URL at the address the proxy can reach. Allow the proxy's IP through any Cloudflare or tunnel rules.
:::

### `pterodactyl.apiKey`
Your client API key. Create it in your account settings on the panel, usually under "API Credentials" or "API Keys".

Type: `String`. Required.

| Prefix | Panel VPP detects |
| --- | --- |
| `ptlc_` | Pterodactyl |
| `plcn_` or `pacc_` | Pelican |
| `c7sp_` | Calagopus |
| `ptla_` or `peli_` | Application key. VPP rejects it. |
| anything else | MC Server Soft |

On Calagopus, give the key `control.start`, `control.stop`, `control.restart` and `files.read-content`. VPP needs these for power actions, status and whitelists.

```yaml
apiKey: "ptlc_YOUR_CLIENT_API_KEY_HERE"
```

::: danger Use a client API key
VPP only works with client API keys. Application keys (`ptla_` on Pterodactyl, `peli_` on Pelican) fail. A client key acts on behalf of your user account.
:::

::: info MC Server Soft
MC Server Soft has only one type of API key.
:::

---

## Per-server configuration (`servers`)

Every server VPP manages needs an entry here. The entry name must match the server name in `velocity.toml` exactly, including case.

### `servers.<serverName>`

```yaml
servers:
  survival:
    id: "a1b2c3d4"
    timeout: 600
    startupJoinDelay: 15
    whitelist: true
```

#### `id`
The server's identifier on the panel. Required.

- **Pterodactyl.** Take it from the server URL. For `https://panel.example.com/server/a1b2c3d4`, the ID is `a1b2c3d4`.
- **Pelican.** Use the short UUID from the server details page.
- **Calagopus.** Use the full server UUID from the server URL or details page.

Always quote the ID. YAML reads values such as `91e62747` as a number in scientific notation, which can turn into `Infinity`:

```yaml
servers:
  mc-vanilla-6:
    id: "91e62747"   # correct
  mc-vanilla-7:
    id: 91e62747     # wrong, YAML parses this as a number
```

If the log shows `Invalid type for 'id'` or `Invalid 'id' value ... Quote the ID in YAML`, quote the ID and reload.

VPP skips any server whose ID is `1234abcd`, the placeholder in the shipped example.

#### `timeout`
Seconds VPP waits after the last player leaves before it stops the server. `-1` turns off automatic shutdown for this server. `0` stops it as soon as the last player leaves.

Type: `Integer`. Default if missing: `-1`.

#### `startupJoinDelay`
Once the server counts as online under `serverStatusCheckMethod`, VPP waits this many more seconds before it moves waiting players there. Backend plugins and worlds can finish loading in that time.

Type: `Integer`. Default if missing: `10`.

#### `whitelist`
If `true`, VPP fetches the server's `whitelist.json` from the panel and checks it when a player tries to start the server. Players with `ptero.bypass` skip the check when `whitelistAllowBypass` is on.

Type: `Boolean`. Default: `false`.

::: warning Not available on MC Server Soft
VPP can't fetch files from MC Server Soft. With `whitelist: true` on that panel, VPP logs warnings and the check does nothing.
:::

#### `group`
A shared group name. Starting one member also starts the group's offline members. VPP doesn't shut down idle members while any member of the group still has players. You can also define groups in [`serverGroups`](#servergroups).

Type: `String`. Optional.

#### `linkedServers`
Servers VPP starts together with this one, even if they belong to other groups.

Type: `List` of `String`. Optional.

```yaml
linkedServers:
  - skywars-db
```

#### `startPassword`
A single-use start password, as an alternative to the whitelist. A player has to run `/ptero unlock <server> <password>` before they can start the server, and the password is used up after one successful unlock. Players with `ptero.password.bypass` don't need it. To set a new password, reload the config or use `/ptero password set|generate`.

Type: `String`. Optional.

#### `profile`
The name of an entry in [`profiles`](#profiles). The server takes every field it doesn't set itself from that profile.

Type: `String`. Optional.

#### Overrides
These keys replace the global value for this server only:

- `startupPollInterval`
- `startupTimeoutSeconds` (minimum `30`)
- `serverStatusCheckMethod`

### `profiles`
Named sets of server settings. A server with `profile: <name>` takes every field it doesn't set itself from the profile. A profile can set `timeout`, `startupJoinDelay`, `whitelist`, `startupPollInterval`, `startupTimeoutSeconds`, `serverStatusCheckMethod`, `group`, `linkedServers` and `startPassword`.

```yaml
profiles:
  survival:
    startupJoinDelay: 15
    startupTimeoutSeconds: 300
    serverStatusCheckMethod: HYBRID
  limbo:
    startupJoinDelay: 0
    startupPollInterval: 3
    serverStatusCheckMethod: PANEL_API
    timeout: -1
```

### `serverGroups`
Groups defined in one place. They combine with the per-server `group` key. If a server sets its own `group`, that value wins. Starting any member also starts the offline members, and VPP doesn't shut down idle members until the whole group is empty.

```yaml
serverGroups:
  minigames:
    - skywars
    - bedwars
```

---

## Lobby and limbo balancer (`lobbyBalancer`)

VPP can spread players across several lobbies, keep a number of them online, start more under load, and send players to a limbo while their target server starts. [Lobbies and limbo](lobbies-and-limbo.md) covers how this works in practice.

VPP can only start or measure servers listed under `servers` with a valid panel ID. A lobby or limbo outside `servers` has to be online already for VPP to use it.

| Key | Default | Description |
| --- | --- | --- |
| `enableBuiltin` | `true` | Turns the built-in balancer on or off. Routing providers from addons run first and VPP falls back to the built-in balancer while this is `true`. A registered provider does not turn it off. |
| `lobbies` | `["lobby"]` | Lobby servers VPP balances and uses as holding servers. Each one must also be listed under `servers:`. Order matters when `lobbiesToUse` is set. |
| `limbos` | `["limbo"]` | Limbo servers where players wait while their target starts. |
| `lobbiesToUse` | `0` | Only the first N lobbies take part in balancing. `0` uses all of them. |
| `strategy` | `"ROUND_ROBIN"` | `ROUND_ROBIN` takes the next server in turn, `LEAST_PLAYERS` the one with the fewest players, `LEAST_CPU` the one with the lowest CPU use according to the panel. |
| `healthCheckInterval` | `15` | Seconds between reachability checks and scaling decisions. |
| `minOnline` | `1` | Keeps at least this many lobbies online. `0` sets no minimum. |
| `maxOnline` | `0` | Keeps no more than this many lobbies online. `0` sets no limit. |
| `playersPerServer` | `80` | Roughly how many players a lobby holds. VPP uses it for scaling decisions. |
| `cpuScaleUpThreshold` | `85.0` | With `LEAST_CPU`, VPP starts another lobby when any online lobby passes this CPU percentage. |
| `autoScaleEnabled` | `true` | Starts more lobbies when needed. |
| `preStartThresholdPercent` | `80` | VPP starts another lobby when the players on online lobbies reach this percentage of their total capacity (`online lobbies * playersPerServer`). |
| `startFailureFallbackSeconds` | `60` | If a started lobby isn't online after this many seconds, VPP tries a different one. |
| `startFailureCooldownSeconds` | `120` | Seconds VPP waits after a failed start before it tries the same lobby again. |
| `scaleDownEnabled` | `true` | Stops empty extra lobbies until only `minOnline` remain. |
| `scaleDownIdleSeconds` | `300` | How long a lobby above `minOnline` has to stay empty before VPP stops it. The minimum is `30`. |
| `sendToLimboOnStart` | `true` | Sends players to a limbo while their target starts, and queues them for the target. VPP uses a lobby if no limbo is reachable. If `false`, players stay on their current server while they wait, and players coming straight from the proxy are disconnected. If the key is missing, it is `true` when `limbos` has entries and `false` otherwise. |
| `forcedHostOfflineBehavior` | `LOBBY_OR_LIMBO` | What happens when a player joins through a forced host and the target is offline. `DISCONNECT` disconnects them. `LOBBY_OR_LIMBO` tries a lobby, then a limbo, then disconnects. `LIMBO_ONLY` tries a limbo, then disconnects. This works on its own, so you can keep `sendToLimboOnStart: false` and still keep first-join players connected. If the key is missing, VPP uses `DISCONNECT`. An invalid value falls back to `LOBBY_OR_LIMBO`. |

Older configs had a single `limboServer` key. VPP moves its value into `lobbyBalancer.limbos` and removes the old key. Keep limbos lightweight and put them in `alwaysOnline` if you can.

### How selection works
- VPP picks a lobby if one is available and falls back to a limbo.
- The `strategy` decides which lobby or limbo it picks.
- On each health check, VPP checks which lobbies are reachable and starts lobbies until `minOnline` are running.
- When player load passes `preStartThresholdPercent`, or a lobby passes `cpuScaleUpThreshold` under `LEAST_CPU`, VPP starts the next offline lobby that isn't in its failure cooldown.
- If no lobby or limbo is usable, players stay where they are or are disconnected until the target starts.

---

## Addons (`addons`)

VPP loads addon JARs from `plugins/velocity-ptero-power/addons/`. See [Addons](addons.md) for installing them.

### `addons.catalogUrl`
The URL of the official addon catalog.

Default: `https://raw.githubusercontent.com/Tubs-Pluginz/VelocityPteroPower/dev/catalog/addons.json`

### `addons.extraCatalogUrls`
More catalog JSON URLs that follow the same schema as `catalog/addons.json`.

Type: `List` of `String`. Default: `[]`.

### `addons.extraEntries`
Catalog entries written directly in the config, for private or one-off addons. Each entry takes `id`, `name`, `description`, `authors`, `latestVersion`, `downloadUrl`, `apiVersion` and an optional `homepage`.

Type: `List`. Default: `[]`.

```yaml
addons:
  extraEntries:
    - id: my-addon
      name: My Addon
      description: Private addon
      authors: ["You"]
      latestVersion: "1.0.0"
      downloadUrl: "https://example.com/my-addon-1.0.0.jar"
      apiVersion: "1.0.0"
```

### `addons.autoLoadOnStartup`
If `true`, VPP loads the enabled addon JARs when the proxy starts.

Type: `Boolean`. Default: `true`.

---

## Messages and localization

VPP keeps the texts that players and the console see in YAML files, so you can change the wording without touching the plugin JAR.

- The files are in `plugins/velocity-ptero-power/messages/`.
- Each file is named `<locale>.yml`. VPP ships `en_US.yml` (English) and `de_DE.yml` (German).
- If a key is missing from your language file, VPP uses the value from `en_US.yml`.
- When an update adds messages, VPP adds the new keys to your files on the next start and keeps your edits.
- VPP no longer reads the old single `messages.yml` file. If it still exists, VPP logs an error asking you to remove it.

### Choosing the language
`languageOverride` in `config.yml` decides the language.

```yaml
languageOverride: auto  # or "en_US" / "de_DE"
```

With `auto`, VPP checks these in order and uses the first one that is set:

1. the `velocitypteropower.lang` Java system property
2. the `VPP_LANG` environment variable
3. the JVM's default locale

VPP can only load a language it ships with. If the chosen locale has no bundled file, VPP logs a warning and uses `en_US`. To change texts, edit `en_US.yml` or `de_DE.yml` in the `messages` folder.

### Editing messages
- Messages use MiniMessage formatting. You can use colors and tags such as `<green>` and `<bold>`.
- Some messages contain placeholders such as `<server>`, `<count>`, `<reason>` or `<remaining>`. Keep them, or the message loses that information.
- YAML strings can be quoted, or written as `|` block scalars for multi-line text.

::: tip Reload
After editing files in `plugins/velocity-ptero-power/messages/`, run `/ptero reload` to apply the changes without restarting Velocity.
:::

---

::: details Default `config.yml` (fileversion: 17)
```yaml
################################
#      VelocityPteroPower      #
#         by TubYoub            #
################################

# Version of the configuration file
fileversion: 17

# Check for updates
# If true, the plugin will check for updates on startup.
# If a new version is available, a message will be sent to the console and to players with the permission "ptero.reload".
checkUpdate: true

# Override the language used for plugin messages.
# Set to a locale like "en_US" or "de_DE" to force a specific language.
# Use "auto" to follow the environment (system/Velocity) language.
# default: auto
languageOverride: auto

# Choose which logging level should be used
# It depends on how much you want you're console to be filled with information from VPP
# For production you can safely set it to a higher level but for development/testing it is recommended to use a lower level
# 10 = Debug
# 20 = Info
# 30 = Warning
# 40 = Error
#default: 20
loggerLevel: 20

# How many threads the API client should use for HTTP work.
# VPP auto-scales threads to roughly 2x the number of configured servers (clamped between 4 and 64).
# If you set apiThreads higher than that baseline, your configured value overrides the baseline.
# More threads = more parallel requests but also more resource usage. Restart/reload to apply.
# default: 10
apiThreads: 10

# print updated Rate Limit from the API response to console
# default: false
printRateLimit: false

# Choose the method to check if a managed server is online and ready.
# "VELOCITY_PING": Uses Velocity's built-in ping functionality. Requires the server
#                  to be registered in Velocity's config and accessible from the proxy.
#                  Less resource-intensive, doesn't use API rate limits. Might report
#                  online slightly before the server is fully joinable.
#                  Some simple Limbo servers might not report any Ping back suing this method.
# "PANEL_API": Uses the panel API (Pterodactyl/Pelican/Calagopus) to check the server's state.
#                    More accurate for actual server state ("running"), but uses API
#                    requests and counts towards rate limits.
# "HYBRID": Requires both panel "running" AND a successful Velocity ping before treating
#           the server as ready (best for avoiding premature joins / kicks).
# Default: "HYBRID"
serverStatusCheckMethod: "HYBRID"

# How long the ping to the server lasts, to check if its is online, until it times out (in milliseconds)
# default: 1000 (1 second)
pingTimeout: 1000

# How many retrys to shut down the server if the previous one failed
# default: 3
shutdownRetries: 3

# How long to wait before retrying to shut down the server (in seconds)
# default: 300
shutdownRetryDelay: 300

# How long to wait before shutting down the server if no player joined the server(in seconds)
# default: 300
# -1 = disabled
idleStartShutdownTime: 300

# How long to wait before allowing a player to start a server again (in seconds)
# default: 10
playerStartCooldown: 10

# How long (in seconds) the plugin should wait for the first check if the server is online
# Maybe consider changing this to a lower value if you have for example a
# limbo which is lightweight and starts fast
# default: 10
startupInitialCheckDelay: 10

# How often (in seconds) to poll while waiting for a server to come online.
# Independent of startupJoinDelay (which is the post-online settle delay).
# default: 5
startupPollInterval: 5

# Maximum time (in seconds) to wait for a server to become online after a start signal.
# default: 180
startupTimeoutSeconds: 180

# What to do when startupTimeoutSeconds elapses:
# CANCEL         → clear waiters and starting state (default)
# KEEP_STARTING  → clear player waiters but leave starting state so they can re-queue
# default: CANCEL
startupTimeoutPolicy: CANCEL

# After an auto-connect transfer, if the backend kicks with SERVER_DISCONNECTED within this
# many seconds, soft-requeue the player (settle again) instead of cancelling.
# default: 30
softRequeueGraceSeconds: 30

# Max soft-requeue attempts after premature kicks during start-wait transfers.
# default: 3
softRequeueMaxAttempts: 3

# Pause background prefetch when remaining API requests fall to this threshold (or waiters exist).
# default: 5
rateLimitBackgroundPauseThreshold: 5

# If the panel never sends X-RateLimit-Reset, wait this many seconds before probing again when remaining is 0.
# default: 60
rateLimitProbeFallbackSeconds: 60

# Persist start-wait queues to disk so players can resume after reconnect.
# default: true
persistentQueueEnabled: true

# How long (seconds) a disconnected player's queue entry remains valid.
# default: 900 (15 minutes)
persistentQueueTtlSeconds: 900

# Subscribe to Pterodactyl/Pelican/Calagopus Wings websockets for live server state (reduces polling).
# default: true
panelWebSocketEnabled: true

# How often to (re)ensure websocket subscriptions for managed servers.
# default: 120
panelWebSocketRefreshSeconds: 120

# TTL for cached live panel states from websocket (seconds).
# default: 30
serverStateCacheTtlSeconds: 30

# Reserve this many online slots for always-online / limbo / lobby so player starts cannot fill the entire cap.
# Only applies when maxOnlineServers > 0. default: 0
maxOnlineReservations: 0

# When max online is reached, queue the player for the target instead of hard-denying (start-on-join).
# default: true
queueWhenMaxOnline: true

# Servers to ignore when the stopIdle command is used
# THIS DOES NOT AFFECT forceStop
stopIdleIgnore:
  - hub
  - lobby
  - limbo

# If a player should receive a message when a server is not found in the config
# message will always be send to console
# default: false
serverNotFoundMessage: false

# Allow players with the permission "ptero.bypass" to bypass the whitelist when starting a server.
# This does not work to get around a whitelist because it is enforced by the backend server itself.
# default: true
whitelistAllowBypass: true

# How long (in minutes) VPP should wait in between checks for a new whitelist and enforce it (if enabled)
# default: 10
whitelistCheckInterval: 10

# Limit how many managed servers may be online concurrently. 0 = unlimited
# default = 0
maxOnlineServers: 0

# Allow players with the permission "ptero.maxcap.bypass" to bypass the maxOnlineServers cap when starting servers.
# default: true
maxOnlineAllowBypass: true

# Servers excluded from the above cap (by Velocity server name)
maxOnlineExempt:
  - hub
  - lobby
  - limbo

# Whether to include lobbyBalancer lobbies in the maxOnlineServers count
# default: false
countLobbiesInMaxOnline: false

# Whether to include lobbyBalancer limbos in the maxOnlineServers count
# default: false
countLimbosInMaxOnline: false

# Servers that should always be kept online. The plugin will periodically check and start them if offline.
alwaysOnline:
  - limbo

# How often to check/enforce the alwaysOnline list (in seconds)
# default = 60
alwaysOnlineCheckInterval: 60

# Resource usage caching for panel queries
# How long to cache resource usage responses (in seconds). Set to 0 to disable caching.
# default = 10
resourceCacheSeconds: 10

# If enabled, the plugin will periodically prefetch resource usage for all managed servers
# every 'resourceCacheSeconds' to keep the cache warm and make commands snappy.
# default = true
resourcePrefetchEnabled: true

# Automatic idle shutdown sweep (failsafe)
# Periodically checks all managed servers and schedules idle shutdowns if they are empty.
# This safeguards against missed events or errors preventing scheduling.
# Set to 0 or negative to disable.
# default = 60
idleShutdownCheckInterval: 60

# In-memory player move history (server switch history)
# Pretty display command: /ptero history [player]
# Data is NOT saved to disk; only kept while the proxy is running
moveHistory:
  enabled: false
  # Maximum number of recent moves to retain per player (latest first)
  maxEntriesPerPlayer: 50

# Maintenance cold-start gate
# VPP always ships its own /ptero maintenance mode. Networks that already run
# kennytv/Maintenance (or an addon that registers MaintenanceProvider) can keep
# both, or disable either side independently.
maintenance:
  # Built-in VPP maintenance (/ptero maintenance [server|global] on|off)
  # default = true
  builtinEnabled: true
  # External providers: auto-detect kennytv/Maintenance + addon SPI registrations
  # via VPPApi.registerMaintenanceProvider(...)
  # default = true
  externalProvidersEnabled: true

# Lifecycle related behavior
lifecycle:
  # When the proxy shuts down, stop these servers.
  # Use "all" to stop all managed servers, or provide a list of specific server names.
  # This overrides 'alwaysOnline' during shutdown.
  # Leave the list empty to disable stopping servers on proxy shutdown (default behavior).
  # To stop all managed servers on shutdown, set:
  #   shutdownOnProxyExit:
  #     - all
  # Or list specific servers by name.
  shutdownOnProxyExit: []

# Multi-lobby/limbo balancer settings
lobbyBalancer:
  # Enable the built-in balancer logic provided by VPP. If you plan to use an external addon that registers a RoutingProvider,
  # you can set this to false to disable the built-in balancer entirely. Routing providers do NOT automatically disable the
  # built-in balancer; providers run first and VPP falls back to the built-in balancer if this is true.
  enableBuiltin: true

  # A list of lobby servers to use for player transfers and first-join routing (if you route players there).
  # Order matters if lobbiesToUse is set; only the first N will be considered active.
  lobbies:
    - lobby
    # - hub  # optional: add additional lobby servers here if configured under servers

  # Optional list of limbo servers to use while target servers are starting.
  limbos:
    - limbo

  # How many lobbies from the list above should be considered for balancing (0 = use all listed)
  # default = 0
  lobbiesToUse: 0

  # Balancer strategy: ROUND_ROBIN, LEAST_PLAYERS, or LEAST_CPU
  # default = ROUND_ROBIN
  strategy: "ROUND_ROBIN"

  # Periodic health check interval in seconds for verifying reachability and planning scale-ups
  # default = 15
  healthCheckInterval: 15

  # Keep at least this many lobby servers online (0 = no minimum)
  # default = 1
  minOnline: 1

  # Do not keep more than this many lobby servers online (0 = unlimited)
  # default = 0
  maxOnline: 0

  # Approximate players-per-lobby capacity used to decide when to scale up
  # default = 80
  playersPerServer: 80

  # Only used with LEAST_CPU strategy. If any lobby exceeds this CPU percent, consider scaling up
  # default = 85.0
  cpuScaleUpThreshold: 85.0

  # If true, balancer will auto-start additional lobbies when needed
  # default = true
  autoScaleEnabled: true

  # Start another lobby before capacity is completely full.
  # When total players across online lobbies reach this percent of capacity, VPP starts another lobby (if available).
  # default = 80 (means start a new lobby at ~80% of total lobby capacity)
  preStartThresholdPercent: 80

  # If a lobby was started but doesn't come online within this many seconds, VPP will try starting a different lobby.
  # default = 60
  startFailureFallbackSeconds: 60

  # After a failed start, wait this many seconds before trying that same lobby again.
  # default = 120
  startFailureCooldownSeconds: 120

  # Scale down empty extra lobbies back toward minOnline after they have been idle.
  # default = true
  scaleDownEnabled: true

  # How long (seconds) an empty lobby above minOnline must stay empty before stop.
  # default = 300
  scaleDownIdleSeconds: 300

  # When a target server is starting, send players to a limbo (if available) and auto-queue them.
  # Falls back to a configured lobby when no limbo is reachable.
  # If false, players will stay on their current server (or be disconnected if coming from proxy) while queued.
  # default = true when lobbyBalancer.limbos is non-empty; otherwise false
  sendToLimboOnStart: true

  # Behavior for forced-host joins when the target backend is offline
  # DISCONNECT       → disconnect (default)
  # LOBBY_OR_LIMBO  → try a lobby first, then a limbo; if none, disconnect
  # LIMBO_ONLY      → only try limbo; if none, disconnect
  forcedHostOfflineBehavior: LOBBY_OR_LIMBO

# Panel configuration
pterodactyl:
  # The URL of your panel (Pterodactyl / Pelican / Calagopus)
  # If you use Cloudflare Tunnel, you need to allow the ip in the bypass setting.
  url: "https://panel.example.com"
  # The client API key of your panel.
  # Supported prefixes: "ptlc_" (Pterodactyl), "plcn_"/"pacc_" (Pelican), "c7sp_" (Calagopus).
  # Find it under Account → API Credentials / API Keys.
  # Calagopus: grant control.start/stop/restart + files.read-content for power/status/whitelist.
  apiKey: "ptlc_000000000000000000000000000000000000000000"

# Per server configuration
# Make sure that the server names match the registered servers in the velocity.toml
servers:
  #server name
  example:

    # Panel server ID
    # You can find the Panel server ID in the URL of the server page.
    # For example, if the URL is https://panel.example.com/server/1234abcd, the server ID is 1234abcd.
    # For Pelican, you need the short UUID from Server Details.
    # For Calagopus, use the full server UUID from the server URL / details page.
    # IMPORTANT: Always quote the ID to prevent YAML from parsing it as a number (e.g., 91e62747 would become Infinity if unquoted)

    id: "1234abcd"

    # The time in seconds to stop the server after the last player leaves.
    # If you don't want to stop the server automatically, set it to -1.
    # If you set it to 0, the server will be stopped immediately after the last player leaves.

    timeout: 30

    # The time (in seconds) VPP should wait AFTER the server is online before auto-connecting.
    # Useful when plugins need a few seconds to finish loading after ping/API reports ready.
    startupJoinDelay: 10

    # Optional: shared group name. Starting one member also starts offline peers;
    # idle-shutdown is skipped while any group member still has players.
    # group: "minigames"

    # Optional: explicitly linked servers also started with this one (even across groups).
    # linkedServers:
    #   - skywars-db

    # Optional: single-use start password (alternative to whitelist). Players unlock with
    # /ptero unlock <server> <password>; the password is consumed after one successful unlock.
    # startPassword: "change-me"

    # Optional overrides (omit to use global defaults):
    # startupPollInterval: 5
    # startupTimeoutSeconds: 180
    # serverStatusCheckMethod: HYBRID
    # profile: survival

    # If VPP should fetch the whitelist of the Server and enforce it when a player trys to start a server
    # default: false
    whitelist: false

# Optional named profiles. Servers can set profile: <name> and override individual fields.
# profiles:
#   survival:
#     startupJoinDelay: 15
#     startupTimeoutSeconds: 300
#     serverStatusCheckMethod: HYBRID
#   limbo:
#     startupJoinDelay: 0
#     startupPollInterval: 3
#     serverStatusCheckMethod: PANEL_API
#     timeout: -1

# Optional explicit server groups (merged with per-server group:).
# Starting any member also starts offline peers; idle-shutdown waits for the whole group.
# serverGroups:
#   minigames:
#     - skywars
#     - bedwars

# VPP-managed addons (JARs under plugins/velocity-ptero-power/addons/)
addons:
  # Official catalog (GitHub raw). Operators can extend it with extraCatalogUrls / extraEntries.
  catalogUrl: "https://raw.githubusercontent.com/Tubs-Pluginz/VelocityPteroPower/dev/catalog/addons.json"
  # Additional catalog JSON URLs (same schema as catalog/addons.json)
  extraCatalogUrls: []
  # Inline catalog entries for one-off / private addons
  # extraEntries:
  #   - id: my-addon
  #     name: My Addon
  #     description: Private addon
  #     authors: ["You"]
  #     latestVersion: "1.0.0"
  #     downloadUrl: "https://example.com/my-addon-1.0.0.jar"
  #     apiVersion: "1.0.0"
  extraEntries: []
  # Load enabled addon JARs automatically on proxy start
  autoLoadOnStartup: true
```
:::
