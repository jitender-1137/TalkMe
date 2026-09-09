package com.neo.chat.service.lookup;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.User;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Chat existence / membership lookups for the web layer (media upload foldering and the media
 * serve gate). Mirrors the repository calls one-to-one so semantics are unchanged; it exists only so
 * controllers stop depending on {@link ChatRepository} / {@link ChatMemberRepository} directly.
 */
@Service
@RequiredArgsConstructor
public class ChatMembershipLookupService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;

    /**
     * Plain chat lookup by public UUID (members NOT fetched).
     *
     * @param uuid the chat's public UUID
     * @return the chat, or empty when none exists
     */
    public Optional<Chat> findByUuid(UUID uuid) {
        return chatRepository.findByUuid(uuid);
    }

    /**
     * Chat lookup by public UUID with its members fetch-joined (safe to read {@code chat.getMembers()}
     * outside a transaction).
     *
     * @param uuid the chat's public UUID
     * @return the chat with members, or empty when none exists
     */
    public Optional<Chat> findByUuidWithMembers(UUID uuid) {
        return chatRepository.findByUuidWithMembers(uuid);
    }

    /**
     * Whether {@code user} has a membership row in {@code chat}.
     *
     * @param chat the chat
     * @param user the candidate member
     * @return true when a {@code ChatMember} row exists for the pair
     */
    public boolean isMember(Chat chat, User user) {
        return chatMemberRepository.findByChatAndUser(chat, user).isPresent();
    }
}
