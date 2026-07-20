package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.ConsentStatus;
import com.chat.talkMe.match.MatchConsentService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchConsentServiceImpl implements MatchConsentService {

    /** Mirrors the 1:1 consent cap so the experience is consistent across surfaces. */
    private static final int MAX_DECLINES = 3;

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void handleHeldExplicit(String sender, String clientId, MatchSession session) {
        ConsentStatus status = session.getConsentStatus();
        boolean limitReached = session.getConsentDeclineCount() >= MAX_DECLINES;

        // Auto-ask the peer when we're allowed to: a fresh session (NONE) or a prior
        // decline that's still under the cap. While a request is already PENDING — or
        // after the cap is hit — we hold silently without re-pinging the peer.
        if (status == ConsentStatus.NONE || (status == ConsentStatus.DECLINED && !limitReached)) {
            session.setConsentStatus(ConsentStatus.PENDING);
            session.setConsentRequestedBy(sender);

            String recipient = peer(session, sender);
            // Anonymous — the peer only learns a request arrived, never who/what.
            messagingTemplate.convertAndSendToUser(recipient, "/queue/match",
                    MatchServerEvent.builder().event("CONSENT_REQUEST_RECEIVED").payload(Map.of()).build());
            log.info("Auto-requested 18+ consent for session {}", session.getId());
        }

        // Tell the sender their message was held so the UI flags it in-place (no toast).
        messagingTemplate.convertAndSendToUser(sender, "/queue/match",
                MatchServerEvent.builder()
                        .event("EXPLICIT_HELD")
                        .payload(Map.of(
                                "clientId", clientId == null ? "" : clientId,
                                "status", session.getConsentStatus().name(),
                                "declineCount", session.getConsentDeclineCount(),
                                "limitReached", session.getConsentDeclineCount() >= MAX_DECLINES))
                        .build());
    }

    @Override
    public void acceptConsent(String accepter) {
        MatchSession session = sessionService.getSessionByUser(accepter)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + accepter));

        session.setConsentStatus(ConsentStatus.GRANTED);
        session.setConsentDeclineCount(0);
        session.setConsentRequestedBy(null);

        MatchServerEvent event = MatchServerEvent.builder()
                .event("CONSENT_GRANTED")
                .payload(Map.of()) // anonymous — signal only
                .build();
        messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", event);
        messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", event);
        log.info("18+ consent granted for session {}", session.getId());
    }

    @Override
    public void declineConsent(String decliner) {
        MatchSession session = sessionService.getSessionByUser(decliner)
                .orElseThrow(() -> new IllegalArgumentException("No active session found for user: " + decliner));

        session.setConsentStatus(ConsentStatus.DECLINED);
        session.setConsentDeclineCount(session.getConsentDeclineCount() + 1);
        session.setConsentRequestedBy(null);
        boolean limitReached = session.getConsentDeclineCount() >= MAX_DECLINES;

        MatchServerEvent event = MatchServerEvent.builder()
                .event("CONSENT_DECLINED")
                .payload(Map.of("limitReached", limitReached)) // anonymous — no identity
                .build();
        messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", event);
        messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", event);
        log.info("18+ consent declined for session {} (count {})", session.getId(), session.getConsentDeclineCount());
    }

    private String peer(MatchSession session, String user) {
        return session.getUserA().equals(user) ? session.getUserB() : session.getUserA();
    }
}
