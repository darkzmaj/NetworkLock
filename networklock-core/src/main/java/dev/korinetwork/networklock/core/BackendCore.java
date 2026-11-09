package dev.korinetwork.networklock.core;

import dev.korinetwork.networklock.core.config.ConfigException;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Backend admission control. Holds one immutable configuration snapshot; when no valid
 * snapshot exists (bad config at startup, unsupported platform) it is in lockdown and
 * denies every login.
 */
public final class BackendCore {

    private static final class Active {
        private final Settings.Backend settings;
        private final NonceCache nonces;

        Active(Settings.Backend settings, NonceCache nonces) {
            this.settings = settings;
            this.nonces = nonces;
        }

        Settings.Backend settings() {
            return settings;
        }

        NonceCache nonces() {
            return nonces;
        }
    }

    /** An outcome plus a plain-language explanation for the console. Never shown to players. */
    private static final class Decision {
        private final Outcome outcome;
        private final String detail;

        Decision(Outcome outcome, String detail) {
            this.outcome = outcome;
            this.detail = detail;
        }

        Outcome outcome() {
            return outcome;
        }

        String detail() {
            return detail;
        }
    }

    private final Path dataDir;
    private final Log log;
    private final SecurityLog securityLog;
    private final LongSupplier clock;
    private final Map<String, String> env;
    private final Map<Outcome, LongAdder> outcomes = new EnumMap<>(Outcome.class);
    private final AtomicLong maxObservedSkew = new AtomicLong();
    private final long startedAt;

    private volatile Active active;
    private volatile String lockdownReason = "not started";
    private volatile String platformProblem;

    public BackendCore(Path dataDir, Log log, LongSupplier clock, Map<String, String> env) {
        this.dataDir = dataDir;
        this.log = log;
        this.securityLog = new SecurityLog(log);
        this.clock = clock;
        this.env = env;
        for (Outcome o : Outcome.values()) outcomes.put(o, new LongAdder());
        this.startedAt = clock.getAsLong();
    }

    /** Loads the configuration at startup. On failure the backend stays in lockdown. */
    public boolean start() {
        try {
            ConfigFiles.ensure(dataDir, "backend", ConfigFiles.CONFIG);
        } catch (IOException e) {
            lockdown("cannot create config.yml: " + e.getMessage());
            return false;
        }
        return reload().isEmpty();
    }

    /**
     * Validates the whole file before swapping it in. Returns the problems, empty on success.
     * A failed reload keeps the previous snapshot (or lockdown) unchanged.
     */
    public List<String> reload() {
        Settings.Backend s;
        try {
            s = Settings.Backend.load(dataDir.resolve(ConfigFiles.CONFIG), env);
        } catch (ConfigException e) {
            for (String p : e.problems()) log.error("Configuration problem: " + p);
            if (active == null) lockdown("invalid configuration");
            return e.problems();
        }
        Active previous = active;
        // keep remembered nonces across reloads, otherwise a reload would reopen the replay window
        // one cache for the plugin's lifetime: a reload (even one that changes its size) and logins still in
        // flight during the reload must never forget a used token
        NonceCache cache = previous != null ? previous.nonces() : new NonceCache(s.replayCacheMax());
        cache.capacity(s.replayCacheMax());
        active = new Active(s, cache);
        lockdownReason = null;
        for (String w : s.warnings()) log.warn(w);
        return Collections.emptyList();
    }

    public void lockdown(String reason) {
        active = null;
        lockdownReason = reason;
        log.error("LOCKDOWN: every login will be denied until this is fixed (" + reason + ")");
    }

    /** A permanent lockdown that no reload can lift, e.g. the server lacks a required API. */
    public void blockPlatform(String reason) {
        platformProblem = reason;
        log.error("LOCKDOWN: every login will be denied (" + reason + ")");
    }

