# VPP Move History Addon

Official **VPP-managed** addon that persists player connect/switch/disconnect audit across restarts.

This is **independent** of core `/ptero history` (in-memory only). Use `/ptero audit` for the persisted store.

## Build

```bash
mvn -pl addons/move-history -am package
```

Output: `addons/move-history/target/vpp-addon-move-history-*.jar`

## Install

1. Copy the JAR into `plugins/VelocityPteroPower/addons/`
2. Restart Velocity, or run `/ptero addon reload all`
3. Optional: tune retention in `plugins/VelocityPteroPower/addons/configs/vpp-move-history.yml`

Data file: `plugins/VelocityPteroPower/addons/data/vpp-move-history/history.json`

## Config

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Master switch |
| `maxEntriesPerPlayer` | `100` | Cap per player (latest kept) |
| `retentionDays` | `30` | Drop entries older than N days (`0` = disable time purge) |
| `recordConnect` | `true` | Record post-connect |
| `recordDisconnect` | `true` | Record disconnect |
| `recordSwitch` | `true` | Record pre-server-switch |

## Commands

Permission: `vpp.audit.use` (lookup). Manage/clear/export also accept `vpp.audit.manage` or `ptero.history`.

- `/ptero audit <player\|uuid>` — show recent entries (MiniMessage)
- `/ptero audit clear <player\|uuid>` — clear one player
- `/ptero audit export [player\|uuid]` — write JSON snapshot under the addon data directory
