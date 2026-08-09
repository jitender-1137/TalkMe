package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.MemberCountCache;
import com.chat.talkMe.cache.UserSettingsCache;
import com.chat.talkMe.domain.AuditLog;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.ChatSettings;
import com.chat.talkMe.domain.GroupInvite;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.request.UpdateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupInfoResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.ChatVisibility;
import com.chat.talkMe.enums.GroupAddPrivacy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.JoinPolicy;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.enums.SendPolicy;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.AuditLogRepository;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.GroupInviteRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ChatService;
import com.chat.talkMe.service.EventService;
import com.chat.talkMe.service.GroupAuthzService;
import com.chat.talkMe.service.GroupService;
import com.chat.talkMe.service.MessageService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link GroupService} implementation for group/channel/room management: creation,
 * membership, roles, discovery, invites and reporting. A group IS a chat, so messaging reuses
 * {@link MessageService}; member counts and add-privacy are cache-backed and every mutation
 * broadcasts a WebSocket group event to {@code /topic/chat/&lt;uuid&gt;/messages}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final UserRepository userRepository;
    private final GroupAuthzService authz;
    private final ChatService chatService;
    private final MessageService messageService;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final FriendRepository friendRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationService notificationService;
    private final GroupInviteRepository groupInviteRepository;
    private final MemberCountCache memberCountCache;
    private final UserSettingsCache userSettingsCache;
    /**
     * Lazy to break the GroupService ⇄ EventService constructor cycle (Events spins up rooms via GroupService).
     */
    private final ObjectProvider<EventService> eventServiceProvider;

    /**
     * Create a GROUP/CHANNEL/ROOM (per {@code subtype}), seed the creator as OWNER, apply
     * visibility/join/send policies, add allowed initial members and evict the member-count cache.
     *
     * @param request     group spec (name, subtype, visibility, tags, member ids, flags)
     * @param currentUser the creator, who becomes OWNER
     * @return the created chat as seen by the creator
     */
    @Override
    @Transactional
    public ChatResponse createGroup(CreateGroupRequest request, User currentUser) {
        User creator = userRepository.findById(currentUser.getId()).orElse(currentUser);

        ChatType type;
        if ("channel".equalsIgnoreCase(request.getSubtype())) type = ChatType.CHANNEL;
        else if ("room".equalsIgnoreCase(request.getSubtype())) type = ChatType.ROOM;
        else type = ChatType.GROUP;

        // Rooms are inherently public & open-to-all. Public channels are open to
        // subscribe. Everything else stays private/invite-only.
        ChatVisibility visibility = (type == ChatType.ROOM || "PUBLIC".equalsIgnoreCase(request.getVisibility()))
                ? ChatVisibility.PUBLIC : ChatVisibility.PRIVATE;
        JoinPolicy joinPolicy = (visibility == ChatVisibility.PUBLIC && type != ChatType.GROUP)
                ? JoinPolicy.OPEN : JoinPolicy.INVITE_ONLY;
        boolean allowNonFriends = type == ChatType.ROOM || Boolean.TRUE.equals(request.getAllowNonFriends());

        ChatSettings settings = ChatSettings.builder()
                // Channels are broadcast: only admins post.
                .whoCanSend(type == ChatType.CHANNEL ? SendPolicy.ADMINS_ONLY : SendPolicy.EVERYONE)
                .build();

        Set<Interest> tags = new HashSet<>();
        if (request.getTags() != null) {
            for (String t : request.getTags()) {
                try {
                    tags.add(Interest.valueOf(t.toUpperCase()));
                } catch (Exception ignored) { /* skip unknown tag */ }
            }
        }

        Chat chat = Chat.builder()
                .name(request.getName())
                .chatType(type)
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .visibility(visibility)
                .joinPolicy(joinPolicy)
                .allowNonFriends(allowNonFriends)
                .allowExplicitContent(Boolean.TRUE.equals(request.getAllowExplicitContent()))
                .category(request.getCategory())
                .tags(tags)
                .ownerId(creator.getId())
                .settings(settings)
                .build();
        chat = chatRepository.save(chat);

        // Creator = OWNER.
        ChatMember owner = ChatMember.builder().chat(chat).user(creator).joinedAt(Instant.now()).build();
        owner.setRole(MemberRole.OWNER);
        chatMemberRepository.save(owner);
        chat.getMembers().add(owner);

        // Initial members. When the group disallows non-friends, only the creator's
        // friends may be added (the picker enforces this too — this is the backstop).
        if (request.getMemberIds() != null) {
            for (String memberUuid : request.getMemberIds()) {
                UUID uid = tryUuid(memberUuid);
                if (uid == null) continue;
                User u = userRepository.findByUuid(uid).orElse(null);
                if (u == null || u.getId().equals(creator.getId())) continue;
                if (!chat.isAllowNonFriends() && !areFriends(creator, u)) continue;
                addMemberInternal(chat, u);
            }
        }

        memberCountCache.evict(chat);
        return chatService.getChatByUuid(chat.getUuid().toString(), creator);
    }

    /**
     * Apply partial edits to a group's info and settings, then broadcast a {@code group_updated} event.
     *
     * @param chatUuid    target group uuid
     * @param request     partial update (only non-null fields are applied)
     * @param currentUser the editor
     * @return the updated chat as seen by the editor
     * @throws com.chat.talkMe.exception.ForbiddenException if the caller's role is below whoCanEditInfo (TM_291)
     */
    @Override
    @Transactional
    public ChatResponse updateGroup(String chatUuid, UpdateGroupRequest request, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        ChatMember me = authz.requireMember(chat, currentUser);
        if (!me.getRole().atLeast(chat.getSettings().getWhoCanEditInfo())) {
            throw new ForbiddenException("You can't edit this group's info", "TM_291");
        }

        if (request.getName() != null) chat.setName(request.getName());
        if (request.getDescription() != null) chat.setDescription(request.getDescription());
        if (request.getImageUrl() != null) chat.setImageUrl(request.getImageUrl());
        if (request.getAllowNonFriends() != null) chat.setAllowNonFriends(request.getAllowNonFriends());
        if (request.getAllowExplicitContent() != null) chat.setAllowExplicitContent(request.getAllowExplicitContent());
        if (request.getVisibility() != null) chat.setVisibility(ChatVisibility.valueOf(request.getVisibility()));
        if (request.getJoinPolicy() != null) chat.setJoinPolicy(JoinPolicy.valueOf(request.getJoinPolicy()));

        ChatSettings s = chat.getSettings();
        if (request.getWhoCanSend() != null) s.setWhoCanSend(SendPolicy.valueOf(request.getWhoCanSend()));
        if (request.getWhoCanAddMembers() != null)
            s.setWhoCanAddMembers(MemberRole.valueOf(request.getWhoCanAddMembers()));
        if (request.getWhoCanEditInfo() != null) s.setWhoCanEditInfo(MemberRole.valueOf(request.getWhoCanEditInfo()));
        if (request.getWhoCanPin() != null) s.setWhoCanPin(MemberRole.valueOf(request.getWhoCanPin()));
        if (request.getSlowModeSeconds() != null) s.setSlowModeSeconds(Math.max(0, request.getSlowModeSeconds()));

        chatRepository.save(chat);
        broadcastGroupEvent(chatUuid, "group_updated", Map.of("chatId", chatUuid));
        return chatService.getChatByUuid(chatUuid, currentUser);
    }

    /**
     * List the group's active members (former members excluded), enriched with live presence.
     *
     * @param chatUuid    target group uuid
     * @param currentUser the caller (must be a member)
     * @return active members with role, join time, presence, ban and mute state
     * @throws com.chat.talkMe.exception.ForbiddenException if the caller is not a member
     */
    @Override
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> getMembers(String chatUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        authz.requireMember(chat, currentUser);
        List<ChatMember> members = chatMemberRepository.findByChat(chat);
        List<GroupMemberResponse> out = new ArrayList<>();
        for (ChatMember m : members) {
            if (m.getLeftAt() != null) continue; // former members aren't shown
            User u = m.getUser();
            out.add(GroupMemberResponse.builder()
                    .userId(u.getUuid().toString())
                    .name(u.getName())
                    .username(u.getUsername())
                    .avatar(u.getProfileImage())
                    .role(m.getRole().name())
                    .joinedAt(m.getJoinedAt() != null ? m.getJoinedAt().toString() : null)
                    .presence(presenceService != null ? presenceService.getStatus(u).name().toLowerCase() : "offline")
                    .isBanned(m.isBanned())
                    .mutedUntil(m.getMutedUntil() != null ? m.getMutedUntil().toString() : null)
                    .build());
        }
        return out;
    }

    /**
     * Add members (re-activating former members), enforcing the friends-only policy and each
     * target's add-privacy — sending a group invite instead of a direct add where required —
     * then emit system messages, WS join events and best-effort notifications.
     *
     * @param chatUuid    target group uuid
     * @param memberUuids user uuid to add (invalid/unknown ids skipped)
     * @param currentUser the adder
     * @return the group as seen by the adder
     * @throws com.chat.talkMe.exception.ForbiddenException  caller can't add members (TM_291) or
     *                                                       friends-only violation (TM_306)
     * @throws com.chat.talkMe.exception.BadRequestException member limit reached (TM_297)
     */
    @Override
    @Transactional
    public ChatResponse addMembers(String chatUuid, List<String> memberUuids, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        ChatMember me = authz.requireMember(chat, currentUser);
        if (!me.getRole().atLeast(chat.getSettings().getWhoCanAddMembers())) {
            throw new ForbiddenException("You can't add members to this group", "TM_291");
        }
        long count = chatMemberRepository.countActiveMembers(chat);
        if (memberUuids != null) {
            for (String memberUuid : memberUuids) {
                if (count >= chat.getMemberLimit()) {
                    throw new BadRequestException("Group member limit reached", "TM_297");
                }
                UUID uid = tryUuid(memberUuid);
                if (uid == null) continue;
                User u = userRepository.findByUuid(uid).orElse(null);
                if (u == null) continue;
                // Membership policy: unless the group allows non-friends, only the
                // adder's friends may be added.
                if (!chat.isAllowNonFriends() && !areFriends(currentUser, u)) {
                    throw new ForbiddenException(
                            "This group only allows friends to be added", "TM_306");
                }
                // Respect the target's "who can add me to groups" privacy setting. When
                // they don't allow a direct add (FRIENDS_ONLY / NOBODY), send them a group
                // invite (notification + chat message) to accept instead of adding them.
                if (!u.getId().equals(currentUser.getId()) && !allowsDirectAdd(currentUser, u)) {
                    sendGroupInvite(chat, currentUser, u);
                    continue;
                }
                // Already an ACTIVE member? skip. A former member (left/removed) is
                // re-activated fresh.
                ChatMember existing = chatMemberRepository.findByChatAndUser(chat, u).orElse(null);
                if (existing != null && !existing.isDeleted() && existing.getLeftAt() == null) continue;
                if (existing != null) {
                    existing.setDeleted(false);
                    existing.setBanned(false);
                    existing.setLeftAt(null);
                    existing.setRole(MemberRole.MEMBER);
                    existing.setJoinedAt(Instant.now());
                    chatMemberRepository.save(existing);
                } else {
                    addMemberInternal(chat, u);
                }
                count++;
                systemMessage(chatUuid, currentUser, "member_added", currentUser, u);
                broadcastGroupEvent(chatUuid, "member_joined", memberEventPayload(chatUuid, u));
                // Notify the added member (unless they added themselves) that they're now
                // in the group. Best-effort — a notification failure must not roll back the add.
                if (!u.getId().equals(currentUser.getId())) {
                    try {
                        notificationService.createNotification(
                                u,
                                "Added to a group",
                                currentUser.getName() + " added you to “" + chat.getName() + "”.",
                                "GROUP_ADDED",
                                chatUuid,
                                currentUser,
                                chat.getImageUrl());
                    } catch (Exception e) {
                        log.warn("Failed to notify {} of being added to group {}", u.getUsername(), chatUuid, e);
                    }
                }
            }
        }
        memberCountCache.evict(chatUuid);
        return chatService.getChatByUuid(chatUuid, currentUser);
    }

    /**
     * Remove a member (WhatsApp-style: marked as former, history retained), then emit a system
     * message and WS event. Admins may not remove the owner or other admins (owner-only).
     *
     * @param chatUuid    target group uuid
     * @param memberUuid  uuid of the member to remove
     * @param currentUser the remover (must be ADMIN or above)
     * @throws com.chat.talkMe.exception.NotFoundException  user or member row not found (TM_064 / TM_141)
     * @throws com.chat.talkMe.exception.ForbiddenException removing the owner (TM_303) or an admin
     *                                                      when not owner (TM_304)
     */
    @Override
    @Transactional
    public void removeMember(String chatUuid, String memberUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        ChatMember me = authz.requireRole(chat, currentUser, MemberRole.ADMIN);
        User target = userRepository.findByUuid(safeUuid(memberUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
        ChatMember targetMember = chatMemberRepository.findByChatAndUser(chat, target)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new NotFoundException("Member not found", "TM_141"));

        if (targetMember.getRole() == MemberRole.OWNER) {
            throw new ForbiddenException("The owner cannot be removed", "TM_303");
        }
        // An admin cannot remove another admin; only the owner can.
        if (targetMember.getRole() == MemberRole.ADMIN && me.getRole() != MemberRole.OWNER) {
            throw new ForbiddenException("Only the owner can remove an admin", "TM_304");
        }

        // WhatsApp-style: mark as former member (keeps read-only history) rather
        // than hard-deleting the row.
        targetMember.setLeftAt(Instant.now());
        chatMemberRepository.save(targetMember);
        systemMessage(chatUuid, currentUser, "member_removed", currentUser, target);
        broadcastGroupEvent(chatUuid, "member_removed", memberEventPayload(chatUuid, target));
        memberCountCache.evict(chatUuid);
    }

    /**
     * Promote/demote a member to the given role (owner-only), then emit a system message and
     * {@code role_changed} WS event. OWNER cannot be assigned here (use transfer-ownership).
     *
     * @param chatUuid    target group uuid
     * @param memberUuid  uuid of the member whose role changes
     * @param role        the new role (must not be OWNER)
     * @param currentUser the caller (must be OWNER)
     * @throws com.chat.talkMe.exception.BadRequestException assigning OWNER here (TM_301)
     * @throws com.chat.talkMe.exception.NotFoundException   user or member row not found (TM_064 / TM_141)
     * @throws com.chat.talkMe.exception.ForbiddenException  changing the owner's role (TM_305)
     */
    @Override
    @Transactional
    public void setRole(String chatUuid, String memberUuid, MemberRole role, User currentUser) {
        if (role == MemberRole.OWNER) {
            throw new BadRequestException("Use transfer-ownership to assign the owner", "TM_301");
        }
        Chat chat = loadGroup(chatUuid);
        // Only the owner promotes/demotes admins (MVP).
        authz.requireRole(chat, currentUser, MemberRole.OWNER);
        User target = userRepository.findByUuid(safeUuid(memberUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
        ChatMember targetMember = chatMemberRepository.findByChatAndUser(chat, target)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new NotFoundException("Member not found", "TM_141"));
        if (targetMember.getRole() == MemberRole.OWNER) {
            throw new ForbiddenException("Cannot change the owner's role", "TM_305");
        }
        targetMember.setRole(role);
        chatMemberRepository.save(targetMember);
        systemMessage(chatUuid, currentUser, "role_changed", currentUser, target);
        Map<String, Object> payload = memberEventPayload(chatUuid, target);
        payload.put("role", role.name());
        broadcastGroupEvent(chatUuid, "role_changed", payload);
    }

    /**
     * Leave a group (WhatsApp-style: marked as former, history retained), emit a system message
     * and WS event. The owner must transfer ownership or delete the group first.
     *
     * @param chatUuid    target group uuid
     * @param currentUser the leaving member
     * @throws com.chat.talkMe.exception.BadRequestException if the caller is the OWNER (TM_298)
     */
    @Override
    @Transactional
    public void leaveGroup(String chatUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        ChatMember me = authz.requireMember(chat, currentUser);
        if (me.getRole() == MemberRole.OWNER) {
            throw new BadRequestException(
                    "Transfer ownership or delete the group before leaving", "TM_298");
        }
        // WhatsApp-style: mark as former member (keeps read-only history).
        me.setLeftAt(Instant.now());
        chatMemberRepository.save(me);
        systemMessage(chatUuid, currentUser, "member_left", currentUser, currentUser);
        broadcastGroupEvent(chatUuid, "member_left", memberEventPayload(chatUuid, currentUser));
        memberCountCache.evict(chatUuid);
    }

    /**
     * Transfer ownership to another member: the old owner is demoted to ADMIN, the new owner
     * promoted to OWNER, the chat's ownerId updated, then a system message + WS event emitted.
     *
     * @param chatUuid     target group uuid
     * @param newOwnerUuid uuid of the member to make owner
     * @param currentUser  the current owner
     * @throws com.chat.talkMe.exception.NotFoundException new owner user or member row not found
     *                                                     (TM_064 / TM_141)
     */
    @Override
    @Transactional
    public void transferOwnership(String chatUuid, String newOwnerUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        ChatMember me = authz.requireRole(chat, currentUser, MemberRole.OWNER);
        User newOwner = userRepository.findByUuid(safeUuid(newOwnerUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_064"));
        ChatMember newOwnerMember = chatMemberRepository.findByChatAndUser(chat, newOwner)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new NotFoundException("Member not found", "TM_141"));

        me.setRole(MemberRole.ADMIN);
        newOwnerMember.setRole(MemberRole.OWNER);
        chat.setOwnerId(newOwner.getId());
        chatMemberRepository.save(me);
        chatMemberRepository.save(newOwnerMember);
        chatRepository.save(chat);

        systemMessage(chatUuid, currentUser, "role_changed", currentUser, newOwner);
        broadcastGroupEvent(chatUuid, "group_updated", Map.of("chatId", chatUuid));
    }

    /**
     * Discover public channels/rooms (up to 50) by type, text and tag, returned as
     * membership-free cards so non-members don't trip the member-required guard.
     *
     * @param type        "channel", "room", or anything else for both
     * @param query       optional case-insensitive name filter
     * @param tag         optional {@link Interest} tag filter (unknown values ignored)
     * @param currentUser the viewer, used to fill in own membership state on each card
     * @return discovery cards for matching public chats
     */
    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> discover(String type, String query, String tag, User currentUser) {
        List<ChatType> types = new ArrayList<>();
        if ("channel".equalsIgnoreCase(type)) types.add(ChatType.CHANNEL);
        else if ("room".equalsIgnoreCase(type)) types.add(ChatType.ROOM);
        else {
            types.add(ChatType.CHANNEL);
            types.add(ChatType.ROOM);
        }

        // Pre-build the lowercased LIKE pattern (null = no text filter).
        String pattern = (query != null && !query.isBlank())
                ? "%" + query.trim().toLowerCase() + "%" : null;
        Interest tagEnum = null;
        if (tag != null && !tag.isBlank()) {
            try {
                tagEnum = Interest.valueOf(tag.toUpperCase());
            } catch (Exception ignored) { /* unknown tag → no tag filter */ }
        }

        List<Chat> chats = chatRepository.findPublicForDiscovery(
                types, pattern, tagEnum, PageRequest.of(0, 50));

        User me = userRepository.findById(currentUser.getId()).orElse(currentUser);
        // Build membership-FREE discovery cards. Discovery lists PUBLIC rooms the caller is (by
        // definition) usually NOT a member of, so it must NOT go through getChatByUuid, which
        // throws "Not a member of this chat" (TM_141) for non-members — that would 404 the whole
        // list the moment any not-yet-joined public room shows up (e.g. the seeded city rooms).
        return chats.stream()
                .map(c -> toDiscoveryCard(c, me))
                .collect(Collectors.toList());
    }

    /**
     * Map a public chat to a discovery card without enforcing membership. The viewer's own
     * membership state (role/active) is filled in when present so the client can show "Open" vs
     * "Join", but a non-member never triggers an exception.
     */
    private ChatResponse toDiscoveryCard(Chat chat, User me) {
        ChatMember membership = chatMemberRepository.findByChatAndUser(chat, me).orElse(null);
        boolean isMember = membership != null && !membership.isDeleted() && membership.getLeftAt() == null;
        int memberCount;
        try {
            memberCount = memberCountCache.get(chat);
        } catch (Exception e) {
            memberCount = 0;
        }
        return ChatResponse.builder()
                .id(chat.getUuid().toString())
                .name(chat.getName())
                .chatType(chat.getChatType().name())
                .avatar(chat.getImageUrl())
                .group(GroupInfoResponse.builder()
                        .subtype(chat.getChatType().name().toLowerCase())
                        .visibility(chat.getVisibility().name())
                        .joinPolicy(chat.getJoinPolicy().name())
                        .allowExplicitContent(chat.isAllowExplicitContent())
                        .allowNonFriends(chat.isAllowNonFriends())
                        .memberLimit(chat.getMemberLimit())
                        .memberCount(memberCount)
                        .description(chat.getDescription())
                        .imageUrl(chat.getImageUrl())
                        .publicUsername(chat.getSlug())
                        .category(chat.getCategory())
                        .tags(chat.getTags() == null ? List.of()
                                : chat.getTags().stream().map(Enum::name).collect(Collectors.toList()))
                        .myRole(isMember ? membership.getRole().name() : null)
                        .active(isMember)
                        .build())
                .build();
    }

    /**
     * Join a public, open chat (re-activating a former membership), broadcast a join event, and
     * best-effort credit Midnight-Event attendance if the room hosts an event.
     *
     * @param chatUuid    target chat uuid
     * @param currentUser the joining user
     * @return the chat as seen by the joiner
     * @throws com.chat.talkMe.exception.ForbiddenException  chat is not open to join (TM_293)
     * @throws com.chat.talkMe.exception.BadRequestException room is full (TM_297)
     */
    @Override
    @Transactional
    public ChatResponse joinChat(String chatUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        User me = userRepository.findById(currentUser.getId()).orElse(currentUser);

        if (chat.getVisibility() != ChatVisibility.PUBLIC || chat.getJoinPolicy() != JoinPolicy.OPEN) {
            throw new ForbiddenException("This chat is not open to join", "TM_293");
        }

        ChatMember existing = chatMemberRepository.findByChatAndUser(chat, me).orElse(null);
        if (existing != null && !existing.isDeleted() && existing.getLeftAt() == null) {
            return chatService.getChatByUuid(chatUuid, me); // already a member
        }
        if (chatMemberRepository.countActiveMembers(chat) >= chat.getMemberLimit()) {
            throw new BadRequestException("This room is full", "TM_297");
        }
        if (existing != null) {
            existing.setDeleted(false);
            existing.setBanned(false);
            existing.setLeftAt(null);
            existing.setRole(MemberRole.MEMBER);
            existing.setJoinedAt(Instant.now());
            chatMemberRepository.save(existing);
        } else {
            addMemberInternal(chat, me);
        }
        broadcastGroupEvent(chatUuid, "member_joined", memberEventPayload(chatUuid, me));
        memberCountCache.evict(chatUuid);

        // Midnight Events (#24): if this room hosts an event, credit attendance + reputation
        // exactly once. Best-effort — a hiccup here must never fail the join.
        try {
            eventServiceProvider.getObject().markAttendedByRoom(chatUuid, me);
        } catch (Exception e) {
            log.debug("[events] attendance hook skipped for room {}: {}", chatUuid, e.getMessage());
        }

        return chatService.getChatByUuid(chatUuid, me);
    }

    /**
     * Record a {@code chat.report} audit-log entry against the chat for moderation review.
     *
     * @param chatUuid    reported chat uuid
     * @param reason      report reason (defaults to "other" when null)
     * @param details     optional free-text detail
     * @param currentUser the reporter (recorded as actor)
     */
    @Override
    @Transactional
    public void reportChat(String chatUuid, String reason, String details, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        AuditLog log = AuditLog.builder()
                .eventName("chat.report")
                .entityName("Chat")
                .entityId(chat.getId())
                .actor(currentUser)
                .details("reason=" + (reason != null ? reason : "other")
                        + (details != null && !details.isBlank() ? "; " + details : ""))
                .build();
        auditLogRepository.save(log);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Load a multi-party chat by uuid, rejecting 1:1 chats.
     *
     * @param chatUuid chat uuid
     * @return the group chat with members loaded
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException the chat is not a group (TM_299)
     */
    private Chat loadGroup(String chatUuid) {
        Chat chat = chatRepository.findByUuidWithMembers(safeUuid(chatUuid))
                .orElseThrow(() -> new NotFoundException("Group not found", "TM_121"));
        if (!chat.isMultiParty()) {
            throw new BadRequestException("Not a group chat", "TM_299");
        }
        return chat;
    }

    /**
     * Persist a fresh MEMBER-role membership row and attach it to the chat's member set.
     *
     * @param chat the group
     * @param u    the user to add as a plain member
     */
    private void addMemberInternal(Chat chat, User u) {
        ChatMember m = ChatMember.builder().chat(chat).user(u).joinedAt(Instant.now()).build();
        m.setRole(MemberRole.MEMBER);
        chatMemberRepository.save(m);
        chat.getMembers().add(m);
    }

    /**
     * True if {@code other} is an active friend of {@code user}.
     */
    private boolean areFriends(User user, User other) {
        return friendRepository.findByUserAndFriend(user, other)
                .map(f -> !f.isDeleted())
                .orElse(false);
    }

    /**
     * Whether {@code adder} may add {@code target} to a group/room directly, per the
     * target's "who can add me" setting (EVERYONE / FRIENDS_ONLY / NOBODY). When this is
     * false the caller sends a group invite instead of a direct add.
     */
    private boolean allowsDirectAdd(User adder, User target) {
        GroupAddPrivacy privacy = userSettingsCache.getGroupAddPrivacy(target);
        if (privacy == null || privacy == GroupAddPrivacy.EVERYONE) {
            return true;
        }
        if (privacy == GroupAddPrivacy.FRIENDS_ONLY) {
            return areFriends(adder, target);
        }
        return false; // NOBODY
    }

    /**
     * Message-content sentinel carrying a group-invite payload (mirrors the shared-post
     * encoding; the frontend detects this prefix and renders a Join/Decline card).
     */
    private static final String INVITE_SENTINEL = "tmginvite";

    /**
     * Deliver a group invite to {@code invitee}: (1) a PENDING GroupInvite record,
     * (2) a chat message from the inviter carrying the group info + a join CTA, and
     * (3) an in-app notification. Idempotent — a still-PENDING invite is not re-sent.
     */
    private void sendGroupInvite(Chat chat, User inviter, User invitee) {
        try {
            // Don't spam: if there's already a pending invite, leave it as-is.
            if (groupInviteRepository.existsByChatAndInviteeAndStatus(chat, invitee, "PENDING")) {
                return;
            }
            GroupInvite invite = groupInviteRepository.findByChatAndInvitee(chat, invitee)
                    .orElse(GroupInvite.builder().chat(chat).invitee(invitee).build());
            invite.setInviter(inviter);
            invite.setStatus("PENDING");
            groupInviteRepository.save(invite);

            // Chat message from the inviter → invitee, carrying the invite card payload.
            try {
                String payload = INVITE_SENTINEL + objectMapper.writeValueAsString(Map.of(
                        "chatId", chat.getUuid().toString(),
                        "groupName", chat.getName() == null ? "a group" : chat.getName(),
                        "groupAvatar", chat.getImageUrl() == null ? "" : chat.getImageUrl(),
                        "inviterName", inviter.getName() == null ? "" : inviter.getName()));
                CreateChatRequest chatReq =
                        new CreateChatRequest();
                chatReq.setRecipientId(invitee.getUuid().toString());
                ChatResponse dm = chatService.createChat(chatReq, inviter);
                SendMessageRequest msg =
                        new SendMessageRequest();
                msg.setContent(payload);
                msg.setMessageType("TEXT");
                messageService.sendMessage(dm.getId(), msg, inviter);
            } catch (Exception e) {
                // The notification (below) is the reliable channel; a chat-message failure
                // (e.g. recipient is friends-only) must not abort the invite.
                log.warn("Group-invite chat message not delivered from {} to {}: {}",
                        inviter.getUsername(), invitee.getUsername(), e.getMessage());
            }

            notificationService.createNotification(
                    invitee,
                    "Group invitation",
                    inviter.getName() + " invited you to join “" + chat.getName() + "”.",
                    "GROUP_INVITE",
                    chat.getUuid().toString(),
                    inviter,
                    chat.getImageUrl());
        } catch (Exception e) {
            log.warn("Failed to send group invite for chat {} to {}", chat.getUuid(), invitee.getUsername(), e);
        }
    }

    /**
     * Accept a pending group invite: add/re-activate the caller's membership, mark the invite
     * ACCEPTED, emit a system message + join event, and notify the inviter (best-effort).
     *
     * @param chatUuid    target group uuid
     * @param currentUser the invitee accepting
     * @return the group as seen by the invitee
     * @throws com.chat.talkMe.exception.NotFoundException   no, pending invite for this group (TM_307)
     * @throws com.chat.talkMe.exception.BadRequestException group is full (TM_297)
     */
    @Override
    @Transactional
    public ChatResponse acceptGroupInvite(String chatUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        User me = userRepository.findById(currentUser.getId()).orElse(currentUser);
        GroupInvite invite = groupInviteRepository.findByChatAndInviteeAndStatus(chat, me, "PENDING")
                .orElseThrow(() -> new NotFoundException("No, pending invite for this group", "TM_307"));

        if (chatMemberRepository.countActiveMembers(chat) >= chat.getMemberLimit()) {
            throw new BadRequestException("This group is full", "TM_297");
        }

        ChatMember existing = chatMemberRepository.findByChatAndUser(chat, me).orElse(null);
        if (existing == null) {
            addMemberInternal(chat, me);
        } else if (existing.isDeleted() || existing.getLeftAt() != null) {
            existing.setDeleted(false);
            existing.setBanned(false);
            existing.setLeftAt(null);
            existing.setRole(MemberRole.MEMBER);
            existing.setJoinedAt(Instant.now());
            chatMemberRepository.save(existing);
        }
        invite.setStatus("ACCEPTED");
        groupInviteRepository.save(invite);

        systemMessage(chatUuid, me, "member_added", invite.getInviter(), me);
        broadcastGroupEvent(chatUuid, "member_joined", memberEventPayload(chatUuid, me));

        // Let the inviter know their invite was accepted.
        try {
            if (invite.getInviter() != null && !invite.getInviter().getId().equals(me.getId())) {
                notificationService.createNotification(
                        invite.getInviter(),
                        "Invite accepted",
                        me.getName() + " joined “" + chat.getName() + "”.",
                        "GROUP_ADDED",
                        chatUuid,
                        me,
                        chat.getImageUrl());
            }
        } catch (Exception ignored) { /* best-effort */ }

        memberCountCache.evict(chatUuid);
        return chatService.getChatByUuid(chatUuid, me);
    }

    /**
     * Decline a pending group invite by marking it DECLINED (no-op if none pending).
     *
     * @param chatUuid    target group uuid
     * @param currentUser the invitee declining
     */
    @Override
    @Transactional
    public void declineGroupInvite(String chatUuid, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        User me = userRepository.findById(currentUser.getId()).orElse(currentUser);
        groupInviteRepository.findByChatAndInviteeAndStatus(chat, me, "PENDING")
                .ifPresent(invite -> {
                    invite.setStatus("DECLINED");
                    groupInviteRepository.save(invite);
                });
    }

    /**
     * Emit a JSON-encoded system message describing a membership event (best-effort; logged on failure).
     *
     * @param chatUuid    the chat to post into
     * @param currentUser fallback sender when {@code actor} is null
     * @param kind        event kind (e.g. member_added, member_removed, role_changed)
     * @param actor       the acting user (recorded and preferred sender)
     * @param target      the affected user
     */
    private void systemMessage(String chatUuid, User currentUser, String kind, User actor, User target) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("kind", kind);
            if (actor != null) {
                event.put("actorId", actor.getUuid().toString());
                event.put("actorName", actor.getName());
            }
            if (target != null) {
                event.put("targetId", target.getUuid().toString());
                event.put("targetName", target.getName());
            }
            String json = objectMapper.writeValueAsString(event);
            messageService.sendSystemMessage(chatUuid, actor != null ? actor : currentUser, json, null);
        } catch (Exception e) {
            log.warn("Failed to emit system message {} for chat {}", kind, chatUuid, e);
        }
    }

    /**
     * Build the {chatId, userId, name} payload map used for member-related WS events.
     *
     * @param chatUuid the chat uuid
     * @param u        the member the event is about
     * @return a mutable payload map
     */
    private Map<String, Object> memberEventPayload(String chatUuid, User u) {
        Map<String, Object> p = new HashMap<>();
        p.put("chatId", chatUuid);
        p.put("userId", u.getUuid().toString());
        p.put("name", u.getName());
        return p;
    }

    /**
     * Broadcast an {event, payload} wrapper to the chat's WS topic (best-effort; logged on failure).
     *
     * @param chatUuid the chat uuid whose topic receives the event
     * @param event    the event name
     * @param payload  the event payload
     */
    private void broadcastGroupEvent(String chatUuid, String event, Map<String, Object> payload) {
        try {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("event", event);
            wrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) wrapper);
        } catch (Exception e) {
            log.error("WebSocket group event broadcast failed: {}", event, e);
        }
    }

    /**
     * Strict uuid parse for required ids.
     *
     * @param s the candidate uuid string
     * @return the parsed UUID
     * @throws com.chat.talkMe.exception.BadRequestException if {@code s} is not a valid uuid (TM_300)
     */
    private UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            throw new BadRequestException("Invalid id", "TM_300");
        }
    }

    /**
     * Lenient parse for member-id lists: returns null for a malformed id (caller skips it).
     */
    private UUID tryUuid(String s) {
        if (s == null || s.isBlank() || "undefined".equals(s) || "null".equals(s)) return null;
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
