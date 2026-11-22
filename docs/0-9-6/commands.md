---
title: VPP commands
---
# Commands reference

The base command is `/ptero`, with the alias `/vpp`. Every subcommand below works with either. You can run them from the console or in game if you have the permission.

Running `/ptero` with no arguments prints the command list, including commands registered by loaded addons.

::: tip Case sensitivity
Server names must match the names in `velocity.toml` and `config.yml` exactly. They are usually case-sensitive.
:::

## Server control

### `/ptero start <serverName>`
Sends a start signal for the server to the panel.

Permission: `ptero.start`

```bash
/ptero start skyblock
```

- The command refuses if maintenance blocks the server.
- It respects `maxOnlineServers`, minus `maxOnlineReservations`. Players with `ptero.maxcap.bypass` skip the cap when `maxOnlineAllowBypass: true`.
- It also starts the server's offline group members and `linkedServers`.
- `playerStartCooldown` does not apply to this command. It only applies to starts triggered by a player connecting.
- The command does not check whether the server is already running. It sends the signal either way.

### `/ptero stop <serverName>`
Sends a stop signal to the panel, which shuts the server down gracefully.

Permission: `ptero.stop`

```bash
/ptero stop creative
```

### `/ptero restart <serverName>`
Sends a restart signal to the panel.

Permission: `ptero.restart`

```bash
/ptero restart survival
```

## Status and information

### `/ptero list`
Lists every managed server with its status, player count and TTL (time until shutdown).

Permission: `ptero.list`

- Status comes from your `serverStatusCheckMethod`.
- The TTL column shows one of these:
  - a live countdown if a shutdown is already scheduled
  - the server's configured `timeout`, when the server is empty but no countdown has started yet
  - a dash for servers in `alwaysOnline`, and for servers with players or with `timeout: -1`

### `/ptero info <serverName>`
Shows status, players, CPU, memory, disk, network traffic and uptime for one server.

Permission: `ptero.info`

- VPP fetches resource usage from the panel and caches it for `resourceCacheSeconds`.
- The CPU and disk limits only appear when the panel sets them. An unlimited disk shows no limit, and unlimited memory shows "unlimited".
- Usage values are colored from green to red based on how close they are to the limit. CPU without a limit is measured against 100%.
- If a player started the server through VPP, an extra line shows `Started by: <player>`.

### `/ptero history [playerName|uuid]`
Shows a player's recent server moves and disconnects, newest first.

Permission: `ptero.history`

- Without an argument, a player sees their own history. The console has to name a player.
- Names only resolve for online players. For offline players, use the UUID.
- Needs `moveHistory.enabled: true` in `config.yml`.
- History lives in memory and is cleared when the proxy restarts. For a history that survives restarts, install the move-history addon and use `/ptero audit`.
- Tab completion suggests online player names.

### `/ptero queue`
Shows where you are in the start-wait queue and how many seconds are left before the start times out.

Permission: none. Players with `ptero.list` or `ptero.reload`, and the console, see every server's queue instead: the server name, the number of waiting players and the time left.

### `/ptero apithreads`
Shows the configured `apiThreads`, the number of managed servers, the automatic baseline (2 times the server count, clamped to 4..64) and the thread count in use. Use it to check whether your `apiThreads` value overrides the baseline.

Permission: `ptero.info`

- VPP sizes the thread pool when it creates the API client, on startup. A reload only rebuilds the client when the panel type or API key changes, so plan a restart after changing `apiThreads`.
- The pool uses the larger of `apiThreads` and the baseline.

### `/ptero api`
Shows the public addon API version, whether a routing provider is registered, the addon commands that are registered, and whether the panel HTTP, server control and server registry services are available. This is mostly useful when you develop or debug an addon.

Permission: `ptero.info`

## Bulk commands

### `/ptero stopidle`
Sends a stop signal to every managed server that has no players connected, except the servers listed in `stopIdleIgnore`.

Permission: `ptero.stopIdle`

- VPP checks the player count when you run the command.
- If the panel rate limit runs out, VPP stops sending signals and tells you. Some empty servers may still be running afterwards.

### `/ptero forcestopall [confirm]`
::: danger Stops everything
This sends a stop signal to every server VPP manages. It ignores player counts and `stopIdleIgnore`.
:::

Permission: `ptero.forcestopall`

1. Run `/ptero forcestopall`. You get a warning.
2. Run `/ptero forcestopall confirm` within 30 seconds.

