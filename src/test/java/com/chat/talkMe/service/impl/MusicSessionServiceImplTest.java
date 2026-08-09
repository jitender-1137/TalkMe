package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MusicPlayRequest;
import com.chat.talkMe.dto.response.MusicSessionState;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
 * Pure Mockito unit test for {@link MusicSessionServiceImpl} — the Redis-ephemeral shared
 * per-chat music session (feature #17).
 *
 * <p>Key invariants under test: (1) every entry point enforces the IDOR membership guard
 * (invalid uuid → TM_400, non-member → TM_103); (2) reads/writes fail open against Redis;
 * (3) each mutation broadcasts the matching {@code music_*} event on the chat's music topic;
 * (4) {@code play} chooses its start position from explicit &gt; same-track-resume &gt; 0;
 * (5) pause/seek/react require a live session (TM_802) and validate their inputs
 * (TM_800/TM_801/TM_803); (6) a reaction refreshes the TTL without changing playback and
 * truncates an over-long emoji.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MusicSessionServiceImpl (unit)")
class MusicSessionServiceImplTest {

    private static final String CHAT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String KEY = "music:session:" + CHAT_ID;
    private static final Duration TTL = Duration.ofHours(6);
    private static final String HOST = "alice";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatMemberRepository chatMemberRepository;

    private ObjectMapper objectMapper;
    private MusicSessionServiceImpl service;

    private User user;
    private Chat chat;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MusicSessionServiceImpl(redis, objectMapper, messagingTemplate,
                chatRepository, chatMemberRepository);

        user = User.builder().username(HOST).build();
        user.setId(1L);
        user.setUuid(UUID.randomUUID());

        chat = Chat.builder().build();
        chat.setId(9L);
        chat.setUuid(UUID.fromString(CHAT_ID));
    }

    /** Make the caller a member of the chat behind {@code CHAT_ID}. */
    private void asMember() {
        when(chatRepository.findByUuid(UUID.fromString(CHAT_ID))).thenReturn(Optional.of(chat));
        when(chatMemberRepository.findByChatAndUser(chat, user))
                .thenReturn(Optional.of(new ChatMember()));
    }

    /** Stub the shared Redis value ops so read/write are routed through {@link #valueOps}. */
    private void withValueOps() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** Serialize a state exactly as the impl would, so a stubbed GET round-trips cleanly. */
    private String json(MusicSessionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MusicSessionState liveState(String trackId, double position, boolean playing) {
        return MusicSessionState.builder()
                .trackId(trackId)
                .url("https://cdn/track.mp3")
                .title("Song").artist("Artist").artworkUrl("https://cdn/art.png")
                .positionSec(position)
                .playing(playing)
                .updatedAtEpochMs(1_000L)
                .hostUsername("bob")
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureBroadcast(String expectedTopic) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(expectedTopic), payload.capture());
        return (Map<String, Object>) payload.getValue();
    }

    @Nested
    @DisplayName("membership guard (shared across all entry points)")
    class MembershipGuard {

        @Test
        @DisplayName("invalid chat uuid → BadRequestException TM_400")
        void invalidUuid() {
            assertThatThrownBy(() -> service.getSession(user, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("chat not found → non-member → ForbiddenException TM_103")
        void chatNotFound() {
            when(chatRepository.findByUuid(UUID.fromString(CHAT_ID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSession(user, CHAT_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("caller not a member of the chat → ForbiddenException TM_103")
        void notAMember() {
            when(chatRepository.findByUuid(UUID.fromString(CHAT_ID))).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.play(user, CHAT_ID, new MusicPlayRequest()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Nested
    @DisplayName("getSession")
    class GetSession {

        @Test
        @DisplayName("no live session → not-playing shell with fresh clock refs")
        void noSessionShell() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);

            MusicSessionState result = service.getSession(user, CHAT_ID);

            assertThat(result.isPlaying()).isFalse();
            assertThat(result.getPositionSec()).isZero();
            assertThat(result.getUpdatedAtEpochMs()).isPositive();
            assertThat(result.getServerTimeEpochMs()).isPositive();
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("live session → returned as-is with serverTime stamped fresh")
        void liveSession() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 42.0, true)));

            MusicSessionState result = service.getSession(user, CHAT_ID);

            assertThat(result.isPlaying()).isTrue();
            assertThat(result.getPositionSec()).isEqualTo(42.0);
            assertThat(result.getTrackId()).isEqualTo("t1");
            assertThat(result.getServerTimeEpochMs()).isPositive();
        }

        @Test
        @DisplayName("Redis read blows up → fails open to the not-playing shell")
        void readFailsOpen() {
            asMember();
            when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

            MusicSessionState result = service.getSession(user, CHAT_ID);

            assertThat(result.isPlaying()).isFalse();
            assertThat(result.getServerTimeEpochMs()).isPositive();
        }
    }

    @Nested
    @DisplayName("play")
    class Play {

        @Test
        @DisplayName("null request → BadRequestException TM_800")
        void nullRequest() {
            asMember();
            assertThatThrownBy(() -> service.play(user, CHAT_ID, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_800"));
        }

        @Test
        @DisplayName("null url → BadRequestException TM_800")
        void nullUrl() {
            asMember();
            MusicPlayRequest req = new MusicPlayRequest();
            req.setUrl(null);
            assertThatThrownBy(() -> service.play(user, CHAT_ID, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_800"));
        }

        @Test
        @DisplayName("blank url → BadRequestException TM_800")
        void blankUrl() {
            asMember();
            MusicPlayRequest req = new MusicPlayRequest();
            req.setUrl("   ");
            assertThatThrownBy(() -> service.play(user, CHAT_ID, req))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_800"));
        }

        @Test
        @DisplayName("nominal → writes trimmed state, broadcasts music_play, host stamped")
        void nominal() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);
            MusicPlayRequest req = new MusicPlayRequest();
            req.setTrackId("t1");
            req.setUrl("  https://cdn/track.mp3  ");
            req.setTitle("Song");
            req.setArtist("Artist");
            req.setArtworkUrl("https://cdn/art.png");
            req.setPositionSec(12.5);

            MusicSessionState result = service.play(user, CHAT_ID, req);

            assertThat(result.isPlaying()).isTrue();
            assertThat(result.getUrl()).isEqualTo("https://cdn/track.mp3");
            assertThat(result.getPositionSec()).isEqualTo(12.5);
            assertThat(result.getHostUsername()).isEqualTo(HOST);
            assertThat(result.getUpdatedAtEpochMs()).isPositive();

            verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
            Map<String, Object> body = captureBroadcast("/topic/chat/" + CHAT_ID + "/music");
            assertThat(body.get("event")).isEqualTo("music_play");
            assertThat(body.get("payload")).isSameAs(result);
        }

        @Test
        @DisplayName("explicit negative positionSec is clamped to 0")
        void negativePositionClamped() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);
            MusicPlayRequest req = new MusicPlayRequest();
            req.setUrl("https://cdn/track.mp3");
            req.setPositionSec(-5.0);

            MusicSessionState result = service.play(user, CHAT_ID, req);

            assertThat(result.getPositionSec()).isZero();
        }

        @Test
        @DisplayName("null position + same trackId resumes the existing playhead")
        void resumesSameTrack() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 88.0, false)));
            MusicPlayRequest req = new MusicPlayRequest();
            req.setTrackId("t1");
            req.setUrl("https://cdn/track.mp3");
            req.setPositionSec(null);

            MusicSessionState result = service.play(user, CHAT_ID, req);

            assertThat(result.getPositionSec()).isEqualTo(88.0);
            assertThat(result.isPlaying()).isTrue();
        }

        @Test
        @DisplayName("null position + different track starts from 0")
        void differentTrackStartsAtZero() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 88.0, true)));
            MusicPlayRequest req = new MusicPlayRequest();
            req.setTrackId("t2");
            req.setUrl("https://cdn/other.mp3");
            req.setPositionSec(null);

            MusicSessionState result = service.play(user, CHAT_ID, req);

            assertThat(result.getPositionSec()).isZero();
        }

        @Test
        @DisplayName("broadcast failure is swallowed — play still returns the new state")
        void broadcastFailsOpen() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);
            Mockito.doThrow(new RuntimeException("ws down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));
            MusicPlayRequest req = new MusicPlayRequest();
            req.setUrl("https://cdn/track.mp3");

            MusicSessionState result = service.play(user, CHAT_ID, req);

            assertThat(result.isPlaying()).isTrue();
        }
    }

    @Nested
    @DisplayName("pause")
    class Pause {

        @Test
        @DisplayName("no live session → BadRequestException TM_802")
        void noSession() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);

            assertThatThrownBy(() -> service.pause(user, CHAT_ID, 10.0))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_802"));
        }

        @Test
        @DisplayName("with positionSec → updates playhead, pauses, broadcasts music_pause")
        void withPosition() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 5.0, true)));

            MusicSessionState result = service.pause(user, CHAT_ID, 33.0);

            assertThat(result.isPlaying()).isFalse();
            assertThat(result.getPositionSec()).isEqualTo(33.0);
            assertThat(result.getHostUsername()).isEqualTo(HOST);
            verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
            assertThat(captureBroadcast("/topic/chat/" + CHAT_ID + "/music").get("event"))
                    .isEqualTo("music_pause");
        }

        @Test
        @DisplayName("negative positionSec is clamped to 0")
        void negativeClamped() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 5.0, true)));

            MusicSessionState result = service.pause(user, CHAT_ID, -9.0);

            assertThat(result.getPositionSec()).isZero();
        }

        @Test
        @DisplayName("null positionSec keeps the existing playhead")
        void nullPositionKept() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 5.0, true)));

            MusicSessionState result = service.pause(user, CHAT_ID, null);

            assertThat(result.getPositionSec()).isEqualTo(5.0);
            assertThat(result.isPlaying()).isFalse();
        }
    }

    @Nested
    @DisplayName("seek")
    class Seek {

        @Test
        @DisplayName("null positionSec → BadRequestException TM_801")
        void nullPosition() {
            asMember();
            assertThatThrownBy(() -> service.seek(user, CHAT_ID, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_801"));
        }

        @Test
        @DisplayName("negative positionSec → BadRequestException TM_801")
        void negativePosition() {
            asMember();
            assertThatThrownBy(() -> service.seek(user, CHAT_ID, -1.0))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_801"));
        }

        @Test
        @DisplayName("no live session → BadRequestException TM_802")
        void noSession() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);

            assertThatThrownBy(() -> service.seek(user, CHAT_ID, 10.0))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_802"));
        }

        @Test
        @DisplayName("nominal → sets position, stamps host, broadcasts music_seek")
        void nominal() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 5.0, true)));

            MusicSessionState result = service.seek(user, CHAT_ID, 120.0);

            assertThat(result.getPositionSec()).isEqualTo(120.0);
            assertThat(result.getHostUsername()).isEqualTo(HOST);
            verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
            assertThat(captureBroadcast("/topic/chat/" + CHAT_ID + "/music").get("event"))
                    .isEqualTo("music_seek");
        }
    }

    @Nested
    @DisplayName("react")
    class React {

        @Test
        @DisplayName("null emoji → BadRequestException TM_803")
        void nullEmoji() {
            asMember();
            assertThatThrownBy(() -> service.react(user, CHAT_ID, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_803"));
        }

        @Test
        @DisplayName("blank emoji → BadRequestException TM_803")
        void blankEmoji() {
            asMember();
            assertThatThrownBy(() -> service.react(user, CHAT_ID, "   "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_803"));
        }

        @Test
        @DisplayName("no live session → BadRequestException TM_802")
        void noSession() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(null);

            assertThatThrownBy(() -> service.react(user, CHAT_ID, "🔥"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_802"));
        }

        @Test
        @DisplayName("nominal → refreshes TTL, keeps playback, broadcasts music_react payload")
        void nominal() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 5.0, true)));

            MusicSessionState result = service.react(user, CHAT_ID, "  🔥  ");

            assertThat(result.isPlaying()).isTrue();
            verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
            Map<String, Object> body = captureBroadcast("/topic/chat/" + CHAT_ID + "/music");
            assertThat(body.get("event")).isEqualTo("music_react");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) body.get("payload");
            assertThat(payload.get("emoji")).isEqualTo("🔥");
            assertThat(payload.get("by")).isEqualTo(HOST);
            assertThat(payload.get("session")).isSameAs(result);
        }

        @Test
        @DisplayName("over-long emoji is truncated to the max length")
        void truncatesLongEmoji() {
            asMember();
            withValueOps();
            when(valueOps.get(KEY)).thenReturn(json(liveState("t1", 5.0, true)));
            String longEmoji = "x".repeat(40);

            service.react(user, CHAT_ID, longEmoji);

            Map<String, Object> body = captureBroadcast("/topic/chat/" + CHAT_ID + "/music");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) body.get("payload");
            assertThat((String) payload.get("emoji")).hasSize(24);
        }
    }
}
