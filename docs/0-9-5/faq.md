---
title: VPP faq
---
# Frequently Asked Questions (FAQ) ❓

Here are answers to common questions and issues encountered with VelocityPteroPower.

::: tip Before You Ask
Many common issues can be resolved by:
1.  Ensuring you have the **latest version** of VelocityPteroPower.
2.  Carefully **checking your `config.yml`** for typos or misconfigurations.
3.  Enabling **debug logging** (`loggerLevel: 10` in `config.yml`) and reviewing the console output.
Refer to the [Troubleshooting Guide](troubleshooting.md) for more detailed steps.
:::

## General & Startup

<details>
  <summary><b>Q: My server isn't starting automatically when a player tries to connect. What should I check?</b></summary>

  *   **VPP Configuration (`config.yml`):**
      *   Is the server name under `servers:` an **exact match** (case-sensitive) to the name in your `velocity.toml`?
      *   Is the `id:` for that server the correct **short alphanumeric ID** from your panel (e.g., `a1b2c3d4`), not a long UUID?
  *   **Panel API Connection (`pterodactyl:` section in `config.yml`):**
      *   Is `url:` the full, correct URL to your panel (e.g., `https://panel.example.com/`)?
      *   Is `apiKey:` a **Client API Key** (starts `ptlc_` or `plcn_`)? Application API Keys (`ptla_`, `peli_`) are **not supported**.
      *   Does the API key have necessary permissions on the panel (power control, file read for whitelist)?
  *   **Velocity Console Logs:**
      *   Enable debug logging (`loggerLevel: 10`).
      *   Look for errors from `[VPP]` on startup (e.g., "Invalid API Key") or when a player attempts connection.
  *   **Panel API Rate Limits:**
      *   Set `printRateLimit: true` in `config.yml`.
      *   Are you seeing "Rate limit reached" messages?
  *   **`serverStatusCheckMethod`:**
      *   If `VELOCITY_PING`: Is the backend server's IP/port in `velocity.toml` correct and reachable for pings from the proxy? Is `pingTimeout` adequate?
      *   If `PANEL_API`: This relies on the panel API being responsive.
  *   **Whitelist Feature:**
      *   If `whitelist: true` for the server: Is the player on the server's `whitelist.json`? Or do they have `ptero.bypass` permission (and `whitelistAllowBypass: true` is set)?
      *   Remember, whitelist fetching is not supported for MC Server Soft.
</details>

<details>
  <summary><b>Q: What's the difference between `timeout` (per-server) and `idleStartShutdownTime` (global)?</b></summary>

  *   **`timeout`:** This setting is configured *per-server* in the `servers:` section. It applies when an **active server (that had players)** becomes empty. The countdown for shutdown begins after the last player leaves.
  *   **`idleStartShutdownTime`:** This is a *global* setting. It applies if a server is **started by VPP** (e.g., due to a player connection attempt or a command) but **no player successfully joins it** within this specified duration. It's a safeguard to shut down servers that were initiated but never actually used.
</details>

<details>
  <summary><b>Q: Why does it sometimes take a moment for me to connect to a server after VPP starts it?</b></summary>
  This is likely due to the `startupJoinDelay` setting, configured per-server in `config.yml`. This delay is intentionally added *after* VPP detects the server as "online" (via `serverStatusCheckMethod`). It gives the backend Minecraft server and its plugins additional time to fully initialize and load before VPP attempts to transfer the player. Without this, players might be connected too early, before the server is truly ready to accept them.
</details>

<details>
  <summary><b>Q: Is <code>idleShutdownCheckInterval</code> in minutes or seconds?</b></summary>

  *   **Seconds.** The automatic idle shutdown sweep uses seconds for its interval.
  *   In code, it is scheduled with `TimeUnit.SECONDS`, so set `idleShutdownCheckInterval: 300` for 5 minutes, for example.
  *   Set to `0` or a negative value to disable the sweep.
</details>

## Shutdown Issues

<details>
  <summary><b>Q: Servers aren't stopping automatically when they become empty.</b></summary>

  *   **`timeout` Setting:** For the specific server in `config.yml`, is `timeout:` set to a positive number (seconds)?
      *   If `timeout: -1`, automatic shutdown for that server is disabled.
      *   If `timeout: 0`, it should attempt to stop immediately (though panel processing takes time).
  *   **Velocity Console Logs:** Enable debug logging. Look for messages like "Scheduling server shutdown for...", "Shutdown cancelled for server...", or "Failed to shutdown server...".
  *   **Player Count Accuracy:** Is Velocity accurately reporting the server as empty? Rarely, a player might be "ghosted" on the proxy.
  *   **Panel API Rate Limits:** If VPP is rate-limited, it cannot send the stop signal. Check with `printRateLimit: true`.
  *   **Panel/Server Issues:**
      *   Is the panel API responsive?
      *   Check the console of the backend Minecraft server on the panel itself. Is it stuck or refusing to shut down cleanly?
  *   **`shutdownRetries` & `shutdownRetryDelay`:** VPP will attempt to confirm shutdown and retry. If these retries fail, it will log an error.
</details>

## Limbo

<details>
  <summary><b>Q: The limbo feature isn't working as expected.</b></summary>

  *   **Configuration (`config.yml`):**
      *   Ensure at least one valid limbo is listed under `lobbyBalancer.limbos` and registered in your `velocity.toml`.
  *   **Limbo Status:**
      *   Is a limbo server actually online and accessible via Velocity?
      *   If a limbo is *also* managed by VPP, ensure its own `timeout` and startup settings in `config.yml` are appropriate (e.g., `-1` to keep it online).
  *   **Velocity Console Logs:** Look for messages from VPP about "Redirecting player to limbo..." or any errors related to finding or connecting to a limbo server.
