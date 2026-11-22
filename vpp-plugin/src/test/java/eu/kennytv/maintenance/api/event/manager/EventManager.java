package eu.kennytv.maintenance.api.event.manager;

import java.util.ArrayList;
import java.util.List;

public class EventManager {
    public final List<Object[]> registered = new ArrayList<>();

    public void registerListener(EventListener listener, Class<?> eventClass) {
        registered.add(new Object[] {listener, eventClass});
    }

    public void fire(Object event) {
        for (Object[] entry : registered) {
            if (((Class<?>) entry[1]).isInstance(event)) {
                ((EventListener) entry[0]).onEvent(event);
            }
        }
    }
}
