package io.appfirewall.core.classifier;

/**
 * Outcome of a 404-path classification.
 *
 * <p>Wire-compatible with the FastAPI SDK's {@code Classification} literal
 * (string values: {@code "scanner"}, {@code "benign-miss"}, {@code "unknown"}).
 * The serialized form is the lowercase hyphenated string returned by
 * {@link #wire()}.
 */
public enum Classification {
    SCANNER("scanner"),
    BENIGN_MISS("benign-miss"),
    UNKNOWN("unknown");

    private final String wire;

    Classification(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
