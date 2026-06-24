package com.chat.talkMe.cache;

import com.chat.talkMe.event.MessageSentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis hot-path cache for message metadata.
 *
 * <p>Two key families:
 * <ul>
 *   <li>{@code chat:{chatUuid}:lastmsg} — Hash with the latest message fields.
 *       Lets the chat-list API avoid a JOIN query on every poll.</li>
 *   <li>{@code user:{username}:unread:{chatUuid}} — Integer counter.
 *       Lets the chat-list show unread badges without a GROUP BY query.</li>
 * </ul>
 *
 * <p>All keys carry a 7-day TTL. A cache miss is always safe — callers fall
 * back to the DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageCache {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    /**
     * Called after a message is committed to DB. Updates the last-message cache
     * for the chat and increments the unread counter for every recipient.
     */
    public void onMessageSent(MessageSentEvent event) {
        var msg = event.getMessage();
        if (msg == null) return;

        // Last-message cache: used by the chat-list API to show preview text + timestamp.
        String lastMsgKey = "chat:" + event.getChatUuid() + ":lastmsg";
        Map<String, String> fields = new HashMap<>();
        fields.put("id",          msg.getId() != null   ? msg.getId()           : "");
        fields.put("clientId",    msg.getClientId() != null ? msg.getClientId() : "");
        fields.put("content",     msg.getContent() != null  ? msg.getContent()  : "");
        fields.put("sender",      event.getSenderName() != null ? event.getSenderName() : "");
        fields.put("createdAt",   msg.getCreatedAt() != null   ? msg.getCreatedAt()     : "");
        fields.put("messageType", msg.getMessageType() != null ? msg.getMessageType()   : "TEXT");
        fields.put("seqNum",      msg.getSequenceNumber() != null
                                      ? msg.getSequenceNumber().toString() : "");
        redisTemplate.opsForHash().putAll(lastMsgKey, fields);
        redisTemplate.expire(lastMsgKey, TTL);

        // Unread counters: one per recipient, scoped to this chat.
        // INCR is atomic; TTL is set only when the key is new (INCR returns 1)
        // so we never race between two commands on an existing key.
        if (event.getRecipientUsernames() == null) return;
        for (String username : event.getRecipientUsernames()) {
            String unreadKey = "user:" + username + ":unread:" + event.getChatUuid();
            Long newVal = redisTemplate.opsForValue().increment(unreadKey);
            if (Long.valueOf(1).equals(newVal)) {
                redisTemplate.expire(unreadKey, TTL);
            }
        }
    }

    /** Returns last-message fields for the chat, or an empty map on a cache miss. */
    public Map<Object, Object> getLastMessage(String chatUuid) {
        return redisTemplate.opsForHash().entries("chat:" + chatUuid + ":lastmsg");
    }

    /** Returns the cached unread count for a user in a chat, or 0 on a cache miss. */
    public long getUnreadCount(String username, String chatUuid) {
        String val = redisTemplate.opsForValue().get("user:" + username + ":unread:" + chatUuid);
        if (val == null) return 0;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0; }
    }

    /** Resets the unread counter to zero (call when a user marks a chat as read). */
    public void clearUnreadCount(String username, String chatUuid) {
        redisTemplate.delete("user:" + username + ":unread:" + chatUuid);
    }
}
