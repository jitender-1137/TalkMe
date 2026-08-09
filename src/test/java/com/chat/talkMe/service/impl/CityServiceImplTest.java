package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.MemberCountCache;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.CityDistrictDetailResponse;
import com.chat.talkMe.dto.response.CityDistrictResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.ChatVisibility;
import com.chat.talkMe.enums.CityLocation;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.JoinPolicy;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link CityServiceImpl} — the Virtual Night City discovery layer.
 *
 * <p>Everything Redis/presence-backed is fail-open: an outage degrades counts/rosters to empty,
 * never a failed request. The tests assert (1) the district cards and detail views map correctly
 * off {@link CityLocation} + curated ROOM chats, (2) presence writes/reads and the WS broadcast
 * are best-effort (swallowed on error), (3) an unknown slug is a hard {@code TM_970}, and (4) the
 * live roster self-heals stale offline usernames out of the Redis set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CityServiceImpl (unit)")
class CityServiceImplTest {

    private static final String SLUG = "neon-district";
    private static final String KEY = "city:presence:neon-district";
    private static final String TOPIC = "/topic/city/neon-district";

    @Mock private ChatRepository chatRepository;
    @Mock private MemberCountCache memberCountCache;
    @Mock private PresenceService presenceService;
    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOps;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private CityServiceImpl service;
    private User alice;

    @BeforeEach
    void setUp() {
        service = new CityServiceImpl(chatRepository, memberCountCache, presenceService, redis, messagingTemplate);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        alice = User.builder().username("alice").build();
        alice.setId(1L);
    }

    private Chat room(String name, boolean curated) {
        Chat c = Chat.builder()
                .name(name)
                .chatType(ChatType.ROOM)
                .visibility(ChatVisibility.PUBLIC)
                .joinPolicy(JoinPolicy.OPEN)
                .roomCurated(curated)
                .memberLimit(256)
                .description("desc")
                .imageUrl("img")
                .slug("room-" + name)
                .category("chill")
                .build();
        c.setUuid(UUID.randomUUID());
        return c;
    }

    @Nested
    @DisplayName("listDistricts")
    class ListDistricts {

        @Test
        @DisplayName("returns one card per CityLocation with live + room counts")
        void nominal() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(any(CityLocation.class)))
                    .thenReturn(List.of(room("neon", true), room("ghost", false)));

            List<CityDistrictResponse> out = service.listDistricts();

