/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.hooks;

import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;
import de.tubyoub.vpp.api.maintenance.MaintenanceQuery;
import eu.kennytv.maintenance.api.MaintenanceProvider;
import eu.kennytv.maintenance.api.Settings;
import eu.kennytv.maintenance.api.event.MaintenanceChangedEvent;
import eu.kennytv.maintenance.api.event.manager.EventManager;
import eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the reflective kennytv/Maintenance adapter against stub API classes that mirror
 * the methods it looks up (the real Maintenance JAR is not on the test classpath).
 */
class KennytvMaintenanceProviderTest {

    /** Named public classes (not lambdas) so the adapter's reflective getMethod().invoke() can reach them. */
    public static final class FakeServer implements Server {
        private final String name;
        public FakeServer(String name) { this.name = name; }
        @Override public String getName() { return name; }
    }

    public static final class FakeSettings implements Settings {
        private final Set<UUID> whitelist;
        public FakeSettings(Set<UUID> whitelist) { this.whitelist = whitelist; }
        @Override public boolean isWhitelisted(UUID uuid) { return whitelist.contains(uuid); }
    }

    public static final class FakeMaintenance implements MaintenanceProxy {
        public boolean global;
        public final Set<String> serversInMaintenance = new HashSet<>();
        public final Set<UUID> whitelist = new HashSet<>();
        public final EventManager events = new EventManager();

        @Override public boolean isMaintenance() { return global; }
        @Override public boolean isMaintenance(Server server) { return serversInMaintenance.contains(server.getName()); }
        @Override public Server getServer(String name) { return new FakeServer(name); }
        @Override public Settings getSettings() { return new FakeSettings(whitelist); }
        @Override public EventManager getEventManager() { return events; }
    }

    private FakeMaintenance maintenance;
    private ProxyServer proxy;
    private KennytvMaintenanceProvider provider;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        maintenance = new FakeMaintenance();
        MaintenanceProvider.instance = maintenance;
        proxy = mock(ProxyServer.class);
        PluginManager plugins = mock(PluginManager.class);
        when(proxy.getPluginManager()).thenReturn(plugins);
        when(plugins.isLoaded("maintenance")).thenReturn(true);
        provider = new KennytvMaintenanceProvider(mock(FilteredComponentLogger.class));
    }

    @AfterEach
    void tearDown() {
        MaintenanceProvider.instance = null;
    }

    private MaintenanceQuery query(String server, String... perms) {
        Set<String> granted = Set.of(perms);
        return new MaintenanceQuery(alice, "Alice", server, granted::contains);
    }

    @Test
    void doesNotAttachWhenThePluginIsMissing() {
        when(proxy.getPluginManager().isLoaded("maintenance")).thenReturn(false);
        assertThat(provider.tryAttach(proxy)).isNull();
        assertThat(provider.isActive()).isFalse();
        assertThat(provider.blocks(query("survival"))).isFalse();
    }

    @Test
    void globalMaintenanceBlocksUnlessBypassedOrWhitelisted() {
        assertThat(provider.tryAttach(proxy)).isSameAs(provider);
        assertThat(provider.isActive()).isTrue();
        maintenance.global = true;

        assertThat(provider.blocks(query("survival"))).isTrue();
        assertThat(provider.blocks(query("survival", "maintenance.bypass"))).isFalse();
        maintenance.whitelist.add(alice);
        assertThat(provider.blocks(query("survival"))).isFalse();
    }

    @Test
    void perServerMaintenanceHonoursTheSingleServerBypass() {
        provider.tryAttach(proxy);
        maintenance.serversInMaintenance.add("skywars");

        assertThat(provider.blocks(query("survival"))).isFalse();
        assertThat(provider.blocks(query("skywars"))).isTrue();
        assertThat(provider.blocks(query("skywars", "maintenance.singleserver.bypass.skywars"))).isFalse();
    }

    @Test
    void maintenanceOffEventsNotifyClearListeners() {
        provider.tryAttach(proxy);
        List<String> cleared = new ArrayList<>();
        provider.addClearListener(cleared::add);

        maintenance.events.fire(new MaintenanceChangedEvent(true));
        maintenance.events.fire(new MaintenanceChangedEvent(false));
        maintenance.events.fire(new ServerMaintenanceChangedEvent(new FakeServer("skywars"), false));

        assertThat(cleared).containsExactly(null, "skywars");
    }
}
