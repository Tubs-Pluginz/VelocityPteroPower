---
title: Lobbies & Limbo
sidebar: true
---
# Multi-lobby and limbo 🧭

Lobbies are hubs. Limbos are light waiting rooms while a target boots. VPP can pick among several of each and optionally scale lobbies.

## Quick start

1. Pick Velocity server names for your hubs and waiting rooms.
2. Edit `plugins/VelocityPteroPower/config.yml`:

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
  sendToLimboOnStart: true   # default true once limbos is non-empty
  forcedHostOfflineBehavior: LOBBY_OR_LIMBO
```

3. Put each managed lobby/limbo under `servers:` with a panel `id` if you want VPP to start them. Unmanaged Velocity servers still work when they are already online.
4. Restart or `/ptero reload`.

::: tip Keep limbo alive
Add limbo to `alwaysOnline` so the waiting room is not itself cold-starting.
:::

::: tip Keep it simple
Start with `ROUND_ROBIN` and `minOnline: 1`. Tune later.
:::

## How the choice is made

*   Prefer a usable lobby from `lobbies`.
*   Else try a limbo from `limbos`.
*   Else disconnect or leave the player where they are until the target starts.

### Strategies

*   `ROUND_ROBIN` — rotation
*   `LEAST_PLAYERS` — emptiest
*   `LEAST_CPU` — needs panel resource cache; heavier

## While a target starts

*   Mid-session `/server` to an offline managed server → limbo (or lobby fallback) when `sendToLimboOnStart` is on.
*   Already on limbo → stay put, stay queued.
*   Forced-host join with offline backend → `LOBBY_OR_LIMBO`, `LIMBO_ONLY`, or `DISCONNECT`.
*   After online, VPP waits `startupJoinDelay` (settle), then transfers. Soft-requeue retries early `SERVER_DISCONNECTED` kicks.

## Autoscale (optional)

With `autoScaleEnabled: true`, VPP can start more lobbies for `minOnline`, fill near `preStartThresholdPercent` of capacity, or (LEAST_CPU) high CPU. Failed starts cool down via `startFailureCooldownSeconds`. Extra empty lobbies can scale down with `scaleDownEnabled` / `scaleDownIdleSeconds` toward `minOnline`.

::: info Managed vs unmanaged
*   **Managed** = listed under `servers:` with a panel id → VPP can start/stop it.
*   **Unmanaged** = only in Velocity → usable when already online; VPP cannot start it.
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
  <summary><b>Do I need lobbies under servers: too?</b></summary>
  Add them under <code>servers:</code> if you want VPP to start or stop them. Listed only under <code>lobbies:</code>, they are still usable when already online.
</details>

<details>
  <summary><b>Which strategy?</b></summary>
  Start with <code>ROUND_ROBIN</code>. Uneven counts → <code>LEAST_PLAYERS</code>. Care about CPU and have resource cache → <code>LEAST_CPU</code>.
</details>

<details>
  <summary><b>All lobbies down?</b></summary>
  VPP tries <code>limbos</code>. If none are usable, players may disconnect or wait where they are.
</details>

<details>
  <summary><b>Do lobbies/limbos count toward maxOnlineServers?</b></summary>
  By default no. Flip <code>countLobbiesInMaxOnline</code> / <code>countLimbosInMaxOnline</code> if you want them in the cap.
</details>

## Limbo checklist ✅

1. `lobbyBalancer.limbos` lists real `velocity.toml` names.
2. `sendToLimboOnStart: true` (or leave blank once limbos exist — it defaults on).
3. Optional: `forcedHostOfflineBehavior: LOBBY_OR_LIMBO`, `alwaysOnline: [limbo]`.
4. `/ptero reload`, then join an offline managed server — you should land on limbo and transfer after settle.

Full option list: [Configuration → lobbyBalancer](configuration.md).