            assertThat(out).hasSize(CityLocation.values().length);
            CityDistrictResponse first = out.get(0);
            assertThat(first.getSlug()).isEqualTo(SLUG);
            assertThat(first.getLabel()).isEqualTo("Neon District");
            assertThat(first.getEmoji()).isEqualTo("🌆");
            assertThat(first.getTagline()).isNotBlank();
            assertThat(first.getLiveCount()).isEqualTo(1);   // alice online ∩ present
            assertThat(first.getRoomCount()).isEqualTo(1);   // only the curated room counts
        }

        @Test
        @DisplayName("presence lookup failure → live counts degrade to 0, no error")
        void presenceFailOpen() {
            when(presenceService.getOnlineUsernames()).thenThrow(new RuntimeException("redis down"));
            lenient().when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(any(CityLocation.class)))
                    .thenReturn(List.of(room("neon", true)));

            List<CityDistrictResponse> out = service.listDistricts();

            assertThat(out).hasSize(CityLocation.values().length);
            assertThat(out.get(0).getLiveCount()).isZero();
            assertThat(out.get(0).getRoomCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("presence-set read failure → members degrade to empty, live count 0")
        void membersReadFailOpen() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenThrow(new RuntimeException("redis down"));
            when(chatRepository.findByCityLocation(any(CityLocation.class))).thenReturn(List.of());

            List<CityDistrictResponse> out = service.listDistricts();

            assertThat(out.get(0).getLiveCount()).isZero();
            assertThat(out.get(0).getRoomCount()).isZero();
        }
    }

    @Nested
    @DisplayName("getDistrict")
    class GetDistrict {

        @Test
        @DisplayName("valid slug → district card + curated rooms + online roster")
        void nominal() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT))
                    .thenReturn(List.of(room("neon", true), room("ghost", false)));
            when(memberCountCache.get(any(Chat.class))).thenReturn(7);

            CityDistrictDetailResponse out = service.getDistrict(SLUG, alice);

            assertThat(out.getDistrict().getSlug()).isEqualTo(SLUG);
            assertThat(out.getRooms()).hasSize(1);
            assertThat(out.getOnlineUsernames()).containsExactly("alice");
        }

        @Test
        @DisplayName("unknown slug → NotFoundException TM_970")
        void unknownSlug() {
            assertThatThrownBy(() -> service.getDistrict("no-such-place", alice))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_970"));
        }

        @Test
        @DisplayName("roster contains an offline member → self-heals it out of the Redis set")
        void selfHealsStaleRoster() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice", "ghost"));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of());

            CityDistrictDetailResponse out = service.getDistrict(SLUG, alice);

            assertThat(out.getOnlineUsernames()).containsExactly("alice");
            verify(setOps).remove(eq(KEY), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("getRooms")
    class GetRooms {

        @Test
        @DisplayName("valid slug → curated room cards only, with cached member count")
        void nominal() {
            Chat curated = room("neon", true);
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT))
                    .thenReturn(List.of(curated, room("ghost", false)));
            when(memberCountCache.get(curated)).thenReturn(5);

            List<ChatResponse> out = service.getRooms(SLUG, alice);

            assertThat(out).hasSize(1);
            ChatResponse card = out.get(0);
            assertThat(card.getId()).isEqualTo(curated.getUuid().toString());
            assertThat(card.getName()).isEqualTo("neon");
            assertThat(card.getChatType()).isEqualTo("ROOM");
            assertThat(card.getGroup().getSubtype()).isEqualTo("room");
            assertThat(card.getGroup().getVisibility()).isEqualTo("PUBLIC");
            assertThat(card.getGroup().getJoinPolicy()).isEqualTo("OPEN");
            assertThat(card.getGroup().getMemberCount()).isEqualTo(5);
            assertThat(card.getGroup().getTags()).isEmpty();
        }

        @Test
        @DisplayName("unknown slug → NotFoundException TM_970")
        void unknownSlug() {
            assertThatThrownBy(() -> service.getRooms("no-such-place", alice))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_970"));
        }

        @Test
        @DisplayName("member-count lookup failure → count degrades to 0, no error")
        void memberCountFailOpen() {
            Chat curated = room("neon", true);
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of(curated));
            when(memberCountCache.get(curated)).thenThrow(new RuntimeException("redis down"));

            List<ChatResponse> out = service.getRooms(SLUG, alice);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getGroup().getMemberCount()).isZero();
        }
    }

    @Nested
    @DisplayName("enterDistrict")
    class EnterDistrict {

        @Test
        @DisplayName("valid slug → adds to presence set, refreshes TTL, broadcasts user_joined, returns detail")
        void nominal() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of());

            CityDistrictDetailResponse out = service.enterDistrict(alice, SLUG);

            assertThat(out).isNotNull();
            verify(setOps).add(KEY, "alice");
            verify(redis).expire(KEY, Duration.ofHours(12));

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq(TOPIC), payload.capture());
            assertThat(payload.getValue()).isInstanceOfSatisfying(Map.class,
                    m -> assertThat(m.get("event")).isEqualTo("user_joined"));
        }

        @Test
        @DisplayName("unknown slug → TM_970, no presence write, no broadcast")
        void unknownSlug() {
            assertThatThrownBy(() -> service.enterDistrict(alice, "no-such-place"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_970"));
            verify(setOps, never()).add(anyString(), anyString());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("presence write failure → swallowed, still broadcasts and returns detail")
        void presenceWriteFailOpen() {
            when(setOps.add(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));
            lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            lenient().when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of());

            CityDistrictDetailResponse out = service.enterDistrict(alice, SLUG);

            assertThat(out).isNotNull();
            verify(messagingTemplate).convertAndSend(eq(TOPIC), any(Object.class));
        }
    }

    @Nested
    @DisplayName("leaveDistrict")
    class LeaveDistrict {

        @Test
        @DisplayName("valid slug → removes from presence set and broadcasts user_left")
        void nominal() {
            lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            lenient().when(setOps.members(anyString())).thenReturn(Set.of());

            service.leaveDistrict(alice, SLUG);

            verify(setOps).remove(KEY, "alice");
            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq(TOPIC), payload.capture());
            assertThat(payload.getValue()).isInstanceOfSatisfying(Map.class,
                    m -> assertThat(m.get("event")).isEqualTo("user_left"));
        }

        @Test
        @DisplayName("unknown slug → TM_970, no presence write, no broadcast")
        void unknownSlug() {
            assertThatThrownBy(() -> service.leaveDistrict(alice, "no-such-place"))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_970"));
            verify(setOps, never()).remove(anyString(), any());
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("presence removal failure → swallowed, still broadcasts")
        void removalFailOpen() {
            when(setOps.remove(anyString(), any())).thenThrow(new RuntimeException("redis down"));
            lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            lenient().when(setOps.members(anyString())).thenReturn(Set.of());

            service.leaveDistrict(alice, SLUG);

            verify(messagingTemplate).convertAndSend(eq(TOPIC), any(Object.class));
        }
    }

    @Nested
    @DisplayName("fail-open & mapping branch backfill")
    class BranchBackfill {

        @Test
        @DisplayName("roster self-heal removal failure → swallowed, still returns live roster")
        void selfHealRemovalFailOpen() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice", "ghost"));
            when(setOps.remove(anyString(), any(Object[].class))).thenThrow(new RuntimeException("redis down"));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of());

            CityDistrictDetailResponse out = service.getDistrict(SLUG, alice);

            assertThat(out.getOnlineUsernames()).containsExactly("alice");
        }

        @Test
        @DisplayName("room with tags → tag enum names mapped into the card")
        void roomWithTagsMapsTagNames() {
            Chat curated = room("neon", true);
            curated.setTags(new HashSet<>(Set.of(Interest.MUSIC, Interest.GAMING)));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of(curated));
            when(memberCountCache.get(curated)).thenReturn(3);

            List<ChatResponse> out = service.getRooms(SLUG, alice);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getGroup().getTags())
                    .containsExactlyInAnyOrder("MUSIC", "GAMING");
        }

        @Test
        @DisplayName("presence service returns null online set → degrades to empty, live 0")
        void onlineNullDegrades() {
            when(presenceService.getOnlineUsernames()).thenReturn(null);
            when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(any(CityLocation.class))).thenReturn(List.of());

            List<CityDistrictResponse> out = service.listDistricts();

            assertThat(out.get(0).getLiveCount()).isZero();
        }

        @Test
        @DisplayName("presence set returns null members → degrades to empty, live 0")
        void membersNullDegrades() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(null);
            when(chatRepository.findByCityLocation(any(CityLocation.class))).thenReturn(List.of());

            List<CityDistrictResponse> out = service.listDistricts();

            assertThat(out.get(0).getLiveCount()).isZero();
        }

        @Test
        @DisplayName("WS broadcast failure → swallowed, enter still returns detail")
        void broadcastFailOpen() {
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("alice"));
            when(setOps.members(anyString())).thenReturn(Set.of("alice"));
            when(chatRepository.findByCityLocation(CityLocation.NEON_DISTRICT)).thenReturn(List.of());
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            CityDistrictDetailResponse out = service.enterDistrict(alice, SLUG);

            assertThat(out).isNotNull();
        }
    }
}
