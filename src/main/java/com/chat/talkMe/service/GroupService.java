package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.request.UpdateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.enums.MemberRole;

import java.util.List;

/**
 * Group / channel management (create, membership, roles, info). Messaging in a
 * group reuses the existing MessageService — a group IS a chat.
 */
public interface GroupService {

    ChatResponse createGroup(CreateGroupRequest request, User currentUser);

    ChatResponse updateGroup(String chatUuid, UpdateGroupRequest request, User currentUser);

    List<GroupMemberResponse> getMembers(String chatUuid, User currentUser);

    ChatResponse addMembers(String chatUuid, List<String> memberUuids, User currentUser);

    void removeMember(String chatUuid, String memberUuid, User currentUser);

    /** Promote/demote: change a member's role (OWNER-only in MVP). */
    void setRole(String chatUuid, String memberUuid, MemberRole role, User currentUser);

    void leaveGroup(String chatUuid, User currentUser);

    void transferOwnership(String chatUuid, String newOwnerUuid, User currentUser);

    /** Discover public channels/rooms. type = "channel" | "room" (null = both). */
    List<ChatResponse> discover(String type, String query, String tag, User currentUser);

    /** Join a public, open-join channel/room. Returns the joined chat. */
    ChatResponse joinChat(String chatUuid, User currentUser);

    /** Report a group/channel/room for review. */
    void reportChat(String chatUuid, String reason, String details, User currentUser);
}
