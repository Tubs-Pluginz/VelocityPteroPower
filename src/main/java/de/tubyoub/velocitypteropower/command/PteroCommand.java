/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * This class represents a command that can be executed by a player.
 * It includes subcommands to start, stop, and reload servers.
 */
public class PteroCommand implements SimpleCommand {
    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final PanelAPIClient apiClient;
    public final RateLimitTracker rateLimitTracker;
    private final ConfigurationManager configurationManager;
    private final Map<UUID, Long> pendingForceStopConfirmations = new HashMap<>();
    private static final long CONFIRMATION_TIMEOUT_MS = 30000; // 30 seconds


    /**
     * Constructor for the PteroCommand class.
     * @param plugin the VelocityPteroPower plugin instance
     */
    public PteroCommand(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.proxyServer = plugin.getProxyServer();
        this.logger = plugin.getFilteredLogger();
        this.apiClient = plugin.getApiClient();
        this.rateLimitTracker = plugin.getRateLimitTracker();
        this.configurationManager = plugin.getConfigurationManager();
    }

    /**
     * This method is called when the command is executed.
     * It checks the subcommand and executes the corresponding action.
     *
     * @param invocation the command invocation
     */
    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();


        if (args.length == 0) {
            displayHelp(sender);
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "start":
                if (sender.hasPermission("ptero.start")) {
                    startServer(invocation.source(), args);
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            case "stop":
                if (sender.hasPermission("ptero.stop")) {
                    stopServer(sender, args);
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            case "reload":
                if (sender.hasPermission("ptero.reload")) {
                    reloadConfig(sender);
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            case "restart":
                if (sender.hasPermission("ptero.restart")) {
                    restartServer(sender, args);
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            case "stopidle":
                if (sender.hasPermission("ptero.stopIdle")) {
                    stopIdleServers(sender);
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            case "reloadwhitelist":
                if (configurationManager.getPanelType() == PanelType.mcServerSoft){
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("mcss-not-supported"), TextColor.color(255, 0, 0))));
                    break;
                }
                if (sender.hasPermission("ptero.whitelistReload")) {
                    plugin.getWhitelistManager().updateAllWhitelists();
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            case "forcestopall":
                if (sender.hasPermission("ptero.forcestopall")) {
                    if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                        // Check if there's a pending confirmation for this user
                        if (sender instanceof Player) {
                            UUID playerUuid = ((Player) sender).getUniqueId();
                            Long confirmationTime = pendingForceStopConfirmations.get(playerUuid);

                            if (confirmationTime != null && System.currentTimeMillis() - confirmationTime < CONFIRMATION_TIMEOUT_MS) {
                                // Valid confirmation, remove from pending and execute
                                pendingForceStopConfirmations.remove(playerUuid);
                                forceStopAllServers(sender);
                            } else {
                                // Confirmation expired or not requested
                                sender.sendMessage(plugin.getPluginPrefix().append(Component.text(
                                    "You don't have a pending forcestopall request or it has expired.", NamedTextColor.RED)));
                            }
                        } else {
                            // Console can bypass confirmation
                            forceStopAllServers(sender);
                        }
                    } else {
                        // Request confirmation
                        if (sender instanceof Player) {
                            UUID playerUuid = ((Player) sender).getUniqueId();
                            pendingForceStopConfirmations.put(playerUuid, System.currentTimeMillis());
                        }
                        sender.sendMessage(plugin.getPluginPrefix().append(Component.text(
                            "WARNING: This will stop ALL servers regardless of player count or the exclusion list in the config!", NamedTextColor.RED)));
                        sender.sendMessage(plugin.getPluginPrefix().append(Component.text(
                            "To confirm, type: /ptero forcestopall confirm", NamedTextColor.YELLOW)));
                    }
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-permission"), TextColor.color(255, 0, 0))));
                }
                break;
            default:
                sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("unknown-subcommand") + subCommand)));
                displayHelp(sender);
        }
    }
    private void stopIdleServers(CommandSource sender) {
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
            if (serverInfoMap.isEmpty()) {
                sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-servers-found"), NamedTextColor.RED)));
                return;
            }

            List<String> ignoreList = configurationManager.getStopAllIgnoreList();
            int stoppedCount = 0;

            for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
                String serverName = entry.getKey();
                PteroServerInfo serverInfo = entry.getValue();

                // Skip servers in the ignore list
                if (ignoreList.contains(serverName)) {
                    continue;
                }

                // Skip servers with players online
                if (proxyServer.getServer(serverName).isPresent() &&
                    !proxyServer.getServer(serverName).get().getPlayersConnected().isEmpty()) {
                    continue;
                }

                if (rateLimitTracker.canMakeRequest()) {
                    apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
                    stoppedCount++;
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("rate-limit-exceeded"), NamedTextColor.RED)));
                    break;
                }
            }

            if (stoppedCount > 0) {
                sender.sendMessage(plugin.getPluginPrefix().append(Component.text(
                    plugin.getMessagesManager().getMessage("stopping-all-servers").replace("%count%", String.valueOf(stoppedCount))
                )));
            }
        }

        private void forceStopAllServers(CommandSource sender) {
            Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
            if (serverInfoMap.isEmpty()) {
                sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("no-servers-found"), NamedTextColor.RED)));
                return;
            }

            int stoppedCount = 0;
            for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
                String serverName = entry.getKey();
                PteroServerInfo serverInfo = entry.getValue();

                if (rateLimitTracker.canMakeRequest()) {
                    apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
                    stoppedCount++;
                } else {
                    sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("rate-limit-exceeded"), NamedTextColor.RED)));
                    break;
                }
            }

            if (stoppedCount > 0) {
                sender.sendMessage(plugin.getPluginPrefix().append(Component.text(
                    plugin.getMessagesManager().getMessage("force-stopping-all-servers").replace("%count%", String.valueOf(stoppedCount))
                )));
            }
        }



    /**
     * This method is called to start a server.
     *
     * @param sender the player who executed the command
     * @param args the command arguments
     */
    private void startServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("usage") + " /ptero start <serverName>", NamedTextColor.RED)));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.START);
            }
            sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("starting-server").replace("%server%", serverName))));
        } else {
        }
    }

    /**
     * This method is called to stop a server.
     *
     * @param sender the player who executed the command
     * @param args the command arguments
     */
    private void stopServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("usage") + " /ptero stop <serverName>", TextColor.color(66,135,245))));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
            }
            sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("server-shutting-down").replace("%server%", serverName))));
        } else {
        }
    }

    private void restartServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("usage") + " /ptero restart <serverName>", TextColor.color(66,135,245))));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo serverInfo = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.RESTART);
            }
            sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("server-restarting").replace("%server%", serverName))));
        }
    }

    /**
     * This method is called to reload the configuration.
     *
     * @param sender the player who executed the command
     */
    private void reloadConfig(CommandSource sender) {
        plugin.reload();
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("config-reload"),TextColor.color(0,255,0))));
    }

    /**
     * This method is called to suggest command completions.
     *
     * @param invocation the command invocation
     * @return a list of suggested completions
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] currentArgs = invocation.arguments();

        if (currentArgs.length <= 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("start");
            suggestions.add("stop");
            suggestions.add("restart");
            suggestions.add("stopidle");
            suggestions.add("whitelistReload");
            suggestions.add("reload");
            suggestions.add("forcestopall");
            return suggestions;
        } else if (currentArgs.length == 2) {
            String subCommand = currentArgs[0].toLowerCase();
            if (subCommand.equals("start") || subCommand.equals("stop") || subCommand.equals("restart")) {
                if (plugin.getServerInfoMap() != null) {
                    return plugin.getServerInfoMap().keySet().stream()
                            .filter(serverName -> serverName.startsWith(currentArgs[1]))
                            .collect(Collectors.toList());
                } else {
                    return Collections.emptyList();
                }
            }
        }
        return Collections.emptyList();
    }

    private void displayHelp(CommandSource sender) {
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text(plugin.getMessagesManager().getMessage("available-commands-helpcommand"), NamedTextColor.GREEN)));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero start <serverName>", TextColor.color(66,135,245))));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero stop <serverName>", TextColor.color(66,135,245))));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero stopidle", TextColor.color(66,135,245))));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero forcestopall", TextColor.color(66,135,245))));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero whitelistReload", TextColor.color(66,135,245))));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero reload", TextColor.color(66,135,245))));
        sender.sendMessage(plugin.getPluginPrefix().append(Component.text("/ptero help", TextColor.color(66,135,245))));
    }
}