package com.neo.chat.service.impl;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatExplicitConsent;
import com.neo.chat.domain.Message;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ConsentStateResponse;
import com.neo.chat.enums.ChatType;
import com.neo.chat.enums.ConsentStatus;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatExplicitConsentRepository;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.MessageRepository;
import com.neo.chat.service.ChatConsentService;
import com.neo.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit-content mutual-consent handshake for 1:1 chats. Drives a per-chat
 * {@link ChatExplicitConsent} state machine (NONE → PENDING → GRANTED/DECLINED) with a
 * consecutive-decline cap, releases or drops pre-consent held messages, and broadcasts each
 * transition over WebSocket to the chat topic. All operations are membership-gated and
 * restricted to 1:1 chats.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConsentServiceImpl implements ChatConsentService {

    /**
     * After this many consecutive declines no further request is allowed (either side).
     */
    private static final int MAX_DECLINES = 3;

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final ChatExplicitConsentRepository consentRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;

    /**
     * Returns the viewer-relative consent state for a chat (status, can-request/revoke flags,
     * requester/awaiting-accept flags, held-message count, decline count).
     *
     * @param chatUuid    target chat UUID
     * @param currentUser the requesting member
     * @return the consent state DTO
     * @throws com.neo.chat.exception.NotFoundException  (TM_121) if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException (TM_141) if the caller is not a member
     */
    @Override
    @Transactional(readOnly = true)
    public ConsentStateResponse getState(String chatUuid, User currentUser) {
        Chat chat = loadMemberChat(chatUuid, currentUser);
        ChatExplicitConsent consent = consentRepository.findByChat(chat).orElse(null);
        return toResponse(chatUuid, consent, currentUser, chat);
    }

    /**
     * Opens a consent request, moving NONE/DECLINED to PENDING and broadcasting
     * {@code consent_requested}. Idempotent while PENDING/GRANTED, and refused once the
     * consecutive-decline cap ({@value #MAX_DECLINES}) is reached. Transactional.
     *
     * @param chatUuid    target chat UUID
     * @param currentUser the requesting member
     * @return the updated consent state DTO
     * @throws com.neo.chat.exception.NotFoundException  (TM_121) if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException (TM_141) not a member,
     *                                                      or (TM_494) if the chat is not 1:1
     */
    @Override
    @Transactional
    public ConsentStateResponse requestConsent(String chatUuid, User currentUser) {
        Chat chat = loadMemberChat(chatUuid, currentUser);
        require1to1(chat);

        ChatExplicitConsent consent = consentRepository.findByChat(chat)
                .orElseGet(() -> ChatExplicitConsent.builder().chat(chat).status(ConsentStatus.NONE).build());

        // A fresh request is allowed from NONE (default / after revoke) and from
        // DECLINED (the previous request was turned down — either party may try again),
        // but only while under the consecutive-decline cap: after MAX_DECLINES
        // declines neither participant may request again (until consent is granted).
        // Idempotent: a pending or granted state means "one request only" — no re-notify.
        boolean underDeclineCap = consent.getDeclineCount() < MAX_DECLINES;
        if (underDeclineCap
                && (consent.getStatus() == ConsentStatus.NONE || consent.getStatus() == ConsentStatus.DECLINED)) {
            consent.setStatus(ConsentStatus.PENDING);
            consent.setRequestedBy(currentUser);
            consent.setRequestedAt(Instant.now());
            consent.setRespondedBy(null);
            consent.setRespondedAt(null);
            consent.setRevokedBy(null);
            consent.setRevokedAt(null);
            consent = consentRepository.save(consent);
            broadcastConsent(chatUuid, "consent_requested", Map.of(
                    "chatId", chatUuid,
                    "requestedBy", currentUser.getUuid().toString()));
        }
        return toResponse(chatUuid, consent, currentUser, chat);
    }

    /**
     * Revokes a previously GRANTED consent back to NONE, recording who revoked it (so only the
     * other party may immediately re-request) and broadcasting {@code consent_revoked}. No-op
     * when not currently granted. Transactional.
     *
     * @param chatUuid    target chat UUID
     * @param currentUser the revoking member
     * @return the updated consent state DTO
     * @throws com.neo.chat.exception.NotFoundException  (TM_121) if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException (TM_141) not a member,
     *                                                      or (TM_494) if the chat is not 1:1
     */
    @Override
    @Transactional
    public ConsentStateResponse revokeConsent(String chatUuid, User currentUser) {
        Chat chat = loadMemberChat(chatUuid, currentUser);
        require1to1(chat);

        ChatExplicitConsent consent = consentRepository.findByChat(chat).orElse(null);
        if (consent == null || consent.getStatus() != ConsentStatus.GRANTED) {
            return toResponse(chatUuid, consent, currentUser, chat); // nothing to revoke
        }

        // Reset to the initial (default) state; record who turned it off so they
        // can't immediately re-request — only the other party can.
        consent.setStatus(ConsentStatus.NONE);
        consent.setRevokedBy(currentUser);
        consent.setRevokedAt(Instant.now());
        consent.setRequestedBy(null);
        consent.setRequestedAt(null);
        consent.setRespondedBy(null);
        consent.setRespondedAt(null);
        consent = consentRepository.save(consent);

        broadcastConsent(chatUuid, "consent_revoked", Map.of(
                "chatId", chatUuid,
                "revokedBy", currentUser.getUuid().toString()));

        return toResponse(chatUuid, consent, currentUser, chat);
    }

    /**
     * Accepts a PENDING request (only the non-requesting party may accept), moving to GRANTED,
     * clearing the decline count, releasing the held pre-consent messages via
     * {@link MessageService#releaseHeldMessages}, and broadcasting {@code consent_granted}.
     * Idempotent when already GRANTED. Transactional.
     *
     * @param chatUuid    target chat UUID
     * @param currentUser the accepting member
     * @return the updated consent state DTO
     * @throws com.neo.chat.exception.NotFoundException  (TM_121) chat missing, or (TM_491)
     *                                                      if there is no consent request to accept
     * @throws com.neo.chat.exception.ForbiddenException (TM_141) not a member, (TM_494) not 1:1,
     *                                                      (TM_492) no pending request, or (TM_493) attempting to accept one's own request
     */
    @Override
    @Transactional
    public ConsentStateResponse acceptConsent(String chatUuid, User currentUser) {
        Chat chat = loadMemberChat(chatUuid, currentUser);
        require1to1(chat);

        ChatExplicitConsent consent = consentRepository.findByChat(chat)
                .orElseThrow(() -> new NotFoundException("No consent request to accept", "TM_491"));

        if (consent.getStatus() == ConsentStatus.GRANTED) {
            return toResponse(chatUuid, consent, currentUser, chat); // idempotent
        }
        if (consent.getStatus() != ConsentStatus.PENDING) {
            throw new ForbiddenException("No pending consent request", "TM_492");
        }
        // Only the OTHER party may accept (the requester can't self-accept).
        if (consent.getRequestedBy() != null && consent.getRequestedBy().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You cannot accept your own consent request", "TM_493");
        }

        consent.setStatus(ConsentStatus.GRANTED);
        consent.setRespondedBy(currentUser);
        consent.setRespondedAt(Instant.now());
        consent.setRevokedBy(null);
        consent.setRevokedAt(null);
        consent.setDeclineCount(0); // accepting clears the consecutive-decline cap
        consent = consentRepository.save(consent);

        // Consent granted → RELEASE the pre-consent held messages: mark them
        // RELEASED and deliver them to the recipient (this granting user) through
        // the normal durable broadcast pipeline, so both parties now see them.
        messageService.releaseHeldMessages(chat);

        broadcastConsent(chatUuid, "consent_granted", Map.of(
                "chatId", chatUuid,
                "grantedBy", currentUser.getUuid().toString()));

        return toResponse(chatUuid, consent, currentUser, chat);
    }

    /**
     * Declines a PENDING request (only the non-requesting party may decline), moving to DECLINED,
     * incrementing the consecutive-decline count, deleting the held undelivered messages, and
     * broadcasting {@code consent_declined}. Idempotent when already DECLINED. Transactional.
     *
     * @param chatUuid    target chat UUID
     * @param currentUser the declining member
     * @return the updated consent state DTO
     * @throws com.neo.chat.exception.NotFoundException  (TM_121) chat missing, or (TM_491)
     *                                                      if there is no consent request to decline
     * @throws com.neo.chat.exception.ForbiddenException (TM_141) not a member, (TM_494) not 1:1,
     *                                                      (TM_492) no pending request, or (TM_493) attempting to decline one's own request
     */
    @Override
    @Transactional
    public ConsentStateResponse declineConsent(String chatUuid, User currentUser) {
        Chat chat = loadMemberChat(chatUuid, currentUser);
        require1to1(chat);

        ChatExplicitConsent consent = consentRepository.findByChat(chat)
                .orElseThrow(() -> new NotFoundException("No consent request to decline", "TM_491"));

        if (consent.getStatus() == ConsentStatus.DECLINED) {
            return toResponse(chatUuid, consent, currentUser, chat); // idempotent
        }
        if (consent.getStatus() != ConsentStatus.PENDING) {
            throw new ForbiddenException("No pending consent request", "TM_492");
        }
        if (consent.getRequestedBy() != null && consent.getRequestedBy().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You cannot decline your own consent request", "TM_493");
        }

        consent.setStatus(ConsentStatus.DECLINED);
        consent.setRespondedBy(currentUser);
        consent.setRespondedAt(Instant.now());
        consent.setDeclineCount(consent.getDeclineCount() + 1); // count consecutive declines
        consent = consentRepository.save(consent);

        // Drop the held (undelivered) messages and tell the requester it was declined.
        List<Message> held = messageRepository.findHeldForConsent(chat);
        if (!held.isEmpty()) {
            messageRepository.deleteAll(held);
        }
        broadcastConsent(chatUuid, "consent_declined", Map.of(
                "chatId", chatUuid,
                "declinedBy", currentUser.getUuid().toString(),
                "declineCount", consent.getDeclineCount()));

        return toResponse(chatUuid, consent, currentUser, chat);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Loads a chat by UUID and asserts the caller is a member of it (IDOR guard).
     *
     * @param chatUuid    target chat UUID
     * @param currentUser the caller
     * @return the chat
     * @throws com.neo.chat.exception.NotFoundException  (TM_121) if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException (TM_141) if the caller is not a member
     */
    private Chat loadMemberChat(String chatUuid, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));
        return chat;
    }

    /**
     * Asserts the chat is a 1:1 (not a GROUP), since consent applies only to 1:1 chats.
     *
     * @param chat the chat
     * @throws com.neo.chat.exception.ForbiddenException (TM_494) if the chat is a group
     */
    private void require1to1(Chat chat) {
        if (chat.getChatType() == ChatType.GROUP) {
            throw new ForbiddenException("Consent is only available in 1:1 chats", "TM_494");
        }
    }

    /**
     * Assembles the viewer-relative {@link ConsentStateResponse} from the (possibly null)
     * consent entity, including the count of held messages sent by the caller and the derived
     * can-request/can-revoke/awaiting-accept flags.
     *
     * @param chatUuid    target chat UUID
     * @param consent     the current consent entity, or null when none exists
     * @param currentUser the requesting member
     * @param chat        the chat (for held-message counting)
     * @return the assembled DTO
     */
    private ConsentStateResponse toResponse(String chatUuid, ChatExplicitConsent consent, User currentUser, Chat chat) {
        ConsentStatus status = consent != null ? consent.getStatus() : ConsentStatus.NONE;
        boolean isRequester = consent != null && consent.getRequestedBy() != null
                && consent.getRequestedBy().getId().equals(currentUser.getId());
        boolean pending = status == ConsentStatus.PENDING;

        long heldForMe = messageRepository.findHeldForConsent(chat).stream()
                .filter(m -> m.getSender().getId().equals(currentUser.getId()))
                .count();

        int declineCount = consent != null ? consent.getDeclineCount() : 0;

        return ConsentStateResponse.builder()
                .chatId(chatUuid)
                .status(status.name())
                // A request can start from the default/revoked (NONE) state or after a
                // prior DECLINED — either participant may (re-)request, until the
                // consecutive-decline cap is hit.
                .canRequest((status == ConsentStatus.NONE || status == ConsentStatus.DECLINED)
                        && declineCount < MAX_DECLINES)
                .canRevoke(status == ConsentStatus.GRANTED)
                .isRequester(isRequester)
                .awaitingMyAccept(pending && !isRequester)
                .heldMessageCount(heldForMe)
                .declineCount(declineCount)
                .build();
    }

    /**
     * Best-effort WebSocket broadcast of a consent event to the chat's message topic; logs and
     * swallows any failure.
     *
     * @param chatUuid target chat UUID
     * @param event    event name
     * @param payload  event payload
     */
    private void broadcastConsent(String chatUuid, String event, Map<String, Object> payload) {
        try {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("event", event);
            wrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) wrapper);
        } catch (Exception e) {
            log.error("Consent WS broadcast '{}' failed", event, e);
        }
    }
}
