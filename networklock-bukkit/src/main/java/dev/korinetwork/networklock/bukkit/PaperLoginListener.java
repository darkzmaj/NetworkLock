package dev.korinetwork.networklock.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Outcome;
import dev.korinetwork.networklock.core.PlayerIdentity;
import dev.korinetwork.networklock.core.ProfileScrubber;
import dev.korinetwork.networklock.core.SecurityLog;
import dev.korinetwork.networklock.core.MiniText;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;

/**
 * Verifies the proxy credential before the player exists on the server. Works for both
 * BungeeCord legacy and Velocity modern forwarding, because Paper places the forwarded
 * properties in the pre-login profile in both modes.
 */
final class PaperLoginListener implements Listener {

    private final BackendCore core;
    private final Logger logger;
    /** Denials decided at LOWEST, re-applied at MONITOR in case another plugin re-allowed the login. */
    private final Map<AsyncPlayerPreLoginEvent, Outcome> denied =
            Collections.synchronizedMap(new WeakHashMap<AsyncPlayerPreLoginEvent, Outcome>());

    PaperLoginListener(BackendCore core, Logger logger) {
        this.core = core;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void verify(AsyncPlayerPreLoginEvent e) {
        Outcome outcome;
        try {
            PlayerProfile profile = e.getPlayerProfile();
            List<String> tokens = new ArrayList<>(1);
            List<PlayerIdentity.Property> others = new ArrayList<>();
            for (ProfileProperty p : profile.getProperties()) {
                if (ForwardingToken.PROPERTY.equals(p.getName())) {
                    tokens.add(p.getValue());
                } else {
                    others.add(new PlayerIdentity.Property(p.getName(), p.getValue(), p.getSignature()));
                }
            }
            PlayerIdentity identity = new PlayerIdentity(e.getUniqueId(), e.getName(), Addresses.normalize(e.getAddress()), others);
            outcome = core.verify(identity, tokens);
            if (!tokens.isEmpty()) {
                // The credential must never reach the player's live profile (tab list, skins, other clients).
                profile.removeProperty(ForwardingToken.PROPERTY);
                e.setPlayerProfile(profile);
                if (core.debug()) {
                    boolean left = false;
                    for (ProfileProperty p : e.getPlayerProfile().getProperties()) left |= ForwardingToken.PROPERTY.equals(p.getName());
                    logger.info("Used credential " + (left ? "left in (read-only)" : "removed from") + " the profile of " + SecurityLog.clean(e.getName()));
                }
            }
        } catch (Throwable t) {
            logger.severe("Error verifying login of " + SecurityLog.clean(e.getName()) + " (denied): " + t);
            outcome = Outcome.INTERNAL_ERROR;
        }
        if (outcome != Outcome.OK) {
            denied.put(e, outcome);
            deny(e, outcome);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void enforce(AsyncPlayerPreLoginEvent e) {
        Outcome outcome = denied.remove(e);
        if (outcome != null && e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            logger.warning("Another plugin re-allowed a login NetworkLock denied (" + SecurityLog.clean(e.getName()) + "); denying again");
            deny(e, outcome);
        }
    }

    /** Self-check: the credential must have been stripped before the player joined. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void checkStripped(PlayerJoinEvent e) {
        try {
            for (ProfileProperty p : e.getPlayer().getPlayerProfile().getProperties()) {
                if (ForwardingToken.PROPERTY.equals(p.getName())) {
                    logger.severe("Connection credential still present on " + SecurityLog.clean(e.getPlayer().getName())
                            + "'s profile; please report this with your server version");
                    return;
                }
            }
            if (core.debug()) logger.info("Credential absent at join for " + SecurityLog.clean(e.getPlayer().getName()));
        } catch (LinkageError oldPaper) {
            // Player#getPlayerProfile is missing on some old Paper builds: read the server's own GameProfile instead
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
    }

    @SuppressWarnings("deprecation") // the String overload exists on every Paper version; Adventure only since 1.16.5
    private void deny(AsyncPlayerPreLoginEvent e, Outcome outcome) {
        try {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MiniText.anyToLegacy(core.kickText(outcome)));
        } catch (Throwable t) {
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER); // never let a message problem let the player in
        }
    }
}
