package dev.korinetwork.networklock.bungee;

import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.Commands;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.JulLog;
import dev.korinetwork.networklock.core.MiniText;
import dev.korinetwork.networklock.core.PlayerIdentity;
import dev.korinetwork.networklock.core.SecurityLog;
import dev.korinetwork.networklock.core.ProxyCore;
import dev.korinetwork.networklock.core.UpdateChecker;
import dev.korinetwork.networklock.core.UsageStats;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Settings;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** BungeeCord / Waterfall / FlameCord entry point, declared in {@code bungee.yml}. */
public final class NetworkLockBungee extends Plugin implements Listener {

    private ProxyCore core;
    private BungeeProfiles profiles;
    private UpdateChecker updates;
    private UsageStats stats;

    @Override
    public void onEnable() {
        JulLog log = new JulLog(getLogger());
        core = new ProxyCore(getDataFolder().toPath(), log, System::currentTimeMillis, System.getenv());
        core.start();
        updates = new UpdateChecker(getDescription().getVersion(), log, core::updatesEnabled);
        updates.start();
        stats = new UsageStats(getDescription().getVersion(), getProxy().getName().toLowerCase(java.util.Locale.ROOT),
                "proxy", core::usageId, core::metricsEnabled);
        stats.start();
        try {
            profiles = new BungeeProfiles();
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("This proxy build is not supported (" + e + "). Connections cannot be signed,"
                    + " so backends will reject every player.");
        }
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this,
                new NetworkLockCommand(Settings.CommandNames.read(getDataFolder().toPath().resolve(ConfigFiles.CONFIG))));
        for (String line : doctor()) {
            if (!line.startsWith("OK")) getLogger().warning("doctor: " + line);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(PreLoginEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getConnection().getSocketAddress() instanceof InetSocketAddress address)) return;
        String key = switch (core.admit(address.getAddress())) {
            case ALLOWED -> null;
            case LIMITED -> "connection-limited";
            case BLOCKED -> "temporarily-blocked";
            case LOCKDOWN -> "security-error";
        };
        if (key != null) {
            e.setReason(render(key));
            e.setCancelled(true);
        }
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (stats != null) stats.stop();
    }

    /** Tells staff about a newer version when they join. */
    @EventHandler
    public void onPostLogin(PostLoginEvent e) {
        ProxiedPlayer p = e.getPlayer();
        if (!core.notifyEnabled()) return;
        if (!p.hasPermission(Commands.NOTIFY_PERMISSION) && !p.hasPermission(Commands.ADMIN_PERMISSION)) return;
        String notice = Commands.updateNotice(core.messages().get("update-available"), updates);
        if (notice != null) p.sendMessage(renderMini(notice));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(LoginEvent e) {
        if (core.inLockdown() || profiles == null) {
            e.setReason(render("security-error"));
            e.setCancelled(true);
        }
    }

    /** HIGHEST runs last on BungeeCord, so the signed profile is the one that is actually sent. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerConnect(ServerConnectEvent e) {
        if (e.isCancelled() || profiles == null) return;
        ProxiedPlayer player = e.getPlayer();
        // BungeeCord drops a connect to the current server only after this event: sign nothing that will not be used
        if (player.getServer() != null && player.getServer().getInfo().equals(e.getTarget())) return;
        try {
            String token;
            synchronized (player.getPendingConnection()) {
                List<PlayerIdentity.Property> others = withoutToken(profiles.read(player.getPendingConnection()));
                PlayerIdentity identity = new PlayerIdentity(player.getUniqueId(), player.getName(),
                        Addresses.normalize(player.getSocketAddress()), others);
                token = core.mint(identity, e.getTarget().getName());
                if (token == null) return;
                List<PlayerIdentity.Property> signed = new ArrayList<>(others);
                signed.add(new PlayerIdentity.Property(ForwardingToken.PROPERTY, token, ""));
                profiles.write(player.getPendingConnection(), player.getUniqueId(), player.getName(), signed);
            }
            // Safety net for connects that never happen (already connecting, cancelled later, server down): BungeeCord
            // reads the profile as soon as the TCP connection opens, so after the connect timeout the credential is
            // either sent or never will be. Only this exact credential is removed.
            getProxy().getScheduler().schedule(this, () -> stripToken(player, token), connectTimeout(e) + 2000L, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().severe("Could not sign the connection of " + SecurityLog.clean(player.getName()) + " (backend will refuse it): " + ex);
        }
    }

    private static long connectTimeout(ServerConnectEvent e) {
        try {
            return Math.max(1000, e.getRequest().getConnectTimeout());
        } catch (LinkageError | RuntimeException old) {
            return 5000; // BungeeCord's default on builds without ServerConnectRequest
        }
    }

    /** Drop the single-use credential once the backend accepted it, so nothing else relays it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerConnected(ServerConnectedEvent e) {
        stripToken(e.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(ServerKickEvent e) {
        stripToken(e.getPlayer(), null);
        String reason = e.getReason() == null ? null : e.getReason().toPlainText();
        String replacement = core.translateBackendKick(reason);
        if (replacement != null) e.setReason(renderMini(replacement));
    }

    /** Removes the credential; with {@code only} set, only if it is still exactly that one. */
    private void stripToken(ProxiedPlayer player, String only) {
        if (profiles == null) return;
        try {
            synchronized (player.getPendingConnection()) {
                List<PlayerIdentity.Property> props = profiles.read(player.getPendingConnection());
                boolean present = false;
                for (PlayerIdentity.Property p : props) {
                    present |= ForwardingToken.PROPERTY.equals(p.name()) && (only == null || only.equals(p.value()));
                }
                if (present) {
                    profiles.write(player.getPendingConnection(), player.getUniqueId(), player.getName(), withoutToken(props));
                    if (only != null && core.debug()) {
                        getLogger().info("Removed an unused signature from " + SecurityLog.clean(player.getName()) + " (that connection never happened)");
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the next connection overwrites the stale credential anyway; a replay of it is rejected
        }
    }

    private static List<PlayerIdentity.Property> withoutToken(List<PlayerIdentity.Property> props) {
        List<PlayerIdentity.Property> out = new ArrayList<>(props.size());
        for (PlayerIdentity.Property p : props) {
            if (!ForwardingToken.PROPERTY.equals(p.name())) out.add(p);
        }
        return out;
    }

    private BaseComponent render(String key) {
        return renderMini(core.messages().get(key));
    }

    private static BaseComponent renderMini(String mini) {
        return TextComponent.fromLegacy(MiniText.anyToLegacy(mini));
    }

    @SuppressWarnings("deprecation") // isIpForward has no replacement in the API
    private List<String> doctor() {
        List<String> out = new ArrayList<>(core.doctorLines());
        if (profiles == null) out.add("FAIL proxy internals not recognised; connections are not being signed");
        if (getProxy().getConfig().isIpForward()) {
            out.add("OK   ip_forward is enabled");
        } else {
            out.add("FAIL ip_forward is false in BungeeCord's config.yml; backends cannot verify players");
        }
        return out;
    }

    private final class NetworkLockCommand extends Command implements TabExecutor {
        NetworkLockCommand(Settings.CommandNames names) {
            super(names.name(), null, names.aliases().toArray(new String[0]));
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            Commands.dispatch(getName(), args, sender(sender), new Commands.Target() {
                public List<String> status() {
                    List<String> l = new ArrayList<>(core.statusLines());
                    l.add("Platform: " + getProxy().getName() + " " + getProxy().getVersion());
                    return l;
                }
                public List<String> stats() { return core.statsLines(); }
                public List<String> proxies() { return core.proxiesLines(); }
                public List<String> doctor() { return NetworkLockBungee.this.doctor(); }
                public List<String> reload() { return core.reload(); }
                public UpdateChecker updates() { return updates; }
                public boolean notifyEnabled() { return core.notifyEnabled(); }
                public int servers() { return getProxy().getServers().size(); }
            });
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            return Commands.complete(args, sender(sender));
        }

        private Commands.Sender sender(CommandSender s) {
            return new Commands.Sender() {
                public boolean hasPermission(String node) {
                    return s.hasPermission(node);
                }

                public String text(String key) {
                    return core.messages().get(key);
                }

                public void send(String formatted) {
                    s.sendMessage(renderMini(formatted));
                }

                public void lines(List<String> lines) {
                    String prefix = MiniText.anyToLegacy(core.messages().get("prefix"));
                    for (String l : lines) s.sendMessage(TextComponent.fromLegacy(prefix + l));
                }
            };
        }
    }
}
