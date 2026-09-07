package com.neo.chat.mapper;

import com.neo.chat.domain.Message;
import com.neo.chat.domain.MessageAttachment;
import com.neo.chat.domain.MessageReaction;
import com.neo.chat.dto.response.MessageAttachmentResponse;
import com.neo.chat.dto.response.MessageReactionResponse;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.dto.response.ParentMessageResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Collections;

/**
 * MapStruct mapper for {@link com.neo.chat.domain.Message} and its related entities
 * (attachments, reactions, parent/reply message) to their response DTOs. Renders UUIDs, enums
 * and timestamps as strings; masks deleted messages with a placeholder and never leaks their
 * original text; derives the delivery status (SENT/DELIVERED/READ) from read receipts; and
 * strips media from expired self-destruct messages. Default methods implement the derived-field
 * logic and after-mapping fixups.
 */
@Mapper(componentModel = "spring")
public interface MessageMapper {

    @Mapping(target = "isEdited", source = "edited")
    @Mapping(target = "isDeleted", source = "deleted")
    @Mapping(target = "moderationStatus", expression = "java(message.getModerationStatus() != null ? message.getModerationStatus().name() : \"CLEAN\")")
    @Mapping(target = "sequenceNumber", source = "id")
    @Mapping(target = "id", expression = "java(message.getUuid().toString())")
    @Mapping(target = "senderId", expression = "java(message.getSender().getUuid().toString())")
    @Mapping(target = "chatId", expression = "java(message.getChat() != null ? message.getChat().getUuid().toString() : null)")
    @Mapping(target = "senderName", expression = "java(message.getSender().getName())")
    @Mapping(target = "senderAvatar", expression = "java(message.getSender().getProfileImage())")
    @Mapping(target = "messageType", expression = "java(message.getMessageType().name())")
    // Never leak the original text of a tombstone message — clients render their
    // own "This message was deleted" placeholder off the isDeleted flag.
    @Mapping(target = "content", expression = "java(message.isDeleted() ? \"This message was deleted\" : message.getContent())")
    @Mapping(target = "createdAt", expression = "java(message.getCreatedAt() != null ? message.getCreatedAt().toString() : null)")
    @Mapping(target = "status", expression = "java(resolveMessageStatus(message))")
    @Mapping(target = "deliveredAt", expression = "java(resolveDeliveredAt(message))")
    @Mapping(target = "readAt", expression = "java(resolveReadAt(message))")
    @Mapping(target = "parentMessage", source = "parentMessage")
    // selfDestructSeconds / selfDestructExpired auto-map by name; armedAt is Instant→String.
    @Mapping(target = "selfDestructArmedAt", expression = "java(message.getSelfDestructArmedAt() != null ? message.getSelfDestructArmedAt().toString() : null)")
    MessageResponse toMessageResponse(Message message);

    // Once the media is destroyed the attachment row is gone, but strip defensively so an
    // expired message never carries a media URL to the client.
    @AfterMapping
    default void stripExpiredSelfDestructMedia(Message message, @MappingTarget MessageResponse response) {
        if (message.isSelfDestructExpired()) {
            response.setAttachments(Collections.emptyList());
        }
        // Explicit (MapStruct doesn't auto-map the boolean is-prefixed field reliably).
        response.setForwarded(message.isForwarded());
        response.setAllowDownload(message.isAllowDownload());
    }

    default String resolveMessageStatus(Message message) {
        if (message.getReadReceipts() == null || message.getReadReceipts().isEmpty()) {
            return "SENT";
        }
        boolean hasDelivered = false;
        for (var receipt : message.getReadReceipts()) {
            if (!receipt.getUser().getId().equals(message.getSender().getId())) {
                if ("READ".equals(receipt.getStatus())) {
                    return "READ";
                }
                if ("DELIVERED".equals(receipt.getStatus())) {
                    hasDelivered = true;
                }
            }
        }
        if (hasDelivered) {
            return "DELIVERED";
        }
        return "SENT";
    }

    /**
     * Latest delivery instant across recipient receipts (excludes the sender's own receipt),
     * as an ISO-8601 string, or {@code null} if not yet delivered to anyone. For a 1:1 chat
     * this is exactly the single recipient's delivery time; for a group it is the most recent
     * delivery. Ghost recipients are filtered out later by the service layer, not here.
     */
    default String resolveDeliveredAt(Message message) {
        if (message.getReadReceipts() == null || message.getReadReceipts().isEmpty()) {
            return null;
        }
        java.time.Instant best = null;
        for (var receipt : message.getReadReceipts()) {
            if (receipt.getUser().getId().equals(message.getSender().getId())) {
                continue;
            }
            java.time.Instant d = receipt.getDeliveredAt();
            if (d != null && (best == null || d.isAfter(best))) {
                best = d;
            }
        }
        return best != null ? best.toString() : null;
    }

    /**
     * Latest read instant across recipient receipts (excludes the sender's own receipt), as an
     * ISO-8601 string, or {@code null} if unread by everyone. 1:1 → the single recipient's read
     * time; group → the most recent read. Ghost recipients are filtered by the service layer.
     */
    default String resolveReadAt(Message message) {
        if (message.getReadReceipts() == null || message.getReadReceipts().isEmpty()) {
            return null;
        }
        java.time.Instant best = null;
        for (var receipt : message.getReadReceipts()) {
            if (receipt.getUser().getId().equals(message.getSender().getId())) {
                continue;
            }
            java.time.Instant r = receipt.getReadAt();
            if (r != null && (best == null || r.isAfter(best))) {
                best = r;
            }
        }
        return best != null ? best.toString() : null;
    }

    @Mapping(target = "id", expression = "java(attachment.getUuid().toString())")
    MessageAttachmentResponse toAttachmentResponse(MessageAttachment attachment);

    @Mapping(target = "username", expression = "java(reaction.getUser().getUsername())")
    MessageReactionResponse toReactionResponse(MessageReaction reaction);

    @Mapping(target = "id", expression = "java(parent.getUuid().toString())")
    @Mapping(target = "senderId", expression = "java(parent.getSender().getUuid().toString())")
    @Mapping(target = "messageType", expression = "java(parent.getMessageType() != null ? parent.getMessageType().name() : \"TEXT\")")
    @Mapping(target = "fileUrl", expression = "java(parent.getAttachments() != null && !parent.getAttachments().isEmpty() ? parent.getAttachments().get(0).getFileUrl() : null)")
    @Mapping(target = "fileName", expression = "java(parent.getAttachments() != null && !parent.getAttachments().isEmpty() ? parent.getAttachments().get(0).getFileName() : null)")
    ParentMessageResponse toParentResponse(Message parent);
}
