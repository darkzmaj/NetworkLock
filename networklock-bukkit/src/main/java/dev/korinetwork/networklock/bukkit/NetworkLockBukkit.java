package dev.korinetwork.networklock.bukkit;

import dev.korinetwork.networklock.core.BackendCore;
import dev.korinetwork.networklock.core.Commands;
import dev.korinetwork.networklock.core.JulLog;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Backend entry point for Paper and its forks (Folia, Purpur, Pufferfish), loaded through
 * {@code plugin.yml}. Uses no scheduler at all, so it is Folia-safe by construction.
 */
public final class NetworkLockBukkit extends JavaPlugin implements TabExecutor {

    private BackendCore core;

    @Override
    public void onEnable() {
        core = new BackendCore(getDataFolder().toPath(), new JulLog(getLogger()), System::currentTimeMillis, System.getenv());
        String unsupported = PaperSupport.missingApi();
        try {
            // Register before loading config: until a valid config is active every login is denied.
            if (unsupported == null) {
                getServer().getPluginManager().registerEvents(new PaperLoginListener(core, getLogger()), this);
            } else {
                getServer().getPluginManager().registerEvents(new DenyAllListener(), this);
            }
            core.start();
            if (unsupported != null) core.blockPlatform(unsupported);
            PluginCommand command = getCommand("networklock");
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
            for (String line : doctor()) {
                if (!line.startsWith("OK")) getLogger().warning("doctor: " + line);
            }
        } catch (Throwable t) {
            // Never let an exception disable the plugin: a disabled plugin unregisters its listener.
            getLogger().severe("Startup error (" + t + "); all logins will be denied");
            core.blockPlatform("startup error: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void onDisable() {
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
        File paperGlobal = new File("config", "paper-global.yml");
        boolean velocity = paperGlobal.isFile()
                && YamlConfiguration.loadConfiguration(paperGlobal).getBoolean("proxies.velocity.enabled", false);
        if (!bungee && !velocity) {
            out.add("FAIL no proxy forwarding enabled (spigot.yml settings.bungeecord or paper-global.yml"
                    + " proxies.velocity.enabled); players cannot be verified and every login is denied");
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Commands.dispatch(args, sender(sender), new Commands.Target() {
            public List<String> status() {
                List<String> l = new ArrayList<>(core.statusLines());
                l.add("Platform: " + getServer().getName() + " " + getServer().getBukkitVersion());
                return l;
            }

            public List<String> stats() {
                return core.statsLines();
            }

            public List<String> proxies() {
                return core.proxiesLines();
            }

            public List<String> doctor() {
                return NetworkLockBukkit.this.doctor();
            }

            public List<String> reload() {
                return core.reload();
            }

            @SuppressWarnings("deprecation") // getVersion() is the only API that works on every supported fork
            public String version() {
                return getDescription().getVersion();
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Commands.complete(args, sender(sender));
    }

    private static Commands.Sender sender(CommandSender s) {
        return new Commands.Sender() {
            public boolean hasPermission(String node) {
                return s.hasPermission(node);
            }

            public void message(String key) {
                s.sendMessage("[NetworkLock] " + Commands.builtIn(key));
            }

            public void lines(List<String> lines) {
                for (String l : lines) s.sendMessage("[NetworkLock] " + l);
            }
        };
    }

    /** Fallback for servers without the Paper profile API: no player can be verified, so none may join. */
    static final class DenyAllListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        @SuppressWarnings("deprecation") // the String overload is the one plain Spigot has
        public void onPreLogin(AsyncPlayerPreLoginEvent e) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    dev.korinetwork.networklock.core.Outcome.RejectCode.UNAVAILABLE.backendMessage());
        }
    }
}
