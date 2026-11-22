package de.tubyoub.vpp.addon.scheduler;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import de.tubyoub.vpp.api.AddonCommand;
import de.tubyoub.vpp.api.AddonConfig;
import de.tubyoub.vpp.api.AddonContext;
import de.tubyoub.vpp.api.AddonMeta;
import de.tubyoub.vpp.api.CommandActor;
import de.tubyoub.vpp.api.ServerControl;
import de.tubyoub.vpp.api.VPPAddon;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cron-driven start/stop of managed servers via {@link ServerControl}.
 */
public final class SchedulerAddon implements VPPAddon {
    private static final CronParser UNIX_PARSER = new CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private AddonContext ctx;
    private AddonConfig cfg;
    private ServerControl control;
    private ScheduledExecutorService executor;
    private final AtomicReference<List<ScheduleEntry>> schedules = new AtomicReference<>(List.of());
    private final Map<String, Long> lastFireEpochSecond = new ConcurrentHashMap<>();
    private volatile ZoneId zoneId = ZoneId.of("UTC");

    @Override
    public AddonMeta meta() {
        return AddonMeta.builder("vpp-scheduler")
                .name("VPP Scheduler")
                .version("1.0.0")
                .authors("TubYoub")
                .description("Cron-based scheduled start/stop for managed servers")
                .apiVersion("1.0.0")
                .build();
    }

    @Override
    public void onEnable(AddonContext context) {
        this.ctx = context;
        this.cfg = context.getConfig();
        this.control = context.getApi().getServerControl();
        seedDefaults();
        reloadSchedules();

        int interval = Math.max(5, cfg.getInt("checkIntervalSeconds", 30));
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vpp-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, 5, interval, TimeUnit.SECONDS);
        ctx.registerCommand(new ScheduleCmd());

