package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.MatchRequestDto;
import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.exception.*;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchServiceImpl implements MatchService {

    private final MatchRequestRepository matchRequestRepository;
    private final MatchSessionRepository matchSessionRepository;
    private final MatchReportRepository matchReportRepository;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final BlockUserRepository blockUserRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional
    public MatchSessionResponse joinQueue(MatchRequestDto requestDto, User currentUser) {
        // Expiration/Cancel previous requests
        matchRequestRepository.findByUserAndStatus(currentUser, "WAITING")
                .ifPresent(r -> {
                    r.setStatus("CANCELLED");
                    matchRequestRepository.save(r);
                });

        MatchRequest request = MatchRequest.builder()
                .user(currentUser)
                .filterGender(requestDto.getGender())
                .filterAgeMin(requestDto.getAgeMin())
                .filterAgeMax(requestDto.getAgeMax())
                .filterRegion(requestDto.getRegion())
                .filterInterests(requestDto.getInterests())
                .status("WAITING")
                .build();

        request = matchRequestRepository.save(request);
        log.info("User {} joined stranger matchmaking queue", currentUser.getUsername());

        // Perform instant scan for waiting candidates
        return scanForMatch(request);
    }

    @Override
    @Transactional
    public void leaveQueue(User currentUser) {
        matchRequestRepository.findByUserAndStatus(currentUser, "WAITING")
                .ifPresent(r -> {
                    r.setStatus("CANCELLED");
                    matchRequestRepository.save(r);
                    log.info("User {} left matchmaking queue", currentUser.getUsername());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public MatchSessionResponse checkMatch(User currentUser) {
        MatchSession session = matchSessionRepository.findActiveSessionByUser(currentUser).stream().findFirst().orElse(null);
        if (session != null) {
            return mapToSessionResponse(session, currentUser);
        }
        return null;
    }

    @Override
    @Transactional
    public MatchSessionResponse skipMatch(User currentUser) {
        endMatch(currentUser);
        // Automatically join back to queue with default filters
        return joinQueue(new MatchRequestDto(), currentUser);
    }

    @Override
    @Transactional
    public void endMatch(User currentUser) {
        MatchSession session = matchSessionRepository.findActiveSessionByUser(currentUser).stream().findFirst().orElse(null);
        if (session != null) {
            session.setActive(false);
            session.setEndedAt(Instant.now());
            matchSessionRepository.save(session);
            
            // Mark the chat room as deleted
            List<Chat> chats = chatRepository.findChatsByUser(currentUser);
            for (Chat chat : chats) {
                if (chat.getChatType() == ChatType.STRANGER) {
                    boolean containsBoth = chat.getMembers().stream()
                            .anyMatch(m -> m.getUser().getId().equals(session.getHost().getId()) || 
                                           m.getUser().getId().equals(session.getPeer().getId()));
                    if (containsBoth) {
                        chat.setDeleted(true);
                        chatRepository.save(chat);
                    }
                }
            }

            // Notify partner that match session has ended/stranger disconnected
            User partner = session.getHost().getId().equals(currentUser.getId()) ? session.getPeer() : session.getHost();
            try {
                MatchSessionResponse response = mapToSessionResponse(session, partner);
                messagingTemplate.convertAndSendToUser(partner.getUsername(), "/queue/match", response);
            } catch (Exception e) {
                log.error("Failed to send end match notification to partner", e);
            }

            log.info("Match session ended for user: {}", currentUser.getUsername());
        }
    }

    @Override
    @Transactional
    public void reportStranger(String reason, String details, User currentUser) {
        MatchSession session = matchSessionRepository.findActiveSessionByUser(currentUser).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No active match session to report", "TM_202"));

        User reportedUser = session.getHost().getId().equals(currentUser.getId()) ? session.getPeer() : session.getHost();

        MatchReport report = MatchReport.builder()
                .reporter(currentUser)
                .reported(reportedUser)
                .session(session)
                .reason(reason)
                .details(details)
                .build();

        matchReportRepository.save(report);
        
        // Auto block on report
        BlockUser block = BlockUser.builder()
                .user(currentUser)
                .blocked(reportedUser)
                .build();
        blockUserRepository.save(block);
        
        endMatch(currentUser);
        log.info("Stranger reported by {} successfully", currentUser.getUsername());
    }

    private MatchSessionResponse scanForMatch(MatchRequest request) {
        User currentUser = request.getUser();
        List<MatchRequest> waitingRequests = matchRequestRepository.findByStatus("WAITING");

        for (MatchRequest peerRequest : waitingRequests) {
            User peer = peerRequest.getUser();
            if (peer.getId().equals(currentUser.getId())) continue;

            // Block filter validation
            if (blockUserRepository.existsByUserAndBlocked(currentUser, peer) ||
                blockUserRepository.existsByUserAndBlocked(peer, currentUser)) {
                continue;
            }

            // Gender compatibility check
            if (request.getFilterGender() != null && !request.getFilterGender().isBlank()) {
                if (peer.getGender() == null || !request.getFilterGender().equalsIgnoreCase(peer.getGender())) {
                    continue;
                }
            }
            if (peerRequest.getFilterGender() != null && !peerRequest.getFilterGender().isBlank()) {
                if (currentUser.getGender() == null || !peerRequest.getFilterGender().equalsIgnoreCase(currentUser.getGender())) {
                    continue;
                }
            }

            // Match successfully found!
            request.setStatus("MATCHED");
            peerRequest.setStatus("MATCHED");
            matchRequestRepository.save(request);
            matchRequestRepository.save(peerRequest);

            // Create Stranger Chat Room
            Chat chat = Chat.builder()
                    .chatType(ChatType.STRANGER)
                    .build();
            chat = chatRepository.save(chat);

            ChatMember memberHost = ChatMember.builder()
                    .chat(chat)
                    .user(currentUser)
                    .isAdmin(true)
                    .build();
            ChatMember memberPeer = ChatMember.builder()
                    .chat(chat)
                    .user(peer)
                    .isAdmin(true)
                    .build();

            chatMemberRepository.save(memberHost);
            chatMemberRepository.save(memberPeer);

            chat.getMembers().add(memberHost);
            chat.getMembers().add(memberPeer);

            // Create Match Session
            MatchSession session = MatchSession.builder()
                    .host(currentUser)
                    .peer(peer)
                    .isActive(true)
                    .build();
            session = matchSessionRepository.save(session);

            MatchSessionResponse hostResponse = mapToSessionResponse(session, currentUser);
            MatchSessionResponse peerResponse = mapToSessionResponse(session, peer);

            // Notify both users over websocket to trigger radar navigation
            try {
                messagingTemplate.convertAndSendToUser(currentUser.getUsername(), "/queue/match", hostResponse);
                messagingTemplate.convertAndSendToUser(peer.getUsername(), "/queue/match", peerResponse);
            } catch (Exception e) {
                log.error("WebSocket match notification failed", e);
            }

            return hostResponse;
        }

        return null; // Stays in WAITING state
    }

    private MatchSessionResponse mapToSessionResponse(MatchSession session, User currentUser) {
        User partner = session.getHost().getId().equals(currentUser.getId()) ? session.getPeer() : session.getHost();
        
        // Find stranger chat id
        String chatId = "";
        List<Chat> chats = chatRepository.findChatsByUser(currentUser);
        for (Chat chat : chats) {
            if (chat.getChatType() == ChatType.STRANGER) {
                boolean containsBoth = chat.getMembers().stream()
                        .anyMatch(m -> m.getUser().getId().equals(session.getHost().getId()) || 
                                       m.getUser().getId().equals(session.getPeer().getId()));
                if (containsBoth) {
                    chatId = chat.getUuid().toString();
                    break;
                }
            }
        }

        return MatchSessionResponse.builder()
                .id(session.getUuid().toString())
                .partner(userMapper.toAuthUserResponse(partner))
                .chatId(chatId)
                .isActive(session.isActive())
                .build();
    }
}
