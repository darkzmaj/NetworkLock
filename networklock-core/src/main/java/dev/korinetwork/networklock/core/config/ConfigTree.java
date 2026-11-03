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
            throw new ConfigException(List.of(path.getFileName() + ": cannot read file (" + e.getClass().getSimpleName() + ")"));
        } catch (RuntimeException e) {
            // SnakeYAML messages quote surrounding text, which could include a secret; report position only.
            String where = e instanceof org.yaml.snakeyaml.error.MarkedYAMLException m && m.getProblemMark() != null
                    ? " at line " + (m.getProblemMark().getLine() + 1) : "";
            throw new ConfigException(List.of(path.getFileName() + ": invalid YAML" + where));
        }
        if (doc == null) doc = Map.of();
        if (!(doc instanceof Map<?, ?> map)) {
            throw new ConfigException(List.of(path.getFileName() + ": top level must be a mapping"));
        }
        return new ConfigTree(map, path.getFileName().toString(), new ArrayList<>());
    }

    ConfigTree child(Map<?, ?> map, String prefix) {
        return new ConfigTree(map, file + " (" + prefix + ")", errors);
    }

    Object raw(String path) {
        Object node = root;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> m)) return null;
            node = m.get(part);
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
        if (v instanceof List<?> lines) {
            List<String> parts = new ArrayList<>();
            for (Object line : lines) parts.add(String.valueOf(line));
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
        if (v instanceof Boolean b) return b;
        error(path, "must be true or false");
        return def;
    }

    public int integer(String path, int def, int min, int max) {
        Object v = raw(path);
        if (v == null) return def;
        if (v instanceof Integer i && i >= min && i <= max) return i;
        error(path, "must be a whole number between " + min + " and " + max);
        return def;
    }

    public List<?> list(String path) {
        Object v = raw(path);
        if (v == null) return List.of();
        if (v instanceof List<?> l) return l;
        error(path, "must be a list");
        return List.of();
    }

    public void error(String path, String message) {
        errors.add(file + ": '" + path + "' " + message);
    }

    public void throwIfErrors() throws ConfigException {
        if (!errors.isEmpty()) throw new ConfigException(List.copyOf(errors));
    }
}
