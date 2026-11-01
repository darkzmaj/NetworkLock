package dev.korinetwork.networklock.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity a proxy forwards to a backend, as both sides observe it.
 *
 * @param uuid       forwarded player UUID
 * @param name       login username
 * @param address    normalized client address ({@link Addresses#normalize})
 * @param properties forwarded profile properties, excluding the NetworkLock token itself
 */
public record PlayerIdentity(UUID uuid, String name, String address, List<Property> properties) {

    public PlayerIdentity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
        properties = List.copyOf(properties);
    }

    /** A game-profile property. A missing signature is represented as an empty string. */
    public record Property(String name, String value, String signature) {
        public Property {
            Objects.requireNonNull(name, "name");
            value = value == null ? "" : value;
            signature = signature == null ? "" : signature;
        }
    }
}
