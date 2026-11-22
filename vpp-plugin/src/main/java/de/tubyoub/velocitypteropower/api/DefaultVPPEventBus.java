package de.tubyoub.velocitypteropower.api;

import de.tubyoub.vpp.api.event.VPPEvent;
import de.tubyoub.vpp.api.event.VPPEventBus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

public class DefaultVPPEventBus implements VPPEventBus {
    private final Map<Class<? extends VPPEvent>, Set<Consumer<VPPEvent>>> listeners = new ConcurrentHashMap<>();

    @Override
    public <T extends VPPEvent> AutoCloseable subscribe(Class<T> eventType, Consumer<T> listener) {
        Set<Consumer<VPPEvent>> set = listeners.computeIfAbsent((Class<? extends VPPEvent>) eventType, k -> new CopyOnWriteArraySet<>());
        Consumer<VPPEvent> wrapper = e -> listener.accept((T) e);
        set.add(wrapper);
        return () -> set.remove(wrapper);
    }

    @Override
    public void post(VPPEvent event) {
        if (event == null) return;
        Class<?> type = event.getClass();
        listeners.forEach((cls, set) -> {
            if (cls.isAssignableFrom(type)) {
                for (Consumer<VPPEvent> c : set) {
                    try { c.accept(event); } catch (Throwable ignored) {}
                }
            }
        });
    }
}
