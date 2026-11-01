---
title: VPP installation
---

# Installation Guide 🚀

## Requirements

*   Velocity **4.3+** (test against **4.3.1** if you can)
*   Java **25** on the proxy host
*   A panel client API key (`ptlc_`, `plcn_`/`pacc_`, or `c7sp_`)

Velocity 3.x and Java 21 will not work with 0.9.6.

## Steps

1. **Download** the shaded jar from [Modrinth](https://modrinth.com/plugin/velocitypteropower) or [GitHub Releases](https://github.com/Tubs-Pluginz/VelocityPteroPower/releases). Use `velocitypteropower-*-shaded.jar` when both jars are present.

2. **Drop it** into the Velocity `plugins/` folder.

3. **Start the proxy once** so VPP writes `plugins/VelocityPteroPower/config.yml` and `plugins/VelocityPteroPower/messages/`.

   ::: info First launch
   Language files live under `messages/` (for example `en_US.yml`). A single root `messages.yml` is no longer used.
   :::

4. **Stop the proxy**, edit `config.yml`: panel URL, API key, and each managed server under `servers:` with a quoted panel id.

   ```yaml
   servers:
     survival:
       id: "91e62747"  # always quote — YAML loves turning bare hex into Infinity
       timeout: 300
       startupJoinDelay: 15
   ```

5. **Start again.** Check the console for `[VPP]` / `VelocityPteroPower` lines.

Optional: limbos and lobbies → [Lobbies & limbo](lobbies-and-limbo.md). Full knobs → [Configuration](configuration.md). Official extras → [Addons](addons.md).

::: tip Logs first
If something feels wrong, set `loggerLevel: 10`, reload, and read the proxy log before guessing at the panel.
:::
