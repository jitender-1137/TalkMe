package com.neo.chat.service.impl;

import com.neo.chat.config.LiveAudioProperties;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.LiveTokenResponse;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link LiveAudioServiceImpl} — the LiveKit HS256 token minter (Phase 6).
 * Verifies the not-ready guard, the chat-member IDOR authz, and that a happy-path token is a valid
 * JWT carrying the correct issuer/subject/video-grant claims.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LiveAudioServiceImpl (unit)")
class LiveAudioServiceImplTest {

    // HS256 needs a >= 256-bit (32-byte) secret.
    private static final String API_SECRET = "super-secret-signing-key-01234567890123456789";
    private static final String API_KEY = "livekit-api-key";
    private static final String WS_URL = "wss://live.example.com";
    private static final String CHAT_ID = "33333333-3333-3333-3333-333333333333";
    private static final UUID CHAT_UUID = UUID.fromString(CHAT_ID);

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;

    private LiveAudioProperties props;
    private LiveAudioServiceImpl service;
    private User user;
    private Chat chat;

    @BeforeEach
    void setUp() {
        props = new LiveAudioProperties(true, API_KEY, API_SECRET, WS_URL, 3600);
        service = new LiveAudioServiceImpl(props, chatRepository, chatMemberRepository);

        user = User.builder().username("alice").name("Alice Wonder").build();
        user.setId(11L);
        user.setUuid(UUID.randomUUID());

        chat = Chat.builder().name("Voice Room").build();
        chat.setId(5L);
        chat.setUuid(CHAT_UUID);
    }

    private void stubMember() {
        when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.of(chat));
        when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(new ChatMember()));
    }

    @Nested
    @DisplayName("mintToken")
    class MintToken {

        @Test
        @DisplayName("seam not ready → BadRequest TM_980, no repo access")
        void notReady() {
            // seam switched off → rebuild the (immutable) props and the service under test
            props = new LiveAudioProperties(false, API_KEY, API_SECRET, WS_URL, 3600);
            service = new LiveAudioServiceImpl(props, chatRepository, chatMemberRepository);

            assertThatThrownBy(() -> service.mintToken(user, CHAT_ID))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_980"));
            verifyNoInteractions(chatRepository, chatMemberRepository);
        }

        @Test
        @DisplayName("malformed chat uuid → BadRequest TM_400")
        void malformedUuid() {
            assertThatThrownBy(() -> service.mintToken(user, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("chat not found → NotFound TM_981")
        void chatNotFound() {
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.mintToken(user, CHAT_ID))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_981"));
            verify(chatMemberRepository, never()).findByChatAndUser(any(), any());
        }

        @Test
        @DisplayName("caller not a member → Forbidden TM_103")
        void notMember() {
            when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.of(chat));
            when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.mintToken(user, CHAT_ID))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_103"));
        }

        @Test
        @DisplayName("happy path → valid JWT with issuer, subject and video grant scoped to the room")
        void mintsValidToken() {
            stubMember();

            LiveTokenResponse resp = service.mintToken(user, CHAT_ID);

            assertThat(resp.getWsUrl()).isEqualTo(WS_URL);
            assertThat(resp.getRoom()).isEqualTo(CHAT_ID);
            assertThat(resp.getIdentity()).isEqualTo("alice");
            assertThat(resp.getToken()).isNotBlank();

            Claims claims = parse(resp.getToken());
            assertThat(claims.getIssuer()).isEqualTo(API_KEY);
            assertThat(claims.getSubject()).isEqualTo("alice");
            assertThat(claims.get("name")).isEqualTo("Alice Wonder");
            assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());

            @SuppressWarnings("unchecked")
            Map<String, Object> video = claims.get("video", Map.class);
            assertThat(video.get("room")).isEqualTo(CHAT_ID);
            assertThat(video.get("roomJoin")).isEqualTo(Boolean.TRUE);
            assertThat(video.get("canPublish")).isEqualTo(Boolean.TRUE);
            assertThat(video.get("canSubscribe")).isEqualTo(Boolean.TRUE);
            assertThat(video.get("canPublishData")).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("name claim falls back to the username when the display name is null")
        void nameFallsBackToIdentity() {
            user.setName(null);
            stubMember();

            LiveTokenResponse resp = service.mintToken(user, CHAT_ID);

            Claims claims = parse(resp.getToken());
            assertThat(claims.get("name")).isEqualTo("alice");
        }
    }

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(API_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
