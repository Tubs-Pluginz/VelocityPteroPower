# VPP move history addon

Official VPP addon that saves player connects, server switches and disconnects to disk, so the audit survives proxy restarts.

It is separate from the core `/ptero history` command, which keeps moves in memory only. Use `/ptero audit` to read the saved history.

## Build

```bash
mvn -pl addons/move-history -am package
```

Output: `addons/move-history/target/vpp-addon-move-history-*.jar`

## Install

1. Copy the JAR into `plugins/velocity-ptero-power/addons/`, or run `/ptero addon install vpp-move-history` to download it from the catalog.
2. Restart Velocity. VPP only scans the addons folder at startup. `/ptero addon reload vpp-move-history` reloads an addon VPP already loaded, for example after you replace the JAR with one of the same file name.
3. Optional: change retention in `plugins/velocity-ptero-power/addons/configs/vpp-move-history.yml`.

Data file: `plugins/velocity-ptero-power/addons/data/vpp-move-history/history.json`

## Config

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Turns recording and `/ptero audit` on or off |
| `maxEntriesPerPlayer` | `100` | Entries kept per player. The oldest are dropped first. |
| `retentionDays` | `30` | Drop entries older than this many days. `0` turns off the age limit. |
| `recordConnect` | `true` | Record when a player connects to a server |
| `recordDisconnect` | `true` | Record when a player leaves the proxy |
| `recordSwitch` | `true` | Record when a player switches servers (before the switch) |

VPP also purges expired entries every 30 minutes.

## Commands

`/ptero audit` needs `vpp.audit.use`. `clear` and `export` also need `vpp.audit.manage` or `ptero.history`. The aliases `/ptero moveaudit` and `/ptero phistory` work too.

- `/ptero audit <player|uuid>` shows the 25 most recent entries.
- `/ptero audit clear <player|uuid>` deletes one player's history.
- `/ptero audit export [player|uuid]` writes a JSON file to the addon data folder. Without a player it exports everyone.
