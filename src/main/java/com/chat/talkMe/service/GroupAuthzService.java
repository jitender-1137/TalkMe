package com.chat.talkMe.service;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Centralized authorization for group/channel/room actions. Every membership,
 * role, and access decision goes through here so the rules live in one place.
 */
@Service
@RequiredArgsConstructor
public class GroupAuthzService {

    private final ChatMemberRepository chatMemberRepository;

    /** The caller's membership, or 403 if not an active member. */
    public ChatMember requireMember(Chat chat, User user) {
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, user)
                .filter(m -> !m.isDeleted() && m.getLeftAt() == null)
                .orElseThrow(() -> new ForbiddenException("Not a member of this chat", "TM_141"));
        if (member.isBanned()) {
            throw new ForbiddenException("You are banned from this group", "TM_290");
        }
        return member;
    }

    /** The caller's membership, or 403 if their role is below {@code minRole}. */
    public ChatMember requireRole(Chat chat, User user, MemberRole minRole) {
        ChatMember member = requireMember(chat, user);
        if (!member.getRole().atLeast(minRole)) {
            throw new ForbiddenException("Insufficient permissions for this action", "TM_291");
        }
        return member;
    }

    /** True if the user may currently post in the chat (role/send-policy/ban/mute aware). */
    public boolean canSend(Chat chat, ChatMember member) {
        if (member == null || member.isBanned()) return false;
        if (member.getMutedUntil() != null && member.getMutedUntil().isAfter(java.time.Instant.now())) return false;
        switch (chat.getSettings().getWhoCanSend()) {
            case ADMINS_ONLY:
                return member.getRole().atLeast(MemberRole.ADMIN);
            case EVERYONE:
            default:
                return true;
        }
    }
}
