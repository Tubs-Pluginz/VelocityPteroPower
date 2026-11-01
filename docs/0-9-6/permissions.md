---
title: VPP permission
---
# Permissions Reference 🔑

Assign these with a Velocity permissions plugin (LuckPerms works fine).

## Command permissions

*   `ptero.start` — `/ptero start <server>`
*   `ptero.stop` — `/ptero stop <server>`
*   `ptero.restart` — `/ptero restart <server>`
*   `ptero.list` — `/ptero list`
*   `ptero.info` — `/ptero info <server>` and `/ptero apithreads`
*   `ptero.stopIdle` — `/ptero stopidle`
*   `ptero.forcestopall` — `/ptero forcestopall [confirm]`
        ::: danger High Privilege
        Stops every managed server. Keep this on trusted admins only.
        :::
*   `ptero.forcestartall` — `/ptero forcestartall [confirm]`
*   `ptero.whitelistReload` — `/ptero whitelistReload`
        ::: warning Panel Limitation
        Only useful when the panel can fetch files (Pterodactyl / Pelican / Calagopus). No effect on MC Server Soft.
        :::
*   `ptero.reload` — `/ptero reload` (also opens the addon command tree)
*   `ptero.history` — in-memory `/ptero history`
*   `ptero.maintenance` — built-in `/ptero maintenance` (or use `ptero.reload`)

## Special permissions

*   `ptero.bypass` — if `whitelistAllowBypass: true`, skips VPP's own whitelist gate for servers with `whitelist: true`.
::: danger Backend Whitelist Still Applies
`ptero.bypass` only skips VPP's check. The Minecraft backend still enforces its own whitelist. Staff can trigger a start and still get denied by the game server.
:::

*   `ptero.maxcap.bypass` — ignore `maxOnlineServers` when starting.

## Addon permissions

*   `ptero.addon` — list / browse / info
*   `ptero.addon.install` — install / update from catalog
*   `ptero.addon.manage` — uninstall / enable / disable / reload

::: tip Best Practices
Give high-privilege nodes (`forcestopall`, `forcestartall`, addon manage) only to people who already break things on purpose.
:::
