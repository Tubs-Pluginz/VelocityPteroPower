---
title: VPP commands
---
# Commands Reference 💻

VelocityPteroPower (VPP) offers a suite of commands to manage your Pterodactyl/Pelican servers directly from the Velocity console or by in-game players with the appropriate permissions.

The base command is `/ptero`. An alias `/vpp` is also available.

::: tip Case Sensitivity
Remember that server names used in commands must exactly match how they are defined in your `velocity.toml` and VPP's `config.yml`. These are often case-sensitive.
:::

## Core Commands

### `/ptero start <serverName>`
*   **Alias:** `/vpp start <serverName>`
*   **Description:** Attempts to send a "start" signal to the specified server via the panel API.
*   **Arguments:**
    *   `<serverName>`: (Required) The name of the server to start.
*   **Permission:** `ptero.start`
*   **Usage Example:**
    ```bash
    /ptero start skyblock
    ```
*   **Notes:**
    *   Respects player start cooldowns (see `playerStartCooldown` in config).
    *   Checks if the server is already online or in the process of starting.

### `/ptero stop <serverName>`
*   **Alias:** `/vpp stop <serverName>`
*   **Description:** Attempts to send a "stop" signal (graceful shutdown) to the specified server via the panel API.
*   **Arguments:**
    *   `<serverName>`: (Required) The name of the server to stop.
*   **Permission:** `ptero.stop`
*   **Usage Example:**
    ```bash
    /ptero stop creative
    ```

### `/ptero restart <serverName>`
*   **Alias:** `/vpp restart <serverName>`
*   **Description:** Attempts to send a "restart" signal to the specified server via the panel API.
*   **Arguments:**
    *   `<serverName>`: (Required) The name of the server to restart.
*   **Permission:** `ptero.restart`
*   **Usage Example:**
    ```bash
    /ptero restart survival
    ```

## Bulk & Utility Commands

### `/ptero stopidle`
*   **Alias:** `/vpp stopidle`
*   **Description:** Attempts to stop all currently managed servers that are detected as empty (no players connected) AND are *not* listed in the `stopIdleIgnore` section of `config.yml`.
*   **Permission:** `ptero.stopIdle`
*   **Notes:**
    *   Player count is checked at the moment of command execution.
    *   This command respects API rate limits. If limits are hit, not all eligible servers may receive the stop signal in one go.

### `/ptero forcestopall [confirm]`
*   **Alias:** `/vpp forcestopall [confirm]`
*   **Description:**
    ::: danger Extreme Caution Advised!
    This command attempts to send a "stop" signal to **ALL** servers managed by VPP, irrespective of player counts or the `stopIdleIgnore` list. Use with extreme care as it can be highly disruptive.
    :::
*   **Permission:** `ptero.forcestopall`
*   **Confirmation Protocol:**
    *   **Players:**
        1.  Execute `/ptero forcestopall`. You will receive a warning and instructions.
        2.  Execute `/ptero forcestopall confirm` within 30 seconds to proceed.
    *   **Console:** Confirmation is bypassed when run from the console.
*   **Notes:** Also respects API rate limits.

### `/ptero whitelistReload`
*   **Alias:** `/vpp whitelistReload`
*   **Description:** Forces an immediate re-fetch and update of the `whitelist.json` file for all servers configured with `whitelist: true`.
*   **Permission:** `ptero.whitelistReload`
    ::: warning MC Server Soft Incompatibility
    This command (and the whitelist feature it manages) is **not supported** if your panel is MC Server Soft, as VPP cannot fetch files from it.
    :::

### `/ptero reload`
*   **Alias:** `/vpp reload`
*   **Description:** Reloads VPP's `config.yml` and `messages.yml` files. This allows most configuration changes to be applied without a full Velocity proxy restart.
*   **Permission:** `ptero.reload`
*   **Notes:**
    *   Some settings, like `apiThreads`, will still need a full proxy restart to take effect.
    *   The panel API client will be re-initialized if `pterodactyl.url`, `pterodactyl.apiKey`, or the detected panel type changes upon reload.

### `/ptero help`
*   **Alias:** `/vpp help`
*   **Description:** Displays a summary of available VPP commands and their basic usage.
*   **Permission:** None required by default.


::: info
**Using Commands:**
Replace `<serverName>` with the exact name of the server as it is defined in your Velocity `velocity.toml` and in the `servers` section of the VelocityPteroPower `config.yml`. Command and server names are typically case-sensitive.
:::