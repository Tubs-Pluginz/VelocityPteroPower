---
title: VPP core mechanics
---

# Core Mechanics Explained 🧠

How VPP decides to start, wait, transfer, and stop.

## Automatic server start 🚀

When a player tries a managed server:

1. **Is it managed?** Name must exist under `servers:` in `config.yml` and match `velocity.toml`.
2. **Whitelist / password / maintenance** — if enabled for that server, those gates run first.
3. **Cooldown** — `playerStartCooldown` blocks rapid player-driven starts (not `/ptero start`).
4. **Already starting?** If the server is in `starting` / settle / has waiters, VPP **skips** a blocking online check on the event thread and sends the player down the queue/limbo path.
5. **Online check** — otherwise uses `serverStatusCheckMethod` (`HYBRID` by default: panel running **and** ping). Fresh panel websocket state and a short ping cache avoid hammering the event thread.
6. **Cold start** — marks the server starting, sends `START` on the player-priority API lane, schedules idle-start shutdown if configured.
7. **Holding** — with limbos (and `sendToLimboOnStart`), players go to limbo, or a lobby if limbo pick fails. Forced-host joins follow `forcedHostOfflineBehavior`.
8. **Watcher** — after `startupInitialCheckDelay`, VPP polls every `startupPollInterval` (per-server override allowed). When online, it waits `startupJoinDelay` (settle), then transfers. Soft-requeue retries if the backend kicks with `SERVER_DISCONNECTED` inside `softRequeueGraceSeconds`.
9. **Timeout** — if still not ready by `startupTimeoutSeconds`, `startupTimeoutPolicy` is `CANCEL` or `KEEP_STARTING`. Waiters see remaining time on the action bar via the queue progress tick.

::: tip Poll vs settle
`startupPollInterval` = how often to ask “are you up yet?”  
`startupJoinDelay` = how long to wait *after* online before moving players. Do not conflate them.
:::

Idle shutdown will not fire while waiters, settle, or an in-flight start exist for that server.

## Automatic shutdown 💤

*   **Per-server `timeout`** — after the last player leaves: `-1` never, `0` ASAP, `>0` after that many seconds (cancelled if someone rejoins).
*   **`idleStartShutdownTime`** — started by VPP but nobody joined.
*   **Retries** — `shutdownRetries` / `shutdownRetryDelay` if the panel still shows online and empty.
*   **`alwaysOnline`** — never scheduled for idle stop; keeper tick restarts them if they die.
*   **Idle sweep** — `idleShutdownCheckInterval` rescans empties as a failsafe.

## Limbo / lobby holding 🏝️

See [Lobbies & limbo](lobbies-and-limbo.md). Short version: list limbos, leave `sendToLimboOnStart` alone (defaults on when limbos exist), keep limbo in `alwaysOnline` if you can.

## Rate limits 🚦

Panel clients decrement an optimistic remaining counter before send and restore on transport failure. When remaining hits 0, VPP waits for reset headers (or a probe fallback) and prefers player starts over background prefetch (`rateLimitBackgroundPauseThreshold`). Power starts for players go through the PLAYER scheduler lane so prefetch cannot starve them.

## Whitelist ✅

If `whitelist: true` on a server, VPP caches `whitelist.json` from the panel (Pterodactyl / Pelican / Calagopus). MC Server Soft cannot fetch files. `ptero.bypass` only skips VPP’s gate — the backend whitelist still applies.

## Always-online & capacity

`alwaysOnline` + interval. `maxOnlineServers` caps concurrent managed onlines; lobbies/limbos can be excluded; `queueWhenMaxOnline` can queue instead of hard deny.
