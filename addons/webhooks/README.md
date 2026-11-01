# VPP Webhooks Addon

Official **VPP-managed** addon that posts power and connect events to Discord or generic webhook endpoints.

## Build

```bash
mvn -pl addons/webhooks -am package
```

Output: `addons/webhooks/target/vpp-addon-webhooks-*.jar`

## Install

1. Copy the JAR into `plugins/VelocityPteroPower/addons/`
2. Restart Velocity, or run `/ptero addon reload all`
3. Edit `plugins/VelocityPteroPower/addons/configs/vpp-webhooks.yml` and set `webhookUrl`

## Config

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Master switch |
| `webhookUrl` | `""` | Primary webhook URL |
| `webhookUrls` | `[]` | Extra URLs |
| `format` | `auto` | `auto` / `discord` / `generic` |
| `events.power` | `true` | `ServerPowerSignalEvent` (fires **before** panel call) |
| `events.connect` | `true` | Post-connect |
| `events.disconnect` | `true` | Disconnect |
| `events.switch` | `false` | Pre-server-switch |
| `skipCancelledPower` | `true` | Skip cancelled power signals |

`format: auto` uses Discord embeds when the URL looks like a Discord webhook; otherwise sends structured JSON.

## Commands

- `/ptero webhook test` — send a sample payload (`vpp.webhooks.test`)
- `/ptero webhook reload` — reload addon config
