package dev.korinetwork.networklock.core;

import dev.korinetwork.networklock.core.config.ConfigException;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Messages;
import dev.korinetwork.networklock.core.config.Settings;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/** Proxy-side runtime: login throttling, token minting and the proxy-owned messages. */
public final class ProxyCore {

    public enum Admission { ALLOWED, LIMITED, BLOCKED, LOCKDOWN }

    private static final class Active {
        private final Settings.Proxy settings;
        private final Messages messages;
        private final ConnectionLimiter limiter;

        Active(Settings.Proxy settings, Messages messages, ConnectionLimiter limiter) {
            this.settings = settings;
            this.messages = messages;
            this.limiter = limiter;
        }

        Settings.Proxy settings() {
            return settings;
        }

        Messages messages() {
            return messages;
        }

        ConnectionLimiter limiter() {
            return limiter;
        }
    }

    private final Path dataDir;
    private final Log log;
    private final SecurityLog securityLog;
    private final LongSupplier clock;
    private final Map<String, String> env;
    private final LongAdder minted = new LongAdder();
    private final java.util.Set<String> badTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final LongAdder limited = new LongAdder();
    private final LongAdder blocked = new LongAdder();
    private final LongAdder lockdownDenied = new LongAdder();
    private final LongAdder backendRejections = new LongAdder();

    private volatile Active active;
    private volatile String lockdownReason = "not started";

    public ProxyCore(Path dataDir, Log log, LongSupplier clock, Map<String, String> env) {
        this.dataDir = dataDir;
        this.log = log;
        this.securityLog = new SecurityLog(log);
        this.clock = clock;
        this.env = env;
    }

    public boolean start() {
        try {
            Path config = ConfigFiles.ensure(dataDir, "proxy", ConfigFiles.CONFIG);
            ConfigFiles.ensure(dataDir, "proxy", ConfigFiles.MESSAGES);
            if (ConfigFiles.generateProxyKeyIfUnset(config)) {
                log.warn("Generated a new proxy key in config.yml. Add this proxy's id and key to every backend's"
                        + " authentication.trusted-proxies list, then run /networklock reload there.");
            }
        } catch (IOException e) {
            lockdown("cannot create configuration files: " + e.getMessage());
            return false;
        }
        return reload().isEmpty();
    }

    /** Validates config.yml and messages.yml together and swaps both in only if both are valid. */
    public List<String> reload() {
        Settings.Proxy s;
        Messages m;
        try {
            s = Settings.Proxy.load(dataDir.resolve(ConfigFiles.CONFIG), env);
            m = Messages.load(dataDir.resolve(ConfigFiles.MESSAGES));
        } catch (ConfigException e) {
            for (String p : e.problems()) log.error("Configuration problem: " + p);
            if (active == null) lockdown("invalid configuration");
            return e.problems();
        }
        active = new Active(s, m, new ConnectionLimiter(s.limiter()));
        lockdownReason = null;
        log.info("Proxy '" + s.networkId() + "' ready, key fingerprint " + s.keyFingerprint());
        return Collections.emptyList();
    }

    private void lockdown(String reason) {
        active = null;
        lockdownReason = reason;
        log.error("LOCKDOWN: logins will be refused until this is fixed (" + reason + ")");
    }

    public Admission admit(InetAddress address) {
        Active a = active;
        if (a == null) {
            lockdownDenied.increment();
            return Admission.LOCKDOWN;
        }
        long now = clock.getAsLong();
        if (a.limiter().recordAttemptAndCheckFlood(now)) {
            log.warn("Connection flood: more than " + a.settings().limiter().floodThreshold()
                    + " login attempts in one second across all addresses");
        }
        ConnectionLimiter.Decision decision = a.limiter().check(address, now);
        final Admission result;
        switch (decision) {
            case ALLOWED:
                result = Admission.ALLOWED;
                break;
            case LIMITED:
                result = Admission.LIMITED;
                break;
            case BLOCKED:
                result = Admission.BLOCKED;
                break;
            default:
                throw new IncompatibleClassChangeError(String.valueOf(decision));
        }
        if (result != Admission.ALLOWED) {
            (result == Admission.LIMITED ? limited : blocked).increment();
            if (a.settings().logging().securityEvents()) {
                boolean redact = a.settings().logging().redactAddresses();
                securityLog.event(() -> "Throttled login from " + SecurityLog.address(address, redact) + " (" + result + ")", now);
            }
        }
        return result;
    }