        ctx.getLogger().info("Scheduler addon enabled (timezone=" + zoneId
                + ", schedules=" + schedules.get().size()
                + ", interval=" + interval + "s)");
        ctx.getLogger().debug("Cron help: " + cfg.getString("cronHelpUrl", "https://crontab.guru/"));
    }

    @Override
    public void onDisable() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        schedules.set(List.of());
        lastFireEpochSecond.clear();
        control = null;
        cfg = null;
        ctx = null;
    }

    private void seedDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        // Cron expression help: https://crontab.guru/
        defaults.put("cronHelpUrl", "https://crontab.guru/");
        defaults.put("timezone", "UTC");
        defaults.put("checkIntervalSeconds", 30);
        defaults.put("enabled", true);
        List<Map<String, Object>> example = new ArrayList<>();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("name", "evening-start");
        sample.put("enabled", false);
        sample.put("cron", "0 18 * * *");
        sample.put("action", "start");
        sample.put("servers", List.of("lobby", "survival"));
        example.add(sample);
        defaults.put("schedules", example);
        cfg.ensureDefaults(defaults);
        cfg.save();
    }

    private void reloadSchedules() {
        cfg.reload();
        String tz = cfg.getString("timezone", "UTC");
        try {
            zoneId = ZoneId.of(tz);
        } catch (Exception e) {
            ctx.getLogger().warn("Invalid timezone '" + tz + "', falling back to UTC");
            zoneId = ZoneId.of("UTC");
        }

        List<ScheduleEntry> parsed = new ArrayList<>();
        for (Map<String, Object> raw : cfg.getMapList("schedules")) {
            try {
                ScheduleEntry entry = ScheduleEntry.from(raw);
                if (entry != null) {
                    parsed.add(entry);
                    ctx.getLogger().debug("Loaded schedule name=" + entry.name
                            + " cron='" + entry.cronExpression + "' action=" + entry.action
                            + " servers=" + entry.servers + " enabled=" + entry.enabled);
                }
            } catch (Exception e) {
                ctx.getLogger().warn("Skipping invalid schedule entry: " + e.getMessage());
            }
        }
        schedules.set(List.copyOf(parsed));
        ctx.getLogger().info("Loaded " + parsed.size() + " schedule(s); cron help: "
                + cfg.getString("cronHelpUrl", "https://crontab.guru/"));
    }

    private void tick() {
        if (ctx == null || control == null) {
            return;
        }
        if (!cfg.getBoolean("enabled", true)) {
            ctx.getLogger().debug("Scheduler tick skipped (addon disabled in config)");
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        long nowEpoch = now.toEpochSecond();
        ctx.getLogger().debug("Scheduler tick at " + now + " (" + schedules.get().size() + " schedules)");

        for (ScheduleEntry entry : schedules.get()) {
            if (!entry.enabled) {
                continue;
            }
            try {
                Optional<ZonedDateTime> last = entry.executionTime.lastExecution(now);
                if (last.isEmpty()) {
                    continue;
                }
                ZonedDateTime lastExec = last.get();
                long lastEpoch = lastExec.toEpochSecond();
                // Fire if the most recent cron match was within the last check window (plus small slack).
                long ageSeconds = nowEpoch - lastEpoch;
                int interval = Math.max(5, cfg.getInt("checkIntervalSeconds", 30));
                if (ageSeconds < 0 || ageSeconds > interval + 5) {
                    continue;
                }
                Long previous = lastFireEpochSecond.get(entry.name);
                if (previous != null && previous == lastEpoch) {
                    ctx.getLogger().debug("Schedule '" + entry.name + "' already fired for "
                            + lastExec + "; skipping");
                    continue;
                }
                lastFireEpochSecond.put(entry.name, lastEpoch);
                fire(entry, lastExec);
            } catch (Exception e) {
                ctx.getLogger().error("Schedule tick failed for '" + entry.name + "': " + e.getMessage(), e);
            }
        }
    }

    private void fire(ScheduleEntry entry, ZonedDateTime at) {
        ctx.getLogger().info("Firing schedule '" + entry.name + "' action=" + entry.action
                + " at " + at + " servers=" + entry.servers);
        for (String server : entry.servers) {
            if (server == null || server.isBlank()) {
                continue;
            }
            String name = server.trim();
            try {
                if ("stop".equals(entry.action)) {
                    ctx.getLogger().debug("Scheduled STOP -> " + name);
                    control.stopByName(name);
                } else {
                    ctx.getLogger().debug("Scheduled START -> " + name);
                    control.startByName(name);
                }
            } catch (Exception e) {
                ctx.getLogger().error("Failed to " + entry.action + " server '" + name + "': " + e.getMessage(), e);
            }
        }
    }

    private Optional<ZonedDateTime> nextExecution(ScheduleEntry entry) {
        try {
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            return entry.executionTime.nextExecution(now);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private record ScheduleEntry(
            String name,
            boolean enabled,
            String cronExpression,
            String action,
            List<String> servers,
            ExecutionTime executionTime
    ) {
        @SuppressWarnings("unchecked")
        static ScheduleEntry from(Map<String, Object> raw) {
            String name = String.valueOf(raw.getOrDefault("name", "unnamed")).trim();
            if (name.isEmpty()) {
                name = "unnamed";
            }
            boolean enabled = parseBool(raw.get("enabled"), true);
            String cronExpr = String.valueOf(raw.getOrDefault("cron", "")).trim();
            if (cronExpr.isEmpty()) {
                throw new IllegalArgumentException("schedule '" + name + "' missing cron");
            }
            String action = String.valueOf(raw.getOrDefault("action", "start")).trim().toLowerCase(Locale.ROOT);
            if (!"start".equals(action) && !"stop".equals(action)) {
                throw new IllegalArgumentException("schedule '" + name + "' action must be start|stop");
            }
            List<String> servers = new ArrayList<>();
            Object serversRaw = raw.get("servers");
            if (serversRaw instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) {
                        servers.add(String.valueOf(o));
                    }
                }
            } else if (serversRaw instanceof String s && !s.isBlank()) {
                for (String part : s.split(",")) {
                    if (!part.isBlank()) {
                        servers.add(part.trim());
                    }
                }
            }
            if (servers.isEmpty()) {
                throw new IllegalArgumentException("schedule '" + name + "' has no servers");
            }
            Cron cron = UNIX_PARSER.parse(cronExpr);
            cron.validate();
            ExecutionTime et = ExecutionTime.forCron(cron);
            return new ScheduleEntry(name, enabled, cronExpr, action, List.copyOf(servers), et);
        }

        private static boolean parseBool(Object v, boolean def) {
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                return Boolean.parseBoolean(s);
            }
            return def;
        }
    }

    private final class ScheduleCmd implements AddonCommand {
        @Override
        public String name() {
            return "schedule";
        }

        @Override
        public List<String> aliases() {
            return List.of("schedules", "cron");
        }

        @Override
        public String description() {
            return "List or reload cron schedules";
        }

        @Override
        public String permission() {
            return "vpp.scheduler.manage";
        }

        @Override
        public void execute(CommandActor actor, String[] args) {
            String sub = (args != null && args.length > 0) ? args[0].toLowerCase(Locale.ROOT) : "list";
            switch (sub) {
                case "reload" -> {
                    reloadSchedules();
                    actor.sendMessage("<green>Schedules reloaded.</green> <gray>("
                            + schedules.get().size() + " entries, timezone=" + zoneId + ")</gray>");
                    actor.sendMessage("<gray>Cron help:</gray> <aqua>"
                            + cfg.getString("cronHelpUrl", "https://crontab.guru/") + "</aqua>");
                }
                case "list" -> listSchedules(actor);
                default -> actor.sendMessage("<yellow>Usage:</yellow> <gray>/ptero schedule list|reload</gray>");
            }
        }

        private void listSchedules(CommandActor actor) {
            List<ScheduleEntry> entries = schedules.get();
            actor.sendMessage("<gold>VPP Schedules</gold> <gray>(timezone=" + zoneId + ")</gray>");
            actor.sendMessage("<gray>Cron help:</gray> <aqua>"
                    + cfg.getString("cronHelpUrl", "https://crontab.guru/") + "</aqua>");
            if (entries.isEmpty()) {
                actor.sendMessage("<yellow>No schedules configured.</yellow>");
                return;
            }
            for (ScheduleEntry e : entries) {
                String next = nextExecution(e)
                        .map(ZonedDateTime::toString)
                        .orElse("n/a");
                String status = e.enabled ? "<green>on</green>" : "<red>off</red>";
                actor.sendMessage("<aqua>" + e.name + "</aqua> " + status
                        + " <gray>" + e.action + "</gray> <white>" + e.cronExpression + "</white>"
                        + " <dark_gray>→</dark_gray> <yellow>" + String.join(",", e.servers) + "</yellow>"
                        + " <gray>next=" + next + "</gray>");
            }
        }

        @Override
        public List<String> suggest(CommandActor actor, String[] args) {
            if (args == null || args.length <= 1) {
                return List.of("list", "reload");
            }
            return List.of();
        }
    }
}
