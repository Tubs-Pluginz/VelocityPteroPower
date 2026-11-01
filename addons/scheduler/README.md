# VPP Scheduler Addon

Official **VPP-managed** addon that starts/stops managed servers on a cron schedule via `ServerControl`.

## Build

```bash
mvn -pl addons/scheduler -am package
```

Output: `addons/scheduler/target/vpp-addon-scheduler-*.jar` (cron-utils shaded in)

## Install

1. Copy the JAR into `plugins/VelocityPteroPower/addons/`
2. Restart Velocity, or run `/ptero addon reload all`
3. Edit `plugins/VelocityPteroPower/addons/configs/vpp-scheduler.yml`

## Config

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Master switch |
| `cronHelpUrl` | `https://crontab.guru/` | Help URL for writing cron expressions |
| `timezone` | `UTC` | Zone ID for cron evaluation (e.g. `Europe/Berlin`) |
| `checkIntervalSeconds` | `30` | How often schedules are evaluated |
| `schedules` | example entry (disabled) | List of schedule maps |

### Schedule entry

```yaml
schedules:
  - name: evening-start
    enabled: true
    cron: "0 18 * * *"   # minute hour day-of-month month day-of-week (UNIX)
    action: start          # start | stop
    servers: [lobby, survival]
```

Cron expressions use **UNIX** format (5 fields). See the URL in `cronHelpUrl`.

Idle shutdown from core VPP still applies after a scheduled start.

## Commands

- `/ptero schedule list` — show schedules and next fire times (`vpp.scheduler.manage`)
- `/ptero schedule reload` — reload config
