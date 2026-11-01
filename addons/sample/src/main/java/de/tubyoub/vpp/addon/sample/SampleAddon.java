package de.tubyoub.vpp.addon.sample;

import de.tubyoub.vpp.api.*;
import de.tubyoub.vpp.api.event.PlayerPostConnectEvent;
import de.tubyoub.vpp.api.event.VPPEvent;
import de.tubyoub.vpp.api.routing.PlayerRouteContext;
import de.tubyoub.vpp.api.routing.RoutingProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Example VPP-managed addon. Drop the built JAR into
 * {@code plugins/VelocityPteroPower/addons/} or install via {@code /ptero addon install vpp-sample}.
 */
public final class SampleAddon implements VPPAddon {
    private AddonContext ctx;
    private VPPApi api;
    private AddonConfig cfg;
    private RoutingProvider registeredRouter;

    @Override
    public AddonMeta meta() {
        return AddonMeta.builder("vpp-sample")
                .name("VPP Sample Addon")
                .version("1.0.0")
                .authors("TubYoub")
                .description("Demo commands, config, events and routing")
                .apiVersion("1.0.0")
                .build();
    }

    @Override
    public void onEnable(AddonContext context) {
        this.ctx = context;
        this.api = context.getApi();
        this.cfg = context.getConfig();
        cfg.ensureDefaults(Map.of(
                "routingEnabled", true,
                "defaultLobby", "lobby",
                "allowHttpCalls", false,
                "allowServerControl", false,
                "testServerName", "lobby",
                "httpTestPath", "/"
        ));
        cfg.save();

        ctx.registerCommand(new GreetCmd());
        ctx.registerCommand(new InfoCmd());
        ctx.registerCommand(new ConfigCmd());
        ctx.registerCommand(new EventCmd());
        ctx.registerCommand(new HttpCmd());
        ctx.registerCommand(new ServersCmd());
        ctx.registerCommand(new RouteCmd());

        if (cfg.getBoolean("routingEnabled", true)) {
            String lobby = cfg.getString("defaultLobby", "lobby");
            registeredRouter = new SimpleLobbyRouter(lobby);
            ctx.registerRoutingProvider(registeredRouter, 100);
            ctx.getLogger().info("Registered SimpleLobbyRouter -> " + lobby);
        }

        ctx.subscribe(VPPEvent.class, ev ->
                ctx.getLogger().debug("Event: " + ev.getClass().getSimpleName()));

        ctx.getLogger().info("Sample addon enabled. Try /ptero greet, /ptero sampleinfo, /ptero cfg, ...");
    }

    @Override
    public void onDisable() {
        registeredRouter = null;
        ctx = null;
        api = null;
        cfg = null;
    }

    public static final class GreetCmd implements AddonCommand {
        @Override public String name() { return "greet"; }
        @Override public List<String> aliases() { return List.of("hello"); }
        @Override public String description() { return "Greets a player (sample addon)"; }
        @Override public String permission() { return "vpp.sample.greet"; }
        @Override public void execute(CommandActor actor, String[] args) {
            String who = (args != null && args.length > 0) ? args[0] : actor.getName();
            actor.sendMessage("Hello, " + who + "! (from VPP sample addon)");
        }
    }

    public final class InfoCmd implements AddonCommand {
        @Override public String name() { return "sampleinfo"; }
        @Override public List<String> aliases() { return List.of("sample-about"); }
        @Override public String description() { return "Shows VPP API info from the sample addon"; }
        @Override public String permission() { return "vpp.sample.info"; }
        @Override public void execute(CommandActor actor, String[] args) {
            actor.sendMessage("VPP API version: " + api.getApiVersion());
            actor.sendMessage("Registered addon commands: " + api.getRegisteredCommands().size());
            api.dispatchSubcommand("greet", actor, new String[]{actor.getName()});
        }
    }

