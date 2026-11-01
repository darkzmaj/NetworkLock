package dev.korinetwork.networklock.core.config;

import java.util.List;

/** One or more configuration problems. Messages never contain secret values. */
public final class ConfigException extends Exception {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = problems;
    }

    public List<String> problems() {
        return problems;
    }
}
