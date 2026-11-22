# VPP webhooks addon

Official VPP addon that posts power and player events to Discord or any webhook URL that accepts JSON.

## Build

```bash
mvn -pl addons/webhooks -am package
```

Output: `addons/webhooks/target/vpp-addon-webhooks-*.jar`

## Install

1. Copy the JAR into `plugins/velocity-ptero-power/addons/`, or run `/ptero addon install vpp-webhooks` to download it from the catalog.
2. Restart Velocity. VPP only scans the addons folder at startup. `/ptero addon reload vpp-webhooks` reloads an addon VPP already loaded, for example after you replace the JAR with one of the same file name.
3. Edit `plugins/velocity-ptero-power/addons/configs/vpp-webhooks.yml` and set `webhookUrl`.

## Config

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Turns sending on or off |
| `webhookUrl` | `""` | Main webhook URL |
| `webhookUrls` | `[]` | More URLs. Every event goes to all of them. |
| `format` | `auto` | `auto`, `discord` or `generic` |
| `events.power` | `true` | Power signals (`ServerPowerSignalEvent`). VPP fires this before it calls the panel. |
| `events.connect` | `true` | A player connected to a server |
| `events.disconnect` | `true` | A player left the proxy |
| `events.switch` | `false` | A player is about to switch servers |
| `skipCancelledPower` | `true` | Don't send power signals that another listener cancelled |

With `format: auto`, URLs containing `discord.com/api/webhooks` or `discordapp.com/api/webhooks` get Discord embeds. Every other URL gets a JSON body with `event`, `title`, `timestamp`, `source` and `payload`.

## Commands

Both commands need `vpp.webhooks.test`. `/ptero webhooks` is an alias.

- `/ptero webhook test` sends a test event to every configured URL.
- `/ptero webhook reload` reloads the addon config.
