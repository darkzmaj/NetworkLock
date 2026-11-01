package dev.korinetwork.networklock.sponge;

import com.google.inject.Inject;
import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.Commands;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Log;
import dev.korinetwork.networklock.core.Outcome;
import dev.korinetwork.networklock.core.PlayerIdentity;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.ArgumentReader;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.IsCancelled;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.profile.property.ProfileProperty;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sponge entry point, declared in {@code META-INF/sponge_plugins.json}. Players are checked in
 * {@link ServerSideConnectionEvent.Auth}, before the player exists on the server. Sponge game
 * profiles are read-only, so the (already used, single-use) credential stays in the profile.
 */
@Plugin("networklock")
public final class NetworkLockSponge {

    private static final Pattern FORWARDING_MODULE = Pattern.compile("(?m)^\\s*ip-forwarding\\s*=\\s*(\\w+)");
    private static final Pattern FORWARDING_MODE = Pattern.compile("(?s)ip-forwarding\\s*\\{.*?\\bmode\\s*=\\s*\"?(\\w+)");

    private final PluginContainer container;
    private final Logger logger;
    private final BackendCore core;
    /** Denials decided first, re-applied last in case another plugin un-cancelled the login. */
    private final Map<ServerSideConnectionEvent.Auth, Outcome> denied = Collections.synchronizedMap(new WeakHashMap<>());

    @Inject
    public NetworkLockSponge(PluginContainer container, Logger logger, @ConfigDir(sharedRoot = false) Path dataDir) {
        this.container = container;
        this.logger = logger;
        this.core = new BackendCore(dataDir, new Log() {
            public void info(String m) { logger.info(m); }
            public void warn(String m) { logger.warn(m); }
            public void error(String m) { logger.error(m); }
        }, System::currentTimeMillis, System.getenv());
    }

    @Listener
    public void onConstruct(ConstructPluginEvent event) {
        try {
            core.start();
            for (String line : doctor()) {
                if (!line.startsWith("OK")) logger.warn("doctor: {}", line);
            }
        } catch (RuntimeException e) {
            core.blockPlatform("startup error: " + e.getClass().getSimpleName());
        }
    }

    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Raw> event) {
        event.register(container, new NetworkLockCommand(), "networklock", "nlock");
    }

    @Listener(order = Order.FIRST)
    public void verify(ServerSideConnectionEvent.Auth event) {
        Outcome outcome;
        try {
            List<String> tokens = new ArrayList<>(1);
            List<PlayerIdentity.Property> others = new ArrayList<>();
            for (ProfileProperty p : event.profile().properties()) {
                if (ForwardingToken.PROPERTY.equals(p.name())) {
                    tokens.add(p.value());
                } else {
                    others.add(new PlayerIdentity.Property(p.name(), p.value(), p.signature().orElse("")));
                }
            }
            PlayerIdentity identity = new PlayerIdentity(event.profile().uuid(), event.profile().name().orElse(""),
                    Addresses.normalize(event.connection().address()), others);
            outcome = core.verify(identity, tokens);
        } catch (Throwable t) {
            logger.error("Error verifying a login (denied): {}", t.toString());
            outcome = Outcome.INTERNAL_ERROR;
        }
        if (outcome != Outcome.OK) {
            denied.put(event, outcome);
            deny(event, outcome);
        }
    }

    @Listener(order = Order.LAST)
    @IsCancelled(Tristate.UNDEFINED)
    public void enforce(ServerSideConnectionEvent.Auth event) {
        Outcome outcome = denied.remove(event);
        if (outcome != null && !event.isCancelled()) {
            logger.warn("Another plugin re-allowed a login NetworkLock denied; denying again");
            deny(event, outcome);
        }
    }

    private static void deny(ServerSideConnectionEvent.Auth event, Outcome outcome) {
        event.setMessage(Component.text(outcome.code().backendMessage()));
        event.setCancelled(true);
    }

    private List<String> doctor() {
        List<String> out = new ArrayList<>(core.doctorLines());
        try {
            String conf = Files.readString(Path.of("config", "sponge", "sponge.conf"));
            Matcher module = FORWARDING_MODULE.matcher(conf);
            Matcher mode = FORWARDING_MODE.matcher(conf);
            boolean enabled = module.find() && module.group(1).equalsIgnoreCase("true");
            String m = mode.find() ? mode.group(1).toUpperCase() : "NONE";
            if (!enabled || m.equals("NONE")) {
                out.add("FAIL proxy forwarding is off (sponge.conf: modules.ip-forwarding=true and ip-forwarding.mode"
                        + " LEGACY or MODERN); players cannot be verified and every login is denied");
            } else {
                out.add("OK   proxy forwarding: " + m);
            }
        } catch (IOException e) {
            out.add("WARN config/sponge/sponge.conf not readable; forwarding mode not checked");
        }
        return out;
    }

    private final class NetworkLockCommand implements Command.Raw {

        @Override
        public CommandResult process(CommandCause cause, ArgumentReader.Mutable arguments) {
            Commands.dispatch(args(arguments), sender(cause), new Commands.Target() {
                public List<String> status() {
                    List<String> l = new ArrayList<>(core.statusLines());
                    l.add("Platform: Sponge");
                    return l;
                }
                public List<String> stats() { return core.statsLines(); }
                public List<String> proxies() { return core.proxiesLines(); }
                public List<String> doctor() { return NetworkLockSponge.this.doctor(); }
                public List<String> reload() { return core.reload(); }
                public String version() { return container.metadata().version().toString(); }
            });
            return CommandResult.success();
        }

        @Override
        public List<CommandCompletion> complete(CommandCause cause, ArgumentReader.Mutable arguments) {
            return Commands.complete(args(arguments), sender(cause)).stream().map(CommandCompletion::of).toList();
        }

        @Override
        public boolean canExecute(CommandCause cause) {
            return true; // each subcommand checks its own permission
        }

        @Override
        public Optional<Component> shortDescription(CommandCause cause) {
            return Optional.of(Component.text("NetworkLock administration"));
        }

        @Override
        public Optional<Component> extendedDescription(CommandCause cause) {
            return Optional.empty();
        }

        @Override
        public Component usage(CommandCause cause) {
            return Component.text("/networklock [help|status|reload|doctor|stats|proxies|version]");
        }

        private String[] args(ArgumentReader.Mutable arguments) {
            String rest = arguments.remaining().trim();
            return rest.isEmpty() ? new String[0] : rest.split("\\s+");
        }

        private Commands.Sender sender(CommandCause cause) {
            return new Commands.Sender() {
                public boolean hasPermission(String node) {
                    return cause.hasPermission(node);
                }

                public void message(String key) {
                    cause.audience().sendMessage(Component.text("[NetworkLock] " + Commands.builtIn(key)));
                }

                public void lines(List<String> lines) {
                    for (String l : lines) cause.audience().sendMessage(Component.text("[NetworkLock] " + l));
                }
            };
        }
    }
}
