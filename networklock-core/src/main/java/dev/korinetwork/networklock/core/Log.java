package dev.korinetwork.networklock.core;

/** Minimal logging bridge (JUL on Bukkit/Bungee, SLF4J on Velocity). */
public interface Log {
    void info(String message);

    void warn(String message);

    void error(String message);
}
