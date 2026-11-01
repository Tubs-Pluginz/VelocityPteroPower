---
# title: VelocityPteroPower - Introduction (Optional, VitePress often infers from H1)
# editLink: true # Optional: if you want an "Edit this page" link for this page
# sidebar: true # Usually not needed if a sidebar config matches this path, but can be explicit.
---

# VelocityPteroPower ⚡️

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300" alt="Discord Invite">
    </a>
    <br>
    <i>Please join the Discord if you have questions!</i>
    <br>
     <i>Developed by TubYoub</i>
</p>

Welcome to the official documentation for **VelocityPteroPower (VPP)**!

VPP is a powerful Velocity proxy plugin designed to seamlessly integrate your Minecraft servers managed by the [Pterodactyl](https://pterodactyl.io/), [Pelican](https://pelican.dev/) or [Mc Server Soft](https://mcserversoft.com/) Panel. It provides dynamic control over your servers, enabling automatic startup when players connect and intelligent shutdown of idle instances, optimizing your server resources and player experience.

This project is a port of the popular [BungeePteroPower](https://github.com/Kamesuta/BungeePteroPower) plugin, bringing its functionality to the Velocity proxy platform.

::: tip What can VPP do for you?
VPP automates the tedious parts of managing a dynamic server network, such as starting servers on demand and stopping them when they're not in use. This saves server resources and provides a smoother, more responsive experience for your players.
:::

## Key Features

*   **🚀 Dynamic Server Management:** Automatically starts backend Minecraft servers when players attempt to connect to them.
*   **💤 Idle Server Shutdown:** Configures servers to automatically shut down when they are empty for a specified period, conserving valuable server resources.
*   **🔧 Manual Control:** Provides administrators with in-game commands to manually start, stop, and restart managed servers.
*   **🚦 Rate Limit Handling:** Respects the panel API's rate limits to prevent issues and ensure stable communication with your panel.
*   **🏝️ Forced Host Redirection:** Players connecting through forced hosts to an offline server can be temporarily redirected to a configured "limbo" server while their target server starts up.
*   **✅ Whitelist Integration:** Optionally fetches and enforces server whitelists directly from the panel for managed servers.
    ::: warning MC Server Soft Limitation
    Please note that the whitelist fetching feature is **not supported** if your panel is MC Server Soft due to API differences.
    :::
*   **🔄 Config Reload:** Allows for easy reloading of the plugin's configuration files without requiring a full proxy restart.
*   **🔔 Update Checks:** Keeps you informed about new plugin versions with automatic update checks on startup.

VelocityPteroPower aims to streamline the management of your dynamic Minecraft server infrastructure, offering a robust and user-friendly solution for both players and administrators.

---

**Ready to dive in?**

*   Learn how to install the plugin: [**Installation Guide &rarr;**](./installation.md)
*   Understand all configuration options: [**Configuration Details &rarr;**](./configuration.md)
*   Explore how VPP works: [**Core Mechanics &rarr;**](./core-mechanics.md)