From the console, `/ptero forcestopall confirm` runs right away with no 30-second window. Without `confirm`, the console only gets the warning.

The command stops sending signals if the panel rate limit runs out.

### `/ptero forcestartall [confirm]`
::: danger Heavy on resources
This sends a start signal to every server VPP manages. Check that your host has enough CPU, RAM and disk I/O first.
:::

Permission: `ptero.forcestartall`

1. Run `/ptero forcestartall`. You get a warning.
2. Run `/ptero forcestartall confirm` within 30 seconds.

From the console, `/ptero forcestartall confirm` runs right away. Without `confirm`, the console only gets the warning.

If the panel rate limit runs out, some servers won't get the start signal.

## Access control

### `/ptero unlock <serverName> <password>`
Uses the single-use start password of a server that has `startPassword` set. After a successful unlock the password is used up, and the player can start the server.

Permission: none. Only players can run it. Players with `ptero.password.bypass` don't need to unlock.

### `/ptero password <set|generate|clear|status> <serverName> [password]`
Manages start passwords while the proxy runs.

Permission: `ptero.password` or `ptero.reload`

| Action | Effect |
| --- | --- |
| `set <serverName> <password>` | Sets a new password. |
| `generate <serverName>` | Creates a random 12-character password, sets it and shows it to you. |
| `clear <serverName>` | Removes the password, so the server starts without one. |
| `status <serverName>` | Shows whether a password is active. |

Passwords set with this command live in memory only. `/ptero reload` and a proxy restart reset them to the `startPassword` values in `config.yml`.

### `/ptero maintenance [server|global] <on|off>`
Turns VPP's built-in maintenance mode on or off. While maintenance is on, VPP doesn't start the affected servers.

Permission: `ptero.maintenance` or `ptero.reload`

```bash
/ptero maintenance on              # global
/ptero maintenance global off
/ptero maintenance survival on     # one server
```

- `true` and `false` work in place of `on` and `off`.
- With no arguments, the command shows the current global and per-server state.
- VPP saves the state to `maintenance.json`, so it survives restarts.
- The command refuses when `maintenance.builtinEnabled: false`. External providers, such as kennytv's Maintenance plugin or an addon, can still block starts. See `maintenance.externalProvidersEnabled`.

### `/ptero whitelistReload`
Fetches `whitelist.json` again for every server with `whitelist: true`. `/ptero reloadwhitelist` does the same.

Permission: `ptero.whitelistReload`

::: warning Not available on MC Server Soft
VPP can't fetch files from MC Server Soft, so this command and the whitelist feature don't work there.
:::

## Admin

### `/ptero reload`
Reloads `config.yml` and the files in `messages/`. Most settings apply without restarting the proxy.

Permission: `ptero.reload`

- VPP rebuilds the panel API client only when the detected panel type or the API key changes. Changes to `apiThreads` or `pterodactyl.url` alone need a proxy restart.
- The reload also reapplies the maintenance settings, turns the built-in lobby balancer on or off based on `lobbyBalancer.enableBuiltin`, and resets start passwords to the values in the config.

### `/ptero addon [action]`
Manages VPP addons. `/ptero addons` works too. Without an action, it lists installed addons.

| Action | Permission |
| --- | --- |
| `list` (alias `ls`) | `ptero.addon` |
| `browse` (aliases `catalog`, `market`) | `ptero.addon` |
| `info <id>` | `ptero.addon` |
| `install <id>` | `ptero.addon.install` |
| `update <id>` | `ptero.addon.install` |
| `uninstall <id>` (alias `remove`) | `ptero.addon.manage` |
| `enable <id>` / `disable <id>` | `ptero.addon.manage` |
| `reload [id\|all]` | `ptero.addon.manage` |
| `help` | `ptero.addon` |

`ptero.reload` grants every action. See [Addons](addons.md) for details.

## Addon commands

Loaded addons can register their own subcommands under `/ptero`, each with its own permission. `/ptero` with no arguments lists them. One example is `/ptero audit` from the move-history addon. It keeps a persistent move and connect log, unlike the in-memory `/ptero history`:

- `/ptero audit [player|uuid]` needs `vpp.audit.use`
- `/ptero audit clear <player|uuid>` and `/ptero audit export [player|uuid]` also need `vpp.audit.manage` or `ptero.history`
- The aliases are `/ptero moveaudit` and `/ptero phistory`.
