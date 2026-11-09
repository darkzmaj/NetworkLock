package dev.korinetwork.networklock.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.Commands;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Log;
import dev.korinetwork.networklock.core.MiniText;
import dev.korinetwork.networklock.core.PlayerIdentity;
import dev.korinetwork.networklock.core.ProxyCore;
import dev.korinetwork.networklock.core.UpdateChecker;
import dev.korinetwork.networklock.core.UsageStats;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Velocity entry point, declared in {@code velocity-plugin.json}. */
public final class NetworkLockVelocity {

    private static final Pattern FORWARDING_MODE = Pattern.compile("(?m)^\\s*player-info-forwarding-mode\\s*=\\s*\"(\\w+)\"");

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final Log log;
    private final ProxyCore core;
    private UpdateChecker updates;
    private UsageStats stats;
    private String label = Settings.CommandNames.DEFAULT.name();

    @Inject
    public NetworkLockVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        this.log = new Log() {
            public void info(String m) { logger.info(m); }
            public void warn(String m) { logger.warn(m); }
            public void error(String m) { logger.error(m); }
        };
        this.core = new ProxyCore(dataDir, log, System::currentTimeMillis, System.getenv());
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        core.start();
        String version = proxy.getPluginManager().fromInstance(this)
                .flatMap(c -> c.getDescription().getVersion()).orElse("unknown");
        updates = new UpdateChecker(version, log, core::updatesEnabled);
        updates.start();
        stats = new UsageStats(version, "velocity", "proxy", core::usageId, core::metricsEnabled);
        stats.start();
        Settings.CommandNames names = Settings.CommandNames.read(dataDir.resolve(ConfigFiles.CONFIG));
        label = names.name();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(names.name())
                .aliases(names.aliases().toArray(new String[0])).plugin(this).build(), new Command());
        for (String line : doctor()) {
            if (!line.startsWith("OK")) logger.warn("doctor: {}", line);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (updates != null) updates.stop();
        if (stats != null) stats.stop();
    }

    /** Tells staff about a newer version when they join. */
    @Subscribe
    public void onPostLogin(PostLoginEvent e) {
        Player p = e.getPlayer();
        if (updates == null || !core.notifyEnabled()) return;
        if (!p.hasPermission(Commands.NOTIFY_PERMISSION) && !p.hasPermission(Commands.ADMIN_PERMISSION)) return;
        String notice = Commands.updateNotice(core.messages().get("update-available"), updates);
        if (notice != null) p.sendMessage(text(notice));
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onPreLogin(PreLoginEvent e) {
        String key = switch (core.admit(e.getConnection().getRemoteAddress().getAddress())) {
            case ALLOWED -> null;
            case LIMITED -> "connection-limited";
            case BLOCKED -> "temporarily-blocked";
            case LOCKDOWN -> "security-error";
        };
        if (key != null) e.setResult(PreLoginEvent.PreLoginComponentResult.denied(render(key)));
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onLogin(LoginEvent e) {
        if (core.inLockdown()) e.setResult(LoginEvent.ComponentResult.denied(render("security-error")));
    }

    /** Runs last so it signs exactly the profile other plugins (skins etc.) have settled on. */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerPreConnect(ServerPreConnectEvent e) {
        RegisteredServer target = e.getResult().getServer().orElse(null);
        if (target == null) return;
        Player player = e.getPlayer();
        // a plugin may redirect to the server the player is already on; Velocity then connects nowhere
        if (player.getCurrentServer().map(c -> c.getServer().equals(target)).orElse(false)) return;
        List<GameProfile.Property> others = withoutToken(player.getGameProfileProperties());
        List<PlayerIdentity.Property> props = new ArrayList<>(others.size());
        for (GameProfile.Property p : others) props.add(new PlayerIdentity.Property(p.getName(), p.getValue(), p.getSignature()));
        PlayerIdentity identity = new PlayerIdentity(player.getUniqueId(), player.getUsername(),
                Addresses.normalize(player.getRemoteAddress()), props);
        String token = core.mint(identity, target.getServerInfo().getName());
        if (token == null) return; // lockdown: the backend will refuse the unsigned connection
        List<GameProfile.Property> signed = new ArrayList<>(others);
        signed.add(new GameProfile.Property(ForwardingToken.PROPERTY, token, ""));
        player.setGameProfileProperties(signed);
    }

    /** Removes the single-use credential again so proxy plugins never see or relay it. */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerConnected(ServerConnectedEvent e) {
        Player player = e.getPlayer();
        List<GameProfile.Property> props = player.getGameProfileProperties();
        if (props.stream().anyMatch(p -> ForwardingToken.PROPERTY.equals(p.getName()))) {
            player.setGameProfileProperties(withoutToken(props));
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onKicked(KickedFromServerEvent e) {
        Player player = e.getPlayer();
        player.setGameProfileProperties(withoutToken(player.getGameProfileProperties()));
        String reason = e.getServerKickReason().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).orElse(null);
        String replacement = core.translateBackendKick(reason);
        if (replacement == null) return;
        Component message = text(replacement);
        KickedFromServerEvent.ServerKickResult result = e.getResult();
        if (result instanceof KickedFromServerEvent.DisconnectPlayer) {
            e.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
        } else if (result instanceof KickedFromServerEvent.RedirectPlayer redirect) {
            e.setResult(KickedFromServerEvent.RedirectPlayer.create(redirect.getServer(), message));
        } else if (result instanceof KickedFromServerEvent.Notify) {
            e.setResult(KickedFromServerEvent.Notify.create(message));
        }
    }

    private static List<GameProfile.Property> withoutToken(List<GameProfile.Property> props) {
        List<GameProfile.Property> out = new ArrayList<>(props.size());
        for (GameProfile.Property p : props) {
            if (!ForwardingToken.PROPERTY.equals(p.getName())) out.add(p);
        }
        return out;
    }

    private Component render(String key) {
        return text(core.messages().get(key));
    }

    /** {@code &}-codes (the default style) and MiniMessage tags both work; plain links become clickable. */
    private static Component text(String message) {
        return MiniMessage.miniMessage().deserialize(MiniText.ampersandToMini(message))
                .replaceText(TextReplacementConfig.builder().match(URL)
                        .replacement(b -> b.clickEvent(ClickEvent.openUrl(b.content()))).build());
    }

    private List<String> doctor() {
        List<String> out = new ArrayList<>(core.doctorLines());
        try {
            Matcher m = FORWARDING_MODE.matcher(Files.readString(Path.of("velocity.toml")));
            String mode = m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : "unknown";
            switch (mode) {
                case "modern" -> out.add("OK   forwarding mode: modern (Velocity's own HMAC forwarding stays active)");
                case "legacy", "bungeeguard" -> out.add("OK   forwarding mode: " + mode
                        + " (NetworkLock signs each connection; use modern for 1.13+ backends where possible)");
                case "none" -> out.add("FAIL player-info-forwarding-mode is none; backends cannot verify players");
                default -> out.add("WARN could not determine player-info-forwarding-mode from velocity.toml");
            }
        } catch (IOException e) {
            out.add("WARN velocity.toml not readable; forwarding mode not checked");
        }
        return out;
    }

    private final class Command implements SimpleCommand {
        @Override
        public void execute(Invocation inv) {
            Commands.dispatch(label, inv.arguments(), sender(inv), new Commands.Target() {
                public List<String> status() {
                    List<String> l = new ArrayList<>(core.statusLines());
                    l.add("Platform: " + proxy.getVersion().getName() + " " + proxy.getVersion().getVersion());
                    return l;
                }
                public List<String> stats() { return core.statsLines(); }
                public List<String> proxies() { return core.proxiesLines(); }
                public List<String> doctor() { return NetworkLockVelocity.this.doctor(); }
                public List<String> reload() { return core.reload(); }
                public UpdateChecker updates() { return updates; }
                public boolean notifyEnabled() { return core.notifyEnabled(); }
                public int servers() { return proxy.getAllServers().size(); }
            });
        }

        @Override
        public List<String> suggest(Invocation inv) {
            return Commands.complete(inv.arguments(), sender(inv));
        }

        private Commands.Sender sender(Invocation inv) {
            return new Commands.Sender() {
                public boolean hasPermission(String node) {
                    return inv.source().hasPermission(node);
                }

                public String text(String key) {
                    return core.messages().get(key);
                }

                public void send(String formatted) {
                    inv.source().sendMessage(NetworkLockVelocity.text(formatted));
                }

                public void lines(List<String> lines) {
                    Component prefix = NetworkLockVelocity.text(core.messages().get("prefix"));
                    for (String l : lines) inv.source().sendMessage(prefix.append(Component.text(l)));
                }
            };
        }
    }
}
