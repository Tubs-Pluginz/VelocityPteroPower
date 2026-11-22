---
title: VPP core mechanics
---

# Core mechanics

How VPP decides when to start a server, how long to wait, when to transfer players, and when to stop it again.

## Automatic server start

When a player connects or switches to a server, VPP runs these checks in order:

1. **Addons.** A routing provider or a listener on the pre-connect events can send the player to another server or cancel the connection. If an addon does either, VPP stops here.
2. **Managed or not.** The server name must be listed under `servers:` in `config.yml` and match the name in `velocity.toml`. VPP lets connections to unmanaged servers through untouched. With `serverNotFoundMessage: true` the player also gets a message.
3. **Maintenance.** If built-in maintenance or an external provider (kennytv/Maintenance, or an addon) blocks the server, VPP sends the player to a limbo if one is reachable and denies the connection otherwise. It does not start the server.
4. **Whitelist.** With `whitelist: true` on the server, a player who is not on its `whitelist.json` is denied. A player joining the proxy gets disconnected, a player switching servers gets a message.
5. **Cooldown.** After a player triggers a cold start, VPP denies their server switches to managed servers for `playerStartCooldown` seconds. This does not apply to the first join, to VPP's own transfers, or to `/ptero start`.
6. **Already starting.** If the server is starting, settling, or has players queued for it, VPP skips the online check and puts the player in the queue.
7. **Online check.** Otherwise VPP checks the server with `serverStatusCheckMethod`. The default `HYBRID` needs the panel to report "running" and a Velocity ping to succeed. Live state from the panel websocket and a short ping cache keep this check cheap.
8. **Start password.** If the server is offline and has a `startPassword`, the player must run `/ptero unlock <server> <password>` first, unless they have `ptero.password.bypass`.
9. **Rate limit.** If the panel has no requests left, VPP tells the player and denies the connection.
10. **Capacity.** If `maxOnlineServers` is set and reached, VPP queues the player until a slot frees up (`queueWhenMaxOnline: true`, the default) or denies them. `maxOnlineReservations` keeps slots free for always-online servers, lobbies and limbos. Players with `ptero.maxcap.bypass` skip the cap when `maxOnlineAllowBypass` is on.
11. **Cold start.** VPP marks the server as starting and sends `START` on the player priority lane. It also starts any offline members of the server's `group` and its `linkedServers`, and schedules the check for `idleStartShutdownTime`.
12. **Holding.** With `sendToLimboOnStart` on, VPP moves the player to a limbo, or to a lobby if no limbo is reachable. Without a holding server, a player who was switching stays where they are and waits in the queue. A player joining through a forced host is handled by `forcedHostOfflineBehavior`. See [Lobbies and limbo](lobbies-and-limbo.md).
13. **Watcher.** After `startupInitialCheckDelay` seconds, VPP checks the server every `startupPollInterval` seconds (a server can override this). Once it is online, VPP waits `startupJoinDelay` seconds, checks again, and transfers everyone in the queue.
14. **Soft requeue.** If the backend kicks a transferred player with `SERVER_DISCONNECTED` and no kick message within `softRequeueGraceSeconds`, VPP puts them back in the queue and tries again, up to `softRequeueMaxAttempts` times. A kick that carries a message (a ban, DiscordSRV linking, and so on) is shown to the player and not retried.
15. **Timeout.** If the server is still not ready `startupInitialCheckDelay` + `startupTimeoutSeconds` seconds after the first queued player (the timeout is at least 30), VPP tells the waiting players and clears the queue. With `startupTimeoutPolicy: CANCEL` it also clears the starting state. With `KEEP_STARTING` the server stays marked as starting, so new players queue without another start signal.

While players wait, the action bar shows their queue position and the time left.

::: tip Poll interval and join delay
`startupPollInterval` is how often VPP asks the server if it is up.
`startupJoinDelay` is how long VPP waits after the server is up before it moves players. They are separate settings.
:::

A queued player who moves to a server that is not a lobby, a limbo, or the target loses their place. A player who disconnects keeps their queue entry for `persistentQueueTtlSeconds` (default 900). When they reconnect and land on a lobby or limbo, VPP resumes the wait, after checking the whitelist, maintenance and start password again. Turn this off with `persistentQueueEnabled: false`.

Idle shutdown does not run for a server while players are queued for it, while it is settling, or while a start is in progress.

## Automatic shutdown

*   **Per-server `timeout`.** When the last player leaves, VPP waits this many seconds and stops the server if it is still empty. `-1` never stops it, `0` stops it right away. A player joining cancels the countdown. A server without a `timeout` key never stops.
*   **`idleStartShutdownTime`.** If VPP started a server and nobody joined it within this many seconds, VPP stops it. Servers with `timeout: -1`, lobbies and limbos are skipped. `-1` turns the check off.
*   **Groups.** Servers in the same `group` or `serverGroups` entry, or linked with `linkedServers`, stay up while any of them has players.
*   **Retries.** After a stop signal, VPP waits `shutdownRetryDelay` seconds. If the server is still online and empty, it sends the stop again, up to `shutdownRetries` times.
*   **`alwaysOnline`.** These servers never get an idle shutdown. Every `alwaysOnlineCheckInterval` seconds VPP starts any of them that are offline, unless maintenance blocks them.
*   **Idle sweep.** Every `idleShutdownCheckInterval` seconds VPP looks for empty online servers that have no shutdown scheduled and schedules one. This catches missed events.
*   **Proxy shutdown.** Servers listed in `lifecycle.shutdownOnProxyExit` (or `all`) are stopped when the proxy shuts down, including always-online ones.

## Limbo and lobby holding

See [Lobbies and limbo](lobbies-and-limbo.md). The short version: list your limbos, leave `sendToLimboOnStart` out (it turns on when limbos are listed), and put the limbo in `alwaysOnline` if you can.

## Rate limits

VPP subtracts one from its count of remaining requests before each panel call and adds it back if the request fails before reaching the panel. When the count reaches 0, VPP waits for the reset time from the panel's headers. If the panel sends no reset header, player requests try again after `rateLimitProbeFallbackSeconds`.

Panel calls run in four priority lanes: player starts, lifecycle (always-online and shutdowns), the lobby balancer, and background work. Background prefetching pauses when the remaining count drops to `rateLimitBackgroundPauseThreshold` or when any player is waiting for a server. MC Server Soft has no rate limit tracking.

## Whitelist

With `whitelist: true` on a server, VPP fetches that server's `whitelist.json` from the panel (Pterodactyl, Pelican or Calagopus) every `whitelistCheckInterval` minutes. Players not on it cannot connect to that server through VPP, whether it is online or not. MC Server Soft cannot serve files, so VPP turns whitelist checks off on that panel. `ptero.bypass` (with `whitelistAllowBypass: true`) only skips VPP's check. The backend server still enforces its own whitelist.

## Always-online and capacity

`alwaysOnline` lists servers VPP keeps running, checked every `alwaysOnlineCheckInterval` seconds. `maxOnlineServers` limits how many managed servers can be online at once. Servers in `maxOnlineExempt` are not counted, and lobbies and limbos are not counted unless you set `countLobbiesInMaxOnline` or `countLimbosInMaxOnline`. With `queueWhenMaxOnline: true`, players wait in the queue for a free slot instead of being denied.
