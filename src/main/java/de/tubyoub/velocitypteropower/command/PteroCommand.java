package de.tubyoub.velocitypteropower.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class PteroCommand implements SimpleCommand {
    private final ProxyServer proxyServer;
    private final VelocityPteroPower plugin;
    private final Logger logger;
    private final PanelAPIClient apiClient;
    public final RateLimitTracker rateLimitTracker;
    private final ConfigurationManager configurationManager;
    private final MessagesManager messages;

    private final Map<UUID, Long> pendingForceStopConfirmations = new HashMap<>();
    private static final long CONFIRMATION_TIMEOUT_MS = 30000;

    public PteroCommand(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.proxyServer = plugin.getProxyServer();
        this.logger = plugin.getFilteredLogger();
        this.apiClient = plugin.getApiClient();
        this.rateLimitTracker = plugin.getRateLimitTracker();
        this.configurationManager = plugin.getConfigurationManager();
        this.messages = plugin.getMessagesManager();
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            displayHelp(sender);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start":
                if (sender.hasPermission("ptero.start")) {
                    startServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "stop":
                if (sender.hasPermission("ptero.stop")) {
                    stopServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "reload":
                if (sender.hasPermission("ptero.reload")) {
                    reloadConfig(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "restart":
                if (sender.hasPermission("ptero.restart")) {
                    restartServer(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "stopidle":
                if (sender.hasPermission("ptero.stopIdle")) {
                    stopIdleServers(sender);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "reloadwhitelist":
                if (configurationManager.getPanelType() == PanelType.mcServerSoft) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_MCSS_NOT_SUPPORTED));
                    break;
                }
                if (sender.hasPermission("ptero.whitelistReload")) {
                    plugin.getWhitelistManager().updateAllWhitelists();
                    sender.sendMessage(
                            messages.prefixed(MessageKey.GENERIC_SUCCESS, "message", "Whitelist reloaded"));
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            case "forcestopall":
                if (sender.hasPermission("ptero.forcestopall")) {
                    forceStopAll(sender, args);
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                }
                break;

            default:
                sender.sendMessage(
                        messages.prefixed(
                                MessageKey.COMMAND_UNKNOWN_SUBCOMMAND, "sub", sub));
                displayHelp(sender);
        }
    }

    private void stopIdleServers(CommandSource sender) {
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_SERVERS_FOUND));
            return;
        }

        List<String> ignoreList = configurationManager.getStopAllIgnoreList();
        int stoppedCount = 0;

        for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
            String serverName = entry.getKey();
            PteroServerInfo serverInfo = entry.getValue();

            if (ignoreList.contains(serverName)) continue;

            if (proxyServer.getServer(serverName).isPresent()
                    && !proxyServer.getServer(serverName).get().getPlayersConnected().isEmpty()) {
                continue;
            }

            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
                stoppedCount++;
            } else {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_RATE_LIMIT_EXCEEDED));
                break;
            }
        }

        if (stoppedCount > 0) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_STOPPING_ALL_SERVERS, "count", String.valueOf(stoppedCount)));
        }
    }

    private void forceStopAll(CommandSource sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
            // Only meaningful for players; console bypasses anyway
            if (sender instanceof com.velocitypowered.api.proxy.Player p) {
                UUID id = p.getUniqueId();
                Long at = pendingForceStopConfirmations.get(id);
                if (at != null && System.currentTimeMillis() - at < CONFIRMATION_TIMEOUT_MS) {
                    pendingForceStopConfirmations.remove(id);
                    forceStopAllServers(sender);
                } else {
                    sender.sendMessage(
                            messages.prefixed(MessageKey.GENERIC_ERROR, "message", "No pending confirmation."));
                }
            } else {
                forceStopAllServers(sender);
            }
            return;
        }

        if (sender instanceof com.velocitypowered.api.proxy.Player p) {
            pendingForceStopConfirmations.put(p.getUniqueId(), System.currentTimeMillis());
        }
        sender.sendMessage(
                messages.prefixed(
                        MessageKey.COMMAND_FORCE_STOPPING_ALL_SERVERS, "count", "ALL"));
        sender.sendMessage(
                Component.text("Type '/ptero forcestopall confirm' to confirm.")); // Plain helper
    }

    private void forceStopAllServers(CommandSource sender) {
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_SERVERS_FOUND));
            return;
        }

        int stoppedCount = 0;
        for (Map.Entry<String, PteroServerInfo> entry : serverInfoMap.entrySet()) {
            PteroServerInfo serverInfo = entry.getValue();

            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(serverInfo.getServerId(), PowerSignal.STOP);
                stoppedCount++;
            } else {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_RATE_LIMIT_EXCEEDED));
                break;
            }
        }

        if (stoppedCount > 0) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_FORCE_STOPPING_ALL_SERVERS, "count", String.valueOf(stoppedCount)));
        }
    }

    private void startServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_USAGE, "usage", "ptero start <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo info = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(info.getServerId(), PowerSignal.START);
            }
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.POWER_ACTION_SENT, "action", "start", "server", serverName));
        } else {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
        }
    }

    private void stopServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_USAGE, "usage", "ptero stop <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo info = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(info.getServerId(), PowerSignal.STOP);
            }
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.POWER_ACTION_SENT, "action", "stop", "server", serverName));
        } else {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
        }
    }

    private void restartServer(CommandSource sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.COMMAND_USAGE, "usage", "ptero restart <serverName>"));
            return;
        }
        String serverName = args[1];
        Map<String, PteroServerInfo> serverInfoMap = plugin.getServerInfoMap();
        if (serverInfoMap.containsKey(serverName)) {
            PteroServerInfo info = serverInfoMap.get(serverName);
            if (rateLimitTracker.canMakeRequest()) {
                apiClient.powerServer(info.getServerId(), PowerSignal.RESTART);
            }
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.POWER_ACTION_SENT, "action", "restart", "server", serverName));
        } else {
            sender.sendMessage(
                    messages.prefixed(
                            MessageKey.CONNECT_UNMANAGED_SERVER, "server", serverName));
        }
    }

    private void reloadConfig(CommandSource sender) {
        plugin.reload();
        sender.sendMessage(messages.prefixed(MessageKey.GENERIC_RELOAD_SUCCESS));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] currentArgs = invocation.arguments();

        if (currentArgs.length <= 1) {
            return Arrays.asList(
                    "start", "stop", "restart", "stopidle", "whitelistReload", "reload", "forcestopall");
        } else if (currentArgs.length == 2) {
            String sub = currentArgs[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start") || sub.equals("stop") || sub.equals("restart")) {
                if (plugin.getServerInfoMap() != null) {
                    return plugin.getServerInfoMap().keySet().stream()
                            .filter(name -> name.startsWith(currentArgs[1]))
                            .collect(Collectors.toList());
                }
            }
        }
        return Collections.emptyList();
    }

    private void displayHelp(CommandSource sender) {
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_HELP_HEADER));
        sender.sendMessage(Component.text("/ptero start <serverName>"));
        sender.sendMessage(Component.text("/ptero stop <serverName>"));
        sender.sendMessage(Component.text("/ptero stopidle"));
        sender.sendMessage(Component.text("/ptero forcestopall"));
        sender.sendMessage(Component.text("/ptero whitelistReload"));
        sender.sendMessage(Component.text("/ptero reload"));
        sender.sendMessage(Component.text("/ptero help"));
    }
}