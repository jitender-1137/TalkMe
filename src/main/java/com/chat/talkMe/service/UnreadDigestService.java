package com.chat.talkMe.service;

import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.EmailUnreadPreview;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the daily "you have unread messages" digest email.
 *
 * <p><b>Dedup:</b> a per-user watermark ({@link User#getLastUnreadDigestMessageId()}) records the
 * highest message id already covered by a digest. A user is emailed only when their newest unread
 * message id exceeds that watermark — so the same still-unread messages never trigger a second
 * email. If 5 messages go unread today they're mailed once; if nothing new arrives tomorrow no
 * mail is sent; when a 6th arrives, a fresh digest goes out and the watermark advances.</p>
 *
 * <p>Only eligible recipients are mailed: non-guest, not deleted, with a <b>verified</b> email, and
 * who haven't turned off unread-digest emails ({@code UserSetting.emailUnreadMessages}). Delivery
 * itself is gated by {@code app.mail.enabled} inside {@link EmailService}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadDigestService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final UserSettingRepository userSettingRepository;
    private final EmailService emailService;

    @Value("${app.mail.unread-digest.enabled:true}")
    private boolean enabled;

    @Value("${app.mail.unread-digest.max-previews:5}")
    private int maxPreviews;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /**
     * Find every user with new (not-yet-notified) unread messages and email them a digest.
     * Best-effort per user — one failure never aborts the run. Returns the number of emails sent.
     */
    @Transactional
    public void sendDailyUnreadDigests() {
        if (!enabled) {
            return;
        }
        List<Long> candidateIds = messageRepository.findUserIdsWithNewUnread();
        if (candidateIds.isEmpty()) {
            return;
        }
        String openLink = frontendBaseUrl.replaceAll("/+$", "") + "/";
        int sent = 0;
        for (Long userId : candidateIds) {
            try {
                if (sendForUser(userId, openLink)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("[UnreadDigest] skipped user {}: {}", userId, e.getMessage());
            }
        }
        log.info("[UnreadDigest] processed {} candidate(s), sent {} digest email(s)",
                candidateIds.size(), sent);
    }

    private boolean sendForUser(Long userId, String openLink) {
        // The candidate query already filtered to eligible users (non-guest, not deleted,
        // verified, has email); this load is just to read the opt-out + advance the watermark.
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return false;
        }
        // Respect the per-user opt-out (defaults to on when no settings row exists yet).
        boolean allowed = userSettingRepository.findByUser(user)
                .map(com.chat.talkMe.domain.UserSetting::isEmailUnreadMessages)
                .orElse(true);
        if (!allowed) {
            return false;
        }

        // Fetch the recent unread once — its newest row (ordered by id DESC) IS the newest
        // unread id, so we avoid a separate MAX(id) query.
        List<Message> recent = messageRepository.findRecentUnreadForUser(
                userId, PageRequest.of(0, Math.max(maxPreviews * 3, maxPreviews)));
        if (recent.isEmpty()) {
            return false;
        }
        long newestUnreadId = recent.getFirst().getId();
        long lastNotified = user.getLastUnreadDigestMessageId() == null
                ? 0L : user.getLastUnreadDigestMessageId();
        // Nothing new since the last digest → don't re-notify the same messages.
        if (newestUnreadId <= lastNotified) {
            return false;
        }

        long totalUnread = messageRepository.countTotalUnreadForUser(userId);
        if (totalUnread <= 0) {
            return false;
        }

        emailService.sendUnreadMessagesEmail(
                user.getEmail(), user.getName(), buildPreviews(recent), (int) totalUnread, openLink);

        // Advance the watermark so these messages won't be mailed again.
        user.setLastUnreadDigestMessageId(newestUnreadId);
        userRepository.save(user);
        return true;
    }

    /** One preview row per sender (most recent kept), capped at {@code maxPreviews}. */
    private List<EmailUnreadPreview> buildPreviews(List<Message> recentNewestFirst) {
        Map<Long, EmailUnreadPreview> bySender = new LinkedHashMap<>();
        for (Message m : recentNewestFirst) {
            User sender = m.getSender();
            if (sender == null || bySender.containsKey(sender.getId())) {
                continue;
            }
            bySender.put(sender.getId(), new EmailUnreadPreview(
                    sender.getName(), snippet(m), null, timeAgo(m.getCreatedAt())));
            if (bySender.size() >= maxPreviews) {
                break;
            }
        }
        return new ArrayList<>(bySender.values());
    }

    /** Plain-text preview; media/blank messages fall back to a generic label. */
    private String snippet(Message m) {
        String content = m.getContent();
        return (content != null && !content.isBlank()) ? content : "Sent you a message";
    }

    private String timeAgo(Instant t) {
        if (t == null) {
            return "";
        }
        long mins = Math.max(0, Duration.between(t, Instant.now()).toMinutes());
        if (mins < 1) return "just now";
        if (mins < 60) return mins + "m ago";
        long hrs = mins / 60;
        if (hrs < 24) return hrs + "h ago";
        return (hrs / 24) + "d ago";
    }
}
