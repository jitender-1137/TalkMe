package com.chat.talkMe.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects disposable / temporary email addresses so the mail layer can refuse to send to
 * them — even if the user "verified" (many throwaway inboxes let you read the verify link).
 * This stops the provider quota being burned on addresses we never want to reach.
 *
 * <p>The blocklist is loaded from the bundled {@code disposable-email-domains.txt} resource
 * (one domain per line, {@code #} comments allowed) and can be extended without editing the
 * file via {@code app.mail.disposable-domains} (comma-separated). Matching is exact on the
 * address domain and on any parent domain (so {@code x.mailinator.com} matches
 * {@code mailinator.com}).</p>
 */
@Slf4j
@Component
public class DisposableEmailDomains {

    private static final String RESOURCE = "disposable-email-domains.txt";

    private final Set<String> domains = new HashSet<>();

    @Value("${app.mail.disposable-domains:}")
    private String extraCsv;

    @PostConstruct
    void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                        .map(l -> l.trim().toLowerCase())
                        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .forEach(domains::add);
            } else {
                log.warn("[Mail] {} not found on classpath — disposable blocklist is empty", RESOURCE);
            }
        } catch (Exception e) {
            log.warn("[Mail] failed to load disposable-domain blocklist: {}", e.getMessage());
        }
        if (extraCsv != null && !extraCsv.isBlank()) {
            Arrays.stream(extraCsv.split(","))
                    .map(s -> s.trim().toLowerCase())
                    .filter(s -> !s.isEmpty())
                    .forEach(domains::add);
        }
        log.info("[Mail] disposable-email blocklist loaded: {} domains", domains.size());
    }

    /** True if the address's domain (or a parent domain) is a known disposable provider. */
    public boolean isDisposable(String email) {
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase();
        if (domain.isEmpty()) {
            return false;
        }
        if (domains.contains(domain)) {
            return true;
        }
        // Parent-domain match: sub.mailinator.com → mailinator.com.
        for (int dot = domain.indexOf('.'); dot >= 0; dot = domain.indexOf('.', dot + 1)) {
            if (domains.contains(domain.substring(dot + 1))) {
                return true;
            }
        }
        return false;
    }
}
