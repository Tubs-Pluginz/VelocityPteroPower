---
title: VPP faq
---
# Frequently asked questions

Answers to common questions about VelocityPteroPower.

::: tip Before you ask
Update the jar, check `config.yml` for typos, then set `loggerLevel: 10` and read the console. The full walkthrough is in [Troubleshooting](troubleshooting.md).
:::

## 0.9.6

<details>
  <summary><b>Q: The plugin will not load, or it complains about Java or Velocity.</b></summary>

  0.9.6 needs **Velocity 4.3+** and **Java 25** on the proxy. Upgrade the JDK and Velocity, then install the jar again.
</details>

<details>
  <summary><b>Q: What is HYBRID?</b></summary>

  It is the default ready check: the panel must report the server as running and a Velocity ping must succeed. Switch to `VELOCITY_PING` or `PANEL_API` only if you have a specific reason.
</details>

<details>
  <summary><b>Q: Players join too early or get kicked right after the transfer.</b></summary>

  Raise the server's `startupJoinDelay`, the wait after the server is online. `startupPollInterval` only controls how often VPP checks. If the backend kicks a player with `SERVER_DISCONNECTED` and no message within `softRequeueGraceSeconds`, VPP requeues them. Modpacks often need a higher `startupTimeoutSeconds` as well.
</details>

<details>
  <summary><b>Q: How do I install the official addons?</b></summary>

  `/ptero addon install <id>` downloads them from the GitHub nightly release. The Modrinth pages are for downloading in a browser. A jar you copy into `addons/` yourself also shows as installed in `browse`. See [Addons](addons.md).
</details>

<details>
  <summary><b>Q: Does Calagopus work?</b></summary>

  Yes. Use a client key that starts with `c7sp_` and give it the start, stop and restart permissions, plus file read access if you use the whitelist.
</details>

## General and startup

<details>
  <summary><b>Q: My server does not start when a player tries to connect. What should I check?</b></summary>

  *   **Server entry in `config.yml`**
      *   The name under `servers:` must match the name in `velocity.toml` exactly, including case.
      *   `id:` must be the server ID from your panel. On Pterodactyl it is the short ID from the server URL (for example `a1b2c3d4`). On Pelican it is the short UUID from Server Details. On Calagopus it is the full server UUID.
  *   **Panel section (`pterodactyl:` in `config.yml`)**
      *   `url:` is the full URL of your panel, for example `https://panel.example.com/`.
      *   `apiKey:` is a client API key (`ptlc_`, `plcn_` or `pacc_`, or `c7sp_`). Application keys (`ptla_`, `peli_`) do not work.
      *   The key has power control permission, and file read permission if you use the whitelist.
  *   **Console log**
      *   Set `loggerLevel: 10`.
      *   Look for the "Initialization Failed" block on startup, and for VPP lines when the player connects.
  *   **Rate limits**
      *   Set `printRateLimit: true`.
      *   Look for "Rate limit reached" warnings.
  *   **`serverStatusCheckMethod`**
      *   The default `HYBRID` needs the panel to report "running" and a Velocity ping to succeed.
      *   With `VELOCITY_PING`, check the address in `velocity.toml` and `pingTimeout`.
      *   With `PANEL_API`, the panel must respond.
  *   **Whitelist**
      *   With `whitelist: true` on the server, the player must be in its `whitelist.json`, or have `ptero.bypass` while `whitelistAllowBypass: true` is set.
      *   MC Server Soft does not support whitelist fetching.
  *   **Maintenance and start password**
      *   VPP does not start a server that is in maintenance (`/ptero maintenance`, or kennytv/Maintenance).
      *   A server with a `startPassword` needs `/ptero unlock <server> <password>` before it starts.
</details>

<details>
  <summary><b>Q: What is the difference between <code>timeout</code> and <code>idleStartShutdownTime</code>?</b></summary>

  *   `timeout` is set per server under `servers:`. It applies to a server that had players and is now empty. The countdown starts when the last player leaves.
  *   `idleStartShutdownTime` is global. It applies to a server that VPP started (for a player or a command) that nobody joined within that many seconds. It does not apply to lobbies, limbos, or servers with `timeout: -1`.
</details>

<details>
  <summary><b>Q: Why is there a wait after the server looks online?</b></summary>

  That is `startupJoinDelay`, the time VPP waits after the ready check passes so backend plugins can finish loading. Polling uses `startupPollInterval`, not the join delay.
</details>

<details>
  <summary><b>Q: Is <code>idleShutdownCheckInterval</code> in minutes or seconds?</b></summary>

  Seconds. Set `idleShutdownCheckInterval: 300` for five minutes. `0` or a negative value turns the sweep off.
</details>

## Shutdown

<details>
  <summary><b>Q: Servers do not stop when they become empty.</b></summary>

  *   **`timeout`:** check that the server's `timeout:` is a positive number of seconds.
      *   `-1`, or no `timeout` key at all, turns automatic shutdown off for that server.
      *   `0` sends the stop right away, though the panel still needs time to shut it down.
  *   **`alwaysOnline`:** servers in this list are never stopped for being idle.
  *   **Groups:** a server in a `group`, `serverGroups` entry or `linkedServers` list stays up while any of its peers has players.
  *   **Console log:** with debug logging on, look for "Scheduling server shutdown for", "Shutdown cancelled for server:" or "Failed to shutdown server:".
  *   **Player count:** check that Velocity reports the server as empty. Rarely, a player stays attached to the proxy after leaving.
  *   **Rate limits:** a rate-limited VPP cannot send the stop. Check with `printRateLimit: true`.
  *   **Panel and backend:** check that the panel API responds, and read the backend console on the panel to see if the server hangs on shutdown.
  *   **Retries:** VPP checks again after `shutdownRetryDelay` seconds and resends the stop up to `shutdownRetries` times. When all retries fail, it logs an error.
