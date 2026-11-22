package de.tubyoub.vpp.addon.webhooks;

import de.tubyoub.vpp.api.AddonCommand;
import de.tubyoub.vpp.api.AddonConfig;
import de.tubyoub.vpp.api.AddonContext;
import de.tubyoub.vpp.api.AddonMeta;
import de.tubyoub.vpp.api.CommandActor;
import de.tubyoub.vpp.api.VPPAddon;
import de.tubyoub.vpp.api.event.PlayerDisconnectEvent;
import de.tubyoub.vpp.api.event.PlayerPostConnectEvent;
import de.tubyoub.vpp.api.event.PlayerPreServerSwitchEvent;
import de.tubyoub.vpp.api.event.ServerPowerSignalEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts VPP power/connect events to Discord or generic webhook endpoints.
 */
public final class WebhooksAddon implements VPPAddon {
    private AddonContext ctx;
    private AddonConfig cfg;
    private HttpClient http;
    private ExecutorService executor;

    @Override
    public AddonMeta meta() {
        return AddonMeta.builder("vpp-webhooks")
                .name("VPP Webhooks")
                .version("1.0.0")
                .authors("TubYoub")
                .description("Webhook notifications for power and connect events")
                .apiVersion("1.0.0")
                .build();
    }

    @Override
    public void onEnable(AddonContext context) {
        this.ctx = context;
        this.cfg = context.getConfig();
        seedDefaults();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vpp-webhooks");
            t.setDaemon(true);
            return t;
        });

        ctx.subscribe(ServerPowerSignalEvent.class, this::onPower);
        ctx.subscribe(PlayerPostConnectEvent.class, this::onConnect);
        ctx.subscribe(PlayerDisconnectEvent.class, this::onDisconnect);
        ctx.subscribe(PlayerPreServerSwitchEvent.class, this::onSwitch);
        ctx.registerCommand(new WebhookCmd());

        ctx.getLogger().info("Webhooks addon enabled (format=" + cfg.getString("format", "auto") + ")");
        ctx.getLogger().debug("Webhook URLs configured: " + resolveUrls().size());
    }

    @Override
    public void onDisable() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        http = null;
        cfg = null;
        ctx = null;
    }

    private void seedDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", true);
        defaults.put("webhookUrl", "");
        defaults.put("webhookUrls", List.of());
        defaults.put("format", "auto");
        defaults.put("events.power", true);
        defaults.put("events.connect", true);
        defaults.put("events.disconnect", true);
        defaults.put("events.switch", false);
        // Power events fire before the panel call; cancelled signals are still reported unless skipped.
        defaults.put("skipCancelledPower", true);
        cfg.ensureDefaults(defaults);
        cfg.save();
    }

    private void onPower(ServerPowerSignalEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("events.power", true)) {
            return;
        }
        if (cfg.getBoolean("skipCancelledPower", true) && ev.isCancelled()) {
            ctx.getLogger().debug("Skipping cancelled power signal for " + ev.getServerId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverName", nullToEmpty(ev.getServerName()));
        payload.put("serverId", nullToEmpty(ev.getServerId()));
        payload.put("signal", nullToEmpty(ev.getSignal()));
        payload.put("cancelled", ev.isCancelled());
        dispatch("power", "Server power signal", 0x5865F2, payload);
    }

    private void onConnect(PlayerPostConnectEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("events.connect", true)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", ev.getPlayerId().toString());
        payload.put("playerName", ev.getPlayerName());
        payload.put("targetServer", nullToEmpty(ev.getTargetServer()));
        dispatch("connect", "Player connected", 0x57F287, payload);
    }

    private void onDisconnect(PlayerDisconnectEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("events.disconnect", true)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", ev.getPlayerId().toString());
        payload.put("playerName", ev.getPlayerName());
        payload.put("lastServer", nullToEmpty(ev.getLastServer()));
        dispatch("disconnect", "Player disconnected", 0xED4245, payload);
    }

    private void onSwitch(PlayerPreServerSwitchEvent ev) {
        if (!cfg.getBoolean("enabled", true) || !cfg.getBoolean("events.switch", false)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", ev.getPlayerId().toString());
        payload.put("playerName", ev.getPlayerName());
        payload.put("fromServer", nullToEmpty(ev.getFromServer()));
        payload.put("targetServer", nullToEmpty(ev.getTargetServer()));
        payload.put("reason", nullToEmpty(ev.getReason()));
        dispatch("switch", "Player switching server", 0xFEE75C, payload);
    }

    private void dispatch(String eventType, String title, int color, Map<String, Object> payload) {
        List<String> urls = resolveUrls();
        if (urls.isEmpty()) {
            ctx.getLogger().debug("No webhook URLs configured; skipping event " + eventType);
            return;
        }
        String timestamp = Instant.now().toString();
        for (String url : urls) {
            String body = buildBody(url, eventType, title, color, timestamp, payload);
            postAsync(url, body, eventType);
        }
    }

    private String buildBody(String url, String eventType, String title, int color,
                             String timestamp, Map<String, Object> payload) {
        String format = cfg.getString("format", "auto").toLowerCase(Locale.ROOT);
        boolean discord = "discord".equals(format)
                || ("auto".equals(format) && isDiscordWebhook(url));
        if (discord) {
            return buildDiscordBody(eventType, title, color, timestamp, payload);
        }
        return buildGenericBody(eventType, title, timestamp, payload);
    }

    private String buildDiscordBody(String eventType, String title, int color,
                                    String timestamp, Map<String, Object> payload) {
        StringBuilder fields = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!first) {
                fields.append(',');
            }
            first = false;
            fields.append("{\"name\":").append(jsonString(e.getKey()))
                    .append(",\"value\":").append(jsonString(String.valueOf(e.getValue())))
                    .append(",\"inline\":true}");
        }
        return "{\"username\":\"VelocityPteroPower\","
                + "\"embeds\":[{"
                + "\"title\":" + jsonString(title) + ","
                + "\"description\":" + jsonString("Event: `" + eventType + "`") + ","
                + "\"color\":" + color + ","
                + "\"timestamp\":" + jsonString(timestamp) + ","
                + "\"fields\":[" + fields + "]"
                + "}]}";
    }

    private String buildGenericBody(String eventType, String title, String timestamp,
                                    Map<String, Object> payload) {
        StringBuilder payloadJson = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!first) {
                payloadJson.append(',');
            }
            first = false;
            Object v = e.getValue();
            payloadJson.append(jsonString(e.getKey())).append(':');
            if (v instanceof Boolean || v instanceof Number) {
                payloadJson.append(v);
            } else {
                payloadJson.append(jsonString(String.valueOf(v)));
            }
        }
        payloadJson.append('}');
        return "{\"event\":" + jsonString(eventType)
                + ",\"title\":" + jsonString(title)
                + ",\"timestamp\":" + jsonString(timestamp)
                + ",\"source\":\"vpp-webhooks\""
                + ",\"payload\":" + payloadJson
                + "}";
    }

    private void postAsync(String url, String body, String eventType) {
        if (executor == null || http == null) {
            return;
        }
        executor.execute(() -> {
            try {
                ctx.getLogger().debug("POST webhook event=" + eventType + " url=" + redactUrl(url)
                        + " bodyBytes=" + body.length());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "VelocityPteroPower-Webhooks/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    ctx.getLogger().debug("Webhook OK event=" + eventType + " status=" + code);
                } else {
                    ctx.getLogger().warn("Webhook non-2xx event=" + eventType + " status=" + code
                            + " body=" + truncate(response.body(), 200));
                }
            } catch (Exception e) {
                ctx.getLogger().error("Webhook POST failed for event " + eventType + ": " + e.getMessage(), e);
            }
        });
    }

    private List<String> resolveUrls() {
        List<String> urls = new ArrayList<>();
        String single = cfg.getString("webhookUrl", "");
        if (single != null && !single.isBlank()) {
            urls.add(single.trim());
        }
        for (String u : cfg.getStringList("webhookUrls")) {
            if (u != null && !u.isBlank()) {
                urls.add(u.trim());
            }
        }
        return urls;
    }

    private static boolean isDiscordWebhook(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("discord.com/api/webhooks")
                || lower.contains("discordapp.com/api/webhooks");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String redactUrl(String url) {
        int idx = url.lastIndexOf('/');
        if (idx > 0 && idx < url.length() - 4) {
            return url.substring(0, idx + 1) + "***";
        }
        return "***";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private final class WebhookCmd implements AddonCommand {
        @Override
        public String name() {
            return "webhook";
        }

        @Override
        public List<String> aliases() {
            return List.of("webhooks");
        }

        @Override
        public String description() {
            return "Test or inspect webhook delivery";
        }

        @Override
        public String permission() {
            return "vpp.webhooks.test";
        }

        @Override
        public void execute(CommandActor actor, String[] args) {
            String sub = (args != null && args.length > 0) ? args[0].toLowerCase(Locale.ROOT) : "test";
            if ("reload".equals(sub)) {
                cfg.reload();
                actor.sendMessage("<green>Webhook config reloaded.</green>");
                ctx.getLogger().info("Webhook config reloaded by " + actor.getName());
                return;
            }
            if (!"test".equals(sub)) {
                actor.sendMessage("<yellow>Usage:</yellow> <gray>/ptero webhook test|reload</gray>");
                return;
            }
            if (resolveUrls().isEmpty()) {
                actor.sendMessage("<red>No webhook URL configured.</red> Set <aqua>webhookUrl</aqua> in <gray>addons/vpp-webhooks.yml</gray>");
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "manual-test");
            payload.put("actor", actor.getName());
            dispatch("test", "Webhook test", 0xEB459E, payload);
            actor.sendMessage("<green>Test webhook dispatched</green> to <aqua>"
                    + resolveUrls().size() + "</aqua> endpoint(s).");
            ctx.getLogger().info("Webhook test triggered by " + actor.getName());
        }

        @Override
        public List<String> suggest(CommandActor actor, String[] args) {
            if (args == null || args.length <= 1) {
                return List.of("test", "reload");
            }
            return List.of();
        }
    }
}