    public final class ConfigCmd implements AddonCommand {
        @Override public String name() { return "cfg"; }
        @Override public List<String> aliases() { return List.of("config"); }
        @Override public String description() { return "Demonstrates AddonConfig get/set/save/reload"; }
        @Override public String permission() { return "vpp.sample.cfg"; }
        @Override public void execute(CommandActor actor, String[] args) {
            if (args.length >= 2 && "set".equalsIgnoreCase(args[0])) {
                cfg.set(args[1], args.length >= 3 ? args[2] : "true");
                cfg.save();
                actor.sendMessage("Set " + args[1] + " and saved.");
                return;
            }
            if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
                cfg.reload();
                actor.sendMessage("Config reloaded.");
                return;
            }
            actor.sendMessage("routingEnabled=" + cfg.getBoolean("routingEnabled", true)
                    + ", defaultLobby=" + cfg.getString("defaultLobby", "lobby"));
        }
    }

    public final class EventCmd implements AddonCommand {
        @Override public String name() { return "event"; }
        @Override public List<String> aliases() { return List.of(); }
        @Override public String description() { return "Posts a dummy PlayerPostConnectEvent"; }
        @Override public String permission() { return "vpp.sample.event"; }
        @Override public void execute(CommandActor actor, String[] args) {
            api.getEventBus().post(new PlayerPostConnectEvent(
                    UUID.randomUUID(), actor.getName(), "lobby"));
            actor.sendMessage("Posted PlayerPostConnectEvent.");
        }
    }

    public final class HttpCmd implements AddonCommand {
        @Override public String name() { return "http"; }
        @Override public List<String> aliases() { return List.of(); }
        @Override public String description() { return "Optional panel HTTP GET demo"; }
        @Override public String permission() { return "vpp.sample.http"; }
        @Override public void execute(CommandActor actor, String[] args) {
            if (!cfg.getBoolean("allowHttpCalls", false)) {
                actor.sendMessage("HTTP demo disabled (allowHttpCalls=false).");
                return;
            }
            String path = cfg.getString("httpTestPath", "/");
            try {
                PanelHttpResponse resp = api.getPanelHttp().get(path).join();
                actor.sendMessage("HTTP status=" + resp.statusCode());
            } catch (Exception e) {
                actor.sendMessage("HTTP failed: " + e.getMessage());
            }
        }
    }

    public final class ServersCmd implements AddonCommand {
        @Override public String name() { return "servers"; }
        @Override public List<String> aliases() { return List.of(); }
        @Override public String description() { return "Lists managed servers / optional start"; }
        @Override public String permission() { return "vpp.sample.servers"; }
        @Override public void execute(CommandActor actor, String[] args) {
            actor.sendMessage("Managed servers: " + api.getServerRegistry().listServers().size());
            if (args.length > 0 && "start".equalsIgnoreCase(args[0])) {
                if (!cfg.getBoolean("allowServerControl", false)) {
                    actor.sendMessage("Server control disabled (allowServerControl=false).");
                    return;
                }
                String name = args.length > 1 ? args[1] : cfg.getString("testServerName", "lobby");
                api.getServerControl().startByName(name);
                actor.sendMessage("Start requested for " + name);
            }
        }
    }

    public static final class SimpleLobbyRouter implements RoutingProvider {
        private final String lobby;
        public SimpleLobbyRouter(String lobby) { this.lobby = lobby; }
        @Override
        public boolean selectTarget(PlayerRouteContext routeCtx) {
            if (routeCtx.getRequestedServer() == null) {
                routeCtx.setTargetServer(lobby);
                return true;
            }
            if ("@lobby".equalsIgnoreCase(routeCtx.getRequestedServer())) {
                routeCtx.setTargetServer(lobby);
                return true;
            }
            return false;
        }
    }

    public final class RouteCmd implements AddonCommand {
        @Override public String name() { return "route"; }
        @Override public List<String> aliases() { return List.of("routing"); }
        @Override public String description() { return "Self-test selectRoute"; }
        @Override public String permission() { return "vpp.sample.route"; }
        @Override public void execute(CommandActor actor, String[] args) {
            PlayerRouteContext routeCtx = new PlayerRouteContext(
                    UUID.randomUUID(), actor.getName(), null, "INITIAL");
            boolean handled = api.selectRoute(routeCtx);
            actor.sendMessage("selectRoute handled=" + handled + ", target=" + routeCtx.getTargetServer());
        }
    }
}
