---
title: Lobbies & Limbo (Simple Guide)
sidebar: true
---
# Multi‑Lobby and Limbo – Simple Setup 🧭

This page explains the new multi‑lobby and multi‑limbo support in a simple, practical way. If you want the full list of options, see the lobbyBalancer section in the Configuration Guide.

## What is it?

- Lobbies are the places where players wait or gather (like hub or lobby).
- Limbo is a light “waiting room” while a target server starts.
- You can now list several lobbies and several limbos.
- The plugin can choose the best one automatically and even start more lobbies when needed.

## Quick Start (recommended)

1. Pick the Velocity names of your lobby servers (for example lobby and hub).
2. Open plugins/VelocityPteroPower/config.yml.
3. Find the lobbyBalancer section and set something like:

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
```

4. If you want the plugin to be able to start a lobby when needed, make sure each lobby is also listed under the top‑level servers: section with a valid id from your panel.
5. Restart or /ptero reload.

:::: tip Keep it simple
Start with just strategy: "ROUND_ROBIN" and minOnline: 1. You can always tune later.
::::

## How the choice is made

- The plugin prefers a lobby (from lobbies) when possible.
- If no lobby is usable, it will try a limbo (from limbos).
- If no limbo is usable, players may be disconnected or kept where they are until the target starts.

### Strategies
- ROUND_ROBIN: cycles through your options one by one (easy and fair).
- LEAST_PLAYERS: picks the one with the fewest players.
- LEAST_CPU: picks the one with the lowest CPU use (needs panel resources). Use this if you want smarter picks.

## While your target server is starting

- If you try to join a server that is currently starting, and you are already on a limbo, you will remain there.
- The player who initiated the server start gets a clearer message that they started the server and should wait; others are told the server is already starting.
- You are automatically queued: VPP will move you to the target server as soon as it is online and past the configured join delay.

## Auto‑start (optional)

If autoScaleEnabled: true and lobbies are defined under servers: with IDs, the plugin can start extra lobbies when:
- You set a minimum to keep online (minOnline), or
- Current lobbies begin to fill up (playersPerServer) — by default VPP starts a new lobby a bit early (around 80% of total lobby capacity) so players don’t hit a bottleneck. You can change this with preStartThresholdPercent.
- With LEAST_CPU, a lobby’s CPU goes above cpuScaleUpThreshold.

If a lobby start doesn’t succeed quickly, VPP will try a different lobby automatically (and will wait a short cooldown before retrying the failed one). You can adjust this with startFailureFallbackSeconds and startFailureCooldownSeconds.

:::: info Managed vs. unmanaged lobbies
- Managed = listed under servers: with the correct panel id → VPP can start/stop it.
- Unmanaged = only in Velocity but not in servers: → VPP can use it if it’s already online, but can’t start it.
::::

## Example layouts

:::: details Two lobbies, one limbo (simple)
```yaml
lobbyBalancer:
  lobbies:
    - lobby
    - hub
  limbos:
    - limbo
  strategy: "ROUND_ROBIN"
  minOnline: 1
```
::::

:::: details Many lobbies with scaling
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
::::

## Common questions

<details>
  <summary><b>Do I need to add my lobbies under servers: too?</b></summary>
  Add them under servers: if you want VPP to auto‑start or stop them. If you only add them to lobbies: and not servers:, VPP can still use them when they’re already online.
</details>

<details>
  <summary><b>What should I choose for strategy?</b></summary>
  Start with ROUND_ROBIN. If you notice uneven player counts, try LEAST_PLAYERS. If you care about CPU usage and have the panel resource endpoint available, try LEAST_CPU.
</details>

<details>
  <summary><b>What happens if all lobbies are down?</b></summary>
  VPP will try your configured limbos list. If none is usable, players may be disconnected or kept where they are until the target starts.
</details>

<details>
  <summary><b>How many lobbies should I keep online?</b></summary>
  For most setups, minOnline: 1 is enough. Increase if your network is busy and you want instant joins.
</details>

<details>
  <summary><b>Do lobbies/limbos count towards maxOnlineServers?</b></summary>
  By default, they do not. You can change this in config.yml:
  <ul>
    <li><code>countLobbiesInMaxOnline</code> — include lobbies in the cap when true</li>
    <li><code>countLimbosInMaxOnline</code> — include limbos when true</li>
  </ul>
</details>

## Helpful tips

- Keep your lobby servers lightweight for fast start times.
- Set alwaysOnline: [limbo] if you want to ensure a limbo is always ready.
- Use loggerLevel: 10 temporarily to see detailed balancer logs while testing.

---

## Limbo setup checklist ✅

Use this quick checklist to verify your configuration matches the current Limbo functionality:

1) In config.yml:
   - lobbyBalancer.limbos: ["limbo"]
   - lobbyBalancer.sendToLimboOnStart: true
   - (optional) lobbyBalancer.forcedHostOfflineBehavior: "LOBBY_OR_LIMBO"
   - (optional) alwaysOnline: ["limbo"]
   - (optional) moveHistory.enabled: true
2) Restart the proxy or run /ptero reload.
3) Try connecting to a managed server that’s offline or starting:
   - You should be redirected to the configured limbo and receive a message.
   - You will later be auto-connected when the server is ready (after its join delay).
4) Runtime checks:
   - /ptero info `<server>` now includes "Started by: `<player>`" when VPP initiated a start due to a player.
   - /ptero history [player] shows a pretty in-memory move history when enabled.

If your network uses forced hosts, you may prefer forcedHostOfflineBehavior: "LOBBY_OR_LIMBO" to keep players online while targets start.

---

## Limbo records: what VPP tracks 🗂️

VPP maintains an in-memory record of every player currently in a limbo and why they are there. This helps diagnose edge cases where a player might remain in limbo unexpectedly.

- Data model (PlayerLimboRecord):
  - playerId (UUID)
  - playerName (string)
  - limboServer (string)
  - reason (enum LimboReason): SERVER_START_WAIT, SELF_MOVE, OTHER
  - context (string, optional): when reason = SERVER_START_WAIT, this is the name of the server the player is waiting for
  - timestamp (epoch millis)

- When records are created:
  - When VPP redirects a player to a limbo because their requested server is starting (reason=SERVER_START_WAIT, context=`<target>`).
  - When a player manually joins a limbo server with no prior record (reason=SELF_MOVE).

- When records are cleared:
  - When the player leaves a limbo for another server.
  - When the player disconnects.
  - During periodic sweeps, if the player is no longer on a configured limbo server.

- Persistence: in-memory only; records do not survive a proxy restart.

- Programmatic access (for developers):
  - plugin.getLimboTrackerService().get(playerUuid) → Optional`<PlayerLimboRecord>`
  - .all() → list of current records
  - .sweepNow() → run the cleanup sweep immediately

Tip: Combine limbo records with move history (optional) for a complete picture during live operations.
