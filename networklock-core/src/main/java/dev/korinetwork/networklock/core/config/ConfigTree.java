package dev.korinetwork.networklock.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Typed, path-based access to a parsed YAML document. Problems are collected rather than
 * thrown one at a time, so an administrator sees every mistake from a single reload.
 */
public final class ConfigTree {

    private final Map<?, ?> root;
    private final String file;
    final List<String> errors;

    private ConfigTree(Map<?, ?> root, String file, List<String> errors) {
        this.root = root;
        this.file = file;
        this.errors = errors;
    }

    public static ConfigTree load(Path path) throws ConfigException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(1024 * 1024);
        Object doc;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            doc = new Yaml(new SafeConstructor(options)).load(r);
        } catch (IOException e) {
            throw new ConfigException(Collections.singletonList(path.getFileName() + ": cannot read file (" + e.getClass().getSimpleName() + ")"));
        } catch (RuntimeException e) {
            // SnakeYAML messages quote surrounding text, which could include a secret; report position only.
            String where = e instanceof org.yaml.snakeyaml.error.MarkedYAMLException
                    && ((org.yaml.snakeyaml.error.MarkedYAMLException) e).getProblemMark() != null
                    ? " at line " + (((org.yaml.snakeyaml.error.MarkedYAMLException) e).getProblemMark().getLine() + 1) : "";
            throw new ConfigException(Collections.singletonList(path.getFileName() + ": invalid YAML" + where));
        }
        if (doc == null) doc = Collections.emptyMap();
        if (!(doc instanceof Map<?, ?>)) {
            throw new ConfigException(Collections.singletonList(path.getFileName() + ": top level must be a mapping"));
        }
        return new ConfigTree((Map<?, ?>) doc, path.getFileName().toString(), new ArrayList<>());
    }

    ConfigTree child(Map<?, ?> map, String prefix) {
        return new ConfigTree(map, file + " (" + prefix + ")", errors);
    }

    Object raw(String path) {
        Object node = root;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?>)) return null;
            node = ((Map<?, ?>) node).get(part);
        }
        return node;
    }

    public String string(String path, String def) {
        Object v = raw(path);
        if (v == null) return def;
        if (v instanceof Map || v instanceof List) {
            error(path, "must be a single value");
            return def;
        }
        return String.valueOf(v);
    }

    /** A message: a single string, or a list of strings joined into lines. Null when absent. */
    public String text(String path, String def) {
        Object v = raw(path);
        if (v == null) return def;
        String s;
        if (v instanceof List<?>) {
            List<String> parts = new ArrayList<>();
            for (Object line : (List<?>) v) parts.add(String.valueOf(line));
            s = String.join("\n", parts);
        } else if (v instanceof Map) {
            error(path, "must be text or a list of lines");
            return def;
        } else {
            s = String.valueOf(v);
        }
        if (s.length() > 2048) {
            error(path, "is too long (max 2048 characters)");
            return def;
        }
        return s;
    }

    public boolean bool(String path, boolean def) {
        Object v = raw(path);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        error(path, "must be true or false");
        return def;
    }

    public int integer(String path, int def, int min, int max) {
        Object v = raw(path);
        if (v == null) return def;
        if (v instanceof Integer && (Integer) v >= min && (Integer) v <= max) return (Integer) v;
        error(path, "must be a whole number between " + min + " and " + max);
        return def;
    }

    public List<?> list(String path) {
        Object v = raw(path);
        if (v == null) return Collections.emptyList();
        if (v instanceof List<?>) return (List<?>) v;
        error(path, "must be a list");
        return Collections.emptyList();
    }

    public void error(String path, String message) {
        errors.add(file + ": '" + path + "' " + message);
    }

    public void throwIfErrors() throws ConfigException {
        if (!errors.isEmpty()) throw new ConfigException(Collections.unmodifiableList(new ArrayList<>(errors)));
    }
}
