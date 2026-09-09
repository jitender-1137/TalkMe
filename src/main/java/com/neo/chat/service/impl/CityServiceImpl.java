package com.neo.chat.service.impl;

import com.neo.chat.cache.MemberCountCache;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ChatResponse;
import com.neo.chat.dto.response.CityDistrictDetailResponse;
import com.neo.chat.dto.response.CityDistrictResponse;
import com.neo.chat.dto.response.GroupInfoResponse;
import com.neo.chat.enums.CityLocation;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.service.CityService;
import com.neo.chat.service.PresenceService;
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
    /**
     * Safety expiry so an abandoned district set can't linger forever; refreshed on enter.
     */
    private static final Duration PRESENCE_TTL = Duration.ofHours(12);

    private final ChatRepository chatRepository;
    private final MemberCountCache memberCountCache;
    private final PresenceService presenceService;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Lists every district as a lightweight card (label/emoji/tagline plus live + room counts).
     * Live counts intersect the district's Redis presence set with the global online set.
     *
     * @return one card per {@link CityLocation}
     */
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

    /**
     * Full district detail: the card, its curated room cards, and the live (online) roster.
     *
     * @param slug district slug
     * @param user the requesting user (unused beyond routing/entitlement context)
     * @return the district detail DTO
     * @throws com.neo.chat.exception.NotFoundException (TM_970) if the slug is unknown
     */
    @Override
    @Transactional(readOnly = true)
    public CityDistrictDetailResponse getDistrict(String slug, User user) {
        return computeDistrict(slug, user);
    }

    /**
     * Internal, proxy-free counterpart of {@link #getDistrict(String, User)} for the same-bean
     * caller {@link #enterDistrict(User, String)} (BootUI ARCH-SPRING-004).
     */
    private CityDistrictDetailResponse computeDistrict(String slug, User user) {
        CityLocation loc = require(slug);
        Set<String> online = safeOnline();
        return CityDistrictDetailResponse.builder()
                .district(card(loc, online))
                .rooms(mapRooms(loc))
                .onlineUsernames(liveRoster(loc.getSlug(), online))
                .build();
    }

    /**
     * The curated room cards for a district.
     *
     * @param slug district slug
     * @param user the requesting user
     * @return room discovery cards
     * @throws com.neo.chat.exception.NotFoundException (TM_970) if the slug is unknown
     */
    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> getRooms(String slug, User user) {
        return mapRooms(require(slug));
    }

    // ── Presence mutations ─────────────────────────────────────────────────────

    /**
     * Marks the user present in a district: adds their username to the district's Redis set
     * (best-effort, refreshes the 12h TTL), broadcasts a {@code user_joined} event, and returns
     * the refreshed district detail.
     *
     * @param user the entering user
     * @param slug district slug
     * @return the refreshed district detail
     * @throws com.neo.chat.exception.NotFoundException (TM_970) if the slug is unknown
     */
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
        return computeDistrict(slug, user);
    }

    /**
     * Marks the user absent from a district: removes them from the Redis presence set
     * (best-effort) and broadcasts a {@code user_left} event. Returns no payload by design so
     * this USER-gated endpoint can't leak VIRTUAL_CITY-gated detail to a non-entitled caller.
     *
     * @param user the leaving user
     * @param slug district slug
     * @throws com.neo.chat.exception.NotFoundException (TM_970) if the slug is unknown
     */
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

    /**
     * Resolves a slug to its {@link CityLocation} or fails.
     *
     * @param slug district slug
     * @return the matching location
     * @throws com.neo.chat.exception.NotFoundException (TM_970) if the slug is unknown
     */
    private CityLocation require(String slug) {
        CityLocation loc = CityLocation.fromSlug(slug);
        if (loc == null) {
            throw new NotFoundException("Unknown city district: " + slug, "TM_970");
        }
        return loc;
    }

    /**
     * Builds a district card, computing live count as presence-members intersected with the
     * supplied online set and room count as the curated ROOM chats for the location.
     *
     * @param loc    the district
     * @param online the current global online-username set
     * @return the card DTO
     */
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

    /**
     * Maps a curated room Chat entity to a discovery {@link ChatResponse} card.
     *
     * @param chat the room chat
     * @return the room card
     */
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

    /**
     * Cached member count for a chat, falling back to 0 on any lookup failure.
     *
     * @param chat the chat
     * @return member count (0 on failure)
     */
    private int safeMemberCount(Chat chat) {
        try {
            return memberCountCache.get(chat);
        } catch (Exception e) {
            log.debug("City member-count lookup failed for {}: {}", chat.getUuid(), e.getMessage());
            return 0;
        }
    }

    /**
     * Set members currently online (sorted); prunes stale offline entries best-effort.
     */
    private List<String> liveRoster(String slug, Set<String> online) {
        Set<String> members = members(slug);
        List<String> live = members.stream().filter(online::contains).sorted().collect(Collectors.toList());
        if (members.size() > live.size()) {
            try {
                Set<String> stale = new HashSet<>(members);
                live.forEach(stale::remove);
                if (!stale.isEmpty()) {
                    redis.opsForSet().remove(key(slug), stale.toArray());
                }
            } catch (Exception e) {
                log.debug("City roster self-heal skipped for {}: {}", slug, e.getMessage());
            }
        }
        return live;
    }

    /**
     * Reads the raw Redis presence set for a district slug; empty on read failure (fail-open).
     *
     * @param slug district slug
     * @return the set of member usernames (possibly stale/offline)
     */
    private Set<String> members(String slug) {
        try {
            Set<String> m = redis.opsForSet().members(key(slug));
            return m != null ? m : Collections.emptySet();
        } catch (Exception e) {
            log.debug("City presence read skipped for {}: {}", slug, e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * The global online-username set from {@link PresenceService}; empty on failure (fail-open).
     *
     * @return online usernames
     */
    private Set<String> safeOnline() {
        try {
            Set<String> online = presenceService.getOnlineUsernames();
            return online != null ? online : Collections.emptySet();
        } catch (Exception e) {
            log.debug("City online-presence lookup failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Best-effort WebSocket broadcast to {@code /topic/city/{slug}} carrying the event, the
     * acting username and the online-intersected live count (so the WS badge matches the REST
     * card). Swallows any failure.
     *
     * @param event event name (e.g. user_joined / user_left)
     * @param loc   the district
     * @param user  the acting user
     */
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

    /**
     * The Redis presence-set key for a district slug.
     *
     * @param slug district slug
     * @return the namespaced Redis key
     */
    private static String key(String slug) {
        return KEY_PREFIX + slug;
    }
}