</details>

## Lobbies and limbo

<details>
  <summary><b>Q: Limbo does not work the way I expect.</b></summary>

  *   **Config:** list at least one limbo under `lobbyBalancer.limbos` that is registered in `velocity.toml`.
  *   **Limbo status:** check that the limbo is online and that `/server <limbo>` works.
  *   **Managed limbo:** if VPP also manages the limbo, give it `timeout: -1` or put it in `alwaysOnline` so it stays up.
  *   **Console log:** look for "Redirecting player ... to holding server" lines, or errors about picking or connecting to a limbo.
</details>

<details>
  <summary><b>Q: Can I use more than one lobby or limbo?</b></summary>
  Yes. Add the Velocity server names under <code>lobbyBalancer.lobbies</code> and <code>lobbyBalancer.limbos</code> in <code>config.yml</code>. VPP picks one based on the strategy you set.
</details>

<details>
  <summary><b>Q: Do lobbies need to be in the top-level <code>servers:</code> list?</b></summary>
  Yes. VPP skips lobbies that are not under <code>servers:</code> with a panel ID. Limbos can be unmanaged as long as they are registered in Velocity, but VPP cannot start them.
</details>

<details>
  <summary><b>Q: Which strategy should I pick?</b></summary>
  Start with <b>ROUND_ROBIN</b>. If player counts end up uneven, try <b>LEAST_PLAYERS</b>. To send players to the lobby with the lowest CPU usage, use <b>LEAST_CPU</b>. It reads resource usage from the panel.
</details>

<details>
  <summary><b>Q: What happens if all lobbies are down?</b></summary>
  VPP tries the servers in <code>lobbyBalancer.limbos</code>. If none is reachable, a player who was switching stays on their current server until the target starts, and a player who was joining the proxy is disconnected.
</details>

<details>
  <summary><b>Q: How does VPP start lobbies on its own?</b></summary>
  VPP keeps at least <code>minOnline</code> lobbies running. With <code>autoScaleEnabled: true</code>, it starts another lobby when players reach <code>preStartThresholdPercent</code> of the online lobbies' capacity (<code>playersPerServer</code> each), or, with <b>LEAST_CPU</b>, when a lobby's CPU usage goes above <code>cpuScaleUpThreshold</code>. The Lobbies and limbo page has the details.
</details>

## API and whitelist

<details>
  <summary><b>Q: I see rate limit warnings in the console.</b></summary>

  *   Set `printRateLimit: true` in `config.yml`. VPP then logs the limit, remaining requests and reset time it gets from the panel.
  *   Check whether other plugins, scripts or services use the same panel API key or account. They count toward the same limit.
  *   `apiThreads` only sets how many requests can run at once. VPP already scales it to about twice the number of managed servers (4 to 64). A higher value lets bursts use up the limit faster.
  *   Your panel's documentation lists its rate limits.
</details>

<details>
  <summary><b>Q: The whitelist does not work, or <code>/ptero whitelistReload</code> does nothing or shows errors.</b></summary>

  *   **Server config:** check that `whitelist: true` is set on the server under `servers:`.
  *   ::: danger MC Server Soft
      Whitelist fetching does **not** work when VPP detects MC Server Soft, because its API has no file access. VPP logs a warning on startup and skips whitelist checks.
      :::
  *   **Key permissions:** the client API key (`ptlc_`, `plcn_` or `pacc_`, or `c7sp_`) must be allowed to read files on the server, so VPP can read `whitelist.json`.
  *   **File location:** `whitelist.json` must be in the root directory of the Minecraft server on the panel.
  *   **Console log (debug on):** look for:
      *   "Fetching whitelist for server..."
      *   "Updated whitelist for server..."
      *   "Failed to fetch whitelist for server..." or "Error parsing whitelist JSON..."
  *   **`whitelistCheckInterval`:** for automatic refreshes, set it to a positive number of minutes.
</details>

<details>
  <summary><b>Q: Why does /ptero info sometimes show the memory limit as "unlimited"?</b></summary>

  *   VPP shows the limit your panel reports:
      *   **Pterodactyl:** `memory_limit_bytes` from `/resources`. If that is missing or `0`, VPP uses `limits.memory` (MiB) from `/servers/{id}`.
      *   **Pelican:** `attributes.limits.memory` (MiB).
      *   **Calagopus:** `memory_limit_bytes` from the resources response.
      *   **MC Server Soft:** `latest.memoryLimit` (MiB).
  *   A limit of `0` means unlimited, and VPP shows "unlimited". Set a memory limit on the panel to see a number.
</details>

<details>
  <summary><b>Q: Can VPP manage servers on different nodes, or on different panels?</b></summary>

   *  **Several nodes under one panel: yes.** VPP talks to one panel, the one set in `pterodactyl.url`. That panel can run servers on many machines (nodes running Pterodactyl Wings or the Pelican daemon). VPP can manage any server on that panel, whichever node it runs on.

   *  **Several separate panels: no, not from one VPP instance.** One VPP instance talks to one panel. To manage servers on two separate panel installations (for example `panel1.example.com` and `panel2.example.com`), you would need two proxies, each with its own VPP pointed at its own panel.
</details>

---

If your question is not answered here, check the [Troubleshooting guide](troubleshooting.md) or ask on the [Discord](https://discord.pluginz.dev).
