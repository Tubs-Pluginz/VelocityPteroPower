package de.tubyoub.velocitypteropower.api;

import com.velocitypowered.api.command.CommandSource;
import de.tubyoub.vpp.api.CommandActor;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class VelocityCommandActor implements CommandActor {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final CommandSource source;

    public VelocityCommandActor(CommandSource source) {
        this.source = source;
    }

    @Override
    public boolean hasPermission(String permission) {
        try { return source.hasPermission(permission); } catch (Throwable t) { return false; }
    }

    @Override
    public void sendMessage(String message) {
        try { source.sendMessage(MINI.deserialize(message)); } catch (Throwable ignored) {}
    }

    @Override
    public String getName() {
        try { return source.toString(); } catch (Throwable t) { return "unknown"; }
    }
}
