---
title: VPP permission
---
# Permissions reference

Assign these with a Velocity permissions plugin such as LuckPerms.

## Command permissions

| Permission | Grants |
| --- | --- |
| `ptero.start` | `/ptero start <server>` |
| `ptero.stop` | `/ptero stop <server>` |
| `ptero.restart` | `/ptero restart <server>` |
| `ptero.list` | `/ptero list`. Also shows every queue in `/ptero queue`. |
| `ptero.info` | `/ptero info <server>`, `/ptero apithreads` and `/ptero api` |
| `ptero.history` | `/ptero history`, the in-memory move history |
| `ptero.stopIdle` | `/ptero stopidle` |
| `ptero.forcestopall` | `/ptero forcestopall [confirm]` |
| `ptero.forcestartall` | `/ptero forcestartall [confirm]` |
| `ptero.whitelistReload` | `/ptero whitelistReload` |
| `ptero.maintenance` | `/ptero maintenance` (built-in maintenance mode) |
| `ptero.password` | `/ptero password` (manage start passwords) |
| `ptero.reload` | `/ptero reload`. Also grants `/ptero maintenance`, `/ptero password`, every `/ptero addon` subcommand, and the staff view of `/ptero queue`. |

`/ptero queue` and `/ptero unlock` need no permission.

::: danger High privilege
`ptero.forcestopall` stops every managed server. Give it to trusted admins only.
:::

::: warning Panel limitation
`ptero.whitelistReload` only does something on panels VPP can read files from (Pterodactyl, Pelican, Calagopus). It has no effect on MC Server Soft.
:::

## Bypass permissions

| Permission | Effect |
| --- | --- |
| `ptero.bypass` | Skips VPP's whitelist check for servers with `whitelist: true`. Only works while `whitelistAllowBypass: true`. |
| `ptero.maxcap.bypass` | Ignores the `maxOnlineServers` cap when starting a server. Only works while `maxOnlineAllowBypass: true`. |
| `ptero.password.bypass` | Starts a server that has a `startPassword` without running `/ptero unlock`. |

::: danger The backend whitelist still applies
`ptero.bypass` only skips VPP's check. The Minecraft server still enforces its own whitelist, so a player with this permission can start a server and then get kicked by it.
:::

If you run kennytv's Maintenance plugin, VPP also respects that plugin's own bypass nodes when it decides whether a start is blocked: `maintenance.admin`, `maintenance.bypass`, and `maintenance.singleserver.bypass.<server>`.

## Addon permissions

| Permission | Grants |
| --- | --- |
| `ptero.addon` | `/ptero addon list`, `browse` and `info` |
| `ptero.addon.install` | `/ptero addon install` and `update` |
| `ptero.addon.manage` | `/ptero addon uninstall`, `enable`, `disable` and `reload` |

`ptero.reload` grants all three.

Addons can register their own commands with their own permission nodes. The move-history addon, for example, uses `vpp.audit.use` for `/ptero audit` and `vpp.audit.manage` (or `ptero.history`) for `clear` and `export`. See [Addons](addons.md).

::: tip
Give `forcestopall`, `forcestartall` and `ptero.addon.manage` only to people you trust to take servers down.
:::
