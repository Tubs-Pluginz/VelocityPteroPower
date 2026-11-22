---
title: VPP installation
---
# Installation Guide 🚀

Installing VelocityPteroPower on your Velocity server is a simple process:

1.  **Download the Plugin:**
    Obtain the latest `.jar` file for VelocityPteroPower from its official release page:
    *   [Modrinth (Recommended)](https://modrinth.com/plugin/velocitypteropower)
    *   [GitHub Releases](https://github.com/TubYoub/VelocityPteroPower/releases)

2.  **Place the Plugin File:**
    Locate your Velocity server's `plugins` folder and place the downloaded `VelocityPteroPower-x.y.z.jar` file into it.

3.  **Start/Restart Your Server:**
    Start or restart your Velocity proxy server.

    ::: info First Launch
    On its first launch, VelocityPteroPower will:
    *   Generate its main configuration file: `plugins/VelocityPteroPower/config.yml`
    *   Create a `messages/` folder with language files (e.g., `en_US.yml`, `de_DE.yml`) under `plugins/VelocityPteroPower/messages/`.
      - Note: the old single `messages.yml` file is deprecated and no longer used.
    :::

4.  **Initial Configuration:**
    Stop your Velocity server again. Now, open the generated `plugins/VelocityPteroPower/config.yml` and configure it according to your panel details and server setup. This step is crucial for the plugin to function correctly.

    Refer to the [Configuration Guide](configuration.md) for detailed explanations of all options.
    If you plan to use multiple lobbies/limbos, see the simple [Lobbies & Limbo guide](lobbies-and-limbo.md).

5.  **Final Restart:**
    After configuring `config.yml`, start your Velocity server one more time.

::: tip Check Your Logs!
After installation and configuration, always check your Velocity console logs for any messages from `[VPP]` or `VelocityPteroPower`. This will help you confirm if the plugin loaded correctly and if it successfully connected to your panel API.
:::

That's it! VelocityPteroPower should now be installed and ready to manage your servers.

# Installation

1. Place the VelocityPteroPower jar into your Velocity plugins folder and restart the proxy.
2. Edit `plugins/VelocityPteroPower/config.yml`.
3. Under the `servers:` section, define each managed server.

Important: Always quote the `id` under each server. Example:

servers:
  mc-vanilla-6:
    id: "91e62747"  # quoted to avoid YAML scientific-notation parsing
    timeout: 30
    startupJoinDelay: 10
    whitelist: true

If you omit the quotes (id: 91e62747), YAML may parse it as a number in scientific notation, resulting in an invalid value like `Infinity`. The plugin will warn and skip such entries.