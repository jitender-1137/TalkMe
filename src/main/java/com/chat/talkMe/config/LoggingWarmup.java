package com.chat.talkMe.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Eagerly loads logback's throwable-rendering classes at startup.
 *
 * The app runs as a Spring Boot fat jar, which loads classes LAZILY from nested
 * jars. {@code ch.qos.logback.classic.spi.ThrowableProxy} (and friends) are only
 * needed the first time a stack trace is logged — which in production is often when
 * Tomcat logs a socket error (e.g. a scanner bot disconnecting). If the jar on disk
 * was replaced/redeployed WHILE the JVM was still running, that first lazy load
 * fails with {@code NoClassDefFoundError: .../ThrowableProxy}, killing the Tomcat
 * worker thread (seen in prod as "Exception in thread http-nio-...").
 *
 * Loading these classes now — while the jar is healthy — makes them resident for the
 * life of the JVM, so a later on-disk jar swap can't break exception logging. This is
 * defence-in-depth: the real fix is to deploy WITHOUT overwriting the running jar
 * (ship a versioned jar / fresh path, then restart the service).
 */
@Slf4j
@Component
public class LoggingWarmup {

    private static final String[] THROWABLE_CLASSES = {
            // Model classes built when a LoggingEvent captures a throwable.
            "ch.qos.logback.classic.spi.ThrowableProxy",
            "ch.qos.logback.classic.spi.ThrowableProxyVO",
            "ch.qos.logback.classic.spi.IThrowableProxy",
            "ch.qos.logback.classic.spi.StackTraceElementProxy",
            "ch.qos.logback.classic.spi.PackagingDataCalculator",
            "ch.qos.logback.classic.spi.ThrowableProxyUtil",
            // Pattern converters that render the stack trace ("%ex"/"%throwable").
            // These lazy-load only when an exception is first formatted through an
            // appender, so eager-load them too — same class-swap failure mode.
            "ch.qos.logback.classic.pattern.ThrowableProxyConverter",
            "ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter",
            "ch.qos.logback.classic.pattern.RootCauseFirstThrowableProxyConverter",
            "ch.qos.logback.core.CoreConstants",
    };

    @PostConstruct
    void warmUp() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String name : THROWABLE_CLASSES) {
            try {
                Class.forName(name, true, cl);
            } catch (Throwable ignored) {
                // Never fail startup over a warmup; if a class truly isn't present the
                // app just behaves as before.
            }
        }
        // NOTE: do NOT log a throwable here to "exercise" the path — that prints a full
        // (harmless) stack trace whenever DEBUG is enabled, which reads as a startup error.
        // The Class.forName loads above are sufficient: once a class is resident it can't
        // NoClassDefFoundError later, even if the running jar file is swapped in place.
        log.debug("Logging warmup complete");
    }
}
