package dev.korinetwork.networklock.core.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxy-owned player and command messages (& colour codes, MiniMessage also accepted) from {@code messages.yml}.
 * Missing entries fall back to the built-in defaults so a partial file never breaks a kick.
 */
public final class Messages {

    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("prefix", "&8[&bNetworkLock&8] ");
        d.put("no-permission", "&cYou do not have permission.");
        d.put("reloaded", "&aConfiguration reloaded.");
        d.put("reload-failed", "&cReload failed, the old config is still active. Check the console.");
        d.put("invalid-command", "&cUnknown command. Use &f/networklock help&c.");
        d.put("authentication-failed", "&cConnection authentication failed.");
        d.put("authentication-timeout", "&cAuthentication timed out. Please reconnect.");
        d.put("connection-limited", "&cToo many connection attempts. Please wait a moment.");
        d.put("temporarily-blocked", "&cConnection temporarily restricted.");
        d.put("security-error", "&cUnable to establish a secure connection. Please try again later.");
        d.put("about", "&b&lNetworkLock &7v%version%\n&7Version: %status%\n&7Update notify: &f%notify%\n"
                + "&7Servers: &f%servers%\n&7Made by &fDarkZmaj &8| &7Support: &bhttps://kori.my");
        d.put("status-latest", "&aLATEST");
        d.put("status-outdated", "&cOUTDATED &7(newest: &f%latest%&7)");
        d.put("status-unknown", "&7not checked");
        d.put("update-available", "&eA new NetworkLock version is available!\n&7Current: &c%current% &7New: &a%latest%\n"
                + "&7Download: &b%link%");
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final Map<String, String> values;

    private Messages(Map<String, String> values) {
        this.values = values;
    }

    public static Messages defaults() {
        return new Messages(DEFAULTS);
    }

    public static Messages load(Path file) throws ConfigException {
        ConfigTree t = ConfigTree.load(file);
        Map<String, String> v = new LinkedHashMap<>(DEFAULTS);
        for (String key : DEFAULTS.keySet()) {
            String path = key.equals("prefix") ? "prefix" : "messages." + key;
            String value = t.text(path, null); // a list of lines is allowed for multi-line messages
            if (value != null) v.put(key, value);
        }
        t.throwIfErrors();
        return new Messages(Collections.unmodifiableMap(v));
    }

    public String get(String key) {
        return values.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
    }

    public String prefixed(String key) {
        return get("prefix") + get(key);
    }

    public List<String> keys() {
        return Collections.unmodifiableList(new ArrayList<>(DEFAULTS.keySet()));
    }
}
