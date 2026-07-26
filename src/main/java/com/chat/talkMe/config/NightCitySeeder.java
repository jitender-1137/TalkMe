package com.chat.talkMe.config;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.enums.CityLocation;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seeds one curated ROOM {@link Chat} per Virtual Night City district (feature #25) on boot.
 * Idempotent: {@link ChatRepository#existsByCityLocationAndRoomCuratedTrue(CityLocation)} guards
 * each district, so re-runs are no-ops and existing rooms are never duplicated.
 *
 * <p>Rooms are created through {@link GroupService#createGroup} (subtype "room" ⇒ PUBLIC / OPEN /
 * allow-non-friends, with the OWNER member row wired up for us), then stamped with
 * {@code cityLocation} + {@code roomCurated} and saved. The room OWNER is a "system" host resolved
 * from {@code app.super-admin.emails} (falling back to the most-recent real account). If no such
 * account exists yet on a fresh install, seeding is skipped and retried on the next boot — mirroring
 * {@link SuperAdminSeeder}'s "elevated on next boot" behaviour.
 *
 * <p>NOTE: {@code run()} is deliberately NOT {@code @Transactional}. Each {@code createGroup} owns its
 * own transaction, so one district failing to seed can't mark a shared transaction rollback-only and
 * poison the rest — every district is attempted independently.
 */
@Slf4j
@Component
@Order(55)
@RequiredArgsConstructor
public class NightCitySeeder implements ApplicationRunner {

    private static final String ROOM_CATEGORY = "Virtual City";

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final GroupService groupService;

    @Value("${app.super-admin.emails:}")
    private String superAdminEmails;

    @Override
    public void run(ApplicationArguments args) {
        User host = resolveHost();
        if (host == null) {
            log.warn("[NightCity] no host account available yet — city rooms will be seeded on a later boot");
            return;
        }

        int created = 0, existing = 0;
        for (CityLocation loc : CityLocation.values()) {
            try {
                if (chatRepository.existsByCityLocationAndRoomCuratedTrue(loc)) {
                    existing++;
                    continue;
                }
                seedRoom(loc, host);
                created++;
            } catch (Exception e) {
                log.warn("[NightCity] failed to seed room for district {}: {}", loc.getSlug(), e.getMessage());
            }
        }
        log.info("[NightCity] seeded city rooms — {} created, {} already present (host={})",
                created, existing, host.getUsername());
    }

    private void seedRoom(CityLocation loc, User host) {
        CreateGroupRequest req = new CreateGroupRequest();
        req.setName(loc.getLabel());
        req.setDescription(loc.getTagline());
        req.setSubtype("room");
        req.setVisibility("PUBLIC");
        req.setCategory(ROOM_CATEGORY);
        req.setAllowNonFriends(true);
        req.setAllowExplicitContent(false);

        ChatResponse cr = groupService.createGroup(req, host);

        Chat chat = chatRepository.findByUuid(UUID.fromString(cr.getId()))
                .orElseThrow(() -> new IllegalStateException("created room vanished: " + cr.getId()));
        chat.setCityLocation(loc);
        chat.setRoomCurated(true);
        chatRepository.save(chat);
    }

    /** First configured super-admin email, else the most-recently-joined real account, else null. */
    private User resolveHost() {
        if (superAdminEmails != null && !superAdminEmails.isBlank()) {
            for (String raw : superAdminEmails.split(",")) {
                String email = raw.trim().toLowerCase();
                if (email.isBlank()) continue;
                User u = userRepository.findByEmailIgnoreCase(email).orElse(null);
                if (u != null) return u;
            }
        }
        List<User> fallback = userRepository
                .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(PageRequest.of(0, 1));
        return fallback.isEmpty() ? null : fallback.get(0);
    }
}
