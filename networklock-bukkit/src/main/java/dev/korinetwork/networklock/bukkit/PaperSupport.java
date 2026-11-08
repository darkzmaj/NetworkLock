package dev.korinetwork.networklock.bukkit;

import org.bukkit.Server;

/** Capability probes, kept free of Paper types so they are safe to run on plain Spigot. */
final class PaperSupport {

    private PaperSupport() {}

    /** Null when the Paper profile API NetworkLock relies on is present, otherwise why not. */
    static String missingApi() {
        try {
            Class<?> profile = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            Class<?> event = Class.forName("org.bukkit.event.player.AsyncPlayerPreLoginEvent");
            event.getMethod("getPlayerProfile");
            event.getMethod("setPlayerProfile", profile);
            profile.getMethod("removeProperty", String.class);
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return "this server lacks the Paper profile API; use Paper, Folia, Purpur or Pufferfish (plain Spigot and CraftBukkit are not supported)";
        }
    }

    /** BungeeCord/Velocity forwarding is a Spigot feature; plain CraftBukkit does not have it. */
    static boolean hasSpigot() {
        try {
            Class.forName("org.spigotmc.SpigotConfig");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static boolean isStopping(Server server) {
        try {
            return (boolean) Server.class.getMethod("isStopping").invoke(server);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
