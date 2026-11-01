---
title: VPP troubleshooting
---
# Troubleshooting Guide 🛠️

Steps that actually help when VelocityPteroPower misbehaves.

## 1. Preliminary Checks ✅

*   **Latest VPP:** Grab a current jar from [Modrinth](https://modrinth.com/plugin/velocitypteropower).
*   **Velocity 4.3+ / Java 25:** 0.9.6 will not load on Velocity 3 or Java 21. Check `java -version` on the proxy host and `/velocity version`.

## 2. Enable debug logging 📝

Set `loggerLevel: 10` in `config.yml`, `/ptero reload` (or restart), reproduce the bug, then read lines tagged `[VPP]` / `VelocityPteroPower`.

::: tip Turn it back down
Set `loggerLevel: 20` when you are done or the console never shuts up.
:::

## 3. Common Issues & Solutions

### A. Server Not Starting/Stopping as Expected

::: details Symptoms & Checklist
**Symptoms:**
*   Players attempt to connect to an offline server, but it doesn't start.
*   An empty server remains online despite `timeout` settings.
*   Commands like `/ptero start` or `/ptero stop` seem to have no effect.

**Checklist:**
1.  **Configuration Accuracy (`config.yml`):**
    *   **`servers.<serverName>`:** Does the server name (e.g., `hub`) **exactly match** the name defined in your `velocity.toml`? (This is case-sensitive).
    *   **`servers.<serverName>.id`:** Is this the correct **short alphanumeric ID** from your panel for that specific server (e.g., `a1b2c3d4` from the panel URL), not a long Docker container ID or full UUID?
    *   **`pterodactyl.url`:** Is this the full, correct base URL to your panel, including `http://` or `https://` (e.g., `https://panel.example.com/`)?
    *   **`pterodactyl.apiKey`:**
        *   Is it a **Client API Key** (`ptlc_`, `plcn_`/`pacc_`, or `c7sp_` for Calagopus)?
        *   **Application API Keys (`ptla_`, `peli_`) are NOT supported.**
        *   Does the key have the necessary permissions on the panel (e.g., control power signals, read server files if using the whitelist feature)?
2.  **Panel API Connectivity:**
    *   From the machine running your Velocity proxy, can you access the panel URL in a browser or via `curl`?
    *   Check VPP's startup logs (with debug enabled) for messages like "Successfully connected to panel" or errors like "Invalid API Key," "Failed to initialize Panel API Client," or connection timeouts.
3.  **API Rate Limits:**
    *   Set `printRateLimit: true` in `config.yml`.
    *   Look for console messages from VPP indicating "Rate limit reached" or "API request blocked due to rate limiting." If you see these, your panel is temporarily throttling requests from VPP.
4.  **`serverStatusCheckMethod` (`config.yml`):**
    *   Default is `HYBRID` (panel running **and** ping). If you switched to `VELOCITY_PING`, check IP/port in `velocity.toml` and `pingTimeout`. For `PANEL_API`, the panel must be responsive.
5.  **Whitelist Feature (for server starting issues):**
    *   If `whitelist: true` is set for the server in `config.yml`:
        *   Is the connecting player listed in that server's `whitelist.json` file on the panel?
        *   If `whitelistAllowBypass: true` is also set, does the player have the `ptero.bypass` permission?
        *   Remember: Whitelist fetching is **not supported for MC Server Soft panels.**
6.  **`timeout` / `idleStartShutdownTime` (for server stopping issues):**
    *   For per-server auto-shutdown: Is `timeout:` in `servers.<serverName>:` set to a positive number (seconds)? A value of `-1` disables it.
    *   For servers started but never joined: Is `idleStartShutdownTime:` (global) set to a positive number? `-1` disables it.
7.  **Backend Server Logs (Panel Console):**
    *   Always check the console output of the actual Minecraft server on the panel. Are there errors there preventing it from starting up correctly or shutting down cleanly? VPP can only send signals; it can't fix underlying issues with the Minecraft server itself.
:::

### B. Limbo Server Functionality Issues

::: details Symptoms & Checklist
**Symptoms:**
*   Players are disconnected instead of being sent to the limbo server.
*   Players are sent to limbo but never transferred to the target server.

**Checklist:**
1.  **`lobbyBalancer.limbos` / `sendToLimboOnStart`:** names must match `velocity.toml`. With limbos listed, send-to-limbo defaults on.
2.  **Reachable limbo:** `/server <limbo>` works. Prefer `alwaysOnline: [limbo]` so the waiting room is not cold.
3.  **Logs:** look for holding redirects and watcher timeout / settle messages (`loggerLevel: 10`).
4.  **Poll vs settle:** raise `startupJoinDelay` if players transfer too early; keep `startupPollInterval` for how often VPP asks "are you up?".
:::

### C. Whitelist Feature Not Working Correctly

::: details Symptoms & Checklist
**Symptoms:**
*   Players who should be whitelisted are denied by VPP (or vice-versa).
*   The `/ptero whitelistReload` command shows errors or doesn't seem to update.

**Checklist:**
1.  **`whitelist: true`:** Is this set for the specific server(s) in the `servers:` section of `config.yml`?
2.  ::: danger MC Server Soft Incompatibility
    The panel API functionality required for VPP to fetch `whitelist.json` is **not available on MC Server Soft panels.** If VPP detects this panel type, the whitelist feature will be effectively disabled, and warnings may be logged.
    :::
3.  **Panel API Key Permissions:** Your Client API Key (`ptlc_`, `plcn_`/`pacc_`, or `c7sp_`) **must** have permissions on the panel to **read server files** (specifically, to access `whitelist.json` for the relevant servers).
4.  **File Path & Name:** VPP expects the whitelist file to be named exactly `whitelist.json` and located in the **root directory** of the Minecraft server's files on the panel.
5.  **`whitelist.json` Format:** Is the `whitelist.json` file on the panel correctly formatted JSON? (e.g., `[{"uuid": "...", "name": "..."}, ...]`)
6.  **VPP Logs (Debug Enabled):** Look for:
    *   "Fetching whitelist for server `serverName`..."
    *   "Updated whitelist for server `serverName`: `X` players"
    *   Error messages like "Failed to fetch whitelist for server...", "Error parsing whitelist JSON...", or "Whitelist file not found for server...".
7.  **`whitelistCheckInterval`:** If you expect periodic automatic updates, ensure this is set to a positive value (in minutes).
:::

### D. HTTP/2 "GOAWAY" Errors in Logs

::: details Symptoms & Explanation
**Symptoms:**
*   Debug logs show messages containing "GOAWAY" during API requests to the panel.

**Explanation:**
This typically indicates a temporary issue with the HTTP/2 connection between your Velocity server and the panel. It can be caused by the panel itself, network intermediaries (like reverse proxies or Cloudflare), or transient network conditions. VPP includes some internal retry logic for these specific errors.

**Solutions:**
*   Often, these are transient and may resolve themselves.
*   If persistent:
    *   Check the health and logs of your panel.
    *   Investigate the network path between your Velocity server and the panel.
    *   If using Cloudflare or another reverse proxy in front of your panel, check its configuration and logs for any HTTP/2 related issues or connection resets.
:::

### E. Plugin Fails to Load or Throws Errors on Velocity Startup

::: details Symptoms & Checklist
**Symptoms:**
*   VelocityPteroPower does not appear in `/velocity plugins`.
*   Errors related to `de.tubyoub.velocitypteropower` appear in the console during Velocity's startup sequence.

**Checklist:**
1.  **Java / Velocity:** 0.9.6 needs **Java 25** and **Velocity 4.3+**. `java -version` and `/velocity version` first.
2.  **Corrupted JAR:** Re-download the shaded jar from Modrinth / GitHub Releases.
3.  **Incomplete Upload:** Make sure the full file landed in `plugins/`.
4.  **File Permissions:** The Velocity process must be able to read the jar.
5.  **Broken `config.yml`:** Back it up, delete it, let VPP rewrite defaults, then re-apply settings.
6.  **Conflicting Plugins (rare):** Another plugin shading incompatible shared libraries can break load order. If VPP only fails when a specific plugin is present, investigate that combo.
:::

### F. Server appears stuck in "starting"

::: details Symptoms & Checklist
**Symptoms:**
*   Players see messages that a server is starting, but it never seems to finish.
*   Later attempts keep saying the server is starting.

**What VPP does:**
*   A cleanup sweep (~60s) clears "starting" when the panel shows online, or when the startup window / `startupTimeoutSeconds` has elapsed (`CANCEL` clears waiters + starting; `KEEP_STARTING` clears waiters only).

**Checklist:**
1.  Debug logs for watcher timeout / settle / soft-requeue.
2.  Confirm `HYBRID` / ping / panel status matches reality.
3.  Read the backend console on the panel for real startup failures.
4.  `printRateLimit: true` if status checks look starved.
5.  Soft-requeue only helps early `SERVER_DISCONNECTED` kicks — raise `startupJoinDelay` if the world is still loading.
:::

### G. Addon install / browse looks wrong

::: details Symptoms & Checklist
**Symptoms:**
*   `/ptero addon browse` shows an addon as missing even though a jar is in the folder.
*   Catalog install fails.

**Checklist:**
1.  Jars belong in `plugins/VelocityPteroPower/addons/` (not Velocity's root `plugins/`).
2.  Catalog install pulls GitHub Release assets from `releases/latest` — the Release must exist and include the addon jar.
3.  Browse treats a successfully loaded jar as installed even if you dropped it by hand.
4.  Sample addon has no catalog download URL; build it locally if you need it.
:::

## 4. Providing Information for Support 🆘

If you're still stuck, ask in the [Support Discord](https://discord.pluginz.dev). Bring:

*   **VelocityPteroPower Version:** (e.g., `0.9.6`)
*   **Velocity Version:** (e.g., `4.3.1` — from `/velocity version`)
*   **Panel Type & Version:** (e.g., Pterodactyl 1.11.5, Pelican, Calagopus)
*   **Java Version:** (must be 25 for 0.9.6)
*   **Your `config.yml`:**
    ::: danger Mask API Key!
    Before sharing your `config.yml`, **ALWAYS MASK YOUR `apiKey`** like this:
    `apiKey: "ptlc_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"`
    :::
    *   Prefer [Pastebin](https://pastebin.com/) with the key masked.
*   **Language files** under `plugins/VelocityPteroPower/messages/<lang>.yml` if the issue is message-related.
*   **Debug console logs** (`loggerLevel: 10`) from proxy start through the failure — upload to [mclo.gs](https://mclo.gs/).
    ::: tip Do Not Paste Directly
    Please **do not** paste long logs or configs into Discord. Use the links above.
    :::
*   **What you expected vs what happened**, plus any player-facing errors.
*   **Steps to reproduce** if you have them.
