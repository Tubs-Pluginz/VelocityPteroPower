---
title: VPP permission
---
# Permissions Reference 🔑

VelocityPteroPower utilizes a set of permission nodes to grant access to its commands and special functionalities. You'll need a Velocity-compatible permissions plugin (like LuckPerms) to assign these to your players or staff groups.

## Command Permissions

These permissions grant access to specific `/ptero` (or `/vpp`) sub-commands:

*   `ptero.start`
    *   **Allows:** Using the `/ptero start <serverName>` command.
    *   **Purpose:** Enables manual starting of managed servers.

*   `ptero.stop`
    *   **Allows:** Using the `/ptero stop <serverName>` command.
    *   **Purpose:** Enables manual stopping (graceful shutdown) of managed servers.

*   `ptero.restart`
    *   **Allows:** Using the `/ptero restart <serverName>` command.
    *   **Purpose:** Enables manual restarting of managed servers.

*   `ptero.list`
    *   **Allows:** Using the `/ptero list` command.
    *   **Purpose:** Displays the formatted list of managed servers with status, players, and TTL.

*   `ptero.info`
    *   **Allows:** Using the `/ptero info <serverName>` and `/ptero apithreads` commands.
    *   **Purpose:**
        * `/ptero info` shows a detailed resource panel for a managed server (CPU, memory, disk, net, uptime).
        * `/ptero apithreads` shows executor thread configuration: configured value, server count, auto baseline, and final threads in use.

*   `ptero.stopIdle`
    *   **Allows:** Using the `/ptero stopidle` command.
    *   **Purpose:** Enables manual triggering of a shutdown for all eligible empty servers (respecting `stopIdleIgnore` list).

*   `ptero.forcestopall`
    *   **Allows:** Using the `/ptero forcestopall` command (and its `confirm` argument).
    *   **Purpose:** Enables manual triggering of a shutdown for ALL managed servers.
        ::: danger High Privilege
        This is a powerful permission. Grant it only to trusted administrators due to its potential to disrupt all managed servers.
        :::

*   `ptero.forcestartall`
    *   **Allows:** Using the `/ptero forcestartall` command (and its `confirm` argument).
    *   **Purpose:** Starts ALL managed servers. This can consume significant resources; grant only to trusted administrators.

*   `ptero.whitelistReload`
    *   **Allows:** Using the `/ptero whitelistReload` command.
    *   **Purpose:** Enables forcing an immediate update of server whitelists from the panel.
        ::: warning Panel Limitation
        This permission is only effective if your panel supports whitelist fetching (Pterodactyl/Pelican). It has no effect with MC Server Soft.
        :::

*   `ptero.reload`
    *   **Allows:** Using the `/ptero reload` command.
    *   **Purpose:** Enables reloading of VPP's configuration files (`config.yml`, `messages.yml`).

## Special Permissions

*   `ptero.bypass`
    *   **Allows:** If `whitelistAllowBypass: true` is set in `config.yml`, players with this permission can bypass VPP's internal whitelist check when attempting to connect to a server that has `whitelist: true` in its per-server configuration.
    *   **Purpose:** Useful for administrators or testers who need to access servers without being on the formal `whitelist.json`, while still having VPP manage the server's startup.
    <br>
::: danger Backend Whitelist Still Applies
Granting `ptero.bypass` **only** bypasses VPP's preliminary check. The actual Minecraft server (`spigot.yml`, `server.properties`, or `whitelist.json` on the backend) will still enforce its own whitelist. If a player with `ptero.bypass` is not on the backend server's whitelist, they will be able to *trigger the server start* via VPP but will still be denied entry by the Minecraft server itself.
:::

::: tip Best Practices
*   Grant permissions sparingly and only to users or groups that absolutely need them.
*   Regularly review who has high-privilege permissions like `ptero.forcestopall`.
:::


## Additional Special Permissions

*   `ptero.maxcap.bypass`
    *   **Allows:** Players with this permission can bypass the `maxOnlineServers` cap when starting servers.
    *   **Purpose:** Grant to trusted staff who may need to temporarily exceed the online server cap.
