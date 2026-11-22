# VPP scheduler addon

Official VPP addon that starts and stops managed servers on a cron schedule through `ServerControl`.

## Build

```bash
mvn -pl addons/scheduler -am package
```

Output: `addons/scheduler/target/vpp-addon-scheduler-*.jar`. cron-utils is shaded into the JAR.

## Install

1. Copy the JAR into `plugins/velocity-ptero-power/addons/`, or run `/ptero addon install vpp-scheduler` to download it from the catalog.
2. Restart Velocity. VPP only scans the addons folder at startup. `/ptero addon reload vpp-scheduler` reloads an addon VPP already loaded, for example after you replace the JAR with one of the same file name.
3. Edit `plugins/velocity-ptero-power/addons/configs/vpp-scheduler.yml`.

## Config

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Turns the scheduler on or off |
| `cronHelpUrl` | `https://crontab.guru/` | Link shown in `/ptero schedule` output |
| `timezone` | `UTC` | Zone ID used to evaluate cron expressions, for example `Europe/Berlin` |
| `checkIntervalSeconds` | `30` | How often schedules are checked. Minimum `5`. |
| `schedules` | one disabled example | List of schedule entries |

`/ptero schedule reload` re-reads `timezone` and `schedules`. A new `checkIntervalSeconds` takes effect after `/ptero addon reload vpp-scheduler` or a restart.

### Schedule entry

```yaml
schedules:
  - name: evening-start
    enabled: true
    cron: "0 18 * * *"   # minute hour day-of-month month day-of-week (UNIX)
    action: start          # start | stop
    servers: [lobby, survival]
```

Cron expressions use the 5-field UNIX format. `enabled` defaults to `true` if you leave it out, and `servers` also accepts a comma-separated string.

Core VPP idle shutdown still applies after a scheduled start.

## Commands

Both commands need `vpp.scheduler.manage`.

- `/ptero schedule list` shows each schedule and its next run time.
- `/ptero schedule reload` reloads the config.
