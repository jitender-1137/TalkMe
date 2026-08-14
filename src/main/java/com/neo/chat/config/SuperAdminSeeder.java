package com.neo.chat.config;

import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.repository.RoleRepository;
import com.neo.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Grants {@code ROLE_SUPER_ADMIN} on boot to the accounts listed in
 * {@code app.super-admin.emails} (env {@code SUPER_ADMIN_EMAILS}, comma-separated).
 * Idempotent: creates the role if missing and only adds it to a user who doesn't
 * already have it. There is deliberately NO public "promote" endpoint — elevation
 * happens only via this trusted, env-driven allow-list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminSeeder implements ApplicationRunner {

    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    @Value("${app.super-admin.emails:}")
    private String superAdminEmails;

    /**
     * Runs once at startup ({@link ApplicationRunner}, transactional): grants
     * {@code ROLE_SUPER_ADMIN} to each configured account. Creates the role if absent, parses and
     * de-duplicates the configured allow-list, and adds the role only to accounts that don't already
     * have it. Idempotent. Accounts not yet signed up are skipped and elevated on a later boot.
     * No-op when the allow-list is unset.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        if (superAdminEmails == null || superAdminEmails.isBlank()) {
            log.info("[SuperAdmin] SUPER_ADMIN_EMAILS not set — no super-admins seeded");
            return;
        }

        Role role = roleRepository.findByName(ROLE_SUPER_ADMIN)
                .orElseGet(() -> roleRepository.save(Role.builder().name(ROLE_SUPER_ADMIN).build()));

        List<String> emails = Arrays.stream(superAdminEmails.split(","))
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isBlank()).distinct().toList();

        for (String email : emails) {
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (user == null) {
                log.warn("[SuperAdmin] no account for '{}' yet — it will be elevated on next boot after signup", email);
                continue;
            }
            boolean has = user.getRoles().stream().anyMatch(r -> ROLE_SUPER_ADMIN.equals(r.getName()));
            if (!has) {
                user.getRoles().add(role);
                userRepository.save(user);
                log.info("[SuperAdmin] granted {} to {}", ROLE_SUPER_ADMIN, email);
            }
        }
    }
}
