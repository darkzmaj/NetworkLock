package dev.korinetwork.networklock.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The NetworkLock command ({@code /networklock}, alias {@code /nlock} by default), shared by every platform. */
public final class Commands {

    public static final String ADMIN_PERMISSION = "networklock.admin";
    public static final String NOTIFY_PERMISSION = "networklock.notify";
    public static final List<String> SUBCOMMANDS = Collections.unmodifiableList(
            Arrays.asList("help", "about", "status", "reload", "doctor", "stats", "proxies"));

    /** What the command can do on this server. */
    public interface Target {
        List<String> status();

        List<String> stats();

        List<String> proxies();

        List<String> doctor();

        /** Problems, empty on success. */
        List<String> reload();

        UpdateChecker updates();

        /** Whether staff get update notices on join. */
        boolean notifyEnabled();

        /** Servers behind this proxy, or trusted proxies on a game server. */
        int servers();
    }

    /**
     * Who ran it. On proxies {@code text} comes from {@code messages.yml}; on game servers it is built-in English.
     */
    public interface Sender {
        boolean hasPermission(String node);

        /** The raw template for a message key, with &amp; codes and placeholders. */
        String text(String key);

        /** Formats (&amp; codes, MiniMessage) and sends a finished message, which may span several lines. */
        void send(String formatted);

        /** Plain diagnostic lines, sent with the prefix. */
        void lines(List<String> lines);

        default void message(String key) {
            send(text("prefix") + text(key));
        }
    }

    private Commands() {}

    public static String permission(String sub) {
        return "networklock.command." + sub;
    }

    static boolean allowed(Sender s, String sub) {
        return s.hasPermission(ADMIN_PERMISSION) || s.hasPermission(permission(sub));
    }

    /** @param label the command name the player typed, used in the help text */
    public static void dispatch(String label, String[] args, Sender s, Target t) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("version")) sub = "about"; // 1.0.0 name, still accepted
        if (!SUBCOMMANDS.contains(sub)) {
            s.message("invalid-command");
            return;
        }
        if (!allowed(s, sub)) {
            s.message("no-permission");
            return;
        }
        switch (sub) {
            case "help": {
                List<String> lines = new ArrayList<>();
                for (String c : SUBCOMMANDS) {
                    if (allowed(s, c)) lines.add("/" + label + " " + c + " - " + describe(c));
                }
                s.lines(lines);
                break;
            }
            case "about":
                s.send(about(s, t));
                break;
            case "status":
                s.lines(t.status());
                break;
            case "stats":
                s.lines(t.stats());
                break;
            case "proxies":
                s.lines(t.proxies());
                break;
            case "doctor":
                s.lines(t.doctor());
                break;
            case "reload": {
                List<String> problems = t.reload();
                if (problems.isEmpty()) {
                    s.message("reloaded");
                } else {
                    s.message("reload-failed");
                    s.lines(problems);
                }
                break;
            }
            default:
                s.message("invalid-command");
        }
    }

    static String about(Sender s, Target t) {
        UpdateChecker u = t.updates();
        UpdateChecker.Status st = u.status();
        String status;
        switch (st) {
            case LATEST:
                status = s.text("status-latest");
                break;
            case OUTDATED:
                status = s.text("status-outdated");
                break;
            case UNKNOWN:
                status = s.text("status-unknown");
                break;
            default:
                throw new IncompatibleClassChangeError(String.valueOf(st));
        }
        return s.text("about").replace("%status%", status)
                .replace("%version%", u.current())
                .replace("%latest%", u.latest())
                .replace("%notify%", t.notifyEnabled() ? "on" : "off")
                .replace("%servers%", String.valueOf(t.servers()));
    }

    /** The filled-in update notice, or null when no newer version is known. */
    public static String updateNotice(String template, UpdateChecker u) {
        if (u.status() != UpdateChecker.Status.OUTDATED) return null;
        return template.replace("%current%", u.current()).replace("%latest%", u.latest())
                .replace("%link%", UpdateChecker.DOWNLOAD);
    }

    public static List<String> complete(String[] args, Sender s) {
        if (args.length > 1) return Collections.emptyList();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : SUBCOMMANDS) {
            if (c.startsWith(prefix) && allowed(s, c)) out.add(c);
        }
        return out;
    }

    private static String describe(String sub) {
        switch (sub) {
            case "help": return "this list";
            case "about": return "version, update status and info";
            case "status": return "current protection state";
            case "reload": return "validate and apply config changes";
            case "doctor": return "check for unsafe or broken setup";
            case "stats": return "counters since startup";
            case "proxies": return "trusted proxy ids and key fingerprints";
            default: return "";
        }
    }

    /** English templates for game servers, which have no messages.yml. */
    public static String builtIn(String key) {
        switch (key) {
            case "prefix": return "&8[&bNetworkLock&8] &r";
            case "no-permission": return "&cYou do not have permission.";
            case "reloaded": return "&aConfiguration reloaded.";
            case "reload-failed": return "&cReload failed; the previous configuration is still active:";
            case "invalid-command": return "&cUnknown command. Use /networklock help.";
            case "about": return "&b&lNetworkLock &7v%version%\n&7Version: %status%\n&7Trusted proxies: &f%servers%\n"
                    + "&7Made by &fDarkZmaj &8| &7Support: &bhttps://kori.my";
            case "status-latest": return "&aLATEST";
            case "status-outdated": return "&cOUTDATED &7(newest: &f%latest%&7)";
            case "status-unknown": return "&7not checked";
            default: return key;
        }
    }
}
