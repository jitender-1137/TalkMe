package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Group/channel/room metadata carried on {@link ChatResponse#getGroup()} for
 * multi-party chats. Maps to the frontend {@code GroupMeta} view over a Chat.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupInfoResponse {
    /** "group" | "channel" | "room" (lower-cased chatType). */
    private String subtype;
    private String visibility;      // PRIVATE | PUBLIC
    private String joinPolicy;      // OPEN | REQUEST | INVITE_ONLY
    private boolean allowExplicitContent;
    private boolean allowNonFriends;
    private int memberLimit;
    private int memberCount;
    private String description;
    private String imageUrl;
    private String publicUsername;  // slug
    private String category;
    private List<String> tags;
    private String ownerId;         // owner user uuid
    private String myRole;          // OWNER | ADMIN | MEMBER (viewer's role)
    private boolean active;         // false = viewer left/was removed (read-only history)
    private String pinnedMessageId; // uuid of the pinned message, if any

    // Flattened settings
    private String whoCanSend;
    private String whoCanAddMembers;
    private String whoCanEditInfo;
    private String whoCanPin;
    private int slowModeSeconds;
}
