# VPP sample addon

Example VPP addon. VPP loads it from its own addons folder. It is not a Velocity plugin.

## Build

From the repository root:

```bash
mvn -pl addons/sample -am package
```

Output: `addons/sample/target/vpp-addon-sample-*.jar`

## Install

The sample addon is not in the addon catalog or on the nightly release, so build it yourself.

1. Copy the JAR into `plugins/velocity-ptero-power/addons/`.
2. Restart Velocity. VPP only scans the addons folder at startup. If VPP already loaded the addon and you replaced the JAR with one of the same file name, `/ptero addon reload vpp-sample` is enough.

## Config

The config file is `plugins/velocity-ptero-power/addons/configs/vpp-sample.yml`. VPP moves an old `addons/vpp-sample.yml` there on first load.

## Commands

Each command needs `vpp.sample.<command>`, for example `vpp.sample.greet`. `/ptero sampleinfo` uses `vpp.sample.info`.

- `/ptero greet [name]`
- `/ptero sampleinfo`
- `/ptero cfg`
- `/ptero event`
- `/ptero http`
- `/ptero servers`
- `/ptero route`
