package dev.korinetwork.networklock.sponge;

import com.google.inject.Inject;
import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.Commands;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Log;
import dev.korinetwork.networklock.core.MiniText;
import dev.korinetwork.networklock.core.UpdateChecker;
import dev.korinetwork.networklock.core.UsageStats;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Settings;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import dev.korinetwork.networklock.core.Outcome;
import dev.korinetwork.networklock.core.PlayerIdentity;
import dev.korinetwork.networklock.core.ProfileScrubber;
import dev.korinetwork.networklock.core.SecurityLog;
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
 * {@link ServerSideConnectionEvent.Auth}, before the player exists on the server, and the used
 * credential is removed from the profile the server will build the player from.
 */
@Plugin("networklock")
public final class NetworkLockSponge {

    private static final Pattern FORWARDING_MODULE = Pattern.compile("(?m)^\\s*ip-forwarding\\s*=\\s*(\\w+)");
    private static final Pattern FORWARDING_MODE = Pattern.compile("(?s)ip-forwarding\\s*\\{.*?\\bmode\\s*=\\s*\"?(\\w+)");

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final PluginContainer container;
    private final Logger logger;
    private final BackendCore core;
    /** Denials decided first, re-applied last in case another plugin un-cancelled the login. */
    private final Map<ServerSideConnectionEvent.Auth, Outcome> denied = Collections.synchronizedMap(new WeakHashMap<>());

    private final Path dataDir;
    private final UpdateChecker updates;
    private final UsageStats stats;

    @Inject
    public NetworkLockSponge(PluginContainer container, Logger logger, @ConfigDir(sharedRoot = false) Path dataDir) {
        this.container = container;
        this.logger = logger;
        this.dataDir = dataDir;
        Log log = new Log() {
            public void info(String m) { logger.info(m); }
            public void warn(String m) { logger.warn(m); }
            public void error(String m) { logger.error(m); }
        };
        this.core = new BackendCore(dataDir, log, System::currentTimeMillis, System.getenv());
        this.updates = new UpdateChecker(container.metadata().version().toString(), log, core::updatesEnabled);
        this.stats = new UsageStats(container.metadata().version().toString(), "sponge", "server", core::usageId, core::metricsEnabled);
    }

    @Listener
    public void onConstruct(ConstructPluginEvent event) {
        try {
            core.start();
            updates.start();
            stats.start();
            for (String line : doctor()) {
                if (!line.startsWith("OK")) logger.warn("doctor: {}", line);
            }
        } catch (RuntimeException e) {
            core.blockPlatform("startup error: " + e.getClass().getSimpleName());
        }
    }

    @Listener
    public void onStopping(StoppingEngineEvent<?> event) {
        updates.stop();
        stats.stop();
    }

    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Raw> event) {
        Settings.CommandNames names = Settings.CommandNames.read(dataDir.resolve(ConfigFiles.CONFIG));
        event.register(container, new NetworkLockCommand(names.name()), names.name(), names.aliases().toArray(new String[0]));
    }

    @Listener(order = Order.FIRST)
    @IsCancelled(Tristate.UNDEFINED) // also check logins an earlier listener cancelled, in case a later one un-cancels them
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
            if (!tokens.isEmpty()) {
                boolean removed = scrub(event);
                if (core.debug()) logger.info("Used credential {} the profile of {}", removed ? "removed from" : "left in (read-only)",
                        SecurityLog.clean(identity.name()));
            }
        } catch (Throwable t) {
            logger.error("Error verifying a login (denied): {}", t.toString());
            outcome = Outcome.INTERNAL_ERROR;
        }
        if (outcome != Outcome.OK) {
            denied.put(event, outcome);
            deny(event, outcome);
        }
    }

    /** Debug self-check: the joined player's live profile must not carry the credential. */
    @Listener(order = Order.LAST)
    public void checkStripped(ServerSideConnectionEvent.Join event) {
        if (!core.debug()) return;
        String name = SecurityLog.clean(event.player().name());
        if (event.player().profile().properties().stream().anyMatch(p -> ForwardingToken.PROPERTY.equals(p.name()))) {
            logger.error("Connection credential still present on {}'s profile; please report this with your server version", name);
        } else {
            logger.info("Credential absent at join for {}", name);
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

    /**
     * Sponge profiles are read-only through its API. The login listener keeps the Mojang profile it
     * will build the player from; a copy without the credential replaces it there and on Sponge's
     * connection object. Returns whether the listener's profile is clean afterwards.
     */
    private static boolean scrub(ServerSideConnectionEvent.Auth event) {
        try {
            Object connection = event.connection();
            Object network = ProfileScrubber.findField(connection, "network.Connection");
            Object listener = network == null ? null : ProfileScrubber.findField(network, "LoginPacketListenerImpl");
            Object profile = listener == null ? null : ProfileScrubber.findField(listener, "authlib.GameProfile");
            Object clean = profile == null ? null : ProfileScrubber.withoutCredential(profile);
            if (clean == null) return false;
            if (clean != profile) {
                ProfileScrubber.replaceReferences(listener, profile, clean);
                ProfileScrubber.replaceReferences(connection, profile, clean);
            }
            return !ProfileScrubber.hasCredential(ProfileScrubber.findField(listener, "authlib.GameProfile"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false; // the credential is single-use, so leaving it is harmless
        }
    }

    private void deny(ServerSideConnectionEvent.Auth event, Outcome outcome) {
        event.setMessage(LEGACY.deserialize(MiniText.anyToLegacy(core.kickText(outcome))));
        event.setCancelled(true);
    }

    private List<String> doctor() {
        List<String> out = new ArrayList<>(core.doctorLines());
        try {
            String conf = Files.readString(Path.of("config", "sponge", "sponge.conf"));
            Matcher module = FORWARDING_MODULE.matcher(conf);
            Matcher mode = FORWARDING_MODE.matcher(conf);
            boolean enabled = module.find() && module.group(1).equalsIgnoreCase("true");
            String m = mode.find() ? mode.group(1).toUpperCase(java.util.Locale.ROOT) : "NONE";
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

        private final String label;

        NetworkLockCommand(String label) {
            this.label = label;
        }

        @Override
        public CommandResult process(CommandCause cause, ArgumentReader.Mutable arguments) {
            Commands.dispatch(label, args(arguments), sender(cause), new Commands.Target() {
                public List<String> status() {
                    List<String> l = new ArrayList<>(core.statusLines());
                    l.add("Platform: Sponge");
                    return l;
                }
                public List<String> stats() { return core.statsLines(); }
                public List<String> proxies() { return core.proxiesLines(); }
                public List<String> doctor() { return NetworkLockSponge.this.doctor(); }
                public List<String> reload() { return core.reload(); }
                public UpdateChecker updates() { return updates; }
                public boolean notifyEnabled() { return false; } // join notices are a proxy feature
                public int servers() { return core.trustedProxyCount(); }
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
            return Component.text("/" + label + " [help|about|status|reload|doctor|stats|proxies]");
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

                public String text(String key) {
                    return Commands.builtIn(key);
                }

                public void send(String formatted) {
                    cause.audience().sendMessage(LEGACY.deserialize(MiniText.anyToLegacy(formatted)));
                }

                public void lines(List<String> lines) {
                    for (String l : lines) {
                        cause.audience().sendMessage(LEGACY.deserialize(MiniText.anyToLegacy(Commands.builtIn("prefix")) + l));
                    }
                }
            };
        }
    }
}
