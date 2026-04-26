package io.appfirewall.core.breaker;

/** State of a {@link CircuitBreaker}. */
public enum BreakerState {
    /** Normal operation; calls are allowed. */
    CLOSED,
    /** Threshold exceeded; calls denied until the cooldown elapses. */
    OPEN,
    /** Cooldown elapsed; one probe call has been allowed through. */
    HALF_OPEN
}
