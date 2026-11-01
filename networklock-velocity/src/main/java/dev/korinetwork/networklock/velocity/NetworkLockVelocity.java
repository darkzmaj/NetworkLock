package dev.korinetwork.networklock.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
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
import net.kyori.adventure.text.Component;
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

    private final ProxyServer proxy;
    private final Logger logger;
    private final ProxyCore core;

    @Inject
    public NetworkLockVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.core = new ProxyCore(dataDir, new Log() {
            public void info(String m) { logger.info(m); }
            public void warn(String m) { logger.warn(m); }
            public void error(String m) { logger.error(m); }
        }, System::currentTimeMillis, System.getenv());
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        core.start();
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("networklock").aliases("nlock").plugin(this).build(), new Command());
        for (String line : doctor()) {
            if (!line.startsWith("OK")) logger.warn("doctor: {}", line);
        }
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

    /** {@code &}-codes (the default style) and MiniMessage tags both work. */
    private static Component text(String message) {
        return MiniMessage.miniMessage().deserialize(MiniText.ampersandToMini(message));
    }

    private List<String> doctor() {
        List<String> out = new ArrayList<>(core.doctorLines());
        try {
            Matcher m = FORWARDING_MODE.matcher(Files.readString(Path.of("velocity.toml")));
            String mode = m.find() ? m.group(1).toLowerCase() : "unknown";
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
            Commands.dispatch(inv.arguments(), sender(inv), new Commands.Target() {
                public List<String> status() {
                    List<String> l = new ArrayList<>(core.statusLines());
                    l.add("Platform: " + proxy.getVersion().getName() + " " + proxy.getVersion().getVersion());
                    return l;
                }
                public List<String> stats() { return core.statsLines(); }
                public List<String> proxies() { return core.proxiesLines(); }
                public List<String> doctor() { return NetworkLockVelocity.this.doctor(); }
                public List<String> reload() { return core.reload(); }
                public String version() {
                    return proxy.getPluginManager().fromInstance(NetworkLockVelocity.this)
                            .flatMap(c -> c.getDescription().getVersion()).orElse("unknown");
                }
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

                public void message(String key) {
                    inv.source().sendMessage(text(core.messages().prefixed(key)));
                }

                public void lines(List<String> lines) {
                    Component prefix = text(core.messages().get("prefix"));
                    for (String l : lines) inv.source().sendMessage(prefix.append(Component.text(l)));
                }
            };
        }
    }
}
