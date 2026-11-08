package dev.korinetwork.networklock.bukkit;

import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.Commands;
import dev.korinetwork.networklock.core.JulLog;
import dev.korinetwork.networklock.core.MiniText;
import dev.korinetwork.networklock.core.UpdateChecker;
import dev.korinetwork.networklock.core.UsageStats;
import dev.korinetwork.networklock.core.config.ConfigFiles;
import dev.korinetwork.networklock.core.config.Settings;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Backend entry point for every Bukkit-family server from 1.8 up (Paper and its forks, Folia and
 * its forks, Spigot), loaded through {@code plugin.yml}. Uses no scheduler at all, so it is
 * Folia-safe by construction. Compiled for Java 8 so 1.8 to 1.16 servers can load it.
 */
public final class NetworkLockBukkit extends JavaPlugin {

    private BackendCore core;
    private UpdateChecker updates;
    private UsageStats stats;

    @Override
    @SuppressWarnings("deprecation") // getDescription() is the only version API every supported fork has
    public void onEnable() {
        JulLog log = new JulLog(getLogger());
        core = new BackendCore(getDataFolder().toPath(), log, System::currentTimeMillis, System.getenv());
        updates = new UpdateChecker(getDescription().getVersion(), log, core::updatesEnabled);
        stats = new UsageStats(getDescription().getVersion(), getServer().getName().toLowerCase(java.util.Locale.ROOT),
                "server", core::usageId, core::metricsEnabled);
        try {
            // Register before loading config: until a valid config is active every login is denied.
            // Paper 1.12.2+ (and Folia) offer the profile in the async pre-login event; everything older or
            // plain Spigot/CraftBukkit is checked in PlayerLoginEvent instead, which still runs before the join.
            String unsupported = null;
            if (PaperSupport.missingApi() == null) {
                getServer().getPluginManager().registerEvents(new PaperLoginListener(core, getLogger()), this);
            } else {
                unsupported = LegacyLoginListener.missingApi();
                getServer().getPluginManager().registerEvents(new LegacyLoginListener(core, getLogger()), this);
            }
            core.start();
            if (unsupported != null) core.blockPlatform(unsupported);
            registerCommand(Settings.CommandNames.read(getDataFolder().toPath().resolve(ConfigFiles.CONFIG)));
            updates.start();
            stats.start();
            for (String line : doctor()) {
                if (!line.startsWith("OK")) getLogger().warning("doctor: " + line);
            }
        } catch (Throwable t) {
            // Never let an exception disable the plugin: a disabled plugin unregisters its listener.
            getLogger().severe("Startup error (" + t + "); all logins will be denied");
            core.blockPlatform("startup error: " + t.getClass().getSimpleName());
        }
    }

    /** Registered at runtime so the name and aliases can come from config.yml. */
    private void registerCommand(Settings.CommandNames names) {
        try {
            // getCommandMap() is public on CraftServer (Paper, Spigot, Folia) but not part of the Bukkit API
            CommandMap map = (CommandMap) getServer().getClass().getMethod("getCommandMap").invoke(getServer());
            map.register("networklock", new NetworkLockCommand(names));
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Could not register the /" + names.name() + " command: " + e);
        }
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (stats != null) stats.stop();
        if (!PaperSupport.isStopping(getServer())) {
            getLogger().severe("NetworkLock was disabled while the server is running: this backend no longer"
                    + " rejects direct or forged logins. Restart the server to restore protection.");
        }
    }

    private List<String> doctor() {
        List<String> out = new ArrayList<>(core.doctorLines());
        File spigotYml = new File("spigot.yml");
        boolean bungee = spigotYml.isFile()
                && YamlConfiguration.loadConfiguration(spigotYml).getBoolean("settings.bungeecord", false);
        File paperGlobal = new File("config", "paper-global.yml"); // Paper 1.19+
        File paperYml = new File("paper.yml");                     // Paper 1.13 to 1.18
        boolean velocity = (paperGlobal.isFile()
                && YamlConfiguration.loadConfiguration(paperGlobal).getBoolean("proxies.velocity.enabled", false))
                || (paperYml.isFile()
                && YamlConfiguration.loadConfiguration(paperYml).getBoolean("settings.velocity-support.enabled", false));
        if (!PaperSupport.hasSpigot()) {
            out.add("FAIL this is CraftBukkit, which has no proxy forwarding at all; every login is denied."
                    + " Use Spigot, Paper or a Paper fork behind a proxy");
        } else if (!bungee && !velocity) {
            out.add("FAIL no proxy forwarding enabled (spigot.yml settings.bungeecord, or Velocity forwarding in"
                    + " paper-global.yml / paper.yml); players cannot be verified and every login is denied");
        } else if (bungee && velocity) {
            // observed on Paper 1.21.11: the BungeeCord handshake check runs first and refuses Velocity connections
            out.add("FAIL both spigot.yml settings.bungeecord and Velocity forwarding are enabled; enable only the one your proxy uses");
        } else {
            out.add("OK   proxy forwarding: " + (velocity ? "Velocity modern" : "BungeeCord legacy"));
        }
        if (getServer().getIp().isEmpty() || getServer().getIp().equals("0.0.0.0")) {
            out.add("WARN server listens on all interfaces; firewall port " + getServer().getPort()
                    + " so only your proxies can reach it (NetworkLock still rejects direct logins)");
        }
        return out;
    }

    private final class NetworkLockCommand extends Command {
        NetworkLockCommand(Settings.CommandNames names) {
            super(names.name(), "NetworkLock administration", "/" + names.name() + " help", names.aliases());
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            Commands.dispatch(getName(), args, sender(sender), new Commands.Target() {
                public List<String> status() {
                    List<String> l = new ArrayList<>(core.statusLines());
                    l.add("Platform: " + getServer().getName() + " " + getServer().getBukkitVersion());
                    return l;
                }

                public List<String> stats() { return core.statsLines(); }

                public List<String> proxies() { return core.proxiesLines(); }

                public List<String> doctor() { return NetworkLockBukkit.this.doctor(); }

                public List<String> reload() { return core.reload(); }

                public UpdateChecker updates() { return updates; }

                public boolean notifyEnabled() { return false; } // join notices are a proxy feature

                public int servers() { return core.trustedProxyCount(); }
            });
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Commands.complete(args, sender(sender));
        }
    }

    private static Commands.Sender sender(CommandSender s) {
        return new Commands.Sender() {
            public boolean hasPermission(String node) {
                return s.hasPermission(node);
            }

            public String text(String key) {
                return Commands.builtIn(key);
            }

            @SuppressWarnings("deprecation") // the String overload works on Spigot too
            public void send(String formatted) {
                s.sendMessage(MiniText.anyToLegacy(formatted));
            }

            @SuppressWarnings("deprecation")
            public void lines(List<String> lines) {
                String prefix = MiniText.anyToLegacy(Commands.builtIn("prefix"));
                for (String l : lines) s.sendMessage(prefix + l);
            }
        };
    }
}
