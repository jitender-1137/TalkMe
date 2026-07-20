package com.chat.talkMe.util;

import org.slf4j.Logger;

/**
 * Log-hygiene helper for scheduled background reapers (presence, lobby, match).
 *
 * A transient Redis/DB blip — e.g. a laptop waking from sleep with stale pooled
 * connections, or a brief network hiccup — should not dump a full stack trace on
 * every reaper tick (which fires as often as every 1s). Those failures self-heal
 * once the connection pool reconnects, so we log a single concise WARN for them and
 * reserve full ERROR stack traces for genuine, actionable bugs.
 */
public final class BackgroundTaskErrors {

    private BackgroundTaskErrors() {}

    /** True when the throwable (or any cause) is a transient infra connectivity/timeout blip. */
    public static boolean isTransientInfra(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String name = c.getClass().getName();
            if (name.contains("RedisCommandTimeoutException")
                    || name.contains("RedisConnectionException")
                    || name.contains("RedisConnectionFailureException")
                    || name.contains("QueryTimeoutException")
                    || name.contains("DataAccessResourceFailureException")
                    || name.contains("RedisSystemException")
                    || name.contains("SocketException")
                    || name.contains("ConnectException")) {
                return true;
            }
            if (c == c.getCause()) break; // guard against self-referential cause loops
        }
        return false;
    }

    /**
     * Log a reaper failure: a quiet one-line WARN for transient infra blips (self-healing),
     * a full ERROR stack trace for anything else.
     */
    public static void log(Logger log, String label, Throwable t) {
        if (isTransientInfra(t)) {
            log.warn("{}: skipped this run — transient backend issue: {}", label, rootMessage(t));
        } else {
            log.error("{} failed", label, t);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}
