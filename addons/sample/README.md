# VPP Sample Addon

Example **VPP-managed** addon (not a Velocity plugin).

## Build

From the repository root:

```bash
mvn -pl addons/sample -am package
```

Output: `addons/sample/target/vpp-addon-sample-*.jar`

## Install

1. Copy the JAR into `plugins/VelocityPteroPower/addons/`
2. Restart Velocity, or run `/ptero addon reload all` if VPP is already running and the JAR was replaced
3. Or use `/ptero addon install vpp-sample` once an addon release asset is published (see catalog download URLs)

## Config

YAML is stored at `plugins/VelocityPteroPower/addons/configs/vpp-sample.yml` (legacy `addons/vpp-sample.yml` is migrated automatically).

## Commands (examples)

- `/ptero greet [name]`
- `/ptero sampleinfo`
- `/ptero cfg`
- `/ptero event`
- `/ptero servers`
- `/ptero route`
