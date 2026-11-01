---
title: VelocityPteroPower 0.9.6
---

# VelocityPteroPower ⚡️

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300" alt="Discord Invite">
    </a>
    <br>
    <i>Please join the Discord if you have questions!</i>
    <br>
     <i>Developed by TubYoub</i>
</p>

VelocityPteroPower (VPP) is a Velocity proxy plugin that starts and stops game servers on [Pterodactyl](https://pterodactyl.io/), [Pelican](https://pelican.dev/), [Calagopus](https://calagopus.org/), or [Mc Server Soft](https://mcserversoft.com/) when players need them.

It began as a Velocity port of [BungeePteroPower](https://github.com/Kamesuta/BungeePteroPower). **0.9.6 requires Velocity 4.3+ and Java 25** on the proxy. Velocity 3 / Java 21 builds are out.

::: warning Breaking change in 0.9.6
Upgrade the proxy to Velocity 4.3.x and Java 25 before dropping in this jar. The shaded plugin will not run on Velocity 3 / Java 21.
:::

::: tip What can VPP do for you?
A player hits an offline managed server. VPP sends a start to the panel, parks them on limbo/lobby if you configured one, waits until the server is actually joinable, then transfers them. Empty servers shut down on a timer so idle boxes are not burning money.
:::

## What's new in 0.9.6

*   **Velocity 4.3 / Java 25** — hard requirement on the proxy host.
*   **Calagopus** — client keys starting with `c7sp_`.
*   **Smarter startups** — `startupPollInterval` vs `startupJoinDelay`, `startupTimeoutSeconds`, soft-requeue after early kicks, action-bar queue progress.
*   **HYBRID ready-check** — default is panel "running" *and* a Velocity ping.
*   **Limbo by default** — `sendToLimboOnStart` turns on when you list limbos.
*   **Official addons** — webhooks, scheduler, move-history (separate Modrinth pages; in-game install pulls GitHub Release jars).
*   **Public `vpp-api`** — write your own addons against the reactor modules.

## Key Features

*   **🚀 Dynamic server management:** Starts backend servers when players try to join them.
*   **💤 Idle shutdown:** Stops empty servers after a per-server `timeout` (and idle-start safeguards).
*   **🔧 Manual control:** `/ptero start`, `stop`, `restart`, bulk stop/start helpers.
*   **🚦 Rate limit handling:** Tracks panel remaining/reset headers; player starts use a priority lane so prefetch cannot starve them.
*   **🏝️ Limbo / lobby holding:** Forced-host and mid-session joins can wait on limbo while the target boots.
*   **✅ Whitelist fetch:** Optional `whitelist.json` pull from the panel for managed servers.
    ::: warning MC Server Soft Limitation
    Whitelist fetching is **not** available on MC Server Soft (no file API).
    :::
*   **📋 List & info panels:** MiniMessage `/ptero list` and `/ptero info <server>` with status, players, TTL, and resource usage.
*   **🟢 Always-online:** Keeps listed servers up and skips idle stop for them.
*   **🧊 Resource cache:** Caches (and can prefetch) panel resource responses for snappy commands.
*   **🧩 Addons:** Official jars under `plugins/VelocityPteroPower/addons/` plus a public API.
*   **🔄 Config reload:** `/ptero reload` for most settings without a full proxy restart.
*   **🔔 Update checks:** Optional Modrinth update notice on startup.

---

**Ready to dive in?**

*   Install: [**Installation Guide &rarr;**](./installation.md)
*   Config knobs: [**Configuration Details &rarr;**](./configuration.md)
*   How starts work: [**Core Mechanics &rarr;**](./core-mechanics.md)
*   Lobbies & limbo: [**Lobbies & Limbo &rarr;**](./lobbies-and-limbo.md)
*   Addons: [**Addons &rarr;**](./addons.md)
*   Commands / permissions: [**Commands**](./commands.md) · [**Permissions**](./permissions.md)
*   Stuck? [**FAQ**](./faq.md) · [**Troubleshooting**](./troubleshooting.md)
