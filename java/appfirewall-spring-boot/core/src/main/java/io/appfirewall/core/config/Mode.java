package io.appfirewall.core.config;

/** Operating mode for the SDK. Mirrors the FastAPI {@code mode} setting. */
public enum Mode {
    /** Ship events to the AppFirewall ingest service. Production default. */
    SHIP,
    /** Write events to a local JSONL file instead of shipping. Dev/eval. */
    LOCAL,
    /** Disable everything; no observation, no shipping. */
    OFF
}
