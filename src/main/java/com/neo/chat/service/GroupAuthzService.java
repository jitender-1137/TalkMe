package com.neo.chat.service;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.enums.MemberRole;
import com.neo.chat.enums.SendPolicy;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.repository.ChatMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Centralized authorization for group/channel/room actions. Every membership,
 * role, and access decision goes through here so the rules live in one place.
 */
@Service
@RequiredArgsConstructor
public class GroupAuthzService {

    private final ChatMemberRepository chatMemberRepository;

    /**
     * The caller's membership, or 403 if not an active member.
     */
    public ChatMember requireMember(Chat chat, User user) {
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, user)
                .filter(m -> !m.isDeleted() && m.getLeftAt() == null)
                .orElseThrow(() -> new ForbiddenException("Not a member of this chat", "TM_141"));
        if (member.isBanned()) {
            throw new ForbiddenException("You are banned from this group", "TM_290");
        }
        return member;
    }

    /**
     * The caller's membership, or 403 if their role is below {@code minRole}.
     */
    public ChatMember requireRole(Chat chat, User user, MemberRole minRole) {
        ChatMember member = requireMember(chat, user);
        if (!member.getRole().atLeast(minRole)) {
            throw new ForbiddenException("Insufficient permissions for this action", "TM_291");
        }
        return member;
    }

    /**
     * True if the user may currently post in the chat (role/send-policy/ban/mute aware).
     */
    public boolean canSend(Chat chat, ChatMember member) {
        if (member == null || member.isBanned()) return false;
        if (member.getMutedUntil() != null && member.getMutedUntil().isAfter(Instant.now())) return false;
        if (Objects.requireNonNull(chat.getSettings().getWhoCanSend()) == SendPolicy.ADMINS_ONLY) {
            return member.getRole().atLeast(MemberRole.ADMIN);
        }
        return true;
    }
}
