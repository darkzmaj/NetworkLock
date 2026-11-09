package dev.korinetwork.networklock.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Removes the used credential from a Mojang {@code GameProfile} on servers whose profiles are
 * read-only (authlib 7: Spigot 1.21.9+, Sponge). It builds a copy of the profile without the
 * NetworkLock property and swaps it into the server objects that hold exactly the original
 * profile instance. Everything is reflective because authlib is not on NetworkLock's classpath;
 * if anything does not fit, the profile is left as it was (the credential is single-use).
 */
public final class ProfileScrubber {

    private ProfileScrubber() {}

    /** True when the profile still carries a NetworkLock credential. */
    public static boolean hasCredential(Object profile) throws ReflectiveOperationException {
        Object map = call(profile, "getProperties", "properties");
        return !((Collection<?>) map.getClass().getMethod("get", Object.class).invoke(map, ForwardingToken.PROPERTY)).isEmpty();
    }

    /**
     * Tries to drop the credential in place (mutable profiles), otherwise returns a copy without it.
     *
     * @return the same profile if it was changed in place, a new profile, or null if neither is possible
     */
    public static Object withoutCredential(Object profile) {
        try {
            Object map = call(profile, "getProperties", "properties");
            try {
                map.getClass().getMethod("removeAll", Object.class).invoke(map, ForwardingToken.PROPERTY);
                return profile;
            } catch (ReflectiveOperationException | RuntimeException readOnly) {
                // authlib 7: immutable map; build a copy below
            }
            ClassLoader loader = profile.getClass().getClassLoader();
            Class<?> multimapType = Class.forName("com.google.common.collect.Multimap", false, loader);
            Object copy = Class.forName("com.google.common.collect.LinkedHashMultimap", false, loader).getMethod("create").invoke(null);
            Method put = multimapType.getMethod("put", Object.class, Object.class);
            for (Object o : (Collection<?>) map.getClass().getMethod("entries").invoke(map)) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
                if (!ForwardingToken.PROPERTY.equals(entry.getKey())) put.invoke(copy, entry.getKey(), entry.getValue());
            }
            Object newMap = map.getClass().getConstructor(multimapType).newInstance(copy);
            Constructor<?> ctor = profile.getClass().getConstructor(UUID.class, String.class, map.getClass());
            return ctor.newInstance(call(profile, "getId", "id"), call(profile, "getName", "name"), newMap);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Sets every instance field of {@code holder} (and its superclasses) that currently holds
     * exactly {@code oldValue} to {@code newValue}.
     *
     * @return how many fields were replaced
     */
    public static int replaceReferences(Object holder, Object oldValue, Object newValue) {
        int replaced = 0;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isInstance(oldValue)) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(holder) == oldValue) {
                        f.set(holder, newValue);
                        replaced++;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // field not writable on this JVM/server: leave it
                }
            }
        }
        return replaced;
    }

    /** First non-null value of a field of {@code holder} whose class name ends with {@code suffix}. */
    public static Object findField(Object holder, String suffix) {
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v != null && v.getClass().getName().endsWith(suffix)) return v;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // not accessible: skip
                }
            }
        }
        return null;
    }

    /** Calls the old-style getter if present, otherwise the record-style accessor of newer authlib. */
    static Object call(Object target, String oldName, String newName) throws ReflectiveOperationException {
        Method m;
        try {
            m = target.getClass().getMethod(oldName);
        } catch (NoSuchMethodException e) {
            m = target.getClass().getMethod(newName);
        }
        return m.invoke(target);
    }
}
