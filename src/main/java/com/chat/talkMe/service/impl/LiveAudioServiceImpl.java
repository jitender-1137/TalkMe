package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.LiveAudioProperties;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.LiveTokenResponse;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.service.LiveAudioService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * LiveKit token minter (Phase 6). A LiveKit access token is a standard HS256 JWT signed with the
 * API secret, carrying a {@code video} grant claim that scopes the holder to one room. We reuse
 * the app's bundled JJWT rather than add a LiveKit SDK dependency ($0, zero new deps).
 *
 * <p>Authz mirrors the rest of the chat surface: the caller must be a member of the target chat
 * (the same check {@code WebSocketChannelInterceptor} enforces for STOMP). The seam is inert until
 * {@code app.live-audio.enabled} + a key pair + ws URL are configured (see {@link LiveAudioProperties}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LiveAudioServiceImpl implements LiveAudioService {

    private final LiveAudioProperties props;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;

    @Override
    public LiveTokenResponse mintToken(User user, String chatUuid) {
        if (!props.isReady()) {
            // Should be unreachable when the LIVE_AUDIO feature flag is off, but guard anyway.
            throw new BadRequestException("Live audio is not enabled", "TM_980");
        }
        Chat chat = resolveMemberChat(user, chatUuid);

        long nowMs = System.currentTimeMillis();
        Date now = new Date(nowMs);
        Date exp = new Date(nowMs + props.getTokenTtlSeconds() * 1000L);
        String room = chat.getUuid().toString();
        String identity = user.getUsername();

        SecretKey key = Keys.hmacShaKeyFor(props.getApiSecret().getBytes(StandardCharsets.UTF_8));

        // LiveKit video grant: join exactly this room, publish + subscribe (voice room).
        Map<String, Object> videoGrant = Map.of(
                "room", room,
                "roomJoin", true,
                "canPublish", true,
                "canSubscribe", true,
                "canPublishData", true);

        String token = Jwts.builder()
                .issuer(props.getApiKey())
                .subject(identity)
                .issuedAt(now)
                .notBefore(now)
                .expiration(exp)
                .claim("name", user.getName() != null ? user.getName() : identity)
                .claim("video", videoGrant)
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return LiveTokenResponse.builder()
                .token(token)
                .wsUrl(props.getWsUrl())
                .room(room)
                .identity(identity)
                .build();
    }

    private Chat resolveMemberChat(User user, String chatUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(chatUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid chat id", "TM_400");
        }
        Chat chat = chatRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_981"));
        if (chatMemberRepository.findByChatAndUser(chat, user).isEmpty()) {
            throw new ForbiddenException("You are not a member of this chat", "TM_103");
        }
        return chat;
    }
}
