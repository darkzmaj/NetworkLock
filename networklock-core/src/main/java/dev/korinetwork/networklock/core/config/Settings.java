package dev.korinetwork.networklock.core.config;

import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.ConnectionLimiter;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Keys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable, fully validated configuration snapshots. A snapshot is either complete or not built at all. */
public final class Settings {

    private Settings() {}

    public static final class Logging {
        private final boolean securityEvents;
        private final boolean redactAddresses;
        private final boolean debug;

        public Logging(boolean securityEvents, boolean redactAddresses, boolean debug) {
            this.securityEvents = securityEvents;
            this.redactAddresses = redactAddresses;
            this.debug = debug;
        }

        static Logging read(ConfigTree t) {
            return new Logging(t.bool("logging.security-events", true),
                    t.bool("logging.redact-addresses", false),
                    t.bool("logging.debug", false));
        }

        public boolean securityEvents() {
            return securityEvents;
        }

        public boolean redactAddresses() {
            return redactAddresses;
        }

        public boolean debug() {
            return debug;
        }
    }

    /** Update checks and the anonymous usage counter ({@code metrics.enabled}). */
    public static final class Updates {
        private final boolean check;
        private final boolean notifyOnJoin;
        private final boolean metrics;

        public Updates(boolean check, boolean notifyOnJoin, boolean metrics) {
            this.check = check;
            this.notifyOnJoin = notifyOnJoin;
            this.metrics = metrics;
        }

        static Updates read(ConfigTree t) {
            return new Updates(t.bool("updates.check", true), t.bool("updates.notify", true), t.bool("metrics.enabled", true));
        }

        public boolean check() {
            return check;
        }

        public boolean notifyOnJoin() {
            return notifyOnJoin;
        }

        public boolean metrics() {
            return metrics;
        }
    }

    /** The command name and aliases. Read leniently so the command still works when the rest of the file is broken. */
    public static final class CommandNames {
        public static final CommandNames DEFAULT = new CommandNames("networklock", Collections.singletonList("nlock"));
        private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,32}");

        private final String name;
        private final List<String> aliases;

        public CommandNames(String name, List<String> aliases) {
            this.name = name;
            this.aliases = aliases;
        }

        public static CommandNames read(Path file) {
            try {
                ConfigTree t = ConfigTree.load(file);
                String name = t.string("commands.name", DEFAULT.name()).toLowerCase(java.util.Locale.ROOT);
                if (!NAME.matcher(name).matches()) return DEFAULT;
                List<String> aliases = new ArrayList<>();
                Object raw = t.raw("commands.aliases");
                for (Object a : raw instanceof List<?> ? (List<?>) raw : raw == null ? DEFAULT.aliases() : Collections.emptyList()) {
                    String alias = String.valueOf(a).toLowerCase(java.util.Locale.ROOT);
                    if (NAME.matcher(alias).matches() && !alias.equals(name)) aliases.add(alias);
                }
                return new CommandNames(name, Collections.unmodifiableList(aliases));
            } catch (ConfigException | RuntimeException e) {
                return DEFAULT;
            }
        }

        public String name() {
            return name;
        }

