package com.chat.talkMe.event;

import com.chat.talkMe.dto.response.MessageResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Published to {@code talkme.events} (routing key {@code message.send}) after a
 * message is persisted. The consumer fans it out: chat-topic broadcast, per-member
 * queue events, and notification dispatch. Self-contained so the consumer needs no
 * extra DB loads beyond resolving recipient {@code User}s for push.
 *
 * <p>Plain Lombok class (not a record) so the Jackson AMQP converter round-trips it
 * without the parameter-names module.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSentEvent implements Serializable {
    private String chatUuid;
    private MessageResponse message;
    private Long senderUserId;
    private String senderName;
    private String senderProfileImage;
    private List<String> recipientUsernames;
}
