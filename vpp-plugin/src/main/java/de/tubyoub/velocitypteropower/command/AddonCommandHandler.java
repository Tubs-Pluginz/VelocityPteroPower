package de.tubyoub.velocitypteropower.command;

import com.velocitypowered.api.command.CommandSource;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.addon.AddonCatalogEntry;
import de.tubyoub.velocitypteropower.addon.AddonManager;
import de.tubyoub.velocitypteropower.addon.LoadedAddon;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles {@code /ptero addon ...} subcommands.
 */
public final class AddonCommandHandler {
    private final VelocityPteroPower plugin;
    private final MessagesManager messages;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public AddonCommandHandler(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessagesManager();
    }

    public void handle(CommandSource sender, String[] args) {
        if (!sender.hasPermission("ptero.addon") && !sender.hasPermission("ptero.reload")) {
            sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
            return;
        }
        AddonManager manager = plugin.getAddonManager();
        if (manager == null) {
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_MANAGER_UNAVAILABLE));
            return;
        }

        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list", "ls" -> listInstalled(sender, manager);
            case "browse", "catalog", "market" -> browse(sender, manager);
            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero addon info <id>"));
                    return;
                }
                info(sender, manager, args[2]);
            }
            case "install" -> {
                if (!sender.hasPermission("ptero.addon.install") && !sender.hasPermission("ptero.reload")) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero addon install <id>"));
                    return;
                }
                install(sender, manager, args[2]);
            }
            case "uninstall", "remove" -> {
                if (!canManage(sender)) return;
                if (args.length < 3) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero addon uninstall <id>"));
                    return;
                }
                uninstall(sender, manager, args[2]);
            }
            case "enable" -> {
                if (!canManage(sender)) return;
                if (args.length < 3) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero addon enable <id>"));
                    return;
                }
                if (manager.enable(args[2])) {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_ENABLED, "id", args[2]));
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_ACTION_FAILED, "action", "enable", "id", args[2]));
                }
            }
            case "disable" -> {
                if (!canManage(sender)) return;
                if (args.length < 3) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero addon disable <id>"));
                    return;
                }
                if (manager.disable(args[2], true)) {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_DISABLED, "id", args[2]));
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_ACTION_FAILED, "action", "disable", "id", args[2]));
                }
            }
            case "reload" -> {
                if (!canManage(sender)) return;
                String target = args.length >= 3 ? args[2] : "all";
                if ("all".equalsIgnoreCase(target)) {
                    manager.reloadAll();
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_RELOADED_ALL));
                } else if (manager.reload(target)) {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_RELOADED, "id", target));
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_ACTION_FAILED, "action", "reload", "id", target));
                }
            }
            case "update" -> {
                if (!sender.hasPermission("ptero.addon.install") && !sender.hasPermission("ptero.reload")) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(messages.prefixed(MessageKey.COMMAND_USAGE, "usage", "ptero addon update <id>"));
                    return;
                }
                update(sender, manager, args[2]);
            }
            case "help" -> help(sender);
            default -> {
                sender.sendMessage(messages.prefixed(MessageKey.COMMAND_UNKNOWN_SUBCOMMAND, "sub", action));
                help(sender);
            }
        }
    }

    private boolean canManage(CommandSource sender) {
        if (sender.hasPermission("ptero.addon.manage") || sender.hasPermission("ptero.reload")) return true;
        sender.sendMessage(messages.prefixed(MessageKey.COMMAND_NO_PERMISSION));
        return false;
    }

    private void listInstalled(CommandSource sender, AddonManager manager) {
        var addons = manager.getLoadedAddons();
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_LIST_HEADER, "count", String.valueOf(addons.size())));
        if (addons.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_LIST_EMPTY));
            return;
        }
        for (LoadedAddon addon : addons) {
            String statusKey = switch (addon.getStatus()) {
                case ACTIVE -> "ACTIVE";
                case DISABLED -> "DISABLED";
                case ERROR -> "ERROR";
            };
            String statusFormatted = switch (addon.getStatus()) {
                case ACTIVE -> "<green>ACTIVE</green>";
                case DISABLED -> "<yellow>DISABLED</yellow>";
                case ERROR -> "<red>ERROR</red>";
            };
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_LIST_ENTRY,
                    "name", addon.getMeta().name(),
                    "id", addon.getMeta().id(),
                    "version", addon.getMeta().version(),
                    "authors", addon.getMeta().authorsJoined(),
                    "status", statusKey,
                    "status_formatted", statusFormatted));
        }
    }

    private void browse(CommandSource sender, AddonManager manager) {
        List<AddonCatalogEntry> catalog = manager.getCatalogService().getCatalog(true);
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_BROWSE_HEADER, "count", String.valueOf(catalog.size())));
        if (catalog.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_BROWSE_EMPTY));
            return;
        }
        for (AddonCatalogEntry entry : catalog) {
            Optional<LoadedAddon> installed = manager.getAddon(entry.getId());
            // Manual drops and catalog installs both land in loaded[] keyed by meta id
            String badge = installed.map(a -> a.isActive()
                    ? "<green>[INSTALLED]</green>"
                    : "<yellow>[DISABLED]</yellow>").orElse("<gray>[AVAILABLE]</gray>");
            var line = messages.prefixed(MessageKey.ADDON_BROWSE_ENTRY,
                    "name", nullToEmpty(entry.getName()),
                    "id", nullToEmpty(entry.getId()),
                    "version", nullToEmpty(entry.getLatestVersion()),
                    "authors", entry.authorsJoined(),
                    "description", nullToEmpty(entry.getDescription()),
                    "badge", badge);
            if (installed.isEmpty()) {
                line = line.hoverEvent(HoverEvent.showText(mini.deserialize(
                                "<gray>Click to install </gray><aqua>" + entry.getId() + "</aqua>")))
                        .clickEvent(ClickEvent.suggestCommand("/ptero addon install " + entry.getId()));
            } else {
                line = line.hoverEvent(HoverEvent.showText(mini.deserialize(
                                "<gray>Click for info </gray><aqua>" + entry.getId() + "</aqua>")))
                        .clickEvent(ClickEvent.suggestCommand("/ptero addon info " + entry.getId()));
            }
            sender.sendMessage(line);
        }
    }

    private void info(CommandSource sender, AddonManager manager, String id) {
        Optional<LoadedAddon> installed = manager.getAddon(id);
        Optional<AddonCatalogEntry> catalog = manager.getCatalogService().find(id);
        if (installed.isEmpty() && catalog.isEmpty()) {
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_NOT_FOUND, "id", id));
            return;
        }
        String name = installed.map(a -> a.getMeta().name()).orElseGet(() -> catalog.get().getName());
        String version = installed.map(a -> a.getMeta().version()).orElseGet(() -> catalog.get().getLatestVersion());
        String authors = installed.map(a -> a.getMeta().authorsJoined()).orElseGet(() -> catalog.get().authorsJoined());
        String description = installed.map(a -> a.getMeta().description()).orElseGet(() -> nullToEmpty(catalog.get().getDescription()));
        String status = installed.map(a -> a.getStatus().name()).orElse("NOT_INSTALLED");
        String homepage = catalog.map(AddonCatalogEntry::getHomepage).orElse("-");
        String latest = catalog.map(AddonCatalogEntry::getLatestVersion).orElse("-");
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_INFO_HEADER, "name", nullToEmpty(name), "id", id));
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_INFO_BODY,
                "version", nullToEmpty(version),
                "latest", nullToEmpty(latest),
                "authors", authors,
                "status", status,
                "description", description,
                "homepage", nullToEmpty(homepage)));
    }

    private void install(CommandSource sender, AddonManager manager, String id) {
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_INSTALLING, "id", id));
        plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
            try {
                String installedId = manager.installFromCatalog(id);
                sender.sendMessage(messages.prefixed(MessageKey.ADDON_INSTALLED, "id", installedId));
            } catch (Exception e) {
                sender.sendMessage(messages.prefixed(MessageKey.ADDON_INSTALL_FAILED, "id", id, "error", e.getMessage()));
                plugin.getFilteredLogger().warn("Addon install failed for {}: {}", id, e.toString());
            }
        }).schedule();
    }

    private void uninstall(CommandSource sender, AddonManager manager, String id) {
        if (manager.uninstall(id)) {
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_UNINSTALLED, "id", id));
        } else {
            sender.sendMessage(messages.prefixed(MessageKey.ADDON_ACTION_FAILED, "action", "uninstall", "id", id));
        }
    }

    private void update(CommandSource sender, AddonManager manager, String id) {
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_UPDATING, "id", id));
        plugin.getProxyServer().getScheduler().buildTask(plugin, () -> {
            try {
                String result = manager.updateFromCatalog(id);
                if ("up-to-date".equals(result)) {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_UP_TO_DATE, "id", id));
                } else {
                    sender.sendMessage(messages.prefixed(MessageKey.ADDON_UPDATED, "id", id, "version", result));
                }
            } catch (Exception e) {
                sender.sendMessage(messages.prefixed(MessageKey.ADDON_UPDATE_FAILED, "id", id, "error", e.getMessage()));
            }
        }).schedule();
    }

    private void help(CommandSource sender) {
        sender.sendMessage(messages.prefixed(MessageKey.ADDON_HELP_HEADER));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon list</gray> <dark_gray>-</dark_gray> <white>Installed addons</white>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon browse</gray> <dark_gray>-</dark_gray> <white>Official catalog</white>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon info <id></gray>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon install <id></gray>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon uninstall <id></gray>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon enable|disable <id></gray>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon reload [id|all]</gray>"));
        sender.sendMessage(mini.deserialize("<gray>/ptero addon update <id></gray>"));
    }

    public List<String> suggest(CommandSource sender, String[] args) {
        if (!sender.hasPermission("ptero.addon") && !sender.hasPermission("ptero.reload")) {
            return List.of();
        }
        AddonManager manager = plugin.getAddonManager();
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> actions = new ArrayList<>(List.of(
                    "list", "browse", "info", "install", "uninstall", "enable", "disable", "reload", "update", "help"));
            return actions.stream().filter(a -> a.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 3 && manager != null) {
            String action = args[1].toLowerCase(Locale.ROOT);
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if (action.equals("info") || action.equals("install") || action.equals("update")) {
                return manager.getCatalogService().getCatalog(false).stream()
                        .map(AddonCatalogEntry::getId)
                        .filter(id -> id != null && id.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (action.equals("uninstall") || action.equals("enable") || action.equals("disable") || action.equals("reload")) {
                List<String> ids = manager.getLoadedAddons().stream()
                        .map(a -> a.getMeta().id())
                        .filter(id -> id.startsWith(prefix))
                        .collect(Collectors.toCollection(ArrayList::new));
                if (action.equals("reload") && "all".startsWith(prefix)) ids.add("all");
                return ids;
            }
        }
        return List.of();
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
