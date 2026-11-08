package dev.korinetwork.networklock.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity a proxy forwards to a backend, as both sides observe it.
 *
 * uuid: forwarded player UUID; name: login username; address: normalized client address
 * ({@link Addresses#normalize}); properties: forwarded profile properties, excluding the NetworkLock token itself.
 */
public final class PlayerIdentity {

    private final UUID uuid;
    private final String name;
    private final String address;
    private final List<Property> properties;

    public PlayerIdentity(UUID uuid, String name, String address, List<Property> properties) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = Objects.requireNonNull(name, "name");
        this.address = Objects.requireNonNull(address, "address");
        List<Property> copy = new ArrayList<>(properties);
        for (Property p : copy) Objects.requireNonNull(p); // List.copyOf semantics: no null elements
        this.properties = Collections.unmodifiableList(copy);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public List<Property> properties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PlayerIdentity)) return false;
        PlayerIdentity p = (PlayerIdentity) o;
        return uuid.equals(p.uuid) && name.equals(p.name) && address.equals(p.address) && properties.equals(p.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, name, address, properties);
    }

    /** A game-profile property. A missing signature is represented as an empty string. */
    public static final class Property {

        private final String name;
        private final String value;
        private final String signature;

        public Property(String name, String value, String signature) {
            this.name = Objects.requireNonNull(name, "name");
            this.value = value == null ? "" : value;
            this.signature = signature == null ? "" : signature;
        }

        public String name() {
            return name;
        }

        public String value() {
            return value;
        }

        public String signature() {
            return signature;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Property)) return false;
            Property p = (Property) o;
            return name.equals(p.name) && value.equals(p.value) && signature.equals(p.signature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value, signature);
        }
    }
}
