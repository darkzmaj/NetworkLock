package dev.korinetwork.networklock.core;

/** Result of verifying one backend login. Every value except {@link #OK} denies the login. */
public enum Outcome {
    OK(null),
    MISSING(RejectCode.NOT_THROUGH_PROXY),
    MULTIPLE(RejectCode.INVALID),
    MALFORMED(RejectCode.INVALID),
    UNKNOWN_PROXY(RejectCode.INVALID),
    BAD_SIGNATURE(RejectCode.INVALID),
    WRONG_TARGET(RejectCode.INVALID),
    EXPIRED(RejectCode.EXPIRED),
    NOT_YET_VALID(RejectCode.EXPIRED),
    REPLAYED(RejectCode.REPLAYED),
    REPLAY_CACHE_FULL(RejectCode.UNAVAILABLE),
    LOCKDOWN(RejectCode.UNAVAILABLE),
    INTERNAL_ERROR(RejectCode.UNAVAILABLE);

    private final RejectCode code;

    Outcome(RejectCode code) {
        this.code = code;
    }

    public RejectCode code() {
        return code;
    }

    /**
     * The public failure classes. Backends kick with the fixed built-in text; proxies recognise
     * the {@code [NL-xx]} marker and substitute the matching {@code messages.yml} entry. Codes
     * deliberately carry no diagnostic detail.
     */
    public enum RejectCode {
        NOT_THROUGH_PROXY("NL-01", "authentication-failed"),
        INVALID("NL-02", "authentication-failed"),
        EXPIRED("NL-03", "authentication-timeout"),
        REPLAYED("NL-04", "authentication-failed"),
        UNAVAILABLE("NL-05", "security-error");

        public static final String BUILT_IN_TEXT = "Unable to verify your connection. Please join through the network.";

        private final String id;
        private final String messageKey;

        RejectCode(String id, String messageKey) {
            this.id = id;
            this.messageKey = messageKey;
        }

        public String id() {
            return id;
        }

        public String messageKey() {
            return messageKey;
        }

        /** The generic, non-configurable text a backend sends. */
        public String backendMessage() {
            return BUILT_IN_TEXT + " [" + id + "]";
        }

        /** Finds the code a backend embedded in a kick reason, or null. */
        public static RejectCode fromKickText(String plain) {
            if (plain == null || !plain.contains("[NL-")) return null;
            for (RejectCode c : values()) {
                if (plain.endsWith("[" + c.id + "]") && plain.startsWith(BUILT_IN_TEXT)) return c;
            }
            return null;
        }
    }
}
