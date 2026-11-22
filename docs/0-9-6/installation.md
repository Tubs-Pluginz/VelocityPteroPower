---
title: VPP installation
---

# Installation

## Requirements

*   Velocity **4.3+**. VPP is built against the 4.3.0 API; 4.3.1 is a good version to test on.
*   Java **25** on the proxy host
*   A panel client API key: `ptlc_` (Pterodactyl), `plcn_` or `pacc_` (Pelican), or `c7sp_` (Calagopus). A key with none of these prefixes is treated as an MC Server Soft key.

0.9.6 does not run on Velocity 3.x or Java 21.

## Steps

1. **Download** the current pre-release jar (`velocitypteropower-0.9.6-beta-*.jar`) from the rolling [GitHub nightly](https://github.com/Tubs-Pluginz/VelocityPteroPower/releases/tag/nightly). The same builds go to Modrinth as alpha versions. Stable Modrinth and GitHub releases stay on 0.9.5 until 0.9.6 ships.

2. **Copy it** into the Velocity `plugins/` folder.

3. **Start the proxy once.** VPP writes `plugins/velocity-ptero-power/config.yml` and the `plugins/velocity-ptero-power/messages/` folder.

   ::: info First launch
   Language files live under `messages/`, for example `en_US.yml`. VPP no longer uses a single `messages.yml` in the plugin folder.
   :::

4. **Stop the proxy** and edit `config.yml`. Set the panel URL and API key, and add each server VPP should manage under `servers:` with its panel ID in quotes.

   ```yaml
   servers:
     survival:
       id: "91e62747"  # always quote it, YAML reads some bare hex IDs as numbers (91e62747 becomes Infinity)
       timeout: 300
       startupJoinDelay: 15
   ```

   The server name (`survival` here) must match the name in `velocity.toml`.

5. **Start the proxy again** and check the console for lines from `velocity-ptero-power`. If the API key is wrong or is an application key (`ptla_`, `peli_`), VPP prints an "Initialization Failed" block and disables itself.

Next steps: set up limbos and lobbies in [Lobbies and limbo](lobbies-and-limbo.md), look up any setting in [Configuration](configuration.md), or add the official extras from [Addons](addons.md).

::: tip Read the log first
If something looks wrong, set `loggerLevel: 10`, run `/ptero reload`, and read the proxy log before you change anything on the panel.
:::