        public List<String> aliases() {
            return aliases;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CommandNames)) return false;
            CommandNames c = (CommandNames) o;
            return java.util.Objects.equals(name, c.name) && java.util.Objects.equals(aliases, c.aliases);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, aliases);
        }

        @Override
        public String toString() {
            return "CommandNames[name=" + name + ", aliases=" + aliases + "]";
        }
    }

    public static final class Proxy {
        private final String networkId;
        private final byte[] key;
        private final ConnectionLimiter.Settings limiter;
        private final Logging logging;
        private final Updates updates;

        public Proxy(String networkId, byte[] key, ConnectionLimiter.Settings limiter, Logging logging, Updates updates) {
            this.networkId = networkId;
            this.key = key;
            this.limiter = limiter;
            this.logging = logging;
            this.updates = updates;
        }

        public static Proxy load(Path file, Map<String, String> env) throws ConfigException {
            ConfigTree t = ConfigTree.load(file);
            String id = t.string("network.id", "");
            if (!ForwardingToken.PROXY_ID.matcher(id).matches()) {
                t.error("network.id", "must be 1-64 characters of A-Z a-z 0-9 . _ -");
            }
            byte[] key = null;
            try {
                key = Secrets.resolveKey(t.string("security.key", ""), env);
            } catch (IllegalArgumentException e) {
                t.error("security.key", e.getMessage());
            }
            List<Addresses.Cidr> exempt = new ArrayList<>();
            for (Object o : t.list("protection.exempt")) {
                try {
                    exempt.add(Addresses.Cidr.parse(String.valueOf(o)));
                } catch (RuntimeException e) {
                    t.error("protection.exempt", "contains an invalid address or range: " + o);
                }
            }
            ConnectionLimiter.Settings limiter = new ConnectionLimiter.Settings(
                    t.bool("protection.connection-limit.enabled", true),
                    t.integer("protection.connection-limit.max-attempts", 10, 1, 10_000),
                    t.integer("protection.connection-limit.window-seconds", 10, 1, 3600),
                    t.bool("protection.temporary-blocks.enabled", true),
                    t.integer("protection.temporary-blocks.violations-before-block", 5, 1, 10_000),
                    t.integer("protection.temporary-blocks.duration-seconds", 60, 1, 86_400),
                    t.integer("protection.connection-limit.ipv6-prefix-length", 64, 32, 128),
                    Collections.unmodifiableList(exempt),
                    t.integer("protection.max-tracked-addresses", 100_000, 1_000, 10_000_000),
                    t.integer("protection.flood-warning-threshold", 200, 1, 1_000_000));
            Logging logging = Logging.read(t);
            Updates updates = Updates.read(t);
            t.throwIfErrors();
            return new Proxy(id, key, limiter, logging, updates);
        }

        public String keyFingerprint() {
            return Keys.fingerprint(key);
        }

        public String networkId() {
            return networkId;
        }

        public byte[] key() {
            return key;
        }

        public ConnectionLimiter.Settings limiter() {
            return limiter;
        }

        public Logging logging() {
            return logging;
        }

        public Updates updates() {
            return updates;
        }
    }

    public static final class TrustedProxy {
        private final String id;
        private final List<byte[]> keys;

        public TrustedProxy(String id, List<byte[]> keys) {
            this.id = id;
            this.keys = keys;
        }

        public String id() {
            return id;
        }

        public List<byte[]> keys() {
            return keys;
        }
    }

    /** Keys of the optional backend kick texts; the same names the proxy's messages.yml uses. */
    public static final List<String> KICK_MESSAGE_KEYS = Collections.unmodifiableList(
            Arrays.asList("authentication-failed", "authentication-timeout", "security-error"));

    public static final class Backend {
        private final String serverId;
        private final Map<String, TrustedProxy> proxies;
        private final long maxAgeMillis;
        private final long skewMillis;
        private final int replayCacheMax;
        private final Logging logging;
        private final List<String> warnings;
        private final Map<String, String> kickMessages;
        private final Updates updates;

        public Backend(String serverId, Map<String, TrustedProxy> proxies, long maxAgeMillis, long skewMillis,
                       int replayCacheMax, Logging logging, List<String> warnings, Map<String, String> kickMessages,
                       Updates updates) {
            this.serverId = serverId;
            this.proxies = proxies;
            this.maxAgeMillis = maxAgeMillis;
            this.skewMillis = skewMillis;
            this.replayCacheMax = replayCacheMax;
            this.logging = logging;
            this.warnings = warnings;
            this.kickMessages = kickMessages;
            this.updates = updates;
        }

        public static Backend load(Path file, Map<String, String> env) throws ConfigException {
            ConfigTree t = ConfigTree.load(file);
            String serverId = t.string("network.server-id", "");
            if (serverId.trim().isEmpty()) {
                t.error("network.server-id", "must be set to this server's name in the proxy configuration");
            }
            Map<String, TrustedProxy> proxies = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            Set<String> fingerprints = new HashSet<>();
            List<?> entries = t.list("authentication.trusted-proxies");
            if (entries.isEmpty()) {
                t.error("authentication.trusted-proxies", "must list at least one proxy (id + key from the proxy's config.yml)");
            }
            for (int i = 0; i < entries.size(); i++) {
                String where = "authentication.trusted-proxies[" + i + "]";
                if (!(entries.get(i) instanceof Map<?, ?>)) {
                    t.error(where, "must be a mapping with 'id' and 'keys'");
                    continue;
                }
                ConfigTree e = t.child((Map<?, ?>) entries.get(i), where);
                String id = e.string("id", "");
                if (!ForwardingToken.PROXY_ID.matcher(id).matches()) {
                    e.error("id", "must be 1-64 characters of A-Z a-z 0-9 . _ -");
                    continue;
                }
                if (proxies.containsKey(id)) {
                    e.error("id", "is listed twice ('" + id + "')");
                    continue;
                }
                List<Object> rawKeys = new ArrayList<>(e.list("keys"));
                String single = e.string("key", null);
                if (single != null) rawKeys.add(single);
                if (rawKeys.isEmpty()) e.error("keys", "must contain at least one key");
                List<byte[]> keys = new ArrayList<>();
                for (Object k : rawKeys) {
                    try {
                        byte[] key = Secrets.resolveKey(String.valueOf(k), env);
                        if (!fingerprints.add(Keys.fingerprint(key))) {
                            warnings.add("Proxy '" + id + "' shares a key with another entry: a leak of one compromises both."
                                    + " Give every proxy its own key.");
                        }
                        keys.add(key);
                    } catch (IllegalArgumentException ex) {
                        e.error("keys", ex.getMessage());
                    }
                }
                proxies.put(id, new TrustedProxy(id, Collections.unmodifiableList(keys)));
            }
            int maxAge = t.integer("authentication.max-token-age-seconds", 30, 5, 300);
            int skew = t.integer("authentication.max-clock-skew-seconds", 5, 0, 60);
            int cache = t.integer("authentication.replay-cache-max-entries", 100_000, 1_000, 10_000_000);
            Logging logging = Logging.read(t);
            Map<String, String> kickMessages = new LinkedHashMap<>();
            for (String k : KICK_MESSAGE_KEYS) {
                String text = t.text("messages." + k, null);
                if (text != null) kickMessages.put(k, text);
            }
            Updates updates = Updates.read(t);
            t.throwIfErrors();
            return new Backend(serverId, Collections.unmodifiableMap(proxies), maxAge * 1000L, skew * 1000L, cache, logging,
                    Collections.unmodifiableList(warnings), Collections.unmodifiableMap(kickMessages), updates);
        }

        public List<String> fingerprints(String proxyId) {
            TrustedProxy p = proxies.get(proxyId);
            return p == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(p.keys().stream().map(Keys::fingerprint).collect(Collectors.toList()));
        }

        public String serverId() {
            return serverId;
        }

        public Map<String, TrustedProxy> proxies() {
            return proxies;
        }

        public long maxAgeMillis() {
            return maxAgeMillis;
        }

        public long skewMillis() {
            return skewMillis;
        }

        public int replayCacheMax() {
            return replayCacheMax;
        }

        public Logging logging() {
            return logging;
        }

        public List<String> warnings() {
            return warnings;
        }

        public Map<String, String> kickMessages() {
            return kickMessages;
        }

        public Updates updates() {
            return updates;
        }
    }
}
