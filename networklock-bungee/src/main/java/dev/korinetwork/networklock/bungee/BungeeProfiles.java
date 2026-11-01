package dev.korinetwork.networklock.bungee;

import dev.korinetwork.networklock.core.PlayerIdentity;
import net.md_5.bungee.api.connection.PendingConnection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place NetworkLock touches BungeeCord internals. The public API offers no way to
 * change the properties the proxy forwards in the legacy handshake, which BungeeCord reads
 * from {@code InitialHandler#loginProfile} (a {@code LoginResult}). Handles are resolved
 * once at startup; if any is missing the proxy enters lockdown instead of half-working.
 */
final class BungeeProfiles {

    private final Field loginProfileField;
    private final Method getProperties;
    private final Method setProperties;
    private final Constructor<?> loginResultCtor;
    private final Class<?> propertyClass;
    private final Constructor<?> propertyCtor;
    private final Method propName;
    private final Method propValue;
    private final Method propSignature;

    BungeeProfiles() throws ReflectiveOperationException {
        Class<?> handler = Class.forName("net.md_5.bungee.connection.InitialHandler");
        loginProfileField = handler.getDeclaredField("loginProfile");
        loginProfileField.setAccessible(true);
        Class<?> loginResult = loginProfileField.getType();
        getProperties = loginResult.getMethod("getProperties");
        propertyClass = getProperties.getReturnType().getComponentType();
        Class<?> propertyArray = getProperties.getReturnType();
        setProperties = loginResult.getMethod("setProperties", propertyArray);
        loginResultCtor = loginResult.getConstructor(String.class, String.class, propertyArray);
        propertyCtor = propertyClass.getConstructor(String.class, String.class, String.class);
        propName = propertyClass.getMethod("getName");
        propValue = propertyClass.getMethod("getValue");
        propSignature = propertyClass.getMethod("getSignature");
    }

    /** Current forwarded properties (empty for offline-mode logins). */
    List<PlayerIdentity.Property> read(PendingConnection connection) throws ReflectiveOperationException {
        Object profile = loginProfileField.get(connection);
        List<PlayerIdentity.Property> out = new ArrayList<>();
        if (profile == null) return out;
        Object array = getProperties.invoke(profile);
        if (array == null) return out;
        for (int i = 0; i < Array.getLength(array); i++) {
            Object p = Array.get(array, i);
            out.add(new PlayerIdentity.Property((String) propName.invoke(p), (String) propValue.invoke(p),
                    (String) propSignature.invoke(p)));
        }
        return out;
    }

    /** Replaces the forwarded properties, creating the profile for offline-mode logins. */
    void write(PendingConnection connection, UUID uuid, String name, List<PlayerIdentity.Property> props)
            throws ReflectiveOperationException {
        Object array = Array.newInstance(propertyClass, props.size());
        for (int i = 0; i < props.size(); i++) {
            PlayerIdentity.Property p = props.get(i);
            Array.set(array, i, propertyCtor.newInstance(p.name(), p.value(), p.signature().isEmpty() ? null : p.signature()));
        }
        Object profile = loginProfileField.get(connection);
        if (profile == null) {
            loginProfileField.set(connection, loginResultCtor.newInstance(uuid.toString().replace("-", ""), name, array));
        } else {
            setProperties.invoke(profile, array);
        }
    }
}
