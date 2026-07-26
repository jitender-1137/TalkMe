package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.MemberCountCache;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.CityDistrictDetailResponse;
import com.chat.talkMe.dto.response.CityDistrictResponse;
import com.chat.talkMe.dto.response.GroupInfoResponse;
import com.chat.talkMe.enums.CityLocation;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.CityService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Virtual Night City (feature #25). Districts come from the {@link CityLocation} enum;
 * their rooms are the curated ROOM chats seeded by {@code NightCitySeeder} (matched on
 * {@link Chat#getCityLocation()} + {@code roomCurated}). Live presence is a per-district
 * Redis set of usernames ({@code city:presence:{slug}}), maintained by enter/leave and
 * self-healed against the global online set on every read. Everything Redis-backed is
 * fail-open — an outage degrades counts/rosters to empty, never a failed request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CityServiceImpl implements CityService {

    private static final String KEY_PREFIX = "city:presence:";
    /** Safety expiry so an abandoned district set can't linger forever; refreshed on enter. */
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);

    private final ChatRepository chatRepository;
    private final MemberCountCache memberCountCache;
    private final PresenceService presenceService;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CityDistrictResponse> listDistricts() {
        Set<String> online = safeOnline();
        List<CityDistrictResponse> out = new ArrayList<>();
        for (CityLocation loc : CityLocation.values()) {
            out.add(card(loc, online));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public CityDistrictDetailResponse getDistrict(String slug, User user) {
        CityLocation loc = require(slug);
        Set<String> online = safeOnline();
        return CityDistrictDetailResponse.builder()
                .district(card(loc, online))
                .rooms(mapRooms(loc))
                .onlineUsernames(liveRoster(loc.getSlug(), online))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> getRooms(String slug, User user) {
        return mapRooms(require(slug));
    }

    // ── Presence mutations ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CityDistrictDetailResponse enterDistrict(User user, String slug) {
        CityLocation loc = require(slug);
        String key = key(loc.getSlug());
        try {
            redis.opsForSet().add(key, user.getUsername());
            redis.expire(key, PRESENCE_TTL);
        } catch (Exception e) {
            log.debug("City enter presence write skipped for {} / {}: {}", user.getUsername(), key, e.getMessage());
        }
        broadcast("user_joined", loc, user);
        return getDistrict(slug, user);
    }

    @Override
    @Transactional(readOnly = true)
    public void leaveDistrict(User user, String slug) {
        CityLocation loc = require(slug);
        try {
            redis.opsForSet().remove(key(loc.getSlug()), user.getUsername());
        } catch (Exception e) {
            log.debug("City leave presence write skipped for {}: {}", user.getUsername(), e.getMessage());
        }
        // Return no payload: this endpoint is only hasRole('USER'), so it must NOT emit the
        // VIRTUAL_CITY-gated district detail (room list + online roster) to a non-entitled user.
        broadcast("user_left", loc, user);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CityLocation require(String slug) {
        CityLocation loc = CityLocation.fromSlug(slug);
        if (loc == null) {
            throw new NotFoundException("Unknown city district: " + slug, "TM_970");
        }
        return loc;
    }

    private CityDistrictResponse card(CityLocation loc, Set<String> online) {
        Set<String> members = members(loc.getSlug());
        int live = (int) members.stream().filter(online::contains).count();
        int rooms = (int) chatRepository.findByCityLocation(loc).stream()
                .filter(Chat::isRoomCurated)
                .count();
        return CityDistrictResponse.builder()
                .slug(loc.getSlug())
                .label(loc.getLabel())
                .emoji(loc.getEmoji())
                .tagline(loc.getTagline())
                .liveCount(live)
                .roomCount(rooms)
                .build();
    }

    /**
     * Membership-free room cards. The viewer is (by design) NOT a member of a curated city
     * room — {@code ChatService#getChatByUuid} enforces membership and would 403 here — so we
     * map straight off the entity into a lightweight discovery card the client can render + join.
     */
    private List<ChatResponse> mapRooms(CityLocation loc) {
        return chatRepository.findByCityLocation(loc).stream()
                .filter(Chat::isRoomCurated)
                .map(this::toRoomCard)
                .collect(Collectors.toList());
    }

    private ChatResponse toRoomCard(Chat chat) {
        return ChatResponse.builder()
                .id(chat.getUuid().toString())
                .name(chat.getName())
                .chatType(chat.getChatType().name())
                .avatar(chat.getImageUrl())
                .group(GroupInfoResponse.builder()
                        .subtype(chat.getChatType().name().toLowerCase())
                        .visibility(chat.getVisibility().name())
                        .joinPolicy(chat.getJoinPolicy().name())
                        .allowExplicitContent(chat.isAllowExplicitContent())
                        .allowNonFriends(chat.isAllowNonFriends())
                        .memberLimit(chat.getMemberLimit())
                        .memberCount(safeMemberCount(chat))
                        .description(chat.getDescription())
                        .imageUrl(chat.getImageUrl())
                        .publicUsername(chat.getSlug())
                        .category(chat.getCategory())
                        .tags(chat.getTags() == null ? List.of()
                                : chat.getTags().stream().map(Enum::name).collect(Collectors.toList()))
                        .build())
                .build();
    }

    private int safeMemberCount(Chat chat) {
        try {
            return memberCountCache.get(chat);
        } catch (Exception e) {
            log.debug("City member-count lookup failed for {}: {}", chat.getUuid(), e.getMessage());
            return 0;
        }
    }

    /** Set members currently online (sorted); prunes stale offline entries best-effort. */
    private List<String> liveRoster(String slug, Set<String> online) {
        Set<String> members = members(slug);
        List<String> live = members.stream().filter(online::contains).sorted().collect(Collectors.toList());
        if (members.size() > live.size()) {
            try {
                Set<String> stale = new HashSet<>(members);
                stale.removeAll(live);
                if (!stale.isEmpty()) {
                    redis.opsForSet().remove(key(slug), stale.toArray());
                }
            } catch (Exception e) {
                log.debug("City roster self-heal skipped for {}: {}", slug, e.getMessage());
            }
        }
        return live;
    }

    private Set<String> members(String slug) {
        try {
            Set<String> m = redis.opsForSet().members(key(slug));
            return m != null ? m : Collections.emptySet();
        } catch (Exception e) {
            log.debug("City presence read skipped for {}: {}", slug, e.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<String> safeOnline() {
        try {
            Set<String> online = presenceService.getOnlineUsernames();
            return online != null ? online : Collections.emptySet();
        } catch (Exception e) {
            log.debug("City online-presence lookup failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private void broadcast(String event, CityLocation loc, User user) {
        try {
            // Use the online-intersected count so the live WS badge matches the REST card
            // (the raw Redis set can hold stale/offline usernames until the roster self-heals).
            Set<String> online = safeOnline();
            long live = members(loc.getSlug()).stream().filter(online::contains).count();
            messagingTemplate.convertAndSend("/topic/city/" + loc.getSlug(),
                    (Object) Map.of("event", event, "payload", Map.of(
                            "slug", loc.getSlug(),
                            "username", user.getUsername(),
                            "liveCount", live)));
        } catch (Exception e) {
            log.debug("City WS broadcast skipped for {} / {}: {}", event, loc.getSlug(), e.getMessage());
        }
    }

    private static String key(String slug) {
        return KEY_PREFIX + slug;
    }
}
