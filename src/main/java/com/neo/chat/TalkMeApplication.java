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
        SpringApplication.run(TalkMeApplication.class, args);
    }

}
