/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.event.ServerPowerSignalEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VPPEventBusAndAddonTest {

    @Test
    void eventBusDeliversSupportsCancellationUnsubscribeAndIsolatesListenerExceptions() throws Exception {
        DefaultVPPEventBus bus = new DefaultVPPEventBus();
        List<String> received = new ArrayList<>();

        // Throwing listener must not break downstream subscribers
        bus.subscribe(ServerPowerSignalEvent.class, ev -> {
            throw new IllegalStateException("boom");
        });

        AutoCloseable sub = bus.subscribe(ServerPowerSignalEvent.class, ev -> {
            received.add(ev.getServerName() + ":" + ev.getSignal());
            ev.setCancelled(true);
        });

        ServerPowerSignalEvent event1 = new ServerPowerSignalEvent("survival", "srv-1", "start");
        bus.post(event1);

        assertThat(received).containsExactly("survival:start");
        assertThat(event1.isCancelled()).isTrue();

        sub.close();
        ServerPowerSignalEvent event2 = new ServerPowerSignalEvent("survival", "srv-1", "stop");
        bus.post(event2);

        assertThat(received).hasSize(1);
        assertThat(event2.isCancelled()).isFalse();
    }
}
