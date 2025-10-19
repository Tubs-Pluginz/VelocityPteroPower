# VelocityPteroPower
![Static Badge](https://img.shields.io/badge/Velocity-green) <br>
[![forthebadge](https://forthebadge.com/images/badges/works-on-my-machine.svg)](https://forthebadge.com) <br> <br>
<a href="https://www.buymeacoffee.com/tubyoub" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300">
    </a>
    <br>
    <i>Please join the Discord if you have questions or for support!</i>
</p>

**Manage your game servers with ease!** VelocityPteroPower connects your Velocity proxy to Pterodactyl, Pelican Panel, or Mc Server Soft, allowing for dynamic starting and stopping of your game servers.

**For detailed information on configuration, commands, and permissions, please visit our [Docs Page](https://docs.pluginz.dev/).**

## Key Features
-   **Multi-Panel Support**: Works with Pterodactyl, Pelican Panel, and Mc Server Soft.
-   **Dynamic Server Management**:
    -   Automatically start servers when a player attempts to connect.
    -   Automatically stop idle servers after a configurable timeout.
    -   Manual control via commands (`/ptero start`, `/ptero stop`, `/ptero restart`).
-   **Whitelist Integration**:
    -   Fetch and enforce server whitelists from Pterodactyl/Pelican panels.
    -   Reload whitelists on the fly with `/ptero whitelistReload`.
-   **Efficient & Safe**:
    -   Respects panel API rate limits (Pterodactyl/Pelican/Mc Server Soft).
    -   Handles forced host connections, redirecting players to a limbo server.
-   **Update Notifications**: Stay informed about new plugin versions.
-   **Configurable**:
    -   Fine-tune server startup/shutdown behavior.
    -   Customize logging levels.
    -   Per-server settings for timeouts, join delays, and whitelist enforcement.

## Commands
-   `/ptero start <serverName>`
-   `/ptero stop <serverName>`
-   `/ptero restart <serverName>`
-   `/ptero list`
-   `/ptero info <serverName>`
-   `/ptero apithreads`
-   `/ptero reload`
-   `/ptero whitelistReload`
-   `/ptero stopidle`
-   `/ptero forcestopall [confirm]`
-   `/ptero forcestartall [confirm]`

## Permissions
-   `ptero.start`
-   `ptero.stop`
-   `ptero.restart`
-   `ptero.list`
-   `ptero.info`
-   `ptero.whitelistReload`
-   `ptero.reload`
-   `ptero.stopIdle`
-   `ptero.forcestopall`
-   `ptero.forcestartall`
-   `ptero.bypass` (to bypass VPP whitelist checks if enabled)
-   `ptero.maxcap.bypass` (to bypass the maxOnlineServers cap if enabled)

## Installation
1.  Download the latest `.jar` file from [Modrinth](https://modrinth.com/plugin/velocitypteropower). 
2.  Place the `.jar` file into your Velocity server's `plugins` folder.
3.  Restart your Velocity server.
4.  Configure the plugin by editing `config.yml` in the `plugins/VelocityPteroPower` folder. **See the [Docs Page](https://docs.pluginz.dev/) for detailed configuration instructions.**

## Support
For issues, suggestions, or help, please [open an issue on GitHub](https://github.com/TubYoub/VelocityPteroPower/issues/new) or join our [Discord Server](https://discord.pluginz.dev).

## Contributing
Interested in contributing? Join our [Discord](https://discord.pluginz.dev) to discuss your ideas or submit a pull request!

## License
This project is licensed under the [MIT License](LICENSE).

[![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com)