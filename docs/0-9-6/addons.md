---
title: VPP addons
sidebar: true
---

# Addons

0.9.6 has its own addon loader. Addons are **not** Velocity plugins. They are jars that implement `de.tubyoub.vpp.api.VPPAddon` and go in:

```text
plugins/velocity-ptero-power/addons/
```

Configs: `plugins/velocity-ptero-power/addons/configs/{addonId}.yml`  
Data: `plugins/velocity-ptero-power/addons/data/{addonId}/`

## Official addons

| Id | What it does | Modrinth |
|----|--------------|----------|
| `vpp-webhooks` | Sends power and connect events to Discord or any HTTP webhook | [project](https://modrinth.com/project/3UKAAshD) |
| `vpp-scheduler` | Starts and stops managed servers on a cron schedule | [project](https://modrinth.com/project/ZfIvLQ4D) |
| `vpp-move-history` | Keeps connect and switch history across restarts, read with `/ptero audit` | [project](https://modrinth.com/project/KGDp7TnL) |
| `vpp-sample` | Example for addon authors | None, build it yourself |

## Installing

**From the catalog.** `/ptero addon install <id>` downloads the jar from the rolling GitHub `nightly` prerelease:

`https://github.com/Tubs-Pluginz/VelocityPteroPower/releases/download/nightly/vpp-addon-<name>.jar`

CI rebuilds that release, with the plugin and the official addons, on every push. The catalog list itself comes from `addons.catalogUrl`, and you can add your own with `addons.extraCatalogUrls` or `addons.extraEntries`.

**From Modrinth.** Download the jar from the project pages above and copy it into the addons folder yourself. Downloads there show up in each addon's Modrinth stats.

**By hand.** Copy any compatible jar into `addons/`, then restart the proxy or run `/ptero addon reload`. `browse` shows a loaded jar as installed even if it did not come from the catalog.

::: tip Sample addon
`vpp-sample` is not in the catalog. If you are writing an addon, build it with `mvn -pl addons/sample -am package`.
:::

## Commands

The base command is `/ptero addon` (or `/vpp addon`). You need `ptero.addon` or `ptero.reload` to use it. `ptero.reload` also works in place of the install and manage permissions below.

| Command | Permission | What it does |
|---------|------------|--------|
| `list` | `ptero.addon` | Shows loaded addons and their status |
| `browse` | `ptero.addon` | Shows the catalog |
| `info <id>` | `ptero.addon` | Shows local and catalog details |
| `install <id>` | `ptero.addon.install` | Downloads from the catalog |
| `update <id>` | `ptero.addon.install` | Downloads again if the catalog version differs from the loaded one |
| `uninstall <id>` | `ptero.addon.manage` | Unloads the addon and deletes its jar |
| `enable` / `disable` | `ptero.addon.manage` | Turns an addon on or off and saves that state |
| `reload [id\|all]` | `ptero.addon.manage` | Reloads one addon, or all of them when no id is given |

## Writing your own

Add `de.tubyoub:vpp-api` as a `provided` dependency and register your class in `META-INF/services/de.tubyoub.vpp.api.VPPAddon`. The addon's API version must have the same major version as the host's, or VPP refuses to load it. See `addons/sample` and `dev-examples/sample-addon-maven` in the repository.
