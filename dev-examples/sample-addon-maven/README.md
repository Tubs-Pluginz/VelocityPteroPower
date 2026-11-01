# Deprecated — use `addons/sample` instead

This folder is a **legacy** example: a Velocity plugin that talks to VPP via `VPPApiProvider`.

New addons should be **VPP-managed JARs** (not Velocity plugins). See:

- [`addons/sample`](../../addons/sample) — canonical example
- [`vpp-api`](../../vpp-api) — Maven artifact `de.tubyoub:vpp-api`

## If you still need this example

```bash
# from repo root
mvn -pl vpp-api package
mvn -f dev-examples/sample-addon-maven/pom.xml package
```

Or install the API into your local Maven repo:

```bash
mvn -pl vpp-api install
mvn -f dev-examples/sample-addon-maven/pom.xml -Puse-installed-vpp-api package
```
