package com.neo.chat.config;

import com.neo.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time (idempotent) correction: guests must never be verified. An earlier bug
 * created guest accounts with {@code isVerified = true}; this un-verifies any such
 * rows on boot. Cheap no-op once the data is clean.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class GuestVerificationFixer implements ApplicationRunner {

    private final UserRepository userRepository;

    /**
     * Runs once at startup ({@link ApplicationRunner}, transactional): resets {@code isVerified} to
     * false on any guest account incorrectly marked verified, via
     * {@link UserRepository#unverifyAllGuests()}. Idempotent — a no-op once the data is clean.
     * Fail-open: any error is logged and swallowed so startup is never blocked.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        try {
            int fixed = userRepository.unverifyAllGuests();
            if (fixed > 0) {
                log.warn("[GuestFix] un-verified {} guest account(s) that were incorrectly marked verified", fixed);
            }
        } catch (Exception e) {
            log.warn("[GuestFix] guest un-verify pass failed: {}", e.getMessage());
        }
    }
}
