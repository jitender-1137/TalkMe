package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageAttachment;
import com.chat.talkMe.domain.MessageReaction;
import com.chat.talkMe.domain.MessageReadReceipt;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageAttachmentResponse;
import com.chat.talkMe.dto.response.MessageReactionResponse;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.ParentMessageResponse;
import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.enums.ModerationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the MapStruct {@link MessageMapper} (real generated impl, never mocked). Covers all
 * five mapping methods plus the two default methods: uuid/enum/timestamp expressions, the
 * isEdited/isDeleted boolean renames, the tombstone content masking, the null-safe chat/moderation/
 * createdAt/armedAt expressions, {@code resolveMessageStatus}' SENT/DELIVERED/READ state machine,
 * the @AfterMapping (isForwarded + allowDownload copy, self-destruct-expired attachment strip),
 * nested reaction/attachment collection mapping, and the parent-message quote projection.
 */
@DisplayName("MessageMapper (unit)")
class MessageMapperTest {

    private final MessageMapper mapper = Mappers.getMapper(MessageMapper.class);

    private User user(long id, String username, String name, String avatar) {
        User u = User.builder().username(username).name(name).profileImage(avatar).build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private MessageAttachment attachment(String fileName, String fileUrl) {
        MessageAttachment a = MessageAttachment.builder()
                .fileName(fileName)
                .fileSize(2048L)
                .fileUrl(fileUrl)
                .thumbnailUrl("thumb-" + fileUrl)
                .mimeType("image/png")
                .duration(1.5)
                .build();
        a.setUuid(UUID.randomUUID());
        return a;
    }

    private Message baseMessage(User sender) {
        Message m = Message.builder()
                .sender(sender)
                .content("hello")
                .clientId("client-123")
                .messageType(MessageType.TEXT)
                .moderationStatus(ModerationStatus.CLEAN)
                .build();
        m.setUuid(UUID.randomUUID());
        m.setId(42L);
        m.setCreatedAt(Instant.parse("2026-07-30T10:00:00Z"));
        return m;
    }

    private MessageReadReceipt receipt(User user, String status) {
        MessageReadReceipt r = MessageReadReceipt.builder().user(user).status(status).build();
        r.setUuid(UUID.randomUUID());
        return r;
    }

    @Nested
    @DisplayName("toMessageResponse")
    class ToMessageResponse {

        @Test
        @DisplayName("returns null when the input message is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toMessageResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps all scalar fields, sender projection, sequenceNumber=id and status=SENT")
        void shouldMapNominal() {
            User sender = user(1L, "alice", "Alice", "avatar.png");
            Chat chat = Chat.builder().build();
            chat.setUuid(UUID.randomUUID());
            Message m = baseMessage(sender);
            m.setChat(chat);
            m.setEdited(true);
            m.setSelfDestructSeconds(10);
            m.setSelfDestructArmedAt(Instant.parse("2026-07-30T10:05:00Z"));

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res).isNotNull();
            assertThat(res.getId()).isEqualTo(m.getUuid().toString());
            assertThat(res.getChatId()).isEqualTo(chat.getUuid().toString());
            assertThat(res.getSenderId()).isEqualTo(sender.getUuid().toString());
            assertThat(res.getSenderName()).isEqualTo("Alice");
            assertThat(res.getSenderAvatar()).isEqualTo("avatar.png");
            assertThat(res.getMessageType()).isEqualTo("TEXT");
            assertThat(res.getContent()).isEqualTo("hello");
            assertThat(res.getClientId()).isEqualTo("client-123");
            assertThat(res.getSequenceNumber()).isEqualTo(42L);
            assertThat(res.getCreatedAt()).isEqualTo("2026-07-30T10:00:00Z");
            assertThat(res.isEdited()).isTrue();
            assertThat(res.isDeleted()).isFalse();
            assertThat(res.getModerationStatus()).isEqualTo("CLEAN");
            assertThat(res.getStatus()).isEqualTo("SENT");
            assertThat(res.getSelfDestructSeconds()).isEqualTo(10);
            assertThat(res.getSelfDestructArmedAt()).isEqualTo("2026-07-30T10:05:00Z");
            assertThat(res.isSelfDestructExpired()).isFalse();
            assertThat(res.isStarred()).isFalse();
        }

        @Test
        @DisplayName("masks the content of a tombstoned (deleted-for-everyone) message")
        void shouldMaskDeletedContent() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setDeleted(true);

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res.isDeleted()).isTrue();
            assertThat(res.getContent()).isEqualTo("This message was deleted");
        }

        @Test
        @DisplayName("emits null chatId when the message has no owning chat")
        void shouldMapNullChat() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setChat(null);

            assertThat(mapper.toMessageResponse(m).getChatId()).isNull();
        }

        @Test
        @DisplayName("defaults moderationStatus to CLEAN when the source enum is null")
        void shouldDefaultModerationStatus() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setModerationStatus(null);

            assertThat(mapper.toMessageResponse(m).getModerationStatus()).isEqualTo("CLEAN");
        }

        @Test
        @DisplayName("emits null createdAt / selfDestructArmedAt when the source instants are null")
        void shouldMapNullInstants() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setCreatedAt(null);
            m.setSelfDestructArmedAt(null);

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res.getCreatedAt()).isNull();
            assertThat(res.getSelfDestructArmedAt()).isNull();
        }

        @Test
        @DisplayName("@AfterMapping copies the isForwarded and allowDownload flags")
        void shouldCopyForwardedAndAllowDownload() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setForwarded(true);
            m.setAllowDownload(true);

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res.isForwarded()).isTrue();
            assertThat(res.isAllowDownload()).isTrue();
        }

        @Test
        @DisplayName("@AfterMapping strips attachments when the media has self-destructed")
        void shouldStripExpiredSelfDestructMedia() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setMessageType(MessageType.IMAGE);
            m.setSelfDestructExpired(true);
            List<MessageAttachment> atts = new ArrayList<>();
            atts.add(attachment("secret.png", "http://u/secret.png"));
            m.setAttachments(atts);

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res.isSelfDestructExpired()).isTrue();
            assertThat(res.getAttachments()).isEmpty();
        }

        @Test
        @DisplayName("maps nested reaction and attachment collections element-by-element")
        void shouldMapNestedCollections() {
            User sender = user(1L, "alice", "Alice", null);
            User reactor = user(2L, "bob", "Bob", null);
            Message m = baseMessage(sender);
            MessageReaction reaction = MessageReaction.builder().emoji("👍").user(reactor).build();
            reaction.setUuid(UUID.randomUUID());
            m.setReactions(List.of(reaction));
            m.setAttachments(List.of(attachment("pic.png", "http://u/pic.png")));

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res.getReactions()).singleElement()
                    .satisfies(r -> {
                        assertThat(r.getUsername()).isEqualTo("bob");
                        assertThat(r.getEmoji()).isEqualTo("👍");
                    });
            assertThat(res.getAttachments()).singleElement()
                    .satisfies(a -> {
                        assertThat(a.getFileName()).isEqualTo("pic.png");
                        assertThat(a.getFileUrl()).isEqualTo("http://u/pic.png");
                    });
        }

        @Test
        @DisplayName("maps the parent-message quote when present")
        void shouldMapParentMessage() {
            User sender = user(1L, "alice", "Alice", null);
            Message parent = baseMessage(user(2L, "bob", "Bob", null));
            parent.setMessageType(MessageType.IMAGE);
            parent.setAttachments(List.of(attachment("p.png", "http://u/p.png")));
            Message m = baseMessage(sender);
            m.setParentMessage(parent);

            MessageResponse res = mapper.toMessageResponse(m);

            assertThat(res.getParentMessage()).isNotNull();
            assertThat(res.getParentMessage().getId()).isEqualTo(parent.getUuid().toString());
            assertThat(res.getParentMessage().getMessageType()).isEqualTo("IMAGE");
            assertThat(res.getParentMessage().getFileUrl()).isEqualTo("http://u/p.png");
        }
    }

    @Nested
    @DisplayName("resolveMessageStatus")
    class ResolveMessageStatus {

        @Test
        @DisplayName("returns SENT when there are no read receipts (null)")
        void shouldReturnSentWhenReceiptsNull() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setReadReceipts(null);

            assertThat(mapper.resolveMessageStatus(m)).isEqualTo("SENT");
        }

        @Test
        @DisplayName("returns SENT when the receipt list is empty")
        void shouldReturnSentWhenReceiptsEmpty() {
            Message m = baseMessage(user(1L, "alice", "Alice", null));
            m.setReadReceipts(new ArrayList<>());

            assertThat(mapper.resolveMessageStatus(m)).isEqualTo("SENT");
        }

        @Test
        @DisplayName("returns READ when another user's receipt is READ")
        void shouldReturnReadWhenOtherUserRead() {
            User sender = user(1L, "alice", "Alice", null);
            Message m = baseMessage(sender);
            m.setReadReceipts(List.of(receipt(user(2L, "bob", "Bob", null), "READ")));

            assertThat(mapper.resolveMessageStatus(m)).isEqualTo("READ");
        }

        @Test
        @DisplayName("returns DELIVERED when another user's receipt is only DELIVERED")
        void shouldReturnDeliveredWhenOtherUserDelivered() {
            User sender = user(1L, "alice", "Alice", null);
            Message m = baseMessage(sender);
            m.setReadReceipts(List.of(receipt(user(2L, "bob", "Bob", null), "DELIVERED")));

            assertThat(mapper.resolveMessageStatus(m)).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("READ wins over DELIVERED across multiple recipient receipts")
        void shouldReturnReadWhenMixed() {
            User sender = user(1L, "alice", "Alice", null);
            Message m = baseMessage(sender);
            m.setReadReceipts(List.of(
                    receipt(user(2L, "bob", "Bob", null), "DELIVERED"),
                    receipt(user(3L, "carol", "Carol", null), "READ")));

            assertThat(mapper.resolveMessageStatus(m)).isEqualTo("READ");
        }

        @Test
        @DisplayName("ignores the sender's own receipt → SENT")
        void shouldIgnoreSendersOwnReceipt() {
            User sender = user(1L, "alice", "Alice", null);
            Message m = baseMessage(sender);
            m.setReadReceipts(List.of(receipt(sender, "READ")));

            assertThat(mapper.resolveMessageStatus(m)).isEqualTo("SENT");
        }
    }

    @Nested
    @DisplayName("toAttachmentResponse")
    class ToAttachmentResponse {

        @Test
        @DisplayName("returns null when the attachment is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toAttachmentResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps uuid→id and every scalar field")
        void shouldMapNominal() {
            MessageAttachment a = attachment("doc.pdf", "http://u/doc.pdf");

            MessageAttachmentResponse res = mapper.toAttachmentResponse(a);

            assertThat(res.getId()).isEqualTo(a.getUuid().toString());
            assertThat(res.getFileName()).isEqualTo("doc.pdf");
            assertThat(res.getFileSize()).isEqualTo(2048L);
            assertThat(res.getFileUrl()).isEqualTo("http://u/doc.pdf");
            assertThat(res.getThumbnailUrl()).isEqualTo("thumb-http://u/doc.pdf");
            assertThat(res.getMimeType()).isEqualTo("image/png");
            assertThat(res.getDuration()).isEqualTo(1.5);
        }
    }

    @Nested
    @DisplayName("toReactionResponse")
    class ToReactionResponse {

        @Test
        @DisplayName("returns null when the reaction is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toReactionResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps the reacting user's username and the emoji")
        void shouldMapNominal() {
            MessageReaction reaction = MessageReaction.builder()
                    .emoji("🔥").user(user(2L, "bob", "Bob", null)).build();
            reaction.setUuid(UUID.randomUUID());

            MessageReactionResponse res = mapper.toReactionResponse(reaction);

            assertThat(res.getUsername()).isEqualTo("bob");
            assertThat(res.getEmoji()).isEqualTo("🔥");
        }
    }

    @Nested
    @DisplayName("toParentResponse")
    class ToParentResponse {

        @Test
        @DisplayName("returns null when the parent is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toParentResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps id, senderId, content, type and the first attachment's file url/name")
        void shouldMapNominalWithAttachment() {
            Message parent = baseMessage(user(2L, "bob", "Bob", null));
            parent.setMessageType(MessageType.VIDEO);
            parent.setAttachments(List.of(attachment("clip.mp4", "http://u/clip.mp4")));

            ParentMessageResponse res = mapper.toParentResponse(parent);

            assertThat(res.getId()).isEqualTo(parent.getUuid().toString());
            assertThat(res.getSenderId()).isEqualTo(parent.getSender().getUuid().toString());
            assertThat(res.getContent()).isEqualTo("hello");
            assertThat(res.getMessageType()).isEqualTo("VIDEO");
            assertThat(res.getFileUrl()).isEqualTo("http://u/clip.mp4");
            assertThat(res.getFileName()).isEqualTo("clip.mp4");
        }

        @Test
        @DisplayName("defaults messageType to TEXT and file url/name to null when absent")
        void shouldDefaultTypeAndNullFilesWhenNoAttachments() {
            Message parent = baseMessage(user(2L, "bob", "Bob", null));
            parent.setMessageType(null);
            parent.setAttachments(new ArrayList<>());

            ParentMessageResponse res = mapper.toParentResponse(parent);

            assertThat(res.getMessageType()).isEqualTo("TEXT");
            assertThat(res.getFileUrl()).isNull();
            assertThat(res.getFileName()).isNull();
        }
    }
}
