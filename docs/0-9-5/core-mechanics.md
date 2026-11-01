---
title: VPP
---
# Core Mechanics Explained 🧠

Understanding how VelocityPteroPower (VPP) manages your servers can help you configure it optimally and troubleshoot any issues. This page breaks down the key operational logic.

## Automatic Server Starting 🚀

When a player attempts to connect to a server managed by VPP, a sequence of checks and actions occurs:

1.  **Managed Server Identification:**
    VPP first checks if the target server name (from the player's connection attempt) is defined within the `servers` section of its `config.yml`. If not, VPP does not manage this server, and the connection proceeds as Velocity normally would.

2.  **Whitelist Check (Conditional):**
    *   If the server's configuration in `config.yml` has `whitelist: true`:
        *   VPP consults its cached whitelist for that server.
        *   If `whitelistAllowBypass: true` (global config) AND the player has the `ptero.bypass` permission, this check is skipped.
        *   Otherwise, if the player is not on the cached whitelist, the connection attempt will be denied or the server start will be prevented.
    ::: warning MC Server Soft Limitation
    The whitelist fetching and enforcement feature is **not supported** if your panel is MC Server Soft. Whitelist checks will be skipped for such panels.
    :::

3.  **Player Start Cooldown:**
    VPP checks if the player is currently under a cooldown period (defined by `playerStartCooldown` in `config.yml`) from recently initiating a server start. If on cooldown, the current start attempt will be denied.

4.  **Server Status Query:**
    VPP determines if the target server is already online. This is done using the method specified by `serverStatusCheckMethod` in `config.yml`:
    *   **`VELOCITY_PING`**: Sends a standard Minecraft ping.
    *   **`PANEL_API`**: Queries the panel API for the server's state.
    If the server is found to be online, the player's connection proceeds normally, and VPP ensures any internal "starting" flags for that server are cleared.

5.  **Offline Server - Startup Sequence:**
    If the server is determined to be offline:
    *   **Starting Flag:** VPP checks if the server is already internally marked as "starting" (e.g., by another player's recent attempt). If so, the current player might be informed and their connection denied or queued for limbo (if a limbo is configured).
    *   **Rate Limit Check:** Before sending any API command, VPP checks if it can make a request without exceeding panel API rate limits. If rate-limited, the start is aborted, and the player and console is notified.
    *   **API Start Signal:** If all checks pass, VPP sends a "start" signal for the server's ID to the panel API.
    *   **Internal State Update:** The server is marked internally as "starting," and the initiating player's start cooldown is activated.
    *   **Initial Idle Check Scheduled:** If `idleStartShutdownTime` is configured (>= 0), a task is scheduled to shut down this server if no one joins it within that time.

6.  **Player Handling During Startup:**
    *   **Limbo Redirection:** If a valid limbo (from `lobbyBalancer.limbos`) is available, the player is sent there with a notification while the target starts. If the player is already on a limbo when trying again, they remain there.
    *   **Initiator-specific message:** The player who actually triggered the start receives a clearer notice that "You’ve started server `<server>`. Please wait while it boots..." while others see that the server is already starting.
    *   **Auto-queue for delayed connect:** Players attempting to join a server that is in the "starting" state are automatically queued. VPP will try to connect them once the server is online and past the join delay.
    *   **Disconnect/Message:** If no limbo is available (or if it's the player's initial proxy connection), the player will be disconnected with a message that the server is starting, or if they were on a previous server, they will just receive a message and the connection attempt is denied.

7.  **Delayed Connection to Target Server:**
    *   VPP schedules a recurring task (initial delay: `startupInitialCheckDelay`, subsequent checks based on per-server `startupJoinDelay`) to monitor the target server's status.
    *   Once the server is confirmed online (via `serverStatusCheckMethod`), VPP waits for the additional `startupJoinDelay` (per-server config) to allow backend plugins to load.
    *   After this full delay, if the player is still active (e.g., on limbo or attempting to reconnect), VPP attempts to connect them to the now-started target server.
    *   If the server doesn't start within a reasonable number of attempts, the connection task is cancelled, and the player is informed (if connected to the proxy).

### Startup State Cleanup Sweep

To prevent rare cases where a server could remain marked as "starting" indefinitely (e.g., panel never reports success and the attempt has already timed out), VPP periodically runs a small cleanup sweep:

*   The sweep runs about every 60 seconds in the background.
*   For each server flagged as starting, VPP re-checks the real status (respecting your configured status method and rate limits).
*   If the server is online, VPP clears the starting state.
*   If it has exceeded a safe maximum startup window (derived from `startupInitialCheckDelay` and repeated join checks), VPP also clears the stuck state so future attempts can proceed.

This helps keep the system healthy even if an earlier attempt didn’t complete cleanly.

---

## Automatic Server Shutdown 💤

VPP employs several mechanisms to automatically shut down servers, conserving resources:

1.  **Timeout-Based Shutdown (Per-Server `timeout`):**
    *   **Trigger:** When the last player disconnects from a VPP-managed server, or when a server becomes empty after a player switches away from it.
    *   **Action:** VPP checks the `timeout` value for that specific server in `config.yml`.
        *   If `timeout: -1`, automatic shutdown for this server is disabled.
        *   If `timeout: 0`, VPP attempts to send a "stop" signal immediately.
        *   If `timeout > 0` (seconds), a shutdown task is scheduled. If a player rejoins before the task executes, the shutdown is cancelled. Otherwise, a "stop" signal is sent.

2.  **Idle Start Shutdown (Global `idleStartShutdownTime`):**
    *   **Trigger:** A server was started by VPP, but no player successfully connected to it within the `idleStartShutdownTime` (global config).
    *   **Action:** VPP sends a "stop" signal to the panel API for that server.

3.  **Shutdown Confirmation & Retries:**
    *   After VPP sends a "stop" signal, it doesn't just assume the server stopped.
    *   A follow-up task is scheduled (after `shutdownRetryDelay` seconds) to check the server's status via the panel API.
    *   **If Still Online & Empty:** If the server is still reported as online by the panel and is confirmed empty by VPP, it will retry sending the "stop" signal. This retry process occurs up to `shutdownRetries` times.
    *   **If Offline or Players Joined:** If the server is offline, the process completes. If players have joined, the shutdown attempt is cancelled.
    *   This retry mechanism helps handle cases where the panel might be slow to process the stop command or if the initial signal failed.

4.  **Online-only Scheduling & Always-online Override:**
    *   VPP only schedules an idle shutdown when the server is actually online. If a server is offline or its status is unknown, existing deadlines are cleared and no new shutdown is scheduled.
    *   Servers listed under `alwaysOnline` will not have idle shutdowns scheduled at all, even if a per-server `timeout` is configured. The `alwaysOnline` rule outranks per-server settings.

5.  **Failsafe Idle Sweep:**
    *   In addition to event-based scheduling, VPP periodically runs an idle-shutdown sweep (interval controlled by `idleShutdownCheckInterval`, in seconds).
    *   The sweep scans all managed servers and schedules shutdowns for empty ones (respecting `alwaysOnline`, per-server `timeout`, and the online-only rule). This ensures shutdowns are enforced even if events are missed.

---

## Limbo Functionality 🏝️

Limbo lets players wait on a lightweight server while their target server starts.

::: tip Purpose of Limbo
Instead of disconnecting players or leaving them on a "Connecting..." screen, they can be temporarily moved to a lightweight, always-on (or very quickly starting) "limbo" server.
:::

*   **How it Works:**
    1.  A player attempts to connect to an offline VPP-managed server (`Server-A`).
    2.  VPP initiates the startup of `Server-A`.
    3.  If a limbo from your `lobbyBalancer.limbos` list is available and registered in `velocity.toml`, VPP redirects the player there.
    4.  The player receives a message indicating they are being sent to limbo while `Server-A` starts.
    5.  VPP monitors `Server-A`'s startup.
    6.  Once `Server-A` is online and its `startupJoinDelay` has passed, VPP attempts to automatically connect the player from `Lobby-Lite` to `Server-A`.

*   **Configuration is Key:**
    *   Each limbo listed under `lobbyBalancer.limbos` **must** exist in your `velocity.toml`.
    *   If the limbo server itself is managed by VPP, ensure its `timeout` setting is appropriate (e.g., `-1` to prevent auto-shutdown, or a very short `timeout` if it's designed to be ephemeral but also quick to start via VPP).

::: tip
The Limbo server can be any kind of Minecraft server, but it is recommended to use a very lightweight server type to first have a always on lightweight server online and if that is offline to be quickly started.
:::
---

## API Rate Limiting 🚦

Server control panels like Pterodactyl and Pelican implement API rate limits to prevent abuse and ensure stability. VPP is designed to work within these limits.

*   **Detection:** VPP attempts to read rate limit information from the HTTP headers returned by panel API responses (e.g., `X-RateLimit-Limit`, `X-RateLimit-Remaining`).
*   **Behavior:** If VPP anticipates that an API request would exceed the remaining quota, it will temporarily pause or deny actions that require an API call (e.g., starting a server, checking status via `PANEL_API`, stopping a server). Players or console may receive a message indicating the delay.
*   **Monitoring:** You can set `printRateLimit: true` in `config.yml` to have VPP log the rate limit status it receives from the panel. This is very helpful for diagnosing if rate limiting is causing unexpected behavior.
*   **`apiThreads`:** This setting in `config.yml` controls the number of threads VPP can use for concurrent API operations. While increasing it might seem beneficial for many servers, it can also lead to hitting rate limits more quickly if not carefully managed. The default (`10`) is generally a good starting point.
::: info
Mc Server Soft does not have any rate limiting in place.
:::
---

## Server Status Checking Methods (`serverStatusCheckMethod`)

VPP offers two distinct methods to determine if a managed Minecraft server is online and ready for connections:

::: code-group
```yaml [VELOCITY_PING]
# Uses Velocity's built-in server pinging.
# Pros:
#   - No panel API calls, so no impact on rate limits.
#   - Generally faster than an API call.
# Cons:
#   - Server must be correctly defined in velocity.toml and network-reachable.
#   - Might report "online" before backend plugins fully load.
#   - Some minimal servers (e.g., basic limbo) might not respond to pings.
# Relies on `pingTimeout` setting.
serverStatusCheckMethod: "VELOCITY_PING"
```

```yaml [PANEL_API]
# Queries the Pterodactyl/Pelican panel API for server state.
# Pros:
#   - More authoritative status directly from the panel (e.g., "running").
# Cons:
#   - Consumes an API request for each check, impacting rate limits.
#   - Can be slightly slower due to network latency to the panel.
serverStatusCheckMethod: "PANEL_API"
```
:::

Choose the method that best balances accuracy, API usage, and your network setup. For most, `VELOCITY_PING` is efficient, but `PANEL_API` can be more reliable for determining true process state if pings are problematic.

::: tip
If the Panel is in the same Network or even server, the "PANEL_API" is a reliable choice as it has low latency and is accurate with any Server type.
:::

---

## Whitelist Feature Integration ✅

VPP can integrate with the `whitelist.json` file of your backend Minecraft servers (managed via the panel).

*   **Enabling:** For each server in the `servers:` section of `config.yml`, set `whitelist: true`.
*   **Fetching Mechanism:**
    *   VPP attempts to download the content of `whitelist.json` from the server's file directory using the panel API.
    *   This occurs:
        *   On plugin startup/reload.
        *   Periodically, based on `whitelistCheckInterval` (if > 0).
        *   Manually, via the `/ptero whitelistReload` command.
*   **Enforcement Logic (when a player tries to connect/start a whitelisted server):**
    1.  If `whitelistAllowBypass: true` (global config) AND the player has the `ptero.bypass` permission, VPP's whitelist check is skipped.
    2.  Otherwise, VPP checks if the player's username is present in its cached copy of that server's whitelist.
    3.  If not found, the connection/start attempt will be denied by VPP.

::: danger MC Server Soft Incompatibility
The panel API functionality required for VPP to fetch `whitelist.json` is **not available on MC Server Soft panels.**
If VPP detects this panel type, the whitelist feature will be disabled.
:::

::: tip Backend Server Still Enforces
VPP's whitelist check is a *preliminary* check. The backend Minecraft server itself will **always** enforce its own `whitelist.json` file. If a player somehow bypasses VPP's check but isn't on the actual server whitelist, they will still be denied entry by the Minecraft server.
:::


## Server List (TTL) Overview ⏱️

The `/ptero list` command shows a compact overview of all managed servers with a TTL (time-to-shutdown) indicator:

*   If a shutdown has already been scheduled (after the last player left), TTL shows the live remaining time.
*   If the server is currently empty but no countdown exists yet, TTL shows the configured per-server `timeout` as a preview.
*   Servers in `alwaysOnline` display `—` because they are not auto-shutdown by VPP.

Status in the list is resolved using your configured `serverStatusCheckMethod` (either `VELOCITY_PING` or `PANEL_API`). When `PANEL_API` is used, VPP leverages a short resource-usage cache to avoid unnecessary API calls.
