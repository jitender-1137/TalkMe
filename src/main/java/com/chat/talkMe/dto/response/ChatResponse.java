package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String id; // maps to uuid
    private String name;
    private String chatType;
    private String avatar;
    private MessageResponse lastMessage;
    private int unreadCount;
    private boolean isMuted;
    private boolean isArchived;
    private boolean isPinned;
    private AuthUserResponse otherUser;
    private List<String> typingUsers;
    @JsonProperty("isFriend")
    private boolean isFriend;
    @JsonProperty("isBlockedByMe")
    private boolean isBlockedByMe;
    @JsonProperty("hasBlockedMe")
    private boolean hasBlockedMe;

    /** Present only for multi-party chats (GROUP/CHANNEL/ROOM); null for 1:1. */
    private GroupInfoResponse group;
}
