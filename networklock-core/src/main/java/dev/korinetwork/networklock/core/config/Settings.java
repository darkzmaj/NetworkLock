package dev.korinetwork.networklock.core.config;

import dev.korinetwork.networklock.core.Addresses;
import dev.korinetwork.networklock.core.ConnectionLimiter;
import dev.korinetwork.networklock.core.ForwardingToken;
import dev.korinetwork.networklock.core.Keys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, fully validated configuration snapshots. A snapshot is either complete or not built at all. */
public final class Settings {

    private Settings() {}

    public record Logging(boolean securityEvents, boolean redactAddresses, boolean debug) {
        static Logging read(ConfigTree t) {
            return new Logging(t.bool("logging.security-events", true),
                    t.bool("logging.redact-addresses", false),
                    t.bool("logging.debug", false));
        }
    }

    public record Proxy(String networkId, byte[] key, ConnectionLimiter.Settings limiter, Logging logging) {

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
                    List.copyOf(exempt),
                    t.integer("protection.max-tracked-addresses", 100_000, 1_000, 10_000_000),
                    t.integer("protection.flood-warning-threshold", 200, 1, 1_000_000));
            Logging logging = Logging.read(t);
            t.throwIfErrors();
            return new Proxy(id, key, limiter, logging);
        }

        public String keyFingerprint() {
            return Keys.fingerprint(key);
        }
    }

    public record TrustedProxy(String id, List<byte[]> keys) {}

    public record Backend(String serverId, Map<String, TrustedProxy> proxies, long maxAgeMillis, long skewMillis,
                          int replayCacheMax, Logging logging, List<String> warnings) {

        public static Backend load(Path file, Map<String, String> env) throws ConfigException {
            ConfigTree t = ConfigTree.load(file);
            String serverId = t.string("network.server-id", "");
            if (serverId.isBlank()) {
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
                if (!(entries.get(i) instanceof Map<?, ?> m)) {
                    t.error(where, "must be a mapping with 'id' and 'keys'");
                    continue;
                }
                ConfigTree e = t.child(m, where);
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
                proxies.put(id, new TrustedProxy(id, List.copyOf(keys)));
            }
            int maxAge = t.integer("authentication.max-token-age-seconds", 30, 5, 300);
            int skew = t.integer("authentication.max-clock-skew-seconds", 5, 0, 60);
            int cache = t.integer("authentication.replay-cache-max-entries", 100_000, 1_000, 10_000_000);
            Logging logging = Logging.read(t);
            t.throwIfErrors();
            return new Backend(serverId, Map.copyOf(proxies), maxAge * 1000L, skew * 1000L, cache, logging,
                    List.copyOf(warnings));
        }

        public List<String> fingerprints(String proxyId) {
            TrustedProxy p = proxies.get(proxyId);
            return p == null ? List.of() : p.keys().stream().map(Keys::fingerprint).toList();
        }
    }
}
