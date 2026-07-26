package com.chat.talkMe.match.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.RevealChannel;
import com.chat.talkMe.enums.RevealState;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.RevealService;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutual reveal handshake. "Request" means the requester is willing to reveal that
 * channel and asks the peer; the channel only flips to REVEALED (and payloads are
 * exchanged) once the peer also accepts. Identity is exposed at exactly one point —
 * a mutual PROFILE reveal — nowhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RevealServiceImpl implements RevealService {

    private static final int MAX_DECLINES = 3;

    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** When true, a PHOTO reveal is blocked until VOICE is mutually revealed (feature #15). */
    @Value("${match.voice-before-photo.enabled:false}")
    private boolean voiceBeforePhoto;

    @Override
    public void requestReveal(String requester, RevealChannel channel) {
        MatchSession session = session(requester);
        // Serialize the read-modify-write of the shared reveal state so two concurrent
        // peers can't both drive the channel to a mutual reveal (double-exchange).
        synchronized (session) {
            if (session.getRevealExchanged().contains(channel)) return; // already done — terminal

            if (!photoAllowed(session, channel)) {
                send(requester, "REVEAL_BLOCKED", Map.of("channel", "PHOTO", "requires", "VOICE"));
                return;
            }
            if (declineCount(session, channel) >= MAX_DECLINES) {
                send(requester, "REVEAL_DECLINED", Map.of("channel", channel.name(), "limitReached", true));
                return;
            }

            String peer = peerOf(session, requester);
            mapFor(session, requester).put(channel, RevealState.REVEALED);
            session.getRevealRequestedBy().put(channel, requester);

            if (mapFor(session, peer).get(channel) == RevealState.REVEALED) {
                exchange(session, channel);
                return;
            }
            send(peer, "REVEAL_REQUEST_RECEIVED", Map.of("channel", channel.name()));
            log.info("Reveal request {} from {} to peer", channel, requester);
        }
    }

    @Override
    public void acceptReveal(String accepter, RevealChannel channel) {
        MatchSession session = session(accepter);
        synchronized (session) {
            if (session.getRevealExchanged().contains(channel)) return; // already done — terminal
            // Enforce the voice-before-photo gate here too (accept is directly reachable).
            if (!photoAllowed(session, channel)) {
                send(accepter, "REVEAL_BLOCKED", Map.of("channel", "PHOTO", "requires", "VOICE"));
                return;
            }
            mapFor(session, accepter).put(channel, RevealState.REVEALED);
            if (bothRevealed(session, channel)) {
                exchange(session, channel);
            }
        }
    }

    @Override
    public void declineReveal(String decliner, RevealChannel channel) {
        MatchSession session = session(decliner);
        synchronized (session) {
            if (session.getRevealExchanged().contains(channel)) return;
            mapFor(session, decliner).put(channel, RevealState.DECLINED);
            // Reset the requester's side so it can be re-offered later (until the cap).
            String requester = session.getRevealRequestedBy().get(channel);
            if (requester != null) mapFor(session, requester).put(channel, RevealState.HIDDEN);
            int count = session.getRevealDeclineCount().merge(channel, 1, Integer::sum);
            boolean limit = count >= MAX_DECLINES;
            Map<String, Object> payload = Map.of("channel", channel.name(), "limitReached", limit);
            send(session.getUserA(), "REVEAL_DECLINED", payload);
            send(session.getUserB(), "REVEAL_DECLINED", payload);
        }
    }

    /** Voice-before-photo gate: a PHOTO reveal needs VOICE mutually revealed first (if enabled). */
    private boolean photoAllowed(MatchSession session, RevealChannel channel) {
        return !(voiceBeforePhoto && channel == RevealChannel.PHOTO
                && !session.getRevealExchanged().contains(RevealChannel.VOICE));
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private void exchange(MatchSession session, RevealChannel channel) {
        // Terminal: mark exchanged first; add() is false if already done → never fire twice.
        if (!session.getRevealExchanged().add(channel)) return;
        User a = userRepository.findByUsername(session.getUserA()).orElse(null);
        User b = userRepository.findByUsername(session.getUserB()).orElse(null);
        if (channel == RevealChannel.PHOTO) {
            sessionService.grantImagePermission(session.getId());
        }
        // Each peer receives the OTHER's payload for this channel.
        send(session.getUserA(), "REVEAL_GRANTED", grantPayload(channel, b));
        send(session.getUserB(), "REVEAL_GRANTED", grantPayload(channel, a));
        log.info("Reveal {} completed for session {}", channel, session.getId());
    }

    private Map<String, Object> grantPayload(RevealChannel channel, User other) {
        Map<String, Object> p = new HashMap<>();
        p.put("channel", channel.name());
        if (other == null) return p;
        switch (channel) {
            case PROFILE -> {
                // The single, consent-gated point identity is exposed.
                p.put("id", other.getUuid().toString());
                p.put("name", other.getName());
                p.put("username", other.getUsername());
                p.put("avatar", other.getProfileImage());
                p.put("age", other.getAge());
                p.put("gender", other.getGender());
                p.put("country", other.getCountry());
                p.put("city", other.getCity());
            }
            case VOICE -> {
                p.put("voiceIntroUrl", other.getVoiceIntroUrl());
                p.put("voiceIntroDurationMs", other.getVoiceIntroDurationMs());
            }
            case PHOTO -> p.put("avatar", other.getProfileImage());
        }
        return p;
    }

    private boolean bothRevealed(MatchSession session, RevealChannel channel) {
        return mapFor(session, session.getUserA()).get(channel) == RevealState.REVEALED
                && mapFor(session, session.getUserB()).get(channel) == RevealState.REVEALED;
    }

    private int declineCount(MatchSession session, RevealChannel channel) {
        return session.getRevealDeclineCount().getOrDefault(channel, 0);
    }

    private Map<RevealChannel, RevealState> mapFor(MatchSession session, String username) {
        return session.getUserA().equals(username) ? session.getRevealA() : session.getRevealB();
    }

    private String peerOf(MatchSession session, String username) {
        return session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
    }

    private MatchSession session(String username) {
        return sessionService.getSessionByUser(username)
                .orElseThrow(() -> new IllegalArgumentException("No active session for user: " + username));
    }

    private void send(String username, String event, Map<String, Object> payload) {
        messagingTemplate.convertAndSendToUser(username, "/queue/match",
                MatchServerEvent.builder().event(event).payload(payload).build());
    }
}
