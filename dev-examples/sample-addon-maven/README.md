# Deprecated, use `addons/sample` instead

This folder is the old example: a Velocity plugin that talks to VPP through `VPPApiProvider`.

New addons should be JARs that VPP loads itself, not Velocity plugins. See:

- [`addons/sample`](../../addons/sample), the current example
- [`vpp-api`](../../vpp-api), Maven artifact `de.tubyoub:vpp-api`

## If you still need this example

```bash
# from repo root
mvn -pl vpp-api package
mvn -f dev-examples/sample-addon-maven/pom.xml package
```

Or install the API (and the parent POM it references) into your local Maven repo:

```bash
mvn -pl vpp-api -am install
mvn -f dev-examples/sample-addon-maven/pom.xml -Puse-installed-vpp-api package
```
