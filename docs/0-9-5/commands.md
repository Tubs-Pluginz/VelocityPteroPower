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

### `/ptero list`
*   **Alias:** `/vpp list`
*   **Description:** Displays a pretty, MiniMessage-formatted list of all managed servers, including their status, current player count, and TTL (time-to-shutdown).
*   **Permission:** `ptero.list`
*   **Notes:**
    *   Status is determined by your configured `serverStatusCheckMethod`.
    *   TTL shows:
        - A live countdown if a shutdown is already scheduled.
        - The configured per-server `timeout` (as a preview) when the server is currently empty but no countdown has started yet.
        - `—` for servers marked in `alwaysOnline`.

### `/ptero info <serverName>`
*   **Alias:** `/vpp info <serverName>`
*   **Description:** Shows a detailed, MiniMessage-formatted panel for the specified server, including status, players, CPU, memory, disk, network I/O, and uptime.
*   **Arguments:**
    *   `<serverName>`: (Required) The name of the server.
*   **Permission:** `ptero.info`
*   **Notes:**
    *   Live resource usage is fetched from your panel and is cached for `resourceCacheSeconds` to avoid spamming the API.
    *   CPU and disk limits are shown only when configured on the panel; unlimited limits are omitted (disk) or displayed as “unlimited” (memory).
    *   Usage values are colorized with a smooth green→red gradient relative to their limits (or to 100% for CPU when unlimited) for quick visual insight.
    *   When a server was initiated by a player via VPP, the panel includes an extra line: `Started by: <player>`.

### `/ptero history [playerName|uuid]`
*   **Alias:** `/vpp history [playerName|uuid]`
*   **Description:** Displays a pretty, in-memory move history for a player (latest first). Shows server-to-server transitions and disconnects.
*   **Arguments:**
    *   `playerName|uuid`: Optional. If omitted and you are a player, shows your history. You can also query by UUID.
*   **Permission:** `ptero.history`
*   **Notes:**
    *   Requires `moveHistory.enabled: true` in `config.yml`.
    *   History is not persisted to disk; it resets on proxy restart.
    *   Tab-completion suggests online player names.

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

### `/ptero forcestartall [confirm]`
*   **Alias:** `/vpp forcestartall [confirm]`
*   **Description:**
    ::: danger Resource Heavy Operation!
    This command attempts to send a "start" signal to **ALL** servers managed by VPP. Ensure your host has sufficient resources (CPU, RAM, disk I/O) before proceeding.
    :::
*   **Permission:** `ptero.forcestartall`
*   **Confirmation Protocol:**
    *   **Players:**
        1.  Execute `/ptero forcestartall`. You will receive a warning and instructions.
        2.  Execute `/ptero forcestartall confirm` within 30 seconds to proceed.
    *   **Console:** Confirmation is bypassed when run from the console.
*   **Notes:** Respects API rate limits; not all servers may receive the start signal immediately if the panel is rate-limiting.

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

### `/ptero apithreads`
*   **Alias:** `/vpp apithreads`
*   **Description:** Displays API executor thread details: configured `apiThreads`, detected server count, auto baseline (2x servers, clamped 4..64), and the final threads currently in use. Helps verify whether your configured value overrides the baseline.
*   **Permission:** `ptero.info`
*   **Notes:**
    *   Thread pool size is computed on startup or reload. Changing `apiThreads` requires a reload/restart to apply.
    *   Setting `apiThreads` higher than the baseline will override the auto-calculated value.

### `/ptero help`
*   **Alias:** `/vpp help`
*   **Description:** Displays a summary of available VPP commands and their basic usage.
*   **Permission:** None required by default.


::: info
**Using Commands:**
Replace `<serverName>` with the exact name of the server as it is defined in your Velocity `velocity.toml` and in the `servers` section of the VelocityPteroPower `config.yml`. Command and server names are typically case-sensitive.
:::