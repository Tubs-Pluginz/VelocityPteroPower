# VelocityPteroPower
![Static Badge](https://img.shields.io/badge/Velocity-green) <br>
[![forthebadge](https://forthebadge.com/images/badges/works-on-my-machine.svg)](https://forthebadge.com) <br> <br>
<a href="https://www.buymeacoffee.com/tubyoub" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>

<p align="center">
    <a href="https://discord.pluginz.dev">
        <img src="https://i.imgur.com/JgDt1Fl.png" width="300">
    </a>
    <br>
    <i>Please join the Discord if you have questions!</i>
</p>

This is a Plugin for Velocity Servers which can dynamically start and stop servers that are managed with the [Pterodactyl Server Panel](https://pterodactyl.io/)

This Project is a port of the [BungeePteroPower](https://github.com/Kamesuta/BungeePteroPower)
## Features
- start a Server manually with `/ptero start`
- stop a Server manually with `/ptero stop`
- restart a server manually with `/ptero restart`
- reload the config using `/ptero reload`
- stop all servers that are idling (no players online, exceptions configurable)`/ptero stopIdle`
- stop all servers `/ptero forcestopall`
<br><br>
- The plugin will automatically start a Server that a player is trying to connect (if the server is configured in the config file)
-  The plugin will automatically schedule a server shutdown if a server is empty for a certain amount of time (time is configurable
   in the config file)
  - scheduled shutdowns will be canceled if a player joins the server that is scheduled to be shutdown
- The plugin will never exceed the RateLimit of the Panel API
- The plugin will automatically start servers when a player connects through a forced host(and redirect the player to a configured limbo Server in the meantime)
- automatic checks for new updates of the plugin

## Permissions
- `ptero.start` Permission for the `/ptero start` command
- `ptero.stop`Permission for the `/ptero stop` command
- `ptero.restart` Permission for the `/ptero restart` command
- `ptero.whitelistReload` Permission for the `/ptero whitelistReload` command
- `ptero.reload` Permission for the `/ptero reload` command
- `ptero.stopIdle` Permission for the `/ptero stopIdle` command
- `ptero.forcestopall` Permission for the `/ptero forcestopall` command
## Installation 
To install the Plugin on your Velocity Server put the `.jar` in your plugin folder and `restart/start` your server.

## Example config
```yml
################################
#      VelocityPteroPower      #
#         by TubYoub           #
################################

# Version of the configuration file
# Version of the configuration file
fileversion: 7

# Check for updates
# If true, the plugin will check for updates on startup.
# If a new version is available, a message will be sent to the console and to players with the permission "ptero.reload".
checkUpdate: true

# Choose which logging level should be used
# It depends on how much you want you're console to be filled with information from VPP
# For production you can safely set it to a higher level but for development/testing it is recommended to use a lower level
# 10 = Debug
# 20 = Info
# 30 = Warning
# 40 = Error
#default: 20
loggerLevel: 20

# How many Threads should be used to check the server status
# The more threads the more requests can be handled at the same time
# more threads = more resources used
# restart the server so changes take effect
# default: 10
apiThreads: 10

# print updated Rate Limit from the API response to console
# default: false
printRateLimit: false

# Choose the method to check if a managed server is online and ready.
# "VELOCITY_PING": Uses Velocity's built-in ping functionality. Requires the server
#                  to be registered in Velocity's config and accessible from the proxy.
#                  Less resource-intensive, doesn't use API rate limits. Might report
#                  online slightly before the server is fully joinable.
#                  Some simple Limbo servers might not report any Ping back suing this method.
# "PANEL_API": Uses the Pterodactyl/Pelican panel API to check the server's state.
#                    More accurate for actual server state ("running"), but uses API
#                    requests and counts towards rate limits.
# Default: "VELOCITY_PING"
serverStatusCheckMethod: "PANEL_API"

# How long the ping to the server lasts, to check if its is online, until it times out (in milliseconds)
# default: 1000 (1 second)
pingTimeout: 1000

# How many retrys to shut down the server if the previous one failed
# default: 3
shutdownRetries: 3

# How long to wait before retrying to shut down the server (in seconds)
# default: 300
shutdownRetryDelay: 300

# How long to wait before shutting down the server if no player joined the server(in seconds)
# default: 300
# -1 = disabled
idleStartShutdownTime: 300

# How long to wait before allowing a player to start a server again (in seconds)
# default: 10
playerStartCooldown: 10

# Server Name from your limbo
# should be a configured server so it can be automatically started
# changeMe = not configured so limbo server won't be used
limboServer: limbo

# How long (in seconds) the plugin should wait for the first check if the server is online
# The joinDelay (configured for each server) is still applied when the server is online before a
# player is automatically connected (so it doesn't affect this)
# Maybe consider changing this to a lower value if you have for example a
# limbo which is lightweight and starts fast
# default: 10
startupInitialCheckDelay: 10

# Servers to ignore when the stopIdle command is used
# THIS DOES NOT AFFECT forceStop
stopIdleIgnore:
  - hub
  - lobby
  - limbo

# If a player should receive a message when a server is not found in the config
# message will always be send to console
# default: false
serverNotFoundMessage: false

# Allow players with the permission "ptero.bypass" to bypass the whitelist when starting a server.
# This does not work to get around a whitelist because it is enforced by the backend server itself.
# default: true
whitelistAllowBypass: false

# How long (in minutes) VPP should wait in between checks for a new whitelist and enforce it (if enabled)
# default: 10
whitelistCheckInterval: 10

# Pterodactyl configuration
pterodactyl:
  # The URL of your pterodactyl panel
  # If you use Cloudflare Tunnel, you need to allow the ip in the bypass setting.
  url: http://192.168.178.74:2462/
  # The client api key of your pterodactyl panel. It starts with "ptlc_".
  # You can find the client api key in the "API Credentials" tab of the "Account" page.
  # For Pelican, you need the short UUID from Server Details.
  apiKey: ptlc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

# Per server configuration
servers:
  #server name
  hub:
    
    # Pterodactyl server ID
    # You can find the Pterodactyl server ID in the URL of the server page.
    # For example, if the URL is https://panel.example.com/server/1234abcd, the server ID is 1234abcd.

    id: 1234abcd
    
    # The time in seconds to stop the server after the last player leaves.
    # If you don't want to stop the server automatically, set it to -1.
    # If you set it to 0, the server will be stopped immediately after the last player leaves.

    timeout: -1
    
    # If VPP should fetch the whitelist of the Server and enforce it when a player trys to start a server
    # default: false
    whitelist: false
  test:
    id: abcd1234
    timeout: 5
    whitelist: true
  mc-purpur-1:
    id: ab12cd34
    timeout: 180
    whitelist: false
  mcforge1:
    id: 1111abcd
    timeout: -1
    whitelist: false
  limbo:
    id: 9999abcd
    timeout: -1
    whitelist: false

```

## Support

If you have any issues, or suggestions please open a [issue](https://github.com/TubYoub/VelocityPteroPower/issues/new) or join the discord

## Contributing

Join the [discord](https://discord.pluginz.dev) and talk with me about your idea or just do it yourself and open a pull request.

## License

This project is licensed under the [MIT License](LICENSE).

[![forthebadge](https://forthebadge.com/images/badges/powered-by-black-magic.svg)](https://forthebadge.com)
