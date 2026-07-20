package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageAttachment;
import com.chat.talkMe.domain.MessageReaction;
import com.chat.talkMe.dto.response.MessageAttachmentResponse;
import com.chat.talkMe.dto.response.MessageReactionResponse;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.ParentMessageResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

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
    // Never leak the original text of a tombstoned message — clients render their
    // own "This message was deleted" placeholder off the isDeleted flag.
    @Mapping(target = "content", expression = "java(message.isDeleted() ? \"This message was deleted\" : message.getContent())")
    @Mapping(target = "createdAt", expression = "java(message.getCreatedAt() != null ? message.getCreatedAt().toString() : null)")
    @Mapping(target = "status", expression = "java(resolveMessageStatus(message))")
    @Mapping(target = "parentMessage", source = "parentMessage")
    // selfDestructSeconds / selfDestructExpired auto-map by name; armedAt is Instant→String.
    @Mapping(target = "selfDestructArmedAt", expression = "java(message.getSelfDestructArmedAt() != null ? message.getSelfDestructArmedAt().toString() : null)")
    MessageResponse toMessageResponse(Message message);

    // Once the media is destroyed the attachment row is gone, but strip defensively so an
    // expired message never carries a media URL to the client.
    @org.mapstruct.AfterMapping
    default void stripExpiredSelfDestructMedia(Message message, @org.mapstruct.MappingTarget MessageResponse response) {
        if (message.isSelfDestructExpired()) {
            response.setAttachments(java.util.Collections.emptyList());
        }
        // Explicit (MapStruct doesn't auto-map the boolean is-prefixed field reliably).
        response.setForwarded(message.isForwarded());
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
