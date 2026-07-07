package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.ChatSettings;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.request.UpdateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.enums.ChatVisibility;
import com.chat.talkMe.enums.JoinPolicy;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.enums.SendPolicy;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ChatService;
import com.chat.talkMe.service.GroupAuthzService;
import com.chat.talkMe.service.GroupService;
import com.chat.talkMe.service.MessageService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.chat.talkMe.repository.FriendRepository friendRepository;
    private final com.chat.talkMe.repository.AuditLogRepository auditLogRepository;

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

        java.util.Set<com.chat.talkMe.enums.Interest> tags = new java.util.HashSet<>();
        if (request.getTags() != null) {
            for (String t : request.getTags()) {
                try {
                    tags.add(com.chat.talkMe.enums.Interest.valueOf(t.toUpperCase()));
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

        return chatService.getChatByUuid(chat.getUuid().toString(), creator);
    }

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
        if (request.getWhoCanAddMembers() != null) s.setWhoCanAddMembers(MemberRole.valueOf(request.getWhoCanAddMembers()));
        if (request.getWhoCanEditInfo() != null) s.setWhoCanEditInfo(MemberRole.valueOf(request.getWhoCanEditInfo()));
        if (request.getWhoCanPin() != null) s.setWhoCanPin(MemberRole.valueOf(request.getWhoCanPin()));
        if (request.getSlowModeSeconds() != null) s.setSlowModeSeconds(Math.max(0, request.getSlowModeSeconds()));

        chatRepository.save(chat);
        broadcastGroupEvent(chatUuid, "group_updated", Map.of("chatId", chatUuid));
        return chatService.getChatByUuid(chatUuid, currentUser);
    }

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
            }
        }
        return chatService.getChatByUuid(chatUuid, currentUser);
    }

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
    }

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
    }

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

    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> discover(String type, String query, String tag, User currentUser) {
        List<ChatType> types = new ArrayList<>();
        if ("channel".equalsIgnoreCase(type)) types.add(ChatType.CHANNEL);
        else if ("room".equalsIgnoreCase(type)) types.add(ChatType.ROOM);
        else { types.add(ChatType.CHANNEL); types.add(ChatType.ROOM); }

        // Pre-build the lowercased LIKE pattern (null = no text filter).
        String pattern = (query != null && !query.isBlank())
                ? "%" + query.trim().toLowerCase() + "%" : null;
        com.chat.talkMe.enums.Interest tagEnum = null;
        if (tag != null && !tag.isBlank()) {
            try {
                tagEnum = com.chat.talkMe.enums.Interest.valueOf(tag.toUpperCase());
            } catch (Exception ignored) { /* unknown tag → no tag filter */ }
        }

        List<Chat> chats = chatRepository.findPublicForDiscovery(
                types, pattern, tagEnum, org.springframework.data.domain.PageRequest.of(0, 50));

        User me = userRepository.findById(currentUser.getId()).orElse(currentUser);
        return chats.stream()
                .map(c -> chatService.getChatByUuid(c.getUuid().toString(), me))
                .collect(Collectors.toList());
    }

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
        return chatService.getChatByUuid(chatUuid, me);
    }

    @Override
    @Transactional
    public void reportChat(String chatUuid, String reason, String details, User currentUser) {
        Chat chat = loadGroup(chatUuid);
        com.chat.talkMe.domain.AuditLog log = com.chat.talkMe.domain.AuditLog.builder()
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

    private Chat loadGroup(String chatUuid) {
        Chat chat = chatRepository.findByUuidWithMembers(safeUuid(chatUuid))
                .orElseThrow(() -> new NotFoundException("Group not found", "TM_121"));
        if (!chat.isMultiParty()) {
            throw new BadRequestException("Not a group chat", "TM_299");
        }
        return chat;
    }

    private void addMemberInternal(Chat chat, User u) {
        ChatMember m = ChatMember.builder().chat(chat).user(u).joinedAt(Instant.now()).build();
        m.setRole(MemberRole.MEMBER);
        chatMemberRepository.save(m);
        chat.getMembers().add(m);
    }

    /** True if {@code other} is an active friend of {@code user}. */
    private boolean areFriends(User user, User other) {
        return friendRepository.findByUserAndFriend(user, other)
                .map(f -> !f.isDeleted())
                .orElse(false);
    }

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

    private Map<String, Object> memberEventPayload(String chatUuid, User u) {
        Map<String, Object> p = new HashMap<>();
        p.put("chatId", chatUuid);
        p.put("userId", u.getUuid().toString());
        p.put("name", u.getName());
        return p;
    }

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

    private UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            throw new BadRequestException("Invalid id", "TM_300");
        }
    }

    /** Lenient parse for member-id lists: returns null for a malformed id (caller skips it). */
    private UUID tryUuid(String s) {
        if (s == null || s.isBlank() || "undefined".equals(s) || "null".equals(s)) return null;
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
