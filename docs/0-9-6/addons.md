---
title: VPP addons
sidebar: true
---

# Addons 🧩

0.9.6 ships a small addon loader. Addons are **not** Velocity plugins. They are jars that implement `de.tubyoub.vpp.api.VPPAddon` and sit under:

```text
plugins/VelocityPteroPower/addons/
```

Configs: `plugins/VelocityPteroPower/addons/configs/{addonId}.yml`  
Data: `plugins/VelocityPteroPower/addons/data/{addonId}/`

## Official addons

| Id | What it does | Modrinth |
|----|--------------|----------|
| `vpp-webhooks` | Discord / HTTP webhooks on power and connect events | [project](https://modrinth.com/project/3UKAAshD) |
| `vpp-scheduler` | Cron start/stop for managed servers | [project](https://modrinth.com/project/ZfIvLQ4D) |
| `vpp-move-history` | Persistent `/ptero audit` history | [project](https://modrinth.com/project/KGDp7TnL) |
| `vpp-sample` | Author demo only | Build locally — no Modrinth page |

## Install paths

**In-game (catalog)** — `/ptero addon install <id>` downloads from GitHub Release assets:

`https://github.com/Tubs-Pluginz/VelocityPteroPower/releases/latest/download/vpp-addon-….jar`

CI uploads those jars when a GitHub Release is published. You do not hand-upload catalog files.

**Browser** — use the Modrinth project pages above if you want download stats split per addon. Drop the jar into the addons folder yourself.

**Manual drop** — copy any compatible jar into `addons/` and restart or `/ptero addon reload`. Browse treats a loaded jar as installed even if you never used the catalog command.

::: tip Sample addon
`vpp-sample` has no download URL in the catalog. Build it with `mvn -pl addons/sample -am package` if you are writing addons.
:::

## Commands

Base: `/ptero addon` (alias `/vpp addon`). Needs `ptero.addon` or `ptero.reload`.

| Command | Permission | Notes |
|---------|------------|--------|
| `list` | `ptero.addon` | Loaded addons and status |
| `browse` | `ptero.addon` | Official catalog |
| `info <id>` | `ptero.addon` | Local + catalog details |
| `install <id>` | `ptero.addon.install` | Catalog download |
| `update <id>` | `ptero.addon.install` | Re-download if newer |
| `uninstall <id>` | `ptero.addon.manage` | Unload + delete jar |
| `enable` / `disable` | `ptero.addon.manage` | Persist enable state |
| `reload [id\|all]` | `ptero.addon.manage` | Hot reload |

## Writing your own

Depend on `de.tubyoub:vpp-api` with `provided` scope, register via `META-INF/services/de.tubyoub.vpp.api.VPPAddon`, and match the host API major version. See `addons/sample` and `dev-examples/sample-addon-maven` in the repo.
