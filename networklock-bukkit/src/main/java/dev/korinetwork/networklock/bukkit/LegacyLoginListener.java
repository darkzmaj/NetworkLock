package dev.korinetwork.networklock.bukkit;

import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.MiniText;
import dev.korinetwork.networklock.core.Outcome;
import dev.korinetwork.networklock.core.PlayerIdentity;
import dev.korinetwork.networklock.core.ProfileScrubber;
import dev.korinetwork.networklock.core.SecurityLog;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;

/**
 * Login check for servers without the Paper profile API: Spigot, CraftBukkit and every Paper fork
 * before 1.12.2, back to 1.8. It reads the forwarded profile from the server's own Mojang
 * {@code GameProfile} in {@code PlayerLoginEvent}, which fires before the player enters the world.
 * The authlib classes are reached by reflection because their method names changed over the
 * years ({@code getName()} before authlib 6, {@code name()} after).
 */
@SuppressWarnings("deprecation") // PlayerLoginEvent: deprecated on new Paper, but the only pre-join hook on 1.8-1.12 and Spigot
final class LegacyLoginListener implements Listener {

    private final BackendCore core;
    private final Logger logger;
    /** Denials decided at LOWEST, re-applied at MONITOR in case another plugin re-allowed the login. */
    private final Map<org.bukkit.event.player.PlayerLoginEvent, Outcome> denied = Collections.synchronizedMap(new WeakHashMap<org.bukkit.event.player.PlayerLoginEvent, Outcome>());

    LegacyLoginListener(BackendCore core, Logger logger) {
        this.core = core;
        this.logger = logger;
    }

    /** Null when this server exposes the Mojang profile classes NetworkLock needs, otherwise why not. */
    static String missingApi() {
        try {
            Class.forName("com.mojang.authlib.GameProfile");
            return null;
        } catch (ClassNotFoundException | LinkageError e) {
            return "this server has no Mojang GameProfile class, so forwarded players cannot be checked";
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void verify(org.bukkit.event.player.PlayerLoginEvent e) {
        Outcome outcome;
        try {
            Player player = e.getPlayer();
            Object profile = player.getClass().getMethod("getProfile").invoke(player);
            Object propertyMap = call(profile, "getProperties", "properties"); // authlib 7 (1.21.9+) made it a record
            Collection<?> properties = (Collection<?>) propertyMap.getClass().getMethod("values").invoke(propertyMap);
            List<String> tokens = new ArrayList<>(1);
            List<PlayerIdentity.Property> others = new ArrayList<>();
            for (Object p : properties) {
                String name = read(p, "getName", "name");
                String value = read(p, "getValue", "value");
                if (ForwardingToken.PROPERTY.equals(name)) {
                    tokens.add(value);
                } else {
                    others.add(new PlayerIdentity.Property(name, value, read(p, "getSignature", "signature")));
                }
            }
            PlayerIdentity identity = new PlayerIdentity(player.getUniqueId(), player.getName(),
                    Addresses.normalize(e.getAddress()), others);
            outcome = core.verify(identity, tokens);
            if (!tokens.isEmpty()) {
                boolean removed = strip(player, profile);
                if (core.debug()) logger.info("Used credential " + (removed ? "removed from" : "left in (read-only)")
                        + " the profile of " + SecurityLog.clean(player.getName()));
            }
        } catch (Throwable t) {
            logger.severe("Error verifying login of " + SecurityLog.clean(e.getPlayer().getName()) + " (denied): " + t);
            outcome = Outcome.INTERNAL_ERROR;
        }
        if (outcome != Outcome.OK) {
            denied.put(e, outcome);
            deny(e, outcome);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void enforce(org.bukkit.event.player.PlayerLoginEvent e) {
        Outcome outcome = denied.remove(e);
        if (outcome != null && e.getResult() == org.bukkit.event.player.PlayerLoginEvent.Result.ALLOWED) {
            logger.warning("Another plugin re-allowed a login NetworkLock denied (" + SecurityLog.clean(e.getPlayer().getName()) + "); denying again");
            deny(e, outcome);
        }
    }

    /** Debug self-check: the joined player's live profile must not carry the credential. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void checkStripped(org.bukkit.event.player.PlayerJoinEvent e) {
        if (!core.debug()) return;
        try {
            boolean present = ProfileScrubber.hasCredential(e.getPlayer().getClass().getMethod("getProfile").invoke(e.getPlayer()));
            String name = SecurityLog.clean(e.getPlayer().getName());
            if (present) logger.severe("Connection credential still present on " + name + "'s profile; please report this with your server version");
            else logger.info("Credential absent at join for " + name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // diagnostic only
        }
    }

    /**
     * Removes the used credential so it is not sent to other players' tab lists. On authlib 7
     * (Spigot 1.21.9+) the profile is read-only, so a copy without it replaces the original on the
     * player entity. Returns whether the live profile is clean afterwards (checked by reading it back).
     */
    private static boolean strip(Player player, Object profile) {
        try {
            Object clean = ProfileScrubber.withoutCredential(profile);
            if (clean == null) return false;
            if (clean != profile) {
                ProfileScrubber.replaceReferences(player.getClass().getMethod("getHandle").invoke(player), profile, clean);
                ProfileScrubber.replaceReferences(player, profile, clean); // in case CraftPlayer keeps its own reference
            }
            return !ProfileScrubber.hasCredential(player.getClass().getMethod("getProfile").invoke(player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false; // the credential is single-use, so leaving it is harmless
        }
    }

    private static String read(Object property, String oldName, String newName) throws ReflectiveOperationException {
        Object v = call(property, oldName, newName);
        return v == null ? null : v.toString();
    }

    /** Calls the old-style getter if present, otherwise the record-style accessor of newer authlib. */
    private static Object call(Object target, String oldName, String newName) throws ReflectiveOperationException {
        Method m;
        try {
            m = target.getClass().getMethod(oldName);
        } catch (NoSuchMethodException e) {
            m = target.getClass().getMethod(newName);
        }
        return m.invoke(target);
    }

    @SuppressWarnings("deprecation") // the String overload is the one every version has
    private void deny(org.bukkit.event.player.PlayerLoginEvent e, Outcome outcome) {
        try {
            e.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER, MiniText.anyToLegacy(core.kickText(outcome)));
        } catch (Throwable t) {
            e.setResult(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER); // never let a message problem let the player in
        }
    }
}