    /** A fresh token for one backend connection, or null while in lockdown. */
    /** Whether debug logging is on in the active config. */
    public boolean debug() {
        Active a = active;
        return a != null && a.settings().logging().debug();
    }

    public String mint(PlayerIdentity identity, String targetServer) {
        Active a = active;
        if (a == null) return null;
        if (!ForwardingToken.isValidTarget(targetServer)) {
            if (badTargets.add(String.valueOf(targetServer))) {
                log.error("Server name '" + SecurityLog.clean(String.valueOf(targetServer)) + "' cannot be signed (it must be 1 to 64"
                        + " characters without control characters); rename it in the proxy config. Players are refused there.");
            }
            return null;
        }
        String token = ForwardingToken.mint(a.settings().networkId(), a.settings().key(), targetServer, identity, clock.getAsLong());
        minted.increment();
        if (a.settings().logging().debug()) log.info("Signed connection of " + SecurityLog.clean(identity.name()) + " to " + targetServer);
        return token;
    }

    /**
     * If a backend kick carries a NetworkLock reject code, returns the {@code messages.yml}
     * text to show instead; otherwise null.
     */
    public String translateBackendKick(String plainReason) {
        Outcome.RejectCode code = Outcome.RejectCode.fromKickText(plainReason == null ? null : plainReason.trim());
        if (code == null) return null;
        backendRejections.increment();
        return messages().get(code.messageKey());
    }

    public Messages messages() {
        Active a = active;
        return a == null ? Messages.defaults() : a.messages();
    }

    public boolean inLockdown() {
        return active == null;
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

    /** Anonymous ID for the usage counter, derived one-way from the proxy key; null while in lockdown. */
    public String usageId() {
        Active a = active;
        return a == null ? null : UsageStats.anonymousId(Collections.singletonList(a.settings().key()), "proxy");
    }

    /** Whether staff are told about new versions when they join. */
    public boolean notifyEnabled() {
        Active a = active;
        return a != null && a.settings().updates().check() && a.settings().updates().notifyOnJoin();
    }

    public List<String> statusLines() {
        Active a = active;
        if (a == null) return Collections.singletonList("State: LOCKDOWN - logins refused (" + lockdownReason + ")");
        long now = clock.getAsLong();
        List<String> out = new ArrayList<>();
        out.add("State: enforcing");
        out.add("Proxy id: " + a.settings().networkId());
        out.add("Key fingerprint: " + a.settings().keyFingerprint());
        out.add("Connection limit: " + (a.settings().limiter().enabled()
                ? a.settings().limiter().maxAttempts() + " per " + a.settings().limiter().windowSeconds() + "s" : "disabled"));
        out.add("Tracked addresses: " + a.limiter().tracked() + ", temporarily blocked: " + a.limiter().blocked(now));
        return out;
    }

    public List<String> statsLines() {
        return Collections.unmodifiableList(Arrays.asList("signed connections: " + minted.sum(), "throttled logins: " + limited.sum(),
                "blocked logins: " + blocked.sum(), "refused during lockdown: " + lockdownDenied.sum(),
                "backend rejections: " + backendRejections.sum()));
    }

    public List<String> proxiesLines() {
        Active a = active;
        if (a == null) return Collections.singletonList("No configuration loaded (lockdown).");
        return Collections.unmodifiableList(Arrays.asList(
                "This proxy: " + a.settings().networkId() + " (key fingerprint " + a.settings().keyFingerprint() + ")",
                "Backends must list this id with a key of the same fingerprint."));
    }

    public List<String> doctorLines() {
        Active a = active;
        List<String> out = new ArrayList<>();
        if (a == null) {
            out.add("FAIL lockdown: " + lockdownReason);
            return out;
        }
        out.add("OK   configuration valid; key fingerprint " + a.settings().keyFingerprint());
        if (a.settings().networkId().equals("proxy-1")) {
            out.add("INFO network.id is the default 'proxy-1'; give every proxy a distinct id when you run more than one");
        }
        if (!a.settings().limiter().enabled()) out.add("WARN connection limiting is disabled");
        if (a.limiter().saturated()) out.add("WARN address tracker is full; new addresses are not being throttled");
        if (ConfigFiles.isWorldOrGroupReadable(dataDir.resolve(ConfigFiles.CONFIG))) {
            out.add("WARN config.yml (holds the proxy key) is readable by other users; run chmod 600 on it");
        }
        return out;
    }
}