</details>

## Lobbies & Limbo

<details>
  <summary><b>Q: Can I use more than one lobby or limbo?</b></summary>
  Yes. Add your Velocity server names under <code>lobbyBalancer.lobbies</code> and/or <code>lobbyBalancer.limbos</code> in <code>config.yml</code>. The plugin will pick one automatically based on the strategy you choose.
</details>

<details>
  <summary><b>Q: Do lobbies need to be in the top-level <code>servers:</code> list?</b></summary>
  Only if you want VPP to auto-start them. If a lobby is not in <code>servers:</code>, VPP can still use it while it is already online, but won’t be able to start it.
</details>

<details>
  <summary><b>Q: Which strategy should I pick?</b></summary>
  Start with <b>ROUND_ROBIN</b>. If player counts are uneven, try <b>LEAST_PLAYERS</b>. If you want the lowest-CPU lobby (and you use the panel resource endpoint), use <b>LEAST_CPU</b>.
</details>

<details>
  <summary><b>Q: What happens if all lobbies are down?</b></summary>
  VPP will try your <code>lobbyBalancer.limbos</code> list. If none are usable, players may be disconnected or kept on their current server until the target starts.
</details>

<details>
  <summary><b>Q: How does auto-start work for lobbies?</b></summary>
  If <code>autoScaleEnabled: true</code> and your lobbies are listed under <code>servers:</code> with valid panel IDs, VPP can keep <code>minOnline</code> lobbies running and start more when players fill up existing ones (<code>playersPerServer</code>) or, for <b>LEAST_CPU</b>, when CPU usage is above <code>cpuScaleUpThreshold</code>.
</details>

## API & Whitelist

<details>
  <summary><b>Q: I'm getting "API rate limit exceeded" messages in the console.</b></summary>

  *   **Enable `printRateLimit: true`** in `config.yml`. This will show you the rate limit values VPP receives from your panel, helping confirm if this is the issue.
  *   **Other API Consumers:** Are other plugins, scripts, or services also making frequent requests to your panel API? This can contribute to hitting the limit.
  *   **`apiThreads`:** The default (`10`) is usually fine. Excessively high values here could potentially hit rate limits faster if many operations are triggered at once.
  *   **Panel Documentation:** Consult your panel's documentation to understand its specific API rate limits and how they are applied.
</details>

<details>
  <summary><b>Q: The whitelist feature isn't working, or `/ptero whitelistReload` does nothing/shows errors.</b></summary>

  *   **Server Configuration (`config.yml`):** Is `whitelist: true` set for the specific server(s) in the `servers:` section?
  *   ::: danger MC Server Soft Incompatibility
      The whitelist fetching feature is **NOT SUPPORTED** if VPP detects your panel as **MC Server Soft**. This is due to differences in panel APIs for file access. VPP will log a warning, and whitelist checks will be skipped.
      :::
  *   **Panel API Key Permissions:** Your Client API Key (`ptlc_` or `plcn_`) must have permissions on the panel to **read files** for the target server (specifically, to access `whitelist.json`).
  *   **File Existence & Location:** Does `whitelist.json` actually exist in the **root directory** of the Minecraft server on the panel?
  *   **Velocity Console Logs (Debug Enabled):** Look for messages like:
      *   "Fetching whitelist for server..."
      *   "Updated whitelist for server..."
      *   Errors such as "Failed to fetch whitelist for server..." or "Error parsing whitelist JSON..."
  *   **`whitelistCheckInterval`:** If you expect periodic updates, ensure this is set to a positive value (minutes).
</details>

<details>
  <summary><b>Q: Why does the memory limit sometimes show as "unlimited" in /ptero info?</b></summary>

  *   The value comes from your panel's reported limit:
      *   **Pterodactyl:** `memory_limit_bytes` from `/resources` may be missing or `0`; we then fall back to `limits.memory` (MiB) from `/servers/{id}`. A value of `0` means unlimited.
      *   **Pelican:** Uses `attributes.limits.memory` (MiB). `0` means unlimited.
      *   **MC Server Soft:** Uses `latest.memoryLimit` (MiB). `0` means unlimited.
  *   When the limit is `0` (unlimited), the plugin displays "unlimited" for memory. Set a concrete memory cap in your panel if you want a finite limit shown.
</details>

<details>
  <summary><b>Q: Can VPP manage game servers hosted on different Pterodactyl/Pelican Nodes (Wings/Daemon machines)? What about different Panel installations?</b></summary>

   *  **Multiple Nodes (Wings/Daemon) under a Single Panel: YES** VelocityPteroPower connects to one specific Panel installation (defined by the pterodactyl.url in config.yml). This single Panel installation can (and typically does) manage game servers running on many different physical or virtual machines, which are known as Nodes (running the Pterodactyl Wings or Pelican Daemon software). VPP can manage any game server that is registered under that one Panel, regardless of which Node it's hosted on.

   * **Multiple, Separate Panel Installations: NO (with a single VPP instance)** A single instance of VelocityPteroPower is designed to communicate with only one Panel installation at a time. If you have completely separate Pterodactyl or Pelican panel installations (e.g., panel1.example.com and panel2.example.com), you cannot manage servers from both of these distinct panels with a single VPP plugin instance.  To manage servers across entirely separate panel installations, you would theoretically need to run separate, isolated Velocity proxy instances, each with its own VPP plugin configured to point to its respective panel. This is generally not a common or straightforward setup.



</details>

---

If your question isn't answered here, please check the [Troubleshooting Guide](troubleshooting.md) or join our [Discord Community](https://discord.pluginz.dev) for further assistance!
