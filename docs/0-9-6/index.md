---
title: VelocityPteroPower 0.9.6
---

# VelocityPteroPower

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300" alt="Discord Invite">
    </a>
    <br>
    <i>Questions? Ask on the Discord.</i>
    <br>
     <i>Developed by TubYoub</i>
</p>

VelocityPteroPower (VPP) is a Velocity proxy plugin that starts and stops game servers on [Pterodactyl](https://pterodactyl.io/), [Pelican](https://pelican.dev/), [Calagopus](https://calagopus.org/), or [MC Server Soft](https://mcserversoft.com/) when players need them.

It started as a Velocity port of [BungeePteroPower](https://github.com/Kamesuta/BungeePteroPower). **0.9.6 requires Velocity 4.3+ and Java 25** on the proxy. There are no Velocity 3 or Java 21 builds of 0.9.6.

::: warning Breaking change in 0.9.6
Upgrade the proxy to Velocity 4.3.x and Java 25 before you install this jar. It will not run on Velocity 3 or Java 21.
:::

::: tip What VPP does
A player tries to join a managed server that is offline. VPP sends a start signal to the panel and moves the player to a limbo or lobby if you set one up. It waits until the server accepts players, then transfers them. When a server has been empty for its configured timeout, VPP stops it.
:::

## What's new in 0.9.6

*   Velocity 4.3 and Java 25 are now required on the proxy host.
*   Calagopus panels are supported. Use a client key that starts with `c7sp_`.
*   Startup waiting has separate settings for polling (`startupPollInterval`) and the delay after the server is up (`startupJoinDelay`). There is also a hard limit (`startupTimeoutSeconds`), a soft requeue when the backend kicks a player right after transfer, and queue progress on the action bar.
*   The default ready check is `HYBRID`: the panel must report "running" and a Velocity ping must succeed.
*   `sendToLimboOnStart` turns on by default when you list limbos.
*   Start-wait queues survive a reconnect (`persistentQueueEnabled`).
*   Official addons for webhooks, scheduled start/stop, and persistent move history. Each has its own Modrinth page, and `/ptero addon install` downloads the jars from the GitHub nightly release.
*   A public `vpp-api` module for writing your own addons.

## Features

*   Starts a backend server when a player tries to join it.
*   Stops empty servers after a per-server `timeout`, and stops servers that VPP started but nobody joined (`idleStartShutdownTime`).
*   Manual control with `/ptero start`, `stop`, `restart`, plus commands to stop all idle servers or start and stop everything at once.
*   Reads the panel's rate limit headers. Player-triggered starts go through their own priority lane, so background prefetching cannot use up the requests they need.
*   Holds players on a limbo or lobby while their target server boots, for both forced-host joins and `/server` switches.
*   Can fetch `whitelist.json` from the panel and enforce it before starting a server.
    ::: warning MC Server Soft limitation
    Whitelist fetching does not work on MC Server Soft, because its API has no file access.
    :::
*   `/ptero list` and `/ptero info <server>` show status, player counts, time until idle shutdown, and resource usage.
*   Keeps servers in `alwaysOnline` running and never idle-stops them.
*   Caches panel resource responses, and can prefetch them so commands answer quickly.
*   Loads addons from `plugins/velocity-ptero-power/addons/` and exposes a public API for them.
*   `/ptero reload` applies most config changes without restarting the proxy.
*   Optional update check against Modrinth on startup.

---

## Where to go next

*   Set it up: [Installation](./installation.md)
*   Every setting: [Configuration](./configuration.md)
*   How starts and stops work: [Core mechanics](./core-mechanics.md)
*   Waiting rooms and hubs: [Lobbies and limbo](./lobbies-and-limbo.md)
*   Extensions: [Addons](./addons.md)
*   Reference: [Commands](./commands.md) and [Permissions](./permissions.md)
*   Something broken: [FAQ](./faq.md) and [Troubleshooting](./troubleshooting.md)
