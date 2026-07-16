package com.chat.talkMe.service.impl;

import com.chat.talkMe.crypto.MessageCryptoService;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageAttachment;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AdminChatView;
import com.chat.talkMe.dto.response.AdminMessageView;
import com.chat.talkMe.dto.response.AdminStatsResponse;
import com.chat.talkMe.dto.response.AdminUserView;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.AdminService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final PresenceService presenceService;
    private final MessageCryptoService messageCryptoService;
    private final com.chat.talkMe.mapper.MessageMapper messageMapper;
    private final com.chat.talkMe.repository.RoleRepository roleRepository;
    private final com.chat.talkMe.repository.AdminAuditLogRepository auditRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    // ── Analytics-only dependencies ───────────────────────────────────────────
    private final com.chat.talkMe.repository.MessageAttachmentRepository attachmentRepository;
    private final com.chat.talkMe.repository.PostRepository postRepository;
    private final com.chat.talkMe.repository.StoryRepository storyRepository;
    private final com.chat.talkMe.repository.ProfileViewRepository profileViewRepository;
    private final com.chat.talkMe.repository.MatchReportRepository matchReportRepository;
    private final com.chat.talkMe.repository.UserFollowRepository userFollowRepository;
    private final com.chat.talkMe.repository.FriendRepository friendRepository;
    private final com.chat.talkMe.repository.FriendRequestRepository friendRequestRepository;
    private final com.chat.talkMe.repository.MessageReactionRepository reactionRepository;
    private final com.chat.talkMe.repository.PostLikeRepository postLikeRepository;
    private final com.chat.talkMe.repository.PostCommentRepository postCommentRepository;
    private final com.chat.talkMe.repository.UserSettingRepository userSettingRepository;
    private final com.chat.talkMe.repository.UserPresenceRepository userPresenceRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Redis-backed read-through cache for the expensive analytics aggregates, so the
     * dashboard's auto-refresh polling doesn't re-run a dozen GROUP BY / COUNT queries
     * against Postgres every few seconds. Short TTLs keep it near-real-time; a Redis
     * outage transparently falls back to the live DB query.
     */
    /** Namespace cache keys with a generation counter so one INCR invalidates ALL of them. */
    private String genKey(String base) {
        String gen = null;
        try { gen = redisTemplate.opsForValue().get("admin:cachegen"); } catch (Exception ignored) {}
        return "admin:g" + (gen == null ? "0" : gen) + ":" + base;
    }

    /** Bump the generation → every cached analytics/stats/timeseries value is instantly stale. */
    private void bumpCacheGen() {
        try { redisTemplate.opsForValue().increment("admin:cachegen"); }
        catch (Exception e) { log.debug("[AdminCache] gen bump failed: {}", e.getMessage()); }
    }

    private <T> T cached(String key, long ttlSeconds, Class<T> type, java.util.function.Supplier<T> loader) {
        try {
            String hit = redisTemplate.opsForValue().get(key);
            if (hit != null) return objectMapper.readValue(hit, type);
        } catch (Exception e) {
            log.debug("[AdminCache] read miss/err for {}: {}", key, e.getMessage());
        }
        T value = loader.get();
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("[AdminCache] write failed for {}: {}", key, e.getMessage());
        }
        return value;
    }

    /** Roles an admin may grant/revoke from the dashboard. */
    private static final java.util.Set<String> ASSIGNABLE_ROLES =
            java.util.Set.of("ROLE_SUPER_ADMIN", "ROLE_MODERATOR", "ROLE_USER");

    @Override
    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        return cached(genKey("stats"), 15, AdminStatsResponse.class, this::computeStats);
    }

    private AdminStatsResponse computeStats() {
        Instant now = Instant.now();
        return AdminStatsResponse.builder()
                .totalUsers(userRepository.count())
                .verifiedUsers(userRepository.countByIsVerifiedTrue())
                .guestUsers(userRepository.countByIsGuestTrue())
                .newUsersLast7d(userRepository.countByCreatedAtAfter(now.minus(7, ChronoUnit.DAYS)))
                .newUsersLast24h(userRepository.countByCreatedAtAfter(now.minus(24, ChronoUnit.HOURS)))
                .onlineNow(presenceService.getOnlineUsernames().size())
                .totalChats(chatRepository.count())
                .totalMessages(messageRepository.count())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<AdminUserView> listUsers(com.chat.talkMe.dto.request.AdminUserFilter filter, int page, int size) {
        com.chat.talkMe.dto.request.AdminUserFilter f =
                filter != null ? filter : new com.chat.talkMe.dto.request.AdminUserFilter();

        // Sort — whitelist the sortable columns to avoid injection into the property path.
        String sortField = switch (f.getSort() == null ? "" : f.getSort()) {
            case "updatedAt" -> "updatedAt";
            case "username" -> "username";
            case "age" -> "age";
            default -> "createdAt";
        };
        Sort.Direction dir = "asc".equalsIgnoreCase(f.getDir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(dir, sortField));

        Page<User> result = userRepository.findAll(buildUserSpec(f), pageable);

        Set<String> online = presenceService.getOnlineUsernames();
        Set<String> away = presenceService.getAwayUsernames();
        List<AdminUserView> items = result.getContent().stream()
                .map(u -> toView(u, online, away, false))
                .collect(Collectors.toList());

        return PaginatedResponse.<AdminUserView>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(result.hasNext() ? String.valueOf(page + 1) : null)
                        .hasNext(result.hasNext())
                        .hasPrevious(result.hasPrevious())
                        .total(result.getTotalElements())
                        .build())
                .build();
    }

    /** Translate the filter DTO into a JPA Specification over User. */
    private org.springframework.data.jpa.domain.Specification<User> buildUserSpec(
            com.chat.talkMe.dto.request.AdminUserFilter f) {
        return (root, cq, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> ps = new java.util.ArrayList<>();

            if (f.getQuery() != null && !f.getQuery().isBlank()) {
                String like = "%" + f.getQuery().trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("email")), like)));
            }
            if (f.getVerified() != null) ps.add(cb.equal(root.get("isVerified"), f.getVerified()));
            if (f.getGuest() != null) ps.add(cb.equal(root.get("isGuest"), f.getGuest()));
            if (f.getBanned() != null) ps.add(cb.equal(root.get("banned"), f.getBanned()));
            if (f.getDeleted() != null) ps.add(cb.equal(root.get("isDeleted"), f.getDeleted()));
            if (f.getGender() != null && !f.getGender().isBlank())
                ps.add(cb.equal(cb.lower(root.get("gender")), f.getGender().trim().toLowerCase()));
            if (f.getCountries() != null && !f.getCountries().isBlank()) {
                java.util.List<String> wanted = java.util.Arrays.stream(f.getCountries().split("\\|"))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
                if (!wanted.isEmpty()) ps.add(cb.lower(root.get("country")).in(wanted));
            }
            if (f.getMinAge() != null) ps.add(cb.ge(root.get("age"), f.getMinAge()));
            if (f.getMaxAge() != null) ps.add(cb.le(root.get("age"), f.getMaxAge()));

            Instant ca = parseFilterInstant(f.getCreatedAfter(), false);
            Instant cb2 = parseFilterInstant(f.getCreatedBefore(), true);
            Instant ua = parseFilterInstant(f.getUpdatedAfter(), false);
            Instant ub = parseFilterInstant(f.getUpdatedBefore(), true);
            if (ca != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), ca));
            if (cb2 != null) ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), cb2));
            if (ua != null) ps.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), ua));
            if (ub != null) ps.add(cb.lessThanOrEqualTo(root.get("updatedAt"), ub));

            if (f.getRole() != null && !f.getRole().isBlank()) {
                cq.distinct(true);
                var roleJoin = root.join("roles", jakarta.persistence.criteria.JoinType.INNER);
                ps.add(cb.equal(roleJoin.get("name"), f.getRole().trim().toUpperCase()));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /** Parse yyyy-MM-dd or an ISO instant. endOfDay=true pushes a bare date to 23:59:59. */
    private Instant parseFilterInstant(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try { return Instant.parse(v); } catch (Exception ignored) {}
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(v);
            return (endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay())
                    .toInstant(java.time.ZoneOffset.UTC);
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserView getUser(String uuid) {
        User u = userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
        AdminUserView view = toView(u, presenceService.getOnlineUsernames(), presenceService.getAwayUsernames(), true);
        view.setChatCount((long) chatRepository.findChatsByUser(u).size());
        view.setMessageCount(messageRepository.countBySenderId(u.getId()));
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public com.chat.talkMe.dto.response.AdminUserFullView getUserFull(String uuid) {
        User u = userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));

        java.util.Map<String, Object> account = new java.util.LinkedHashMap<>();
        account.put("uuid", u.getUuid() != null ? u.getUuid().toString() : null);
        account.put("id", u.getId());
        account.put("username", u.getUsername());
        account.put("name", u.getName());
        account.put("email", u.getEmail());
        account.put("mobileNumber", u.getMobileNumber());
        account.put("passwordSet", u.getPasswordHash() != null && !u.getPasswordHash().isBlank());
        account.put("googleLinked", u.getGoogleId() != null && !u.getGoogleId().isBlank());
        account.put("age", u.getAge());
        account.put("gender", u.getGender());
        account.put("country", u.getCountry());
        account.put("city", u.getCity());
        account.put("bio", u.getBio());
        account.put("occupation", u.getOccupation());
        account.put("education", u.getEducation());
        account.put("profileImage", u.getProfileImage());
        account.put("interests", u.getInterests() != null
                ? u.getInterests().stream().map(Enum::name).sorted().collect(Collectors.toList()) : List.of());
        account.put("roles", u.getRoles() != null
                ? u.getRoles().stream().map(Role::getName).sorted().collect(Collectors.toList()) : List.of());
        account.put("isGuest", u.isGuest());
        account.put("isVerified", u.isVerified());
        account.put("banned", u.isBanned());
        account.put("isDeleted", u.isDeleted());
        account.put("installationType", u.getInstallationType() != null ? u.getInstallationType().name() : null);
        account.put("totalUnreadCount", u.getTotalUnreadCount());
        account.put("onlineSortWeight", u.getOnlineSortWeight());
        account.put("presenceLastSeenAt", u.getPresenceLastSeenAt() != null ? u.getPresenceLastSeenAt().toString() : null);
        account.put("deletionRequestedAt", u.getDeletionRequestedAt() != null ? u.getDeletionRequestedAt().toString() : null);
        account.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        account.put("updatedAt", u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : null);

        java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>();
        userSettingRepository.findByUser(u).ifPresentOrElse(s -> {
            settings.put("theme", s.getTheme());
            settings.put("language", s.getLanguage());
            settings.put("notificationsEnabled", s.isNotificationsEnabled());
            settings.put("soundEnabled", s.isSoundEnabled());
            settings.put("safeModeEnabled", s.isSafeModeEnabled());
            settings.put("messagingPrivacy", s.getMessagingPrivacy() != null ? s.getMessagingPrivacy().name() : null);
            settings.put("emailLoginAlerts", s.isEmailLoginAlerts());
            settings.put("emailUnreadMessages", s.isEmailUnreadMessages());
            settings.put("emailAnnouncements", s.isEmailAnnouncements());
        }, () -> settings.put("_note", "No settings row — user is on defaults"));

        java.util.Map<String, Object> presence = new java.util.LinkedHashMap<>();
        userPresenceRepository.findByUser(u).ifPresentOrElse(p -> {
            presence.put("status", p.getStatus());
            presence.put("lastSeenAt", p.getLastSeenAt() != null ? p.getLastSeenAt().toString() : null);
            presence.put("ghostModeEnabled", p.isGhostModeEnabled());
            presence.put("invisibleModeEnabled", p.isInvisibleModeEnabled());
            presence.put("hideLastSeenEnabled", p.isHideLastSeenEnabled());
        }, () -> presence.put("_note", "No presence row yet"));

        return com.chat.talkMe.dto.response.AdminUserFullView.builder()
                .account(account).settings(settings).presence(presence).build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminChatView> getUserChats(String uuid) {
        User u = userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
        return chatRepository.findChatsByUser(u).stream().map(this::toChatView).collect(Collectors.toList());
    }

    @Override
    @Transactional // NOT readOnly: this writes an admin audit row (auditRepository.save)
    public PaginatedResponse<AdminMessageView> getChatMessages(String chatUuid, int page, int size, String adminUsername) {
        Chat chat = chatRepository.findByUuidWithMembers(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // Access trail: record who read this chat's decrypted contents (log + DB).
        log.info("[AdminAudit] {} viewed decrypted messages of chat {}", adminUsername, chatUuid);
        audit(adminUsername, "VIEW_MESSAGES", "CHAT", chatUuid, "page=" + page);

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Message> result = messageRepository.findByChatAndIsDeletedFalse(chat, pageable);
        Long chatId = chat.getId();

        List<AdminMessageView> items = result.getContent().stream().map(m -> {
            MessageAttachment att = m.getAttachments() == null || m.getAttachments().isEmpty()
                    ? null : m.getAttachments().iterator().next();
            return AdminMessageView.builder()
                    .id(m.getUuid() != null ? m.getUuid().toString() : String.valueOf(m.getId()))
                    .chatId(chatUuid)
                    .senderId(m.getSender() != null && m.getSender().getUuid() != null ? m.getSender().getUuid().toString() : null)
                    .senderUsername(m.getSender() != null ? m.getSender().getUsername() : null)
                    .senderName(m.getSender() != null ? m.getSender().getName() : null)
                    .senderAvatar(m.getSender() != null ? m.getSender().getProfileImage() : null)
                    .type(m.getMessageType() != null ? m.getMessageType().name() : "TEXT")
                    .content(messageCryptoService.decrypt(chatId, m.getContent()))
                    .mediaUrl(att != null ? messageCryptoService.decrypt(chatId, att.getFileUrl()) : null)
                    .edited(m.isEdited())
                    .deleted(m.isDeleted())
                    .moderationStatus(m.getModerationStatus() != null ? m.getModerationStatus().name() : null)
                    .status(messageMapper.resolveMessageStatus(m))
                    .createdAt(m.getCreatedAt() != null ? m.getCreatedAt().toString() : null)
                    .build();
        }).collect(Collectors.toList());

        return PaginatedResponse.<AdminMessageView>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(result.hasNext() ? String.valueOf(page + 1) : null)
                        .hasNext(result.hasNext())
                        .hasPrevious(result.hasPrevious())
                        .total(result.getTotalElements())
                        .build())
                .build();
    }

    // ── Phase 2: moderation mutations (audited) ───────────────────────────────

    @Override
    @Transactional
    public AdminUserView setBanned(String uuid, boolean banned, String adminUsername) {
        User u = requireUser(uuid);
        u.setBanned(banned);
        userRepository.save(u);
        audit(adminUsername, banned ? "BAN_USER" : "UNBAN_USER", "USER", uuid, "@" + u.getUsername());
        return detailView(u);
    }

    @Override
    @Transactional
    public AdminUserView setVerified(String uuid, boolean verified, String adminUsername) {
        User u = requireUser(uuid);
        u.setVerified(verified);
        userRepository.save(u);
        audit(adminUsername, verified ? "VERIFY_USER" : "UNVERIFY_USER", "USER", uuid, "@" + u.getUsername());
        return detailView(u);
    }

    @Override
    @Transactional
    public AdminUserView setSoftDeleted(String uuid, boolean deleted, String adminUsername) {
        User u = requireUser(uuid);
        u.setDeleted(deleted);
        u.setDeletionRequestedAt(deleted ? Instant.now() : null);
        userRepository.save(u);
        audit(adminUsername, deleted ? "SOFT_DELETE_USER" : "RESTORE_USER", "USER", uuid, "@" + u.getUsername());
        return detailView(u);
    }

    @Override
    @Transactional
    public AdminUserView grantRole(String uuid, String roleName, String adminUsername) {
        String role = normalizeRole(roleName);
        User u = requireUser(uuid);
        com.chat.talkMe.domain.Role r = roleRepository.findByName(role)
                .orElseGet(() -> roleRepository.save(com.chat.talkMe.domain.Role.builder().name(role).build()));
        boolean has = u.getRoles().stream().anyMatch(x -> role.equals(x.getName()));
        if (!has) {
            u.getRoles().add(r);
            userRepository.save(u);
            audit(adminUsername, "GRANT_ROLE", "USER", uuid, role + " → @" + u.getUsername());
        }
        return detailView(u);
    }

    @Override
    @Transactional
    public AdminUserView revokeRole(String uuid, String roleName, String adminUsername) {
        String role = normalizeRole(roleName);
        User u = requireUser(uuid);
        boolean removed = u.getRoles().removeIf(x -> role.equals(x.getName()));
        if (removed) {
            userRepository.save(u);
            audit(adminUsername, "REVOKE_ROLE", "USER", uuid, role + " ✕ @" + u.getUsername());
        }
        return detailView(u);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<com.chat.talkMe.dto.response.AdminAuditView> listAudit(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<com.chat.talkMe.domain.AdminAuditLog> result =
                auditRepository.findAllByOrderByCreatedAtDesc(pageable);
        List<com.chat.talkMe.dto.response.AdminAuditView> items = result.getContent().stream()
                .map(a -> com.chat.talkMe.dto.response.AdminAuditView.builder()
                        .id(a.getUuid() != null ? a.getUuid().toString() : String.valueOf(a.getId()))
                        .adminUsername(a.getAdminUsername())
                        .action(a.getAction())
                        .targetType(a.getTargetType())
                        .targetId(a.getTargetId())
                        .detail(a.getDetail())
                        .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                        .build())
                .collect(Collectors.toList());
        return PaginatedResponse.<com.chat.talkMe.dto.response.AdminAuditView>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(result.hasNext() ? String.valueOf(page + 1) : null)
                        .hasNext(result.hasNext())
                        .hasPrevious(result.hasPrevious())
                        .total(result.getTotalElements())
                        .build())
                .build();
    }

    private User requireUser(String uuid) {
        return userRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
    }

    private String normalizeRole(String roleName) {
        String r = roleName == null ? "" : roleName.trim().toUpperCase();
        if (!r.startsWith("ROLE_")) r = "ROLE_" + r;
        if (!ASSIGNABLE_ROLES.contains(r)) {
            throw new com.chat.talkMe.exception.BadRequestException("Role not assignable: " + r, "TM_071");
        }
        return r;
    }

    private AdminUserView detailView(User u) {
        AdminUserView v = toView(u, presenceService.getOnlineUsernames(), presenceService.getAwayUsernames(), true);
        v.setChatCount((long) chatRepository.findChatsByUser(u).size());
        v.setMessageCount(messageRepository.countBySenderId(u.getId()));
        return v;
    }

    private void audit(String admin, String action, String targetType, String targetId, String detail) {
        try {
            auditRepository.save(com.chat.talkMe.domain.AdminAuditLog.builder()
                    .adminUsername(admin != null ? admin : "unknown")
                    .action(action).targetType(targetType).targetId(targetId).detail(detail)
                    .build());
        } catch (Exception e) {
            log.warn("[AdminAudit] failed to persist audit {}/{}: {}", action, targetId, e.getMessage());
        }
        // Any state-changing action invalidates the cached aggregates so the dashboard
        // reflects it on the next poll. Pure reads (VIEW_*) don't touch the generation.
        if (action != null && !action.startsWith("VIEW")) {
            bumpCacheGen();
        }
    }

    // ── Phase 3: create / edit / delete + charts ──────────────────────────────

    @Override
    @Transactional
    public AdminUserView createUser(com.chat.talkMe.dto.request.AdminCreateUserRequest req, String adminUsername) {
        String email = req.getEmail() == null ? null : req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new com.chat.talkMe.exception.ConflictException("TM_047");
        }
        if (userRepository.existsByUsernameIgnoreCase(req.getUsername().trim())) {
            throw new com.chat.talkMe.exception.ConflictException("TM_048");
        }
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_USER").build()));
        User u = User.builder()
                .name(req.getName())
                .email(email)
                .username(req.getUsername().trim())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .isGuest(false)
                .isVerified(true) // admin-created accounts are pre-verified
                .age(req.getAge())
                .gender(req.getGender())
                .country(req.getCountry())
                .roles(new java.util.HashSet<>(java.util.Set.of(userRole)))
                .build();
        u = userRepository.save(u);
        audit(adminUsername, "CREATE_USER", "USER", u.getUuid().toString(), "@" + u.getUsername() + " <" + email + ">");
        return detailView(u);
    }

    @Override
    @Transactional
    public AdminUserView updateUser(String uuid, com.chat.talkMe.dto.request.AdminUpdateUserRequest req, String adminUsername) {
        User u = requireUser(uuid);
        if (req.getName() != null) u.setName(req.getName());
        if (req.getBio() != null) u.setBio(req.getBio());
        if (req.getCountry() != null) u.setCountry(req.getCountry());
        if (req.getCity() != null) u.setCity(req.getCity());
        if (req.getAge() != null) u.setAge(req.getAge());
        if (req.getGender() != null) u.setGender(req.getGender());
        if (req.getOccupation() != null) u.setOccupation(req.getOccupation());
        if (req.getEducation() != null) u.setEducation(req.getEducation());
        if (req.getMobileNumber() != null) u.setMobileNumber(req.getMobileNumber());
        if (req.getVerified() != null) u.setVerified(req.getVerified());
        // Identity + account changes (unique-checked so we don't create duplicates).
        if (req.getEmail() != null) {
            String email = req.getEmail().trim().toLowerCase();
            if (!email.equalsIgnoreCase(u.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
                throw new com.chat.talkMe.exception.ConflictException("TM_047");
            }
            u.setEmail(email);
        }
        if (req.getUsername() != null) {
            String username = req.getUsername().trim();
            if (!username.equalsIgnoreCase(u.getUsername()) && userRepository.existsByUsernameIgnoreCase(username)) {
                throw new com.chat.talkMe.exception.ConflictException("TM_048");
            }
            u.setUsername(username);
        }
        if (req.getInterests() != null) {
            u.setInterests(req.getInterests().stream()
                    .map(s -> { try { return com.chat.talkMe.enums.Interest.valueOf(s.trim().toUpperCase()); }
                                catch (IllegalArgumentException e) { return null; } })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(java.util.HashSet::new)));
        }
        if (req.getNewPassword() != null && !req.getNewPassword().isBlank()) {
            u.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        }
        userRepository.save(u);
        audit(adminUsername, "EDIT_USER", "USER", uuid, "@" + u.getUsername()
                + (req.getNewPassword() != null && !req.getNewPassword().isBlank() ? " (password reset)" : ""));
        return detailView(u);
    }

    @Override
    @Transactional
    public void deleteMessage(String messageUuid, String adminUsername) {
        Message m = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_150"));
        m.setDeleted(true);
        messageRepository.save(m);
        audit(adminUsername, "DELETE_MESSAGE", "MESSAGE", messageUuid,
                "chat=" + (m.getChat() != null && m.getChat().getUuid() != null ? m.getChat().getUuid() : "?"));
    }

    @Override
    @Transactional
    public void deleteChat(String chatUuid, String adminUsername) {
        Chat c = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        c.setDeleted(true);
        chatRepository.save(c);
        audit(adminUsername, "DELETE_CHAT", "CHAT", chatUuid, c.getName());
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.chat.talkMe.dto.response.AdminTimeseriesPoint> getSignupTimeseries(int days) {
        int d = Math.min(Math.max(days, 1), 365);
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Instant since = today.minusDays(d - 1L).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.util.Map<java.time.LocalDate, Long> counts = userRepository.findSignupTimesSince(since).stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(
                        t -> t.atZone(java.time.ZoneOffset.UTC).toLocalDate(), Collectors.counting()));
        List<com.chat.talkMe.dto.response.AdminTimeseriesPoint> out = new java.util.ArrayList<>(d);
        for (int i = d - 1; i >= 0; i--) {
            java.time.LocalDate day = today.minusDays(i);
            out.add(new com.chat.talkMe.dto.response.AdminTimeseriesPoint(day.toString(), counts.getOrDefault(day, 0L)));
        }
        return out;
    }

    /** A resolved time window: where it starts, bucket size, count and label granularity. */
    private record RangeSpec(Instant since, long bucketMillis, int buckets, String granularity) {}

    private static final long MIN = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;

    private RangeSpec resolveRange(String range) {
        Instant now = Instant.now();
        String r = range == null ? "30d" : range.trim().toLowerCase();
        return switch (r) {
            case "1h"          -> spec(now, 5 * MIN, 12, "hour");   // 12 × 5 min
            case "6h"          -> spec(now, 30 * MIN, 12, "hour");  // 12 × 30 min
            case "12h"         -> spec(now, HOUR, 12, "hour");      // 12 × 1 h
            case "24h", "1d"   -> spec(now, 2 * HOUR, 12, "hour");  // 12 × 2 h
            case "7d", "1w"    -> spec(now, DAY, 7, "day");
            case "90d", "3m"   -> spec(now, DAY, 90, "day");
            case "1y", "365d"  -> spec(now, 7 * DAY, 52, "day");    // weekly buckets
            case "30d", "1m"   -> spec(now, DAY, 30, "day");
            default             -> spec(now, DAY, 30, "day");
        };
    }

    private RangeSpec spec(Instant now, long bucketMillis, int buckets, String gran) {
        Instant since = now.minusMillis(bucketMillis * (long) buckets);
        return new RangeSpec(since, bucketMillis, buckets, gran);
    }

    /** Bucket raw timestamps into fixed windows, zero-filled; labels are ISO bucket-starts. */
    private List<com.chat.talkMe.dto.response.AdminTimeseriesPoint> bucketize(List<Instant> times, RangeSpec spec) {
        long[] counts = new long[spec.buckets()];
        long start = spec.since().toEpochMilli();
        for (Instant t : times) {
            if (t == null) continue;
            long idx = (t.toEpochMilli() - start) / spec.bucketMillis();
            if (idx >= 0 && idx < spec.buckets()) counts[(int) idx]++;
        }
        List<com.chat.talkMe.dto.response.AdminTimeseriesPoint> out = new java.util.ArrayList<>(spec.buckets());
        for (int i = 0; i < spec.buckets(); i++) {
            Instant bucketStart = spec.since().plusMillis((long) i * spec.bucketMillis());
            out.add(new com.chat.talkMe.dto.response.AdminTimeseriesPoint(bucketStart.toString(), counts[i]));
        }
        return out;
    }

    private long intervalMillis(String key) {
        return switch (key == null ? "" : key.trim().toLowerCase()) {
            case "5m" -> 5 * MIN;
            case "15m" -> 15 * MIN;
            case "30m" -> 30 * MIN;
            case "1h" -> HOUR;
            case "6h" -> 6 * HOUR;
            case "12h" -> 12 * HOUR;
            case "1d" -> DAY;
            case "1w" -> 7 * DAY;
            default -> 0L;
        };
    }

    /** Human-readable bucket size, e.g. 3600000 → "1h", 86400000 → "1d". */
    private String describeBucket(long ms) {
        if (ms % (7 * DAY) == 0) return (ms / (7 * DAY)) + "w";
        if (ms % DAY == 0) return (ms / DAY) + "d";
        if (ms % HOUR == 0) return (ms / HOUR) + "h";
        return Math.max(1, ms / MIN) + "m";
    }

    /** Snap an arbitrary bucket size to the nearest supported interval key. */
    private String snapInterval(long span) {
        long target = Math.max(MIN, span / 40); // aim for ~40 buckets
        long[] opts = {5 * MIN, 15 * MIN, 30 * MIN, HOUR, 6 * HOUR, 12 * HOUR, DAY, 7 * DAY};
        String[] keys = {"5m", "15m", "30m", "1h", "6h", "12h", "1d", "1w"};
        for (int i = 0; i < opts.length; i++) if (target <= opts[i]) return keys[i];
        return "1w";
    }

    private Instant parseInstant(String iso, Instant fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try { return Instant.parse(iso.trim()); }
        catch (Exception e) { return fallback; }
    }

    private RangeSpec resolveWindow(String range, String interval, String fromIso, String toIso) {
        boolean custom = (fromIso != null && !fromIso.isBlank());
        Instant now = Instant.now();
        long bucket;
        Instant from, to;
        if (custom) {
            to = parseInstant(toIso, now);
            from = parseInstant(fromIso, to.minusMillis(30 * DAY));
            if (!from.isBefore(to)) from = to.minusMillis(DAY);
            long ov = intervalMillis(interval);
            bucket = ov > 0 ? ov : intervalMillis(snapInterval(to.toEpochMilli() - from.toEpochMilli()));
        } else {
            RangeSpec base = resolveRange(range);
            long ov = intervalMillis(interval);
            if (ov <= 0) return base; // no override → use the range's default bucketing
            from = base.since();
            to = now;
            bucket = ov;
        }
        int buckets = (int) Math.min(500, Math.max(1,
                (long) Math.ceil((double) (to.toEpochMilli() - from.toEpochMilli()) / bucket)));
        String gran = bucket >= DAY ? "day" : "hour";
        return new RangeSpec(from, bucket, buckets, gran);
    }

    @Override
    @Transactional(readOnly = true)
    public com.chat.talkMe.dto.response.AdminTimeseriesResult getTimeseries(
            String metric, String range, String interval, String fromIso, String toIso) {
        // Custom from/to windows aren't cached (unbounded key space); ranged ones are.
        if (fromIso != null && !fromIso.isBlank()) {
            return computeTimeseries(metric, range, interval, fromIso, toIso);
        }
        String key = genKey(String.join(":", "ts", String.valueOf(metric),
                String.valueOf(range), String.valueOf(interval)));
        return cached(key, 30, com.chat.talkMe.dto.response.AdminTimeseriesResult.class,
                () -> computeTimeseries(metric, range, interval, fromIso, toIso));
    }

    private com.chat.talkMe.dto.response.AdminTimeseriesResult computeTimeseries(
            String metric, String range, String interval, String fromIso, String toIso) {
        RangeSpec spec = resolveWindow(range, interval, fromIso, toIso);
        String m = metric == null ? "messages" : metric.trim().toLowerCase();
        Instant since = spec.since();
        List<Instant> times = switch (m) {
            case "signups", "users" -> userRepository.findSignupTimesSince(since);
            case "attachments", "media" -> attachmentRepository.findAttachmentTimesSince(since);
            case "posts" -> postRepository.findTimesSince(since);
            case "stories" -> storyRepository.findTimesSince(since);
            case "profileviews", "profile_views", "views" -> profileViewRepository.findTimesSince(since);
            case "follows" -> userFollowRepository.findTimesSince(since);
            case "friendrequests", "friend_requests", "friends" -> friendRequestRepository.findTimesSince(since);
            case "reports" -> matchReportRepository.findTimesSince(since);
            case "reactions" -> reactionRepository.findTimesSince(since);
            default -> messageRepository.findMessageTimesSince(since);
        };
        List<com.chat.talkMe.dto.response.AdminTimeseriesPoint> points = bucketize(times, spec);
        long total = points.stream().mapToLong(com.chat.talkMe.dto.response.AdminTimeseriesPoint::getCount).sum();
        return com.chat.talkMe.dto.response.AdminTimeseriesResult.builder()
                .metric(m)
                .granularity(spec.granularity())
                .interval(describeBucket(spec.bucketMillis()))
                .total(total)
                .points(points)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public com.chat.talkMe.dto.response.AdminAnalyticsResponse getAnalytics(String range) {
        String key = genKey("analytics:" + (range == null ? "30d" : range));
        return cached(key, 20, com.chat.talkMe.dto.response.AdminAnalyticsResponse.class,
                () -> computeAnalytics(range));
    }

    private com.chat.talkMe.dto.response.AdminAnalyticsResponse computeAnalytics(String range) {
        RangeSpec spec = resolveRange(range);
        Instant now = Instant.now();

        // Live lobby snapshot (ephemeral — Redis set, no history persisted).
        long lobbyNow = 0L;
        try {
            Long size = redisTemplate.opsForSet().size("lobby:users");
            lobbyNow = size == null ? 0L : size;
        } catch (Exception e) {
            log.warn("[AdminAnalytics] lobby size unavailable: {}", e.getMessage());
        }

        // Presence status breakdown (live snapshot from presence service).
        long total = userRepository.count();
        long online = presenceService.getOnlineUsernames().size();
        long idle = presenceService.getAwayUsernames().size();
        long offline = Math.max(0, total - online - idle);
        List<com.chat.talkMe.dto.response.LabelCount> usersByStatus = List.of(
                new com.chat.talkMe.dto.response.LabelCount("Online", online),
                new com.chat.talkMe.dto.response.LabelCount("Idle", idle),
                new com.chat.talkMe.dto.response.LabelCount("Offline", offline));

        // Accounts pending purge — full details (capped for payload sanity).
        Set<String> onlineSet = presenceService.getOnlineUsernames();
        Set<String> awaySet = presenceService.getAwayUsernames();
        List<AdminUserView> pendingDeletion = userRepository
                .findByIsDeletedTrueAndDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtDesc()
                .stream()
                .limit(200)
                .map(u -> toView(u, onlineSet, awaySet, true))
                .collect(Collectors.toList());

        return com.chat.talkMe.dto.response.AdminAnalyticsResponse.builder()
                // headline totals
                .totalUsers(total)
                .verifiedUsers(userRepository.countByIsVerifiedTrue())
                .guestUsers(userRepository.countByIsGuestTrue())
                .bannedUsers(userRepository.countByBannedTrue())
                .onlineNow(online)
                .lobbyNow(lobbyNow)
                .totalChats(chatRepository.count())
                .totalMessages(messageRepository.count())
                .totalAttachments(attachmentRepository.count())
                .totalAttachmentBytes(attachmentRepository.sumFileSize())
                .totalPosts(postRepository.count())
                .totalStories(storyRepository.count())
                .totalProfileViews(profileViewRepository.count())
                .totalReports(matchReportRepository.count())
                .totalFollows(userFollowRepository.count())
                // active-user snapshot (by last-seen recency)
                .activeLast1h(userRepository.countByPresenceLastSeenAtAfter(now.minus(1, ChronoUnit.HOURS)))
                .activeLast24h(userRepository.countByPresenceLastSeenAtAfter(now.minus(24, ChronoUnit.HOURS)))
                .activeLast7d(userRepository.countByPresenceLastSeenAtAfter(now.minus(7, ChronoUnit.DAYS)))
                // breakdowns
                .messagesByType(toLabelCounts(messageRepository.countGroupedByType()))
                .chatsByType(toLabelCounts(chatRepository.countGroupedByType()))
                .usersByGender(toLabelCounts(userRepository.countGroupedByGender()))
                .usersByCountry(topN(toLabelCounts(userRepository.countGroupedByCountry()), 12))
                .usersByStatus(usersByStatus)
                .pendingDeletion(pendingDeletion)
                // friends hierarchy / social graph
                .friendLinks(friendRepository.count())
                .friendships(friendRepository.count() / 2)
                .friendRequestsByStatus(toLabelCounts(friendRequestRepository.countGroupedByStatus()))
                .friendCountDistribution(friendCountDistribution(total))
                .topConnectors(topConnectors(15))
                // time series
                .range(range == null ? "30d" : range)
                .timeseriesGranularity(spec.granularity())
                .signupsSeries(bucketize(userRepository.findSignupTimesSince(spec.since()), spec))
                .messagesSeries(bucketize(messageRepository.findMessageTimesSince(spec.since()), spec))
                .build();
    }

    /** Map a JPA {@code GROUP BY} result ([label, count]) to sorted LabelCounts. */
    private List<com.chat.talkMe.dto.response.LabelCount> toLabelCounts(List<Object[]> rows) {
        return rows.stream()
                .map(r -> {
                    Object k = r[0];
                    String label = k == null ? "Unknown" : (k instanceof Enum<?> e ? e.name() : String.valueOf(k));
                    long count = r[1] == null ? 0L : ((Number) r[1]).longValue();
                    return new com.chat.talkMe.dto.response.LabelCount(label, count);
                })
                .sorted((a, b) -> Long.compare(b.getCount(), a.getCount()))
                .collect(Collectors.toList());
    }

    private List<com.chat.talkMe.dto.response.LabelCount> topN(List<com.chat.talkMe.dto.response.LabelCount> in, int n) {
        return in.size() <= n ? in : new java.util.ArrayList<>(in.subList(0, n));
    }

    /** Bucket users by how many friends they have (0 bucket derived from total). */
    private List<com.chat.talkMe.dto.response.LabelCount> friendCountDistribution(long totalUsers) {
        long[] buckets = new long[6]; // 0 | 1-5 | 6-10 | 11-25 | 26-50 | 50+
        long usersWithFriends = 0;
        for (Object[] row : friendRepository.countFriendsPerUser()) {
            long c = row[1] == null ? 0L : ((Number) row[1]).longValue();
            usersWithFriends++;
            if (c <= 5) buckets[1]++;
            else if (c <= 10) buckets[2]++;
            else if (c <= 25) buckets[3]++;
            else if (c <= 50) buckets[4]++;
            else buckets[5]++;
        }
        buckets[0] = Math.max(0, totalUsers - usersWithFriends); // no friend links at all
        String[] labels = {"0 friends", "1-5", "6-10", "11-25", "26-50", "50+"};
        List<com.chat.talkMe.dto.response.LabelCount> out = new java.util.ArrayList<>(6);
        for (int i = 0; i < labels.length; i++) {
            out.add(new com.chat.talkMe.dto.response.LabelCount(labels[i], buckets[i]));
        }
        return out;
    }

    /** Most-connected users first — the roots of the friends hierarchy. */
    private List<com.chat.talkMe.dto.response.AdminConnectorView> topConnectors(int n) {
        return friendRepository.topConnectors(PageRequest.of(0, Math.max(1, n))).stream()
                .map(row -> connectorView((User) row[0], ((Number) row[1]).longValue()))
                .collect(Collectors.toList());
    }

    private com.chat.talkMe.dto.response.AdminConnectorView connectorView(User u, long friendCount) {
        return com.chat.talkMe.dto.response.AdminConnectorView.builder()
                .id(u.getUuid() != null ? u.getUuid().toString() : null)
                .username(u.getUsername())
                .name(u.getName())
                .avatar(u.getProfileImage())
                .country(u.getCountry())
                .friendCount(friendCount)
                .build();
    }

    // ── News / feed ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<com.chat.talkMe.dto.response.AdminPostView> listPosts(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<com.chat.talkMe.domain.Post> result = postRepository.findByIsDeletedFalse(pageable);
        List<com.chat.talkMe.dto.response.AdminPostView> items =
                result.getContent().stream().map(this::toPostView).collect(Collectors.toList());
        return page(items, result, page);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<com.chat.talkMe.dto.response.AdminPostLikeView> getPostLikes(String postUuid, int page, int size) {
        com.chat.talkMe.domain.Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_180"));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<com.chat.talkMe.domain.PostLike> result = postLikeRepository.findByPost(post, pageable);
        List<com.chat.talkMe.dto.response.AdminPostLikeView> items = result.getContent().stream().map(l -> {
            User u = l.getUser();
            return com.chat.talkMe.dto.response.AdminPostLikeView.builder()
                    .id(l.getUuid() != null ? l.getUuid().toString() : String.valueOf(l.getId()))
                    .userId(u != null && u.getUuid() != null ? u.getUuid().toString() : null)
                    .username(u != null ? u.getUsername() : null)
                    .name(u != null ? u.getName() : null)
                    .avatar(u != null ? u.getProfileImage() : null)
                    .createdAt(l.getCreatedAt() != null ? l.getCreatedAt().toString() : null)
                    .build();
        }).collect(Collectors.toList());
        return page(items, result, page);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<com.chat.talkMe.dto.response.AdminPostCommentView> getPostComments(String postUuid, int page, int size) {
        com.chat.talkMe.domain.Post post = postRepository.findByUuid(UUID.fromString(postUuid))
                .orElseThrow(() -> new NotFoundException("Post not found", "TM_180"));
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<com.chat.talkMe.domain.PostComment> result = postCommentRepository.findAllForPost(post, pageable);
        List<com.chat.talkMe.dto.response.AdminPostCommentView> items = result.getContent().stream().map(c -> {
            User u = c.getUser();
            return com.chat.talkMe.dto.response.AdminPostCommentView.builder()
                    .id(c.getUuid() != null ? c.getUuid().toString() : String.valueOf(c.getId()))
                    .userId(u != null && u.getUuid() != null ? u.getUuid().toString() : null)
                    .username(u != null ? u.getUsername() : null)
                    .name(u != null ? u.getName() : null)
                    .avatar(u != null ? u.getProfileImage() : null)
                    .content(c.getContent())
                    .parentId(c.getParent() != null && c.getParent().getUuid() != null ? c.getParent().getUuid().toString() : null)
                    .createdAt(c.getCreatedAt() != null ? c.getCreatedAt().toString() : null)
                    .build();
        }).collect(Collectors.toList());
        return page(items, result, page);
    }

    private com.chat.talkMe.dto.response.AdminPostView toPostView(com.chat.talkMe.domain.Post p) {
        User a = p.getUser();
        List<com.chat.talkMe.dto.response.AdminPostView.Media> media = p.getMedia() == null ? List.of()
                : p.getMedia().stream()
                    .map(m -> com.chat.talkMe.dto.response.AdminPostView.Media.builder()
                            .url(m.getMediaUrl()).type(m.getMediaType()).build())
                    .collect(Collectors.toList());
        return com.chat.talkMe.dto.response.AdminPostView.builder()
                .id(p.getUuid() != null ? p.getUuid().toString() : String.valueOf(p.getId()))
                .shortCode(p.getShortCode())
                .authorId(a != null && a.getUuid() != null ? a.getUuid().toString() : null)
                .authorUsername(a != null ? a.getUsername() : null)
                .authorName(a != null ? a.getName() : null)
                .authorAvatar(a != null ? a.getProfileImage() : null)
                .content(p.getContent())
                .audience(p.getAudience() != null ? p.getAudience().name() : null)
                .likeCount(postLikeRepository.countByPost(p))
                .commentCount(postCommentRepository.countForPost(p))
                .hasPoll(p.getPoll() != null)
                .hasAudio(p.getAudio() != null)
                .media(media)
                .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().toString() : null)
                .build();
    }

    /** Shared PaginatedResponse assembler for the page-numbered admin lists. */
    private <T> PaginatedResponse<T> page(List<T> items, Page<?> result, int page) {
        return PaginatedResponse.<T>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(result.hasNext() ? String.valueOf(page + 1) : null)
                        .hasNext(result.hasNext())
                        .hasPrevious(result.hasPrevious())
                        .total(result.getTotalElements())
                        .build())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.chat.talkMe.dto.response.AdminConnectorView> getUserFriends(String userUuid) {
        User u = requireUser(userUuid);
        return friendRepository.findFriendsByUser(u).stream()
                .map(f -> connectorView(f, friendRepository.countByUserAndIsDeletedFalse(f)))
                .sorted((a, b) -> Long.compare(b.getFriendCount(), a.getFriendCount()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional // NOT readOnly: writes an admin audit row + decrypts URLs
    public PaginatedResponse<com.chat.talkMe.dto.response.AdminAttachmentView> getAttachments(
            String userUuid, String type, int page, int size, String adminUsername) {
        Long senderId = null;
        if (userUuid != null && !userUuid.isBlank()) {
            senderId = requireUser(userUuid).getId();
        }
        com.chat.talkMe.enums.MessageType mt = null;
        if (type != null && !type.isBlank()) {
            try { mt = com.chat.talkMe.enums.MessageType.valueOf(type.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { /* unknown type → no filter */ }
        }
        audit(adminUsername, "VIEW_ATTACHMENTS", "ATTACHMENT",
                userUuid != null ? userUuid : "all", "type=" + type + " page=" + page);

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<MessageAttachment> result = attachmentRepository.findForAdmin(senderId, mt, pageable);

        List<com.chat.talkMe.dto.response.AdminAttachmentView> items = result.getContent().stream()
                .map(this::toAttachmentView)
                .collect(Collectors.toList());

        return PaginatedResponse.<com.chat.talkMe.dto.response.AdminAttachmentView>builder()
                .items(items)
                .pagination(PaginatedResponse.PaginationInfo.builder()
                        .cursor(result.hasNext() ? String.valueOf(page + 1) : null)
                        .hasNext(result.hasNext())
                        .hasPrevious(result.hasPrevious())
                        .total(result.getTotalElements())
                        .build())
                .build();
    }

    private com.chat.talkMe.dto.response.AdminAttachmentView toAttachmentView(MessageAttachment a) {
        Message m = a.getMessage();
        Chat chat = m.getChat();
        Long chatId = chat != null ? chat.getId() : null;
        User sender = m.getSender();

        List<com.chat.talkMe.dto.response.AdminAttachmentView.SharedUser> sharedWith =
                chat == null || chat.getMembers() == null ? List.of()
                : chat.getMembers().stream()
                    .map(ChatMember::getUser)
                    .filter(mu -> mu != null && (sender == null || !mu.getId().equals(sender.getId())))
                    .map(mu -> com.chat.talkMe.dto.response.AdminAttachmentView.SharedUser.builder()
                            .id(mu.getUuid() != null ? mu.getUuid().toString() : null)
                            .username(mu.getUsername())
                            .name(mu.getName())
                            .avatar(mu.getProfileImage())
                            .build())
                    .collect(Collectors.toList());

        return com.chat.talkMe.dto.response.AdminAttachmentView.builder()
                .id(a.getUuid() != null ? a.getUuid().toString() : String.valueOf(a.getId()))
                .messageId(m.getUuid() != null ? m.getUuid().toString() : null)
                .chatId(chat != null && chat.getUuid() != null ? chat.getUuid().toString() : null)
                .chatName(chat != null ? chat.getName() : null)
                .chatType(chat != null && chat.getChatType() != null ? chat.getChatType().name() : null)
                .senderId(sender != null && sender.getUuid() != null ? sender.getUuid().toString() : null)
                .senderUsername(sender != null ? sender.getUsername() : null)
                .senderName(sender != null ? sender.getName() : null)
                .senderAvatar(sender != null ? sender.getProfileImage() : null)
                .sharedWith(sharedWith)
                .type(m.getMessageType() != null ? m.getMessageType().name() : null)
                .fileName(messageCryptoService.decrypt(chatId, a.getFileName()))
                .fileUrl(messageCryptoService.decrypt(chatId, a.getFileUrl()))
                .thumbnailUrl(a.getThumbnailUrl() != null ? messageCryptoService.decrypt(chatId, a.getThumbnailUrl()) : null)
                .mimeType(a.getMimeType())
                .fileSize(a.getFileSize() != null ? a.getFileSize() : 0L)
                .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                .build();
    }

    // ── mappers ──────────────────────────────────────────────────────────────

    private AdminUserView toView(User u, Set<String> online, Set<String> away, boolean detail) {
        String presence = online.contains(u.getUsername()) ? "online"
                : away.contains(u.getUsername()) ? "idle" : "offline";
        return AdminUserView.builder()
                .id(u.getUuid() != null ? u.getUuid().toString() : null)
                .username(u.getUsername())
                .name(u.getName())
                .email(u.getEmail())
                .mobileNumber(u.getMobileNumber())
                .avatar(u.getProfileImage())
                .bio(detail ? u.getBio() : null)
                .age(u.getAge())
                .gender(u.getGender())
                .country(u.getCountry())
                .city(u.getCity())
                .occupation(detail ? u.getOccupation() : null)
                .education(detail ? u.getEducation() : null)
                .interests(detail && u.getInterests() != null
                        ? u.getInterests().stream().map(Enum::name).collect(Collectors.toSet()) : null)
                .roles(u.getRoles() != null ? u.getRoles().stream().map(Role::getName).collect(Collectors.toList()) : null)
                .verified(u.isVerified())
                .guest(u.isGuest())
                .deleted(u.isDeleted())
                .banned(u.isBanned())
                .hasGoogleLinked(u.getGoogleId() != null && !u.getGoogleId().isBlank())
                .presence(presence)
                .createdAt(u.getCreatedAt() != null ? u.getCreatedAt().toString() : null)
                .lastSeenAt(u.getPresenceLastSeenAt() != null ? u.getPresenceLastSeenAt().toString() : null)
                .deletionRequestedAt(u.getDeletionRequestedAt() != null ? u.getDeletionRequestedAt().toString() : null)
                .totalUnreadCount(u.getTotalUnreadCount())
                .build();
    }

    private AdminChatView toChatView(Chat chat) {
        List<AdminChatView.Member> members = chat.getMembers() == null ? List.of()
                : chat.getMembers().stream()
                    .map(ChatMember::getUser)
                    .filter(mu -> mu != null)
                    .map(mu -> AdminChatView.Member.builder()
                            .id(mu.getUuid() != null ? mu.getUuid().toString() : null)
                            .username(mu.getUsername())
                            .name(mu.getName())
                            .avatar(mu.getProfileImage())
                            .build())
                    .collect(Collectors.toList());
        String name = chat.getName();
        if (name == null || name.isBlank()) {
            name = members.stream().map(AdminChatView.Member::getName)
                    .filter(n -> n != null).collect(Collectors.joining(", "));
        }
        return AdminChatView.builder()
                .id(chat.getUuid() != null ? chat.getUuid().toString() : null)
                .type(chat.getChatType() != null ? chat.getChatType().name() : null)
                .name(name)
                .members(members)
                .messageCount(messageRepository.countByChat(chat))
                .createdAt(chat.getCreatedAt() != null ? chat.getCreatedAt().toString() : null)
                .lastMessageAt(chat.getUpdatedAt() != null ? chat.getUpdatedAt().toString() : null)
                .build();
    }
}
