---
title: Lobbies & Limbo
sidebar: true
---
# Lobbies and limbo

Lobbies are your hub servers. Limbos are small waiting rooms where players sit while their target server boots. VPP can pick between several of each, keep a minimum number of lobbies running, and start or stop extra lobbies as load changes.

VPP does not choose the server for a player's first join. Velocity's `try` list in `velocity.toml` still does that.

## Quick start

1. Pick the Velocity server names for your hubs and waiting rooms.
2. Edit `plugins/velocity-ptero-power/config.yml`:

```yaml
lobbyBalancer:
  lobbies:
    - lobby
    - hub
  limbos:
    - limbo
  strategy: "ROUND_ROBIN"
  minOnline: 1
  autoScaleEnabled: true
  sendToLimboOnStart: true   # defaults to true when limbos is not empty
  forcedHostOfflineBehavior: LOBBY_OR_LIMBO
```

3. Add every lobby under `servers:` with its panel `id`. VPP ignores lobbies that are not managed. Limbos can be managed or just registered in `velocity.toml`. VPP can only start the managed ones.
4. Restart the proxy or run `/ptero reload`.

::: tip Keep limbo running
Add the limbo to `alwaysOnline` so the waiting room does not need a cold start of its own.
:::

::: tip Start simple
Begin with `ROUND_ROBIN` and `minOnline: 1`, and tune later.
:::

## How the holding server is chosen

*   **Waiting for a server to start** (`sendToLimboOnStart` on): a reachable limbo first, then a reachable lobby.
*   **Forced-host join with `sendToLimboOnStart` off:** `forcedHostOfflineBehavior` decides. `LOBBY_OR_LIMBO` tries a lobby first, then a limbo. `LIMBO_ONLY` tries only limbos. `DISCONNECT` disconnects the player.
*   **Server in maintenance:** a reachable limbo, or the connection is denied.

With `sendToLimboOnStart` on, forced-host joins use the limbo-first order too, whatever `forcedHostOfflineBehavior` says. VPP treats any connection without a previous server as a forced-host join, including the first server from the `try` list.

If no holding server is found, a player who was switching servers stays where they are and waits in the queue. A player who was joining the proxy is disconnected with a message saying the server is starting.

A managed lobby or limbo counts as reachable when VPP's online check passes. An unmanaged limbo counts as reachable whenever it is registered in Velocity.

### Strategies

*   `ROUND_ROBIN` takes the next server in the list each time.
*   `LEAST_PLAYERS` picks the server with the fewest players.
*   `LEAST_CPU` picks the managed server with the lowest CPU usage from the panel, which costs one resource request per candidate (cached for `resourceCacheSeconds`). If no CPU data is available, it falls back to `LEAST_PLAYERS`.

## While a target starts

*   A player who runs `/server` to an offline managed server goes to a limbo (or a lobby if no limbo is reachable) when `sendToLimboOnStart` is on.
*   A player who is already on the limbo stays there and stays in the queue.
*   A forced-host join to an offline server follows the rules in the previous section.
*   Once the server is online, VPP waits `startupJoinDelay` seconds, then transfers the queue. If the backend kicks a player with `SERVER_DISCONNECTED` right after the transfer, VPP requeues them (see [Core mechanics](core-mechanics.md)).

## Lobby scaling

Every `healthCheckInterval` seconds VPP checks the lobbies:

*   It starts lobbies until at least `minOnline` are up.
*   With `sendToLimboOnStart` on and no limbo reachable, it starts a limbo.
*   With `autoScaleEnabled: true`, it starts another lobby when players on the online lobbies reach `preStartThresholdPercent` of their combined capacity (`playersPerServer` per lobby). With `LEAST_CPU`, it also starts one when any lobby goes above `cpuScaleUpThreshold`. `maxOnline` caps the number of lobbies (0 means no cap).
*   If a lobby VPP started is not up after `startFailureFallbackSeconds`, VPP leaves it alone for `startFailureCooldownSeconds` and starts a different lobby.
*   With `scaleDownEnabled: true`, VPP stops a lobby above `minOnline` once it has been empty for `scaleDownIdleSeconds`. It stops one lobby per check.

`lobbiesToUse` limits all of this to the first N lobbies in the list (0 uses all of them).

::: info Managed and unmanaged
*   A managed server is listed under `servers:` with a panel ID. VPP can start and stop it.
*   An unmanaged server is only in `velocity.toml`. VPP can send players to it as a limbo but cannot start it, and does not use it as a lobby.
:::

## Example layouts

::: details Two lobbies, one limbo
```yaml
lobbyBalancer:
  lobbies:
    - lobby
    - hub
  limbos:
    - limbo
  strategy: "ROUND_ROBIN"
  minOnline: 1
  sendToLimboOnStart: true
```
:::

::: details Many lobbies with scaling
```yaml
lobbyBalancer:
  lobbies:
    - lobby-1
    - lobby-2
    - lobby-3
  lobbiesToUse: 0   # 0 = consider all listed
  strategy: "LEAST_PLAYERS"
  playersPerServer: 80
  minOnline: 1
  maxOnline: 0      # 0 = no hard cap
  autoScaleEnabled: true
```
:::

## Common questions

<details>
  <summary><b>Do lobbies need to be under servers: too?</b></summary>
  Yes. VPP skips any lobby that is not listed under <code>servers:</code> with a panel ID. Limbos do not need to be, as long as they are registered in Velocity.
</details>

<details>
  <summary><b>Which strategy should I use?</b></summary>
  Start with <code>ROUND_ROBIN</code>. If player counts end up uneven, switch to <code>LEAST_PLAYERS</code>. If CPU load matters more than player count, use <code>LEAST_CPU</code>.
</details>

<details>
  <summary><b>What happens when every lobby is down?</b></summary>
  For a player waiting on a start, VPP tries the limbos first anyway. If no lobby or limbo is reachable, a player who was switching stays on their current server and waits, and a player who was joining the proxy is disconnected.
</details>

<details>
  <summary><b>Do lobbies and limbos count toward maxOnlineServers?</b></summary>
  Not by default. Set <code>countLobbiesInMaxOnline</code> or <code>countLimbosInMaxOnline</code> to <code>true</code> to count them.
</details>

<details>
  <summary><b>What is the default forcedHostOfflineBehavior?</b></summary>
  The shipped <code>config.yml</code> sets <code>LOBBY_OR_LIMBO</code>. If you remove the key from a <code>lobbyBalancer</code> section, VPP uses <code>DISCONNECT</code>.
</details>

## Limbo checklist

1. `lobbyBalancer.limbos` lists names that exist in `velocity.toml`.
2. `sendToLimboOnStart: true`, or leave the key out. It turns on when limbos are listed.
3. Optional: `forcedHostOfflineBehavior: LOBBY_OR_LIMBO` and `alwaysOnline: [limbo]`.
4. Run `/ptero reload`, then join an offline managed server. You should land on the limbo and move to the target after `startupJoinDelay`.

All `lobbyBalancer` options are listed in [Configuration](configuration.md).
