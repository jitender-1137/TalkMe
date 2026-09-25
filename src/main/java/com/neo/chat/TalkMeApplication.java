package com.neo.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point. {@link ConfigurationPropertiesScan} registers the immutable,
 * constructor-bound {@code @ConfigurationProperties} classes (which are deliberately NOT
 * {@code @Component}s — constructor binding is unavailable to component-scanned beans); classes
 * that are still {@code @Component} are skipped by the scan, so nothing is registered twice.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class TalkMeApplication {

    static void main(String[] args) {
        // Permanent fix for the jspawnhelper version-mismatch outage: after an in-place JDK
        // upgrade under a running JVM, the default POSIX_SPAWN mechanism execs the external
        // `jspawnhelper` binary, which refuses to run when its version no longer matches the
        // live JVM — so the app can no longer spawn ANY subprocess (ffmpeg muxing, frame
        // moderation, etc.). `vfork` does the fork+exec in-process and never touches that
        // helper, so future in-place JDK upgrades can't break process spawning.
        //
        // Set as the very first statement so it lands before java.lang.ProcessImpl is loaded
        // (its launch mechanism is read once, on first spawn). Only defaulted on Linux (where
        // vfork is supported — it would fail JVM init on macOS), and only when the operator
        // hasn't already set it, so a command-line -Djdk.lang.Process.launchMechanism=... wins.
        if (System.getProperty("jdk.lang.Process.launchMechanism") == null
                && System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            System.setProperty("jdk.lang.Process.launchMechanism", "vfork");
        }
        SpringApplication.run(TalkMeApplication.class, args);
    }

}
