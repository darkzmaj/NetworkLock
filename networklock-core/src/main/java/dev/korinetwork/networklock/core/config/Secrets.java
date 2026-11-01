package dev.korinetwork.networklock.core.config;

import dev.korinetwork.networklock.core.Keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves a configured key: a literal base64 value, {@code env:VARIABLE}, or
 * {@code file:/path/to/secret} (for container secret mounts).
 */
final class Secrets {

    private Secrets() {}

    static byte[] resolveKey(String configured, Map<String, String> env) {
        String value = configured == null ? "" : configured.trim();
        if (value.startsWith("env:")) {
            String name = value.substring(4);
            String fromEnv = env.get(name);
            if (fromEnv == null || fromEnv.isBlank()) {
                throw new IllegalArgumentException("environment variable " + name + " is not set");
            }
            value = fromEnv;
        } else if (value.startsWith("file:")) {
            Path file = Path.of(value.substring(5));
            try {
                value = Files.readString(file, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read key file " + file);
            }
        }
        return Keys.decode(value);
    }
}
