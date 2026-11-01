package dev.korinetwork.networklock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /networklock} (alias {@code /nlock}), shared by every platform. */
public final class Commands {

    public static final String ADMIN_PERMISSION = "networklock.admin";
    public static final List<String> SUBCOMMANDS = List.of("help", "status", "reload", "doctor", "stats", "proxies", "version");

    /** What the command can do on this server. */
    public interface Target {
        List<String> status();

        List<String> stats();

        List<String> proxies();

        List<String> doctor();

        /** Problems, empty on success. */
        List<String> reload();

        String version();
    }

    /** Who ran it. {@code message} takes a {@code messages.yml} key on proxies (built-in text on backends). */
    public interface Sender {
        boolean hasPermission(String node);

        void message(String key);

        void lines(List<String> lines);
    }

    private Commands() {}

    public static String permission(String sub) {
        return "networklock.command." + sub;
    }

    static boolean allowed(Sender s, String sub) {
        return s.hasPermission(ADMIN_PERMISSION) || s.hasPermission(permission(sub));
    }

    public static void dispatch(String[] args, Sender s, Target t) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            s.message("invalid-command");
            return;
        }
        if (!allowed(s, sub)) {
            s.message("no-permission");
            return;
        }
        switch (sub) {
            case "help" -> {
                List<String> lines = new ArrayList<>();
                for (String c : SUBCOMMANDS) {
                    if (allowed(s, c)) lines.add("/networklock " + c + " - " + describe(c));
                }
                s.lines(lines);
            }
            case "status" -> s.lines(t.status());
            case "stats" -> s.lines(t.stats());
            case "proxies" -> s.lines(t.proxies());
            case "doctor" -> s.lines(t.doctor());
            case "version" -> s.lines(List.of("NetworkLock " + t.version() + " by DarkZmaj", "Support: https://kori.my"));
            case "reload" -> {
                List<String> problems = t.reload();
                if (problems.isEmpty()) {
                    s.message("reloaded");
                } else {
                    s.message("reload-failed");
                    s.lines(problems);
                }
            }
            default -> s.message("invalid-command");
        }
    }

    public static List<String> complete(String[] args, Sender s) {
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : SUBCOMMANDS) {
            if (c.startsWith(prefix) && allowed(s, c)) out.add(c);
        }
        return out;
    }

    private static String describe(String sub) {
        return switch (sub) {
            case "help" -> "this list";
            case "status" -> "current protection state";
            case "reload" -> "validate and apply config changes";
            case "doctor" -> "check for unsafe or broken setup";
            case "stats" -> "counters since startup";
            case "proxies" -> "trusted proxy ids and key fingerprints";
            case "version" -> "plugin version";
            default -> "";
        };
    }

    /** English text for {@link Sender#message} keys on backends, which have no messages.yml. */
    public static String builtIn(String key) {
        return switch (key) {
            case "no-permission" -> "You do not have permission.";
            case "reloaded" -> "Configuration reloaded.";
            case "reload-failed" -> "Reload failed; the previous configuration is still active:";
            case "invalid-command" -> "Unknown command. Use /networklock help.";
            default -> key;
        };
    }
}