    /**
     * Decides one login. {@code tokens} are the values of every {@value ForwardingToken#PROPERTY}
     * property in the forwarded profile; {@code identity.properties()} must exclude them.
     */
    public Outcome verify(PlayerIdentity identity, List<String> tokens) {
        Decision decision;
        try {
            decision = decide(identity, tokens);
        } catch (RuntimeException e) {
            log.error("Internal error while verifying a login (denied): " + e.getClass().getName());
            decision = new Decision(Outcome.INTERNAL_ERROR, "internal error, see the line above");
        }
        Outcome outcome = decision.outcome();
        outcomes.get(outcome).increment();
        Active a = active;
        boolean redact = a != null && a.settings().logging().redactAddresses();
        if (outcome == Outcome.OK) {
            if (a != null && a.settings().logging().debug()) {
                log.info("Accepted " + SecurityLog.clean(identity.name()) + " (" + identity.uuid() + ") properties="
                        + identity.properties().stream().map(PlayerIdentity.Property::name).collect(Collectors.toList()));
            }
        } else if (a == null || a.settings().logging().securityEvents()) {
            Decision d = decision;
            securityLog.event(() -> "Denied login name=" + SecurityLog.clean(identity.name()) + " uuid=" + identity.uuid()
                    + " address=" + SecurityLog.address(identity.address(), redact) + " reason=" + d.outcome()
                    + ": " + d.detail(), clock.getAsLong());
        }
        return outcome;
    }

    private Decision decide(PlayerIdentity identity, List<String> tokens) {
        Active a = active;
        if (a == null || platformProblem != null) {
            return new Decision(Outcome.LOCKDOWN, "this server is in lockdown (" + lockdownText()
                    + "). Fix config.yml and run /networklock reload");
        }
        if (tokens.isEmpty()) {
            return new Decision(Outcome.MISSING, "no NetworkLock signature. The player did not come through a proxy"
                    + " running NetworkLock, or player forwarding is off on the proxy or this server");
        }
        if (tokens.size() > 1) return new Decision(Outcome.MULTIPLE, "more than one signature in the profile");
        ForwardingToken.Parsed t;
        try {
            t = ForwardingToken.parse(tokens.get(0));
        } catch (RuntimeException e) {
            return new Decision(Outcome.MALFORMED, "the signature could not be read");
        }
        Settings.TrustedProxy proxy = a.settings().proxies().get(t.proxyId());
        if (proxy == null) {
            return new Decision(Outcome.UNKNOWN_PROXY, "signed by proxy '" + t.proxyId()
                    + "', which is not in authentication.trusted-proxies");
        }
        if (!ForwardingToken.macMatches(t, proxy.keys(), identity)) {
            return new Decision(Outcome.BAD_SIGNATURE, "the signature does not match the key set for proxy '" + t.proxyId()
                    + "'. Copy security.key from that proxy again and compare /networklock proxies on both sides");
        }
        // only authenticated fields are examined below
        if (!t.target().equals(a.settings().serverId())) {
            return new Decision(Outcome.WRONG_TARGET, "signed for server '" + t.target() + "', but this server's"
                    + " network.server-id is '" + a.settings().serverId() + "'. They must match the server name in the proxy config");
        }
        long now = clock.getAsLong();
        long age = now - t.issuedAtMillis();
        maxObservedSkew.accumulateAndGet(Math.max(0, -age), Math::max);
        if (age > a.settings().maxAgeMillis()) {
            return new Decision(Outcome.EXPIRED, "the signature is " + age / 1000 + "s old (limit "
                    + a.settings().maxAgeMillis() / 1000 + "s). Check that the server clocks are in sync");
        }
        if (-age > a.settings().skewMillis()) {
            return new Decision(Outcome.NOT_YET_VALID, "the proxy's clock is " + (-age) / 1000
                    + "s ahead of this server. Sync the server clocks");
        }
        long retention = a.settings().maxAgeMillis() + a.settings().skewMillis();
        NonceCache.Result result = a.nonces().use(t.nonce(), t.issuedAtMillis(), now, retention);
        switch (result) {
            case FRESH:
                return new Decision(Outcome.OK, "");
            case REPLAY:
                return new Decision(Outcome.REPLAYED, "this signature was already used once");
            case FULL:
                return new Decision(Outcome.REPLAY_CACHE_FULL, "the replay cache is full; raise authentication.replay-cache-max-entries");
            default:
                throw new IncompatibleClassChangeError(String.valueOf(result));
        }
    }

    /** The kick text for a denied login: the server's own messages.* entry, or the built-in text. */
    public String kickText(Outcome outcome) {
        Active a = active;
        String custom = a == null ? null : a.settings().kickMessages().get(outcome.code().messageKey());
        return custom != null ? custom : outcome.code().backendMessage();
    }

