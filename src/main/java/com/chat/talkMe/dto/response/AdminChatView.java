package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A chat as seen by an admin — id, kind, members, activity. */
@Data
@Builder
public class AdminChatView {
    private String id;            // uuid
    private String type;          // PRIVATE / GROUP / CHANNEL / ROOM / STRANGER
    private String name;          // group/room name, or the other participant for 1:1
    private List<Member> members;
    private long messageCount;
    private String lastMessageAt;
    private String lastMessagePreview;  // DECRYPTED snippet of the latest message (or a media label)
    private String lastMessageSender;   // username of the latest message's sender
    private String createdAt;
    private boolean deleted;       // soft-deleted chat (admin sees it flagged)

    @Data
    @Builder
    public static class Member {
        private String id;        // uuid
        private String username;
        private String name;
        private String avatar;
    }
}
