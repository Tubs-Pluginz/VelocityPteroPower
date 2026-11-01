---
title: VPP troubleshooting
---
# Troubleshooting Guide 🛠️

This guide provides steps to help you diagnose and resolve common issues with VelocityPteroPower (VPP).

## 1. Preliminary Checks ✅

*   **Latest VPP Version:** Ensure you are running the latest stable release of VelocityPteroPower. Check the [Modrinth Page](https://modrinth.com/plugin/velocitypteropower) or use the in-plugin update checker (if `checkUpdate: true` in `config.yml`).
*   **Velocity Version:** Confirm your Velocity proxy version is compatible. VPP is generally built against recent Velocity API versions.

## 2. Enable Debug Logging 📝

This is the **most crucial step** for effective troubleshooting.

1.  Open `plugins/VelocityPteroPower/config.yml`.
2.  Locate the `loggerLevel` setting.
3.  Change its value to `10`:
    ```yaml
    loggerLevel: 10 # From 20 (Info) to 10 (Debug)
    ```
4.  Save the `config.yml` file.
5.  Execute `/ptero reload` in the Velocity console or restart the Velocity proxy.

Now, reproduce the issue you are experiencing. Carefully examine the Velocity console logs for messages prefixed with `[VPP]` or `VelocityPteroPower`, as well as any related stack traces or error messages.

::: tip Remember to Revert!
Once troubleshooting is complete, remember to set `loggerLevel` back to `20` (Info) for normal operation to prevent excessive console spam.
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
        *   Is it a **Client API Key** (starts `ptlc_` for Pterodactyl or `plcn_` for Pelican)?
        *   **Application API Keys (`ptla_`, `peli_`) are NOT supported.**
        *   Does the key have the necessary permissions on the panel (e.g., control power signals, read server files if using the whitelist feature)?
2.  **Panel API Connectivity:**
    *   From the machine running your Velocity proxy, can you access the panel URL in a browser or via `curl`?
    *   Check VPP's startup logs (with debug enabled) for messages like "Successfully connected to panel" or errors like "Invalid API Key," "Failed to initialize Panel API Client," or connection timeouts.
3.  **API Rate Limits:**
    *   Set `printRateLimit: true` in `config.yml`.
    *   Look for console messages from VPP indicating "Rate limit reached" or "API request blocked due to rate limiting." If you see these, your panel is temporarily throttling requests from VPP.
4.  **`serverStatusCheckMethod` (`config.yml`):**
    *   If `VELOCITY_PING`: Is the backend server's IP address and port correctly configured in `velocity.toml`? Is the server network-reachable from the proxy machine? Is the `pingTimeout` value in `config.yml` sufficient?
    *   If `PANEL_API`: This method relies entirely on the panel API being responsive and accurate.
5.  **Whitelist Feature (for server starting issues):**
    *   If `whitelist: true` is set for the server in `config.yml`:
        *   Is the connecting player listed in that server's `whitelist.json` file on the panel?
        *   If `whitelistAllowBypass: true` is also set, does the player have the `ptero.bypass` permission?
        *   Remember: Whitelist fetching is **not supported for MC Server Soft panels.**
6.  **`timeout` / `idleStartShutdownTime` (for server stopping issues):**
    *   For per-server auto-shutdown: Is `timeout:` in `servers.<serverName>:` set to a positive number (seconds)? A value of `-1` disables it.
    *   For servers started but never joined: Is `idleStartShutdownTime:` (global) set to a positive number? `-1` disables it.
7.  **Backend Server Logs (Panel Console):**
    *   Always check the console output of the actual Minecraft server on the Pterodactyl/Pelican panel. Are there errors there preventing it from starting up correctly or shutting down cleanly? VPP can only send signals; it can't fix underlying issues with the Minecraft server itself.
:::

### B. Limbo Server Functionality Issues

::: details Symptoms & Checklist
**Symptoms:**
*   Players are disconnected instead of being sent to the limbo server.
*   Players are sent to limbo but never transferred to the target server.

**Checklist:**
1.  **`limboServer` Configuration (`config.yml`):**
    *   Is the server name specified in `limboServer:` an **exact match** (case-sensitive) for a server defined in your `velocity.toml`?
    *   Is it still set to the default `"changeMe"`?
2.  **Limbo Server Accessibility:**
    *   Is the server designated as your limbo server actually online and joinable directly via Velocity (e.g., using `/server limboServerName`)?
    *   If your limbo server is *also* managed by VPP, check its own configuration within the `servers:` section of `config.yml`. Ensure its `timeout` is set appropriately (e.g., `-1` to prevent it from auto-shutting down, or a very short `timeout` if it's designed to be ephemeral but also quick to start via VPP).
3.  **VPP Logs (Debug Enabled):**
    *   Look for messages like "Redirecting player to limbo server..." or any errors related to finding, pinging, or connecting to the limbo server.
    *   Check for messages about the target server's startup progress when a player is in limbo.
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
3.  **Panel API Key Permissions:** Your Client API Key (`ptlc_` or `plcn_`) **must** have permissions on the panel to **read server files** (specifically, to access `whitelist.json` for the relevant servers).
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
    *   Check the health and logs of your Pterodactyl/Pelican panel.
    *   Investigate the network path between your Velocity server and the panel.
    *   If using Cloudflare or another reverse proxy in front of your panel, check its configuration and logs for any HTTP/2 related issues or connection resets.
:::

### E. Plugin Fails to Load or Throws Errors on Velocity Startup

::: details Symptoms & Checklist
**Symptoms:**
*   VelocityPteroPower does not appear in `/velocity plugins`.
*   Errors related to `de.tubyoub.velocitypteropower` appear in the console during Velocity's startup sequence.

**Checklist:**
1.  **Java Version:** VelocityPteroPower requires **Java 21 or higher**. Ensure your Velocity proxy is running on a compatible Java version. Use `java -version` in your server's terminal.
2.  **Corrupted JAR File:** The downloaded `VelocityPteroPower.jar` might be corrupted. Try re-downloading it from the official source (Modrinth/GitHub Releases).
3.  **Incomplete Upload:** Ensure the JAR file was fully uploaded to your server's `plugins` directory.
4.  **File Permissions:** Unlikely, but ensure the Velocity process has read access to the JAR file.
5.  **`config.yml` Syntax Errors (Severe Cases):** While VPP uses BoostedYAML which is robust, a severely malformed `config.yml` (e.g., completely broken YAML syntax) *could* theoretically cause loading issues. If you suspect this, back up your current `config.yml`, delete it from the `plugins/VelocityPteroPower/` folder, and let VPP regenerate a default one on the next startup. Then, carefully re-apply your settings.
6.  **Conflicting Plugins (Very Rare):** In extremely rare cases, another Velocity plugin might cause a severe classpath conflict if it bundles incompatible versions of shared libraries that VPP also uses (though VPP shades its dependencies like BoostedYAML and Jackson to minimize this). If VPP only fails to load when certain other plugins are present, this might be a factor to investigate.
:::

## 4. Providing Information for Support 🆘

If you've gone through these steps and are still stuck, please seek help! The best place is the [Support Discord Server](https://discord.pluginz.dev).

When asking for support, please provide as much of the following information as possible to help us diagnose the issue quickly and effectively:

*   **VelocityPteroPower Version:** (e.g., `0.9.4`)
*   **Velocity Version:** (e.g., `3.3.0-SNAPSHOT (git-...)` - get from `/velocity version`)
*   **Panel Type & Version:** (e.g., Pterodactyl 1.11.5, Pelican 0.8.2)
*   **Java Version:**
*   **Your `config.yml`:**
    ::: danger Mask API Key!
    Before sharing your `config.yml`, **ALWAYS MASK YOUR `apiKey`** like this:
    `apiKey: "ptlc_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"`
    :::
    *   **Preferred Sharing Method:** Please upload your `config.yml` (with masked API key) to [**Pastebin**](https://pastebin.com/) and provide the link.
*   **Relevant language file(s)** from `plugins/VelocityPteroPower/messages/<lang>.yml` (if you've customized them and it relates to the issue). You can also use [Pastebin](https://pastebin.com/) for this.
*   **Detailed Console Logs (with Debug Enabled):**
    *   Ensure `loggerLevel: 10` is set in your `config.yml`.
    *   Capture logs from the moment Velocity starts up through the point where the issue occurs.
    *   **Preferred Sharing Method:** Please upload your full console logs to [**mclo.gs**](https://mclo.gs/) and provide the link. This site is specifically designed for Minecraft logs and offers helpful analysis.
    ::: tip Do Not Paste Directly
    Please **do not** paste long logs or configuration files directly into Discord channels. Use the recommended services above and share the links.
    :::
*   **A Clear Description of the Issue:**
    *   What did you expect to happen?
    *   What actually happened?
    *   Were th---
title: VPP troubleshooting
---
# Troubleshooting Guide 🛠️

This guide provides steps to help you diagnose and resolve common issues with VelocityPteroPower (VPP).

## 1. Preliminary Checks ✅

*   **Latest VPP Version:** Ensure you are running the latest stable release of VelocityPteroPower. Check the [Modrinth Page](https://modrinth.com/plugin/velocitypteropower) or use the in-plugin update checker (if `checkUpdate: true` in `config.yml`).
*   **Velocity Version:** Confirm your Velocity proxy version is compatible. VPP is generally built against recent Velocity API versions.

## 2. Enable Debug Logging 📝

This is the **most crucial step** for effective troubleshooting.

1.  Open `plugins/VelocityPteroPower/config.yml`.
2.  Locate the `loggerLevel` setting.
3.  Change its value to `10`:
    ```yaml
    loggerLevel: 10 # From 20 (Info) to 10 (Debug)
    ```
4.  Save the `config.yml` file.
5.  Execute `/ptero reload` in the Velocity console or restart the Velocity proxy.

Now, reproduce the issue you are experiencing. Carefully examine the Velocity console logs for messages prefixed with `[VPP]` or `VelocityPteroPower`, as well as any related stack traces or error messages.

::: tip Remember to Revert!
Once troubleshooting is complete, remember to set `loggerLevel` back to `20` (Info) for normal operation to prevent excessive console spam.
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
        *   Is it a **Client API Key** (starts `ptlc_` for Pterodactyl or `plcn_` for Pelican)?
        *   **Application API Keys (`ptla_`, `peli_`) are NOT supported.**
        *   Does the key have the necessary permissions on the panel (e.g., control power signals, read server files if using the whitelist feature)?
2.  **Panel API Connectivity:**
    *   From the machine running your Velocity proxy, can you access the panel URL in a browser or via `curl`?
    *   Check VPP's startup logs (with debug enabled) for messages like "Successfully connected to panel" or errors like "Invalid API Key," "Failed to initialize Panel API Client," or connection timeouts.
3.  **API Rate Limits:**
    *   Set `printRateLimit: true` in `config.yml`.
    *   Look for console messages from VPP indicating "Rate limit reached" or "API request blocked due to rate limiting." If you see these, your panel is temporarily throttling requests from VPP.
4.  **`serverStatusCheckMethod` (`config.yml`):**
    *   If `VELOCITY_PING`: Is the backend server's IP address and port correctly configured in `velocity.toml`? Is the server network-reachable from the proxy machine? Is the `pingTimeout` value in `config.yml` sufficient?
    *   If `PANEL_API`: This method relies entirely on the panel API being responsive and accurate.
5.  **Whitelist Feature (for server starting issues):**
    *   If `whitelist: true` is set for the server in `config.yml`:
        *   Is the connecting player listed in that server's `whitelist.json` file on the panel?
        *   If `whitelistAllowBypass: true` is also set, does the player have the `ptero.bypass` permission?
        *   Remember: Whitelist fetching is **not supported for MC Server Soft panels.**
6.  **`timeout` / `idleStartShutdownTime` (for server stopping issues):**
    *   For per-server auto-shutdown: Is `timeout:` in `servers.<serverName>:` set to a positive number (seconds)? A value of `-1` disables it.
    *   For servers started but never joined: Is `idleStartShutdownTime:` (global) set to a positive number? `-1` disables it.
7.  **Backend Server Logs (Panel Console):**
    *   Always check the console output of the actual Minecraft server on the Pterodactyl/Pelican panel. Are there errors there preventing it from starting up correctly or shutting down cleanly? VPP can only send signals; it can't fix underlying issues with the Minecraft server itself.
:::

### B. Limbo Server Functionality Issues

::: details Symptoms & Checklist
**Symptoms:**
*   Players are disconnected instead of being sent to the limbo server.
*   Players are sent to limbo but never transferred to the target server.

**Checklist:**
1.  **Limbo configuration (`config.yml`):**
    *   Ensure at least one valid limbo is listed under `lobbyBalancer.limbos` and registered in your `velocity.toml`.
2.  **Limbo Accessibility:**
    *   Is a limbo server actually online and joinable directly via Velocity (e.g., using `/server <limboServerName>`)?
    *   If a limbo is *also* managed by VPP, check its own configuration within the `servers:` section of `config.yml`. Ensure its `timeout` is set appropriately (e.g., `-1` to prevent it from auto-shutting down, or a very short `timeout` if it's designed to be ephemeral but also quick to start via VPP).
3.  **VPP Logs (Debug Enabled):**
    *   Look for messages like "Redirecting player to limbo server..." or any errors related to finding, pinging, or connecting to the limbo server.
    *   Check for messages about the target server's startup progress when a player is in limbo.
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
3.  **Panel API Key Permissions:** Your Client API Key (`ptlc_` or `plcn_`) **must** have permissions on the panel to **read server files** (specifically, to access `whitelist.json` for the relevant servers).
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
    *   Check the health and logs of your Pterodactyl/Pelican panel.
    *   Investigate the network path between your Velocity server and the panel.
    *   If using Cloudflare or another reverse proxy in front of your panel, check its configuration and logs for any HTTP/2 related issues or connection resets.
:::

### E. Plugin Fails to Load or Throws Errors on Velocity Startup

::: details Symptoms & Checklist
**Symptoms:**
*   VelocityPteroPower does not appear in `/velocity plugins`.
*   Errors related to `de.tubyoub.velocitypteropower` appear in the console during Velocity's startup sequence.

**Checklist:**
1.  **Java Version:** VelocityPteroPower requires **Java 21 or higher**. Ensure your Velocity proxy is running on a compatible Java version. Use `java -version` in your server's terminal.
2.  **Corrupted JAR File:** The downloaded `VelocityPteroPower.jar` might be corrupted. Try re-downloading it from the official source (Modrinth/GitHub Releases).
3.  **Incomplete Upload:** Ensure the JAR file was fully uploaded to your server's `plugins` directory.
4.  **File Permissions:** Unlikely, but ensure the Velocity process has read access to the JAR file.
5.  **`config.yml` Syntax Errors (Severe Cases):** While VPP uses BoostedYAML which is robust, a severely malformed `config.yml` (e.g., completely broken YAML syntax) *could* theoretically cause loading issues. If you suspect this, back up your current `config.yml`, delete it from the `plugins/VelocityPteroPower/` folder, and let VPP regenerate a default one on the next startup. Then, carefully re-apply your settings.
6.  **Conflicting Plugins (Very Rare):** In extremely rare cases, another Velocity plugin might cause a severe classpath conflict if it bundles incompatible versions of shared libraries that VPP also uses (though VPP shades its dependencies like BoostedYAML and Jackson to minimize this). If VPP only fails to load when certain other plugins are present, this might be a factor to investigate.
:::

## 4. Providing Information for Support 🆘

If you've gone through these steps and are still stuck, please seek help! The best place is the [Support Discord Server](https://discord.pluginz.dev).

When asking for support, please provide as much of the following information as possible to help us diagnose the issue quickly and effectively:

*   **VelocityPteroPower Version:** (e.g., `0.9.4`)
*   **Velocity Version:** (e.g., `3.3.0-SNAPSHOT (git-...)` - get from `/velocity version`)
*   **Panel Type & Version:** (e.g., Pterodactyl 1.11.5, Pelican 0.8.2)
*   **Java Version:**
*   **Your `config.yml`:**
    ::: danger Mask API Key!
    Before sharing your `config.yml`, **ALWAYS MASK YOUR `apiKey`** like this:
    `apiKey: "ptlc_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"`
    :::
    *   **Preferred Sharing Method:** Please upload your `config.yml` (with masked API key) to [**Pastebin**](https://pastebin.com/) and provide the link.
*   **Relevant language file(s)** from `plugins/VelocityPteroPower/messages/<lang>.yml` (if you've customized them and it relates to the issue). You can also use [Pastebin](https://pastebin.com/) for this.
*   **Detailed Console Logs (with Debug Enabled):**
    *   Ensure `loggerLevel: 10` is set in your `config.yml`.
    *   Capture logs from the moment Velocity starts up through the point where the issue occurs.
    *   **Preferred Sharing Method:** Please upload your full console logs to [**mclo.gs**](https://mclo.gs/) and provide the link. This site is specifically designed for Minecraft logs and offers helpful analysis.
    ::: tip Do Not Paste Directly
    Please **do not** paste long logs or configuration files directly into Discord channels. Use the recommended services above and share the links.
    :::
*   **A Clear Description of the Issue:**
    *   What did you expect to happen?
    *   What actually happened?
    *   Were there any specific error messages shown to players or in the console that weren't in the full log?
*   **Steps to Reproduce the Issue:** If you can reliably make the issue happen, list the exact steps.

The more detailed and accurately formatted information you provide using these services, the easier and faster it will be for the community or developers to assist you!


### F. Server appears stuck in "starting"

Symptoms:
- Players see messages that a server is starting, but it never seems to finish.
- Subsequent attempts keep saying the server is starting.

What VPP does automatically:
- VPP periodically runs a small cleanup sweep (about every 60 seconds) to verify the real status.
- If the panel reports the server is online, VPP clears the "starting" state.
- If the startup window has been exceeded, VPP also clears the stuck state so new attempts can proceed.

What you can check:
1. Enable debug logs (`loggerLevel: 10`) and watch for messages about the starting sweep.
2. Verify your `serverStatusCheckMethod` works in your environment (try switching between `VELOCITY_PING` and `PANEL_API` for diagnosis).
3. Ensure the backend server actually starts (check panel console logs for errors).
4. Confirm rate limiting isn’t blocking status checks (`printRateLimit: true`).
5. Try reconnecting after the sweep interval to see if the state was cleared.
ere any specific error messages shown to players or in the console that weren't in the full log?
*   **Steps to Reproduce the Issue:** If you can reliably make the issue happen, list the exact steps.

The more detailed and accurately formatted information you provide using these services, the easier and faster it will be for the community or developers to assist you!
