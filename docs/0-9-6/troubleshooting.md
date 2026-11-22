---
title: VPP troubleshooting
---
# Troubleshooting

Steps to work through when VelocityPteroPower does not behave as expected.

## 1. First checks

*   **Current build:** while 0.9.6 is in testing, get the jar from the [GitHub nightly](https://github.com/Tubs-Pluginz/VelocityPteroPower/releases/tag/nightly).
*   **Velocity 4.3+ and Java 25:** 0.9.6 does not load on Velocity 3 or Java 21. Run `java -version` on the proxy host and `/velocity version` in the console.

## 2. Turn on debug logging

Set `loggerLevel: 10` in `config.yml`, run `/ptero reload` (or restart), reproduce the problem, then read the lines from `velocity-ptero-power`.

::: tip Turn it back down
Set `loggerLevel: 20` when you are done. Debug output is noisy.
:::

## 3. Common problems

### A. Server does not start or stop

::: details Symptoms and checklist
**Symptoms**
*   A player connects to an offline server and it does not start.
*   An empty server stays online past its `timeout`.
*   `/ptero start` or `/ptero stop` seems to do nothing.

**Checklist**
1.  **`config.yml` entries**
    *   **`servers.<serverName>`:** the name (for example `hub`) must match the name in `velocity.toml` exactly, including case.
    *   **`servers.<serverName>.id`:** use the panel's server ID. On Pterodactyl that is the short ID from the server URL (for example `a1b2c3d4`). On Pelican it is the short UUID from Server Details, on Calagopus the full server UUID. It is never the Docker container ID.
    *   **`pterodactyl.url`:** the full base URL of the panel, including `http://` or `https://` (for example `https://panel.example.com/`).
    *   **`pterodactyl.apiKey`:**
        *   It must be a client API key (`ptlc_`, `plcn_` or `pacc_`, or `c7sp_` for Calagopus).
        *   Application API keys (`ptla_`, `peli_`) do not work.
        *   The key needs permission to send power signals, and to read files if you use the whitelist.
2.  **Panel connection**
    *   From the proxy machine, open the panel URL in a browser or with `curl`.
    *   Read the startup log. "Initializing API client for panel type: ..." shows which panel VPP detected from the key prefix. An "Initialization Failed" block or "Failed to initialize Panel API Client" means VPP rejected the key and disabled itself.
3.  **Rate limits**
    *   Set `printRateLimit: true` in `config.yml`.
    *   "Rate limit reached" warnings, or "API request blocked due to rate limiting" at debug level, mean the panel is throttling VPP.
4.  **`serverStatusCheckMethod`**
    *   The default is `HYBRID`: the panel must report running and a ping must succeed. With `VELOCITY_PING`, check the address in `velocity.toml` and `pingTimeout`. With `PANEL_API`, the panel must respond.
5.  **Whitelist (server does not start)**
    *   With `whitelist: true` on the server:
        *   The player must be in that server's `whitelist.json` on the panel.
        *   With `whitelistAllowBypass: true`, players with `ptero.bypass` skip the check.
        *   MC Server Soft does not support whitelist fetching.
6.  **Maintenance and start password (server does not start)**
    *   A server in maintenance is never started for players.
    *   A server with a `startPassword` needs `/ptero unlock <server> <password>` first, unless the player has `ptero.password.bypass`.
7.  **`timeout` and `idleStartShutdownTime` (server does not stop)**
    *   For shutdown after players leave, the server's `timeout:` must be a positive number of seconds. `-1`, or no `timeout` key, turns it off.
    *   For servers started but never joined, `idleStartShutdownTime:` must be a positive number. `-1` turns it off. Lobbies, limbos and servers with `timeout: -1` are never stopped this way.
    *   Servers in `alwaysOnline`, and servers whose group peers have players, are not stopped.
8.  **Backend console on the panel**
    *   Read the Minecraft server's own console. VPP only sends power signals. If the server crashes on startup or hangs on shutdown, the cause is on the backend.
:::

### B. Limbo problems

::: details Symptoms and checklist
**Symptoms**
*   Players get disconnected instead of sent to the limbo.
*   Players land on the limbo but never move to the target server.

**Checklist**
1.  **`lobbyBalancer.limbos` and `sendToLimboOnStart`:** the names must match `velocity.toml`. When limbos are listed, `sendToLimboOnStart` is on unless you set it to `false`.
2.  **Reachable limbo:** `/server <limbo>` must work. Put the limbo in `alwaysOnline` so it does not need a cold start itself.
3.  **Forced-host joins:** a player who joins the proxy straight into an offline server is disconnected when no limbo or lobby is reachable. With `sendToLimboOnStart: false`, `forcedHostOfflineBehavior` decides (`DISCONNECT` disconnects them).
4.  **Players leaving the limbo:** a queued player who moves to any server other than a lobby, a limbo or the target loses their place.
5.  **Logs:** with `loggerLevel: 10`, look for "Redirecting player ... to holding server", "Settling", and "did not come online within the expected time".
6.  **Poll interval and join delay:** raise `startupJoinDelay` if players are moved too early. `startupPollInterval` only sets how often VPP checks whether the server is up.
:::

### C. Whitelist does not work

::: details Symptoms and checklist
**Symptoms**
*   VPP denies players who should be on the whitelist, or lets in players who should not be.
*   `/ptero whitelistReload` shows errors or changes nothing.

**Checklist**
1.  **`whitelist: true`:** it must be set on the server under `servers:` in `config.yml`.
2.  ::: danger MC Server Soft
    MC Server Soft's API cannot serve files, so VPP cannot fetch `whitelist.json` there. When VPP detects MC Server Soft, it logs a warning and turns whitelist checks off.
    :::
3.  **Key permissions:** the client API key (`ptlc_`, `plcn_` or `pacc_`, or `c7sp_`) must be allowed to read files on the servers that use the whitelist.
4.  **File name and location:** the file must be named `whitelist.json` and sit in the root directory of the Minecraft server's files on the panel.
5.  **File format:** `whitelist.json` must be valid JSON, for example `[{"uuid": "...", "name": "..."}, ...]`.
6.  **Logs (debug on):** look for:
    *   "Fetching whitelist for server ..."
    *   "Updated whitelist for server `serverName`: `X` players"
    *   "Failed to fetch whitelist for server ...", "Error parsing whitelist JSON ..." or "Whitelist file not found for server ...".
7.  **`whitelistCheckInterval`:** for automatic refreshes, set it to a positive number of minutes.
:::

### D. HTTP/2 "GOAWAY" errors in the log

::: details Symptoms and explanation
**Symptoms**
*   Debug logs show "GOAWAY" during panel API requests.

**Explanation**
The panel, or something between the proxy and the panel (a reverse proxy, Cloudflare, an unstable network), closed the HTTP/2 connection. VPP retries status checks that fail with GOAWAY a few times before it gives up.

**What to do**
*   Occasional GOAWAY lines are harmless.
*   If they keep coming:
    *   Check the panel's health and logs.
    *   Check the network path between the proxy and the panel.
    *   If Cloudflare or another reverse proxy sits in front of the panel, check its settings and logs for HTTP/2 problems or connection resets.
:::

### E. Plugin does not load or throws errors on startup

::: details Symptoms and checklist
**Symptoms**
*   VelocityPteroPower is missing from `/velocity plugins`.
*   Errors mentioning `de.tubyoub.velocitypteropower` appear while Velocity starts.

**Checklist**
1.  **Java and Velocity:** 0.9.6 needs Java 25 and Velocity 4.3+. Check `java -version` and `/velocity version` first.
2.  **Damaged jar:** download it again from the GitHub nightly or Modrinth.
3.  **Incomplete upload:** check that the whole file arrived in `plugins/`.
4.  **File permissions:** the Velocity process must be able to read the jar.
5.  **Broken `config.yml`:** back it up, delete it, let VPP write a fresh one, then copy your settings back.
6.  **Plugin conflicts (rare):** another plugin that bundles incompatible copies of shared libraries can break loading. If VPP fails only when a certain plugin is installed, test without that plugin.
:::

### F. Server stays in "starting"

::: details Symptoms and checklist
**Symptoms**
*   Players are told a server is starting, but it never finishes.
*   Later attempts keep saying the server is starting.

**What VPP does**
*   When the startup wait runs out (`startupInitialCheckDelay` + `startupTimeoutSeconds`), VPP tells the waiting players and clears the queue. With `startupTimeoutPolicy: CANCEL` it also clears the starting state. With `KEEP_STARTING` the server stays marked as starting.
*   A cleanup sweep runs every 60 seconds. It clears the starting state when the server is online, or when nobody is waiting and the start is older than `startupInitialCheckDelay` + `startupTimeoutSeconds` + 10 seconds.
*   If the panel websocket reports the server as offline after a start (for example, someone stopped it on the panel), VPP sends a new start for the players still waiting.

**Checklist**
1.  Look at the debug log for timeout, settle and soft requeue messages.
2.  Check that the `HYBRID` result (panel state and ping) matches what you see on the panel.
3.  Read the backend console on the panel for real startup failures.
4.  Set `printRateLimit: true` if status checks seem to stall.
5.  Soft requeue only helps with kicks right after the transfer that carry no message. If the world is still loading when players arrive, raise `startupJoinDelay`.
:::

### G. Addon install or browse looks wrong

::: details Symptoms and checklist
**Symptoms**
*   `/ptero addon browse` shows an addon as not installed although its jar is in the folder.
*   Installing from the catalog fails.

**Checklist**
1.  Addon jars go in `plugins/velocity-ptero-power/addons/`, not in Velocity's `plugins/` folder.
2.  Catalog installs download from the GitHub `nightly` prerelease. That release must exist and contain the addon jar.
3.  `browse` shows a jar as installed only when it loaded. Check the startup log for load errors, such as an API major version mismatch.
4.  The sample addon is not in the catalog. Build it yourself if you need it.
:::

## 4. Asking for support

If you are still stuck, ask on the [support Discord](https://discord.pluginz.dev). Bring:

*   **VelocityPteroPower version** (for example `0.9.6`)
*   **Velocity version** (for example `4.3.1`, from `/velocity version`)
*   **Panel type and version** (for example Pterodactyl 1.11.5, Pelican, Calagopus)
*   **Java version** (must be 25 for 0.9.6)
*   **Your `config.yml`**
    ::: danger Mask the API key
    Before you share `config.yml`, **always mask your `apiKey`**, like this:
    `apiKey: "ptlc_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"`
    :::
    *   Share it through [Pastebin](https://pastebin.com/) with the key masked.
*   **Language files** from `plugins/velocity-ptero-power/messages/<lang>.yml`, if the problem is about messages.
*   **Debug logs** (`loggerLevel: 10`) from proxy start until the failure, uploaded to [mclo.gs](https://mclo.gs/).
    ::: tip Use a paste site
    Do not paste long logs or configs straight into Discord. Use the links above.
    :::
*   **What you expected and what happened**, including any messages players saw.
*   **Steps to reproduce**, if you have them.
