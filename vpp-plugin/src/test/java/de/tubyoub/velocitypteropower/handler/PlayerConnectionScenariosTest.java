/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.handler;

import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.http.PanelAPIClient;
import de.tubyoub.velocitypteropower.http.PowerSignal;
import de.tubyoub.velocitypteropower.lifecycle.ServerLifecycleManager;
import de.tubyoub.velocitypteropower.lobby.LobbyBalancerManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager.ForcedHostOfflineBehavior;
import de.tubyoub.velocitypteropower.manager.MessageKey;
import de.tubyoub.velocitypteropower.manager.MessagesManager;
import de.tubyoub.velocitypteropower.manager.WhitelistManager;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.service.ServerGroupService;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drives {@link PlayerConnectionHandler#onServerPreConnect} through the config variants the
 * live E2E config does not use: queue-off at the cap, reservations, lobby/limbo counting,
 * forced-host offline behaviours, soft-requeue, idleStartShutdownTime, and linked starts.
 */
class PlayerConnectionScenariosTest {

    /** A scheduled task captured from the mocked Velocity scheduler. */
    record Task(Runnable runnable, long delaySeconds) {}

    private VelocityPteroPower plugin;
    private ProxyServer proxy;
    private ConfigurationManager config;
    private PanelAPIClient api;
    private RateLimitTracker rateLimit;
    private ServerLifecycleManager lifecycle;
    private LobbyBalancerManager balancer;
    private ServerGroupService groups;
    private final List<MessageKey> sentKeys = new CopyOnWriteArrayList<>();
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private Set<String> starting;
    private Map<String, Set<UUID>> waiting;
    private Map<String, PteroServerInfo> servers;

    private RegisteredServer limbo;
    private RegisteredServer lobby;
    private RegisteredServer survival;

    @BeforeEach
    void setUp() {
        plugin = mock(VelocityPteroPower.class);
        proxy = mock(ProxyServer.class);
        config = mock(ConfigurationManager.class);
        api = mock(PanelAPIClient.class);
        rateLimit = mock(RateLimitTracker.class);
        lifecycle = mock(ServerLifecycleManager.class);
        balancer = mock(LobbyBalancerManager.class);
        groups = mock(ServerGroupService.class);
        WhitelistManager whitelist = mock(WhitelistManager.class);
        starting = ConcurrentHashMap.newKeySet();
        waiting = new ConcurrentHashMap<>();
        servers = new HashMap<>();
        servers.put("survival", new PteroServerInfo("srv-survival", 30, 0, false));
        servers.put("limbo", new PteroServerInfo("srv-limbo", -1, 0, false));
        servers.put("lobby-1", new PteroServerInfo("srv-lobby", -1, 0, false));

        MessagesManager messages = mock(MessagesManager.class, inv -> {
            if (inv.getMethod().getName().equals("prefixed") && inv.getArgument(0) instanceof MessageKey key) {
                sentKeys.add(key);
            }
            Class<?> rt = inv.getMethod().getReturnType();
            if (rt == Component.class) return Component.text("msg");
            if (rt == String.class) return "<server> msg";
            return null;
        });

        Scheduler scheduler = mock(Scheduler.class);
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
            long[] delay = {0};
            when(builder.delay(anyLong(), any(TimeUnit.class))).thenAnswer(d -> {
                delay[0] = ((TimeUnit) d.getArgument(1)).toSeconds(d.getArgument(0));
                return builder;
            });
            when(builder.schedule()).thenAnswer(s -> {
                tasks.add(new Task(r, delay[0]));
                return mock(ScheduledTask.class);
            });
            return builder;
        });

        when(plugin.getFilteredLogger()).thenReturn(mock(FilteredComponentLogger.class));
        when(plugin.getConfigurationManager()).thenReturn(config);
        when(plugin.getMessagesManager()).thenReturn(messages);
        when(plugin.getApiClient()).thenReturn(api);
        when(plugin.getRateLimitTracker()).thenReturn(rateLimit);
        when(plugin.getServerInfoMap()).thenReturn(servers);
        when(plugin.getStartingServers()).thenReturn(starting);
        when(plugin.getWaitingPlayers()).thenReturn(waiting);
        when(plugin.getPlayerCooldowns()).thenReturn(new ConcurrentHashMap<>());
        when(plugin.getStartInitiators()).thenReturn(new ConcurrentHashMap<>());
        when(plugin.getStartingServersSince()).thenReturn(new ConcurrentHashMap<>());
        when(plugin.getWhitelistManager()).thenReturn(whitelist);
        when(plugin.getServerLifecycleManager()).thenReturn(lifecycle);
        when(plugin.getLobbyBalancerManager()).thenReturn(balancer);
        when(plugin.getServerGroupService()).thenReturn(groups);
        when(whitelist.isPlayerWhitelisted(anyString(), anyString())).thenReturn(true);

        when(rateLimit.canMakeRequest()).thenReturn(true);
        when(config.getBalancerLobbies()).thenReturn(List.of("lobby-1"));
        when(config.getBalancerLimbos()).thenReturn(List.of("limbo"));
        when(config.getMaxOnlineExemptList()).thenReturn(List.of());
        when(config.getStartupInitialCheckDelay()).thenReturn(3);
        when(config.resolvePollInterval(any())).thenReturn(2);
        when(config.resolveStartupTimeout(any())).thenReturn(90);
        when(config.getIdleStartShutdownTime()).thenReturn(-1);
        when(config.getForcedHostOfflineBehavior()).thenReturn(ForcedHostOfflineBehavior.LOBBY_OR_LIMBO);

        limbo = registered("limbo");
        lobby = registered("lobby-1");
        survival = registered("survival");
        when(proxy.getServer("survival")).thenReturn(Optional.of(survival));
        when(proxy.getServer("limbo")).thenReturn(Optional.of(limbo));
    }

    private static RegisteredServer registered(String name) {
        RegisteredServer rs = mock(RegisteredServer.class);
        when(rs.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved(name, 25565)));
        return rs;
    }

    private Player player(String name) {
        Player p = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(p.getUniqueId()).thenReturn(id);
        when(p.getUsername()).thenReturn(name);
        when(p.isActive()).thenReturn(true);
        when(p.getCurrentServer()).thenReturn(Optional.empty());
        when(proxy.getPlayer(id)).thenReturn(Optional.of(p));
        return p;
    }

    private ServerPreConnectEvent switchTo(Player p, RegisteredServer target, RegisteredServer from) {
        return from == null ? new ServerPreConnectEvent(p, target) : new ServerPreConnectEvent(p, target, from);
    }

    private PlayerConnectionHandler handler() {
        return new PlayerConnectionHandler(proxy, plugin);
    }

    // ---------------------------------------------------------------- caps

    private void capFull(int online) {
        when(config.getMaxOnlineServers()).thenReturn(2);
        when(lifecycle.countOnlineServersExcluding(anySet())).thenReturn(online);
    }

    @Test
    void queueWhenMaxOnlineFalseDeniesWithoutQueueingOrStarting() {
        capFull(2);
        when(config.isQueueWhenMaxOnline()).thenReturn(false);
        Player alice = player("Alice");
        ServerPreConnectEvent ev = switchTo(alice, survival, lobby);

        handler().onServerPreConnect(ev);

        assertThat(ev.getResult().isAllowed()).isFalse();
        assertThat(sentKeys).contains(MessageKey.CONNECT_MAX_ONLINE_REACHED);
        assertThat(waiting).doesNotContainKey("survival");
        verify(api, never()).powerServer(anyString(), any());
    }

    @Test
    void queueWhenMaxOnlineTrueQueuesThePlayerInstead() {
        capFull(2);
        when(config.isQueueWhenMaxOnline()).thenReturn(true);
        Player alice = player("Alice");
        ServerPreConnectEvent ev = switchTo(alice, survival, lobby);

        handler().onServerPreConnect(ev);

        assertThat(ev.getResult().isAllowed()).isFalse();
        assertThat(sentKeys).contains(MessageKey.CONNECT_CAPACITY_QUEUED);
        assertThat(waiting.get("survival")).contains(alice.getUniqueId());
        verify(api, never()).powerServer(anyString(), any());
    }

    @Test
    void maxOnlineReservationsShrinkTheEffectiveCap() {
        capFull(1); // 1 of 2 online would normally leave a slot
        when(config.isQueueWhenMaxOnline()).thenReturn(false);
        when(config.getMaxOnlineReservations()).thenReturn(1);
        Player alice = player("Alice");
        ServerPreConnectEvent ev = switchTo(alice, survival, lobby);

        handler().onServerPreConnect(ev);
        assertThat(sentKeys).contains(MessageKey.CONNECT_MAX_ONLINE_REACHED);
        verify(api, never()).powerServer(anyString(), any());

        // Same count with no reservation starts the server
        when(config.getMaxOnlineReservations()).thenReturn(0);
        handler().onServerPreConnect(switchTo(player("Bob"), survival, lobby));
        verify(api).powerServer("srv-survival", PowerSignal.START);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lobbiesAndLimbosAreExemptFromTheCapUnlessCounted() {
        capFull(0);
        ArgumentCaptor<Set<String>> exempt = ArgumentCaptor.forClass(Set.class);

        when(config.isCountLobbiesInMaxOnline()).thenReturn(false);
        when(config.isCountLimbosInMaxOnline()).thenReturn(false);
        handler().onServerPreConnect(switchTo(player("Alice"), survival, lobby));
        verify(lifecycle).countOnlineServersExcluding(exempt.capture());
        assertThat(exempt.getValue()).contains("lobby-1", "limbo");

        starting.clear();
        reset(lifecycle);
        when(lifecycle.countOnlineServersExcluding(anySet())).thenReturn(0);
        when(config.isCountLobbiesInMaxOnline()).thenReturn(true);
        when(config.isCountLimbosInMaxOnline()).thenReturn(true);
        handler().onServerPreConnect(switchTo(player("Bob"), survival, lobby));
        verify(lifecycle).countOnlineServersExcluding(exempt.capture());
        assertThat(exempt.getValue()).doesNotContain("lobby-1", "limbo");
    }

    @Test
    void maxCapBypassPermissionSkipsTheCap() {
        capFull(2);
        when(config.isMaxOnlineAllowBypass()).thenReturn(true);
        Player admin = player("Admin");
        when(admin.hasPermission("ptero.maxcap.bypass")).thenReturn(true);

        handler().onServerPreConnect(switchTo(admin, survival, lobby));

        verify(api).powerServer("srv-survival", PowerSignal.START);
        verify(lifecycle, never()).countOnlineServersExcluding(anySet());
    }

    // ---------------------------------------------------------------- forced host

    @Test
    void forcedHostDisconnectBehaviourKicksAndStillStarts() {
        when(config.isSendToLimboOnStart()).thenReturn(false);
        when(config.getForcedHostOfflineBehavior()).thenReturn(ForcedHostOfflineBehavior.DISCONNECT);
        when(balancer.pickHoldingServer()).thenReturn(Optional.of(limbo));
        Player alice = player("Alice");
        ServerPreConnectEvent ev = switchTo(alice, survival, null);

        handler().onServerPreConnect(ev);

        verify(alice).disconnect(any());
        assertThat(ev.getResult().isAllowed()).isFalse();
        verify(api).powerServer("srv-survival", PowerSignal.START);
    }

    @Test
    void forcedHostLimboOnlyHoldsOnLimboEvenWhenALobbyIsUp() {
        when(config.isSendToLimboOnStart()).thenReturn(false);
        when(config.getForcedHostOfflineBehavior()).thenReturn(ForcedHostOfflineBehavior.LIMBO_ONLY);
        // A lobby is up (pickHoldingServer prefers it) but no limbo is reachable -> disconnect
        when(balancer.pickHoldingServer()).thenReturn(Optional.of(lobby));
        when(balancer.pickLimbo()).thenReturn(Optional.empty());
        Player alice = player("Alice");

        handler().onServerPreConnect(switchTo(alice, survival, null));
        verify(alice).disconnect(any());

        // Lobby still preferred by pickHoldingServer, but limbo is reachable -> hold on limbo
        when(balancer.pickLimbo()).thenReturn(Optional.of(limbo));
        starting.clear();
        Player bob = player("Bob");
        ServerPreConnectEvent ev = switchTo(bob, survival, null);
        handler().onServerPreConnect(ev);
        verify(bob, never()).disconnect(any());
        assertThat(ev.getResult().getServer()).contains(limbo);
        assertThat(waiting.get("survival")).contains(bob.getUniqueId());
    }

    @Test
    void forcedHostLobbyOrLimboAcceptsALobby() {
        when(config.isSendToLimboOnStart()).thenReturn(false);
        when(balancer.pickHoldingServer()).thenReturn(Optional.of(lobby));
        Player alice = player("Alice");
        ServerPreConnectEvent ev = switchTo(alice, survival, null);

        handler().onServerPreConnect(ev);

        verify(alice, never()).disconnect(any());
        assertThat(ev.getResult().getServer()).contains(lobby);
    }

    // ---------------------------------------------------------------- linked servers

    @Test
    void coldStartAlsoStartsLinkedPeers() {
        when(config.isSendToLimboOnStart()).thenReturn(true);
        when(balancer.pickLimbo()).thenReturn(Optional.of(limbo));

        handler().onServerPreConnect(switchTo(player("Alice"), survival, lobby));

        verify(api).powerServer("srv-survival", PowerSignal.START);
        verify(groups).startLinkedPeers("survival");
    }

    // ---------------------------------------------------------------- idleStartShutdownTime

    @Test
    void idleStartShutdownStopsAColdStartedServerNobodyJoined() {
        when(config.getIdleStartShutdownTime()).thenReturn(45);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        Player alice = player("Alice");
        handler().onServerPreConnect(switchTo(alice, survival, lobby));

        Task idleCheck = tasks.stream().filter(t -> t.delaySeconds() == 45).findFirst().orElseThrow();
        Task watcher = tasks.stream().filter(t -> t.delaySeconds() == 3).findFirst().orElseThrow();
        // Player gave up waiting; the startup watcher notices and retires; server came up empty
        waiting.get("survival").clear();
        watcher.runnable().run();
        when(api.isServerOnline("survival", "srv-survival")).thenReturn(true);
        when(api.isServerEmpty("survival")).thenReturn(true);

        idleCheck.runnable().run();

        verify(api).powerServer("srv-survival", PowerSignal.STOP);
        assertThat(starting).doesNotContain("survival");
    }

    @Test
    void idleStartShutdownKeepsAServerThatGotAPlayer() {
        when(config.getIdleStartShutdownTime()).thenReturn(45);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        handler().onServerPreConnect(switchTo(player("Alice"), survival, lobby));

        Task idleCheck = tasks.stream().filter(t -> t.delaySeconds() == 45).findFirst().orElseThrow();
        Task watcher = tasks.stream().filter(t -> t.delaySeconds() == 3).findFirst().orElseThrow();
        waiting.get("survival").clear();
        watcher.runnable().run();
        when(api.isServerOnline("survival", "srv-survival")).thenReturn(true);
        when(api.isServerEmpty("survival")).thenReturn(false);

        idleCheck.runnable().run();

        verify(api, never()).powerServer("srv-survival", PowerSignal.STOP);
    }

    @Test
    void idleStartShutdownWaitsWhilePlayersAreStillQueued() {
        when(config.getIdleStartShutdownTime()).thenReturn(45);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        handler().onServerPreConnect(switchTo(player("Alice"), survival, lobby));
        Task idleCheck = tasks.stream().filter(t -> t.delaySeconds() == 45).findFirst().orElseThrow();
        when(api.isServerOnline("survival", "srv-survival")).thenReturn(true);
        when(api.isServerEmpty("survival")).thenReturn(true);
        int before = tasks.size();

        idleCheck.runnable().run();

        verify(api, never()).powerServer("srv-survival", PowerSignal.STOP);
        assertThat(tasks).hasSize(before + 1); // rescheduled
    }

    @Test
    void idleStartShutdownNeverStopsAnAlwaysOnlineServer() {
        when(config.getIdleStartShutdownTime()).thenReturn(45);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        when(config.getAlwaysOnlineList()).thenReturn(List.of("survival"));
        handler().onServerPreConnect(switchTo(player("Alice"), survival, lobby));
        Task idleCheck = tasks.stream().filter(t -> t.delaySeconds() == 45).findFirst().orElseThrow();
        waiting.clear();
        when(api.isServerOnline("survival", "srv-survival")).thenReturn(true);
        when(api.isServerEmpty("survival")).thenReturn(true);

        idleCheck.runnable().run();

        verify(api, never()).powerServer("srv-survival", PowerSignal.STOP);
    }

    @Test
    void negativeIdleStartShutdownTimeSchedulesNoIdleCheck() {
        when(config.getIdleStartShutdownTime()).thenReturn(-1);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        handler().onServerPreConnect(switchTo(player("Alice"), survival, lobby));

        // Only the startup watcher (initial delay 3s) is scheduled
        assertThat(tasks).extracting(Task::delaySeconds).containsOnly(3L);
    }

    // ---------------------------------------------------------------- soft requeue

    private Method connectMethod() throws Exception {
        Method m = PlayerConnectionHandler.class.getDeclaredMethod(
                "connectPlayerToServer", Player.class, String.class, boolean.class);
        m.setAccessible(true);
        return m;
    }

    private void stubConnect(Player p, ConnectionRequestBuilder.Status status, Component reason) {
        ConnectionRequestBuilder builder = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getReasonComponent()).thenReturn(Optional.ofNullable(reason));
        when(builder.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(p.createConnectionRequest(survival)).thenReturn(builder);
        // The auto-connect only fires once the player sits on a holding server in PLAY state.
        ServerConnection holding = mock(ServerConnection.class);
        when(holding.getServer()).thenReturn(limbo);
        when(holding.getServerInfo()).thenReturn(new ServerInfo("limbo", new InetSocketAddress("127.0.0.1", 25566)));
        when(p.getCurrentServer()).thenReturn(Optional.of(holding));
        when(p.getProtocolState()).thenReturn(com.velocitypowered.api.network.ProtocolState.PLAY);
    }

    @Test
    void prematureKickWithoutReasonSoftRequeuesThePlayer() throws Exception {
        when(config.getSoftRequeueGraceSeconds()).thenReturn(15);
        when(config.getSoftRequeueMaxAttempts()).thenReturn(2);
        Player alice = player("Alice");
        stubConnect(alice, ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, null);
        PlayerConnectionHandler h = handler();

        connectMethod().invoke(h, alice, "survival", true);

        assertThat(waiting.get("survival")).contains(alice.getUniqueId());
        assertThat(h.hasActiveWaiters("survival")).isTrue();
        assertThat(sentKeys).contains(MessageKey.CONNECT_SERVER_STARTING);
        // settle delay = max(2, joinDelay=0)
        assertThat(tasks).extracting(Task::delaySeconds).contains(2L);
    }

    @Test
    void backendKickReasonIsNotSoftRequeued() throws Exception {
        when(config.getSoftRequeueGraceSeconds()).thenReturn(15);
        when(config.getSoftRequeueMaxAttempts()).thenReturn(2);
        Player alice = player("Alice");
        stubConnect(alice, ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, Component.text("You are banned"));
        PlayerConnectionHandler h = handler();

        connectMethod().invoke(h, alice, "survival", true);

        assertThat(waiting).doesNotContainKey("survival");
        assertThat(h.hasActiveWaiters("survival")).isFalse();
    }

    @Test
    void softRequeueDisabledByZeroGrace() throws Exception {
        when(config.getSoftRequeueGraceSeconds()).thenReturn(0);
        when(config.getSoftRequeueMaxAttempts()).thenReturn(2);
        Player alice = player("Alice");
        stubConnect(alice, ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, null);

        connectMethod().invoke(handler(), alice, "survival", true);

        assertThat(waiting).doesNotContainKey("survival");
    }

    @Test
    void softRequeueStopsAfterMaxAttempts() throws Exception {
        when(config.getSoftRequeueGraceSeconds()).thenReturn(15);
        when(config.getSoftRequeueMaxAttempts()).thenReturn(1);
        Player alice = player("Alice");
        stubConnect(alice, ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, null);
        PlayerConnectionHandler h = handler();
        Method connect = connectMethod();

        connect.invoke(h, alice, "survival", true);
        assertThat(waiting.get("survival")).contains(alice.getUniqueId());

        // The retry after settle gets kicked again: the attempt budget (1) is spent, so give up.
        connect.invoke(h, alice, "survival", true);
        assertThat(waiting).doesNotContainKey("survival");
        assertThat(sentKeys).containsOnlyOnce(MessageKey.CONNECT_SERVER_STARTING);
    }

    @Test
    void transfersNotFromAStartupWaitAreNeverSoftRequeued() throws Exception {
        when(config.getSoftRequeueGraceSeconds()).thenReturn(15);
        when(config.getSoftRequeueMaxAttempts()).thenReturn(3);
        Player alice = player("Alice");
        stubConnect(alice, ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, null);

        connectMethod().invoke(handler(), alice, "survival", false);

        assertThat(waiting).doesNotContainKey("survival");
    }

    @Test
    void idleStartShutdownSkipsAServerAPlayerJoinedAndLeft() throws Exception {
        when(config.getIdleStartShutdownTime()).thenReturn(45);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        Player alice = player("Alice");
        PlayerConnectionHandler h = handler();
        h.onServerPreConnect(switchTo(alice, survival, lobby));
        Task idleCheck = tasks.stream().filter(t -> t.delaySeconds() == 45).findFirst().orElseThrow();

        // Watcher transfers Alice successfully, then she leaves again
        stubConnect(alice, ConnectionRequestBuilder.Status.SUCCESS, null);
        connectMethod().invoke(h, alice, "survival", true);
        waiting.clear();
        when(api.isServerOnline("survival", "srv-survival")).thenReturn(true);
        when(api.isServerEmpty("survival")).thenReturn(true);

        idleCheck.runnable().run();

        verify(api, never()).powerServer("srv-survival", PowerSignal.STOP);
    }

    @Test
    void idleStartShutdownIgnoresNeverStopServersAndLobbies() {
        when(config.getIdleStartShutdownTime()).thenReturn(45);
        when(config.isSendToLimboOnStart()).thenReturn(false);
        RegisteredServer lobbyTarget = registered("lobby-1");
        handler().onServerPreConnect(switchTo(player("Alice"), lobbyTarget, limbo));
        verify(api).powerServer("srv-lobby", PowerSignal.START);
        assertThat(tasks).extracting(Task::delaySeconds).doesNotContain(45L);
    }

    @Test
    void staleStartIsResentWhenThePanelReportsOffline() {
        de.tubyoub.velocitypteropower.service.ServerStateCache cache =
                new de.tubyoub.velocitypteropower.service.ServerStateCache(30);
        cache.put("srv-survival", de.tubyoub.velocitypteropower.service.ServerStateCache.State.OFFLINE);
        when(plugin.getServerStateCache()).thenReturn(cache);
        Map<String, Long> since = new ConcurrentHashMap<>();
        since.put("survival", System.currentTimeMillis() - 60_000);
        when(plugin.getStartingServersSince()).thenReturn(since);
        starting.add("survival"); // left behind by a start that was stopped from the panel
        when(config.isSendToLimboOnStart()).thenReturn(true);
        when(balancer.pickLimbo()).thenReturn(Optional.of(limbo));

        handler().onServerPreConnect(switchTo(player("Bob"), survival, null));

        verify(api).powerServer("srv-survival", PowerSignal.START);
    }

    @Test
    void freshStartIsNotResent() {
        de.tubyoub.velocitypteropower.service.ServerStateCache cache =
                new de.tubyoub.velocitypteropower.service.ServerStateCache(30);
        cache.put("srv-survival", de.tubyoub.velocitypteropower.service.ServerStateCache.State.OFFLINE);
        when(plugin.getServerStateCache()).thenReturn(cache);
        Map<String, Long> since = new ConcurrentHashMap<>();
        since.put("survival", System.currentTimeMillis());
        when(plugin.getStartingServersSince()).thenReturn(since);
        starting.add("survival");
        when(config.isSendToLimboOnStart()).thenReturn(true);
        when(balancer.pickLimbo()).thenReturn(Optional.of(limbo));

        handler().onServerPreConnect(switchTo(player("Bob"), survival, null));

        verify(api, never()).powerServer(anyString(), any());
    }

    private void persistedEntry(Player p, String server) {
        de.tubyoub.velocitypteropower.service.PersistentQueueService pq =
                mock(de.tubyoub.velocitypteropower.service.PersistentQueueService.class);
        when(pq.get(p.getUniqueId())).thenReturn(
                new de.tubyoub.velocitypteropower.service.PersistentQueueService.QueueEntry(server, System.currentTimeMillis()));
        when(plugin.getPersistentQueueService()).thenReturn(pq);
        when(config.isPersistentQueueEnabled()).thenReturn(true);
    }

    @Test
    void persistentQueueResumeHonoursTheStartPassword() {
        Player alice = player("Alice");
        persistedEntry(alice, "survival");
        de.tubyoub.velocitypteropower.service.StartPasswordService pw =
                mock(de.tubyoub.velocitypteropower.service.StartPasswordService.class);
        when(pw.requiresPassword("survival")).thenReturn(true);
        when(plugin.getStartPasswordService()).thenReturn(pw);

        handler().tryResumePersistentQueue(alice);

        assertThat(waiting).doesNotContainKey("survival");
        verify(plugin.getPersistentQueueService()).clear(alice.getUniqueId());

        // Once unlocked the resume goes ahead
        when(pw.isAuthorized(alice.getUniqueId(), "survival")).thenReturn(true);
        handler().tryResumePersistentQueue(alice);
        assertThat(waiting.get("survival")).contains(alice.getUniqueId());
    }

    @Test
    void persistentQueueResumeHonoursMaintenanceAndWhitelist() {
        Player alice = player("Alice");
        persistedEntry(alice, "survival");
        de.tubyoub.velocitypteropower.service.MaintenanceCoordinator maint =
                mock(de.tubyoub.velocitypteropower.service.MaintenanceCoordinator.class);
        when(maint.isBlocked(alice, "survival")).thenReturn(true);
        when(plugin.getMaintenanceCoordinator()).thenReturn(maint);
        handler().tryResumePersistentQueue(alice);
        assertThat(waiting).doesNotContainKey("survival");

        when(maint.isBlocked(alice, "survival")).thenReturn(false);
        when(plugin.getWhitelistManager().isPlayerWhitelisted("survival", "Alice")).thenReturn(false);
        handler().tryResumePersistentQueue(alice);
        assertThat(waiting).doesNotContainKey("survival");
    }
}
