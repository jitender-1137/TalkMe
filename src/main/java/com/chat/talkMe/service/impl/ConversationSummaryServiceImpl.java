package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConversationSummaryResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageAttachmentRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.service.ConversationSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only "Our Story" summary (feature #3.3). Everything here is a cheap COUNT/MIN over
 * indexed columns plus an in-memory interest intersection — no LLM, no persistence.
 */
@Service
@RequiredArgsConstructor
public class ConversationSummaryServiceImpl implements ConversationSummaryService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;

    @Override
    @Transactional(readOnly = true)
    public ConversationSummaryResponse summarize(User me, String chatUuid) {
        Chat chat;
        try {
            chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                    .filter(c -> !c.isDeleted())
                    .orElseThrow(() -> new NotFoundException("Chat not found", "TM_024"));
        } catch (IllegalArgumentException badUuid) {
            throw new NotFoundException("Chat not found", "TM_024");
        }

        // Membership gate (IDOR): the caller must be an active member of this chat.
        ChatMember mine = chatMemberRepository.findByChatAndUser(chat, me)
                .filter(m -> m.getLeftAt() == null && !m.isBanned())
                .orElseThrow(() -> new ForbiddenException("You are not part of this conversation", "TM_026"));

        // Our Story is a 1:1 concept; groups/rooms don't get a pair summary.
        if (chat.getChatType() != null && chat.getChatType().isMultiParty()) {
            throw new ForbiddenException("Summaries are only for 1:1 chats", "TM_026");
        }

        User other = resolveOther(chat, me);

        long total = messageRepository.countVisibleByChat(chat);
        long myCount = messageRepository.countVisibleByChatAndSender(chat, me.getId());
        long theirCount = other != null
                ? messageRepository.countVisibleByChatAndSender(chat, other.getId())
                : Math.max(0, total - myCount);
        long photos = safeCount(() -> messageAttachmentRepository.countImagesByChat(chat));
        long activeDays = safeCount(() -> messageRepository.countActiveDays(chat));
        Instant firstAt = messageRepository.findFirstMessageAt(chat);
        long daysKnown = firstAt == null ? 0 : Math.max(0, Duration.between(firstAt, Instant.now()).toDays());

        List<String> shared = sharedInterests(me, other);

        ConversationSummaryResponse.ConversationSummaryResponseBuilder b = ConversationSummaryResponse.builder()
                .chatUuid(chatUuid)
                .totalMessages(total)
                .myMessages(myCount)
                .theirMessages(theirCount)
                .photosShared(photos)
                .activeDays(activeDays)
                .firstMessageAt(firstAt)
                .daysKnown(daysKnown)
                .sharedInterests(shared);

        if (other != null) {
            b.otherName(other.getName())
                    .otherUsername(other.getUsername())
                    .otherAvatar(other.getProfileImage());
        }

        return b.headline(buildHeadline(total, daysKnown, photos, shared, other)).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User resolveOther(Chat chat, User me) {
        for (ChatMember m : chatMemberRepository.findByChat(chat)) {
            User u = m.getUser();
            if (u != null && !u.getId().equals(me.getId())) {
                return u;
            }
        }
        return null;
    }

    private static long safeCount(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static List<String> sharedInterests(User me, User other) {
        if (me == null || other == null) return List.of();
        Set<Interest> mine = me.getInterests();
        Set<Interest> theirs = other.getInterests();
        if (mine == null || theirs == null || mine.isEmpty() || theirs.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (Interest i : mine) {
            if (theirs.contains(i)) {
                out.add(prettify(i.name()));
                if (out.size() >= 6) break;
            }
        }
        return new ArrayList<>(out);
    }

    private static String buildHeadline(long total, long daysKnown, long photos,
                                        List<String> shared, User other) {
        String who = other != null && other.getName() != null ? other.getName() : "them";
        StringBuilder sb = new StringBuilder();
        if (total <= 0) {
            return "Your story with " + who + " is just getting started.";
        }
        sb.append("You and ").append(who).append(" have exchanged ")
                .append(total).append(total == 1 ? " message" : " messages");
        if (daysKnown > 0) {
            sb.append(" over ").append(daysKnown).append(daysKnown == 1 ? " day" : " days");
        }
        sb.append(".");
        if (photos > 0) {
            sb.append(" Shared ").append(photos).append(photos == 1 ? " photo" : " photos");
            sb.append(".");
        }
        if (!shared.isEmpty()) {
            sb.append(" You both love ").append(humanJoin(shared.subList(0, Math.min(3, shared.size())))).append(".");
        }
        return sb.toString();
    }

    private static String humanJoin(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String prettify(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
