package dev.korinetwork.networklock.core;

import java.util.logging.Logger;

/** {@link Log} over a platform-provided {@link java.util.logging.Logger}. */
public final class JulLog implements Log {

    private final Logger logger;

    public JulLog(Logger logger) {
        this.logger = logger;
    }

    public Logger logger() {
        return logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warning(message);
    }

    @Override
    public void error(String message) {
        logger.severe(message);
    }
}