    /** Whether update checks are on; off while no config is loaded. */
    public boolean updatesEnabled() {
        Active a = active;
        return a != null && a.settings().updates().check(); // no config loaded: the owner's choice is unknown, so stay quiet
    }

    public boolean metricsEnabled() {
        Active a = active;
        return a != null && a.settings().updates().metrics();
    }

    /** Anonymous ID for the usage counter, derived one-way from server-id and the (secret) trusted keys; null in lockdown. */
    public String usageId() {
        Active a = active;
        if (a == null) return null;
        List<byte[]> keys = new ArrayList<>();
        a.settings().proxies().keySet().stream().sorted().forEach(p -> keys.addAll(a.settings().proxies().get(p).keys()));
        return UsageStats.anonymousId(keys, "server", a.settings().serverId());
    }

    public int trustedProxyCount() {
        Active a = active;
        return a == null ? 0 : a.settings().proxies().size();
    }

    public boolean inLockdown() {
        return active == null || platformProblem != null;
    }

    private String lockdownText() {
        return platformProblem != null ? platformProblem : lockdownReason;
    }

    public boolean debug() {
        Active a = active;
        return a != null && a.settings().logging().debug();
    }

    public List<String> statusLines() {
        Active a = active;
        List<String> out = new ArrayList<>();
        if (inLockdown()) {
            out.add("State: LOCKDOWN - all logins denied (" + lockdownText() + ")");
            return out;
        }
        out.add("State: enforcing");
        out.add("Server id: " + a.settings().serverId());
        out.add("Trusted proxies: " + a.settings().proxies().size());
        out.add("Token lifetime: " + a.settings().maxAgeMillis() / 1000 + "s (clock skew allowance "
                + a.settings().skewMillis() / 1000 + "s)");
        out.add("Replay cache: " + a.nonces().size() + "/" + a.nonces().capacity());
        out.add("Uptime: " + (clock.getAsLong() - startedAt) / 1000 + "s");
        return out;
    }

    public List<String> statsLines() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Outcome, LongAdder> e : outcomes.entrySet()) {
            long n = e.getValue().sum();
            if (n > 0 || e.getKey() == Outcome.OK) out.add(e.getKey().name().toLowerCase(java.util.Locale.ROOT) + ": " + n);
        }
        Active a = active;
        if (a != null) out.add("replay memory: " + a.nonces().size() + " / " + a.nonces().capacity());
        return out;
    }

    public List<String> proxiesLines() {
        Active a = active;
        if (a == null) return Collections.singletonList("No configuration loaded (lockdown).");
        List<String> out = new ArrayList<>();
        for (Settings.TrustedProxy p : a.settings().proxies().values()) {
            out.add(p.id() + " - key fingerprints " + a.settings().fingerprints(p.id()));
        }
        return out;
    }

    /** Configuration-level health checks; platform checks are appended by the adapter. */
    public List<String> doctorLines() {
        List<String> out = new ArrayList<>();
        Active a = active;
        if (inLockdown()) {
            out.add("FAIL lockdown: " + lockdownText());
            if (a == null) return out;
        }
        out.add("OK   configuration valid, " + a.settings().proxies().size() + " trusted proxy(ies)");
        for (String w : a.settings().warnings()) out.add("WARN " + w);
        for (Settings.TrustedProxy p : a.settings().proxies().values()) {
            if (p.keys().size() > 1) out.add("INFO proxy '" + p.id() + "' has " + p.keys().size()
                    + " keys; remove the old one once rotation is finished");
        }
        long skew = maxObservedSkew.get();
        if (skew > 1000) out.add("WARN a proxy clock is up to " + skew + "ms ahead of this server; keep clocks NTP-synced");
        if (outcomes.get(Outcome.MISSING).sum() > 0) {
            out.add("INFO " + outcomes.get(Outcome.MISSING).sum() + " login(s) arrived without proxy credentials:"
                    + " direct connections, or a proxy without NetworkLock / with forwarding disabled");
        }
        if (outcomes.get(Outcome.WRONG_TARGET).sum() > 0) {
            out.add("WARN logins signed for another server name were denied; check network.server-id matches the proxy's server name");
        }
        if (ConfigFiles.isWorldOrGroupReadable(dataDir.resolve(ConfigFiles.CONFIG))) {
            out.add("WARN config.yml is readable by other users; run chmod 600 on it");
        }
        return out;
    }
}
