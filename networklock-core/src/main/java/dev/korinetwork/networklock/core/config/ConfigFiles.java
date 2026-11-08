package dev.korinetwork.networklock.core.config;

import dev.korinetwork.networklock.core.Keys;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates the two user-facing files and the proxy key, never overwriting what an administrator wrote. */
public final class ConfigFiles {

    public static final String CONFIG = "config.yml";
    public static final String MESSAGES = "messages.yml";

    /** Matches only the unset key line in the shipped proxy template. */
    private static final Pattern EMPTY_KEY = Pattern.compile("(?m)^(\\s*key:[ \\t]*)\"\"[ \\t]*$");

    private ConfigFiles() {}

    /** Copies {@code /defaults/<side>/<name>} into the data folder when the file does not exist yet. */
    public static Path ensure(Path dataDir, String side, String name) throws IOException {
        Files.createDirectories(dataDir);
        Path target = dataDir.resolve(name);
        if (Files.exists(target)) return target;
        try (InputStream in = ConfigFiles.class.getResourceAsStream("/defaults/" + side + "/" + name)) {
            if (in == null) throw new IOException("missing bundled default " + side + "/" + name);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) != -1; ) buf.write(chunk, 0, n);
            writeAtomically(target, new String(buf.toByteArray(), StandardCharsets.UTF_8));
        }
        restrictPermissions(target);
        return target;
    }

    /**
     * Writes a freshly generated key into a proxy config whose {@code security.key} is still
     * the empty template value. Returns true if a key was generated. Existing keys, env/file
     * references and edited files are left untouched.
     */
    public static boolean generateProxyKeyIfUnset(Path config) throws IOException {
        String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
        Matcher m = EMPTY_KEY.matcher(text);
        if (!m.find()) return false;
        String updated = text.substring(0, m.start()) + m.group(1) + "\"" + Keys.generate() + "\"" + text.substring(m.end());
        writeAtomically(config, updated);
        restrictPermissions(config);
        return true;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), ".networklock", ".tmp");
        try {
            restrictPermissions(tmp);
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Owner read/write only, where the filesystem supports POSIX permissions. */
    static void restrictPermissions(Path file) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort: some mounts (e.g. container volumes) reject chmod
        }
    }

    /** True if group or others can read a file holding secrets (POSIX only). */
    public static boolean isWorldOrGroupReadable(Path file) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return false;
        try {
            String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
            return perms.charAt(3) == 'r' || perms.charAt(6) == 'r';
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
