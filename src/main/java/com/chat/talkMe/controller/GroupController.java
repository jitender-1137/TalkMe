package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.request.UpdateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.GroupMemberResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.MemberRole;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Group / channel management. A group IS a chat, so messaging still flows through
 * ChatController/MessageController — this handles create, info, membership & roles.
 */
@RestController
@RequestMapping("/chats/group")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /**
     * Create a group, channel, or room (subtype from the request); the caller becomes OWNER.
     *
     * @param request      validated group-creation payload (name, subtype, visibility, members, tags…)
     * @param userDetails  the authenticated creator
     * @return the created chat as a ChatResponse (TM_280)
     */
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<ChatResponse>> createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.createGroup(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Group created successfully", "TM_280"));
    }

    /**
     * Update group info/settings (name, image, visibility, send/pin/edit policies, slow mode…).
     *
     * @param uuid         UUID of the group
     * @param request      validated partial-update payload (only non-null fields are applied)
     * @param userDetails  the authenticated caller (needs the group's whoCanEditInfo role)
     * @return the updated chat as a ChatResponse (TM_281)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException chat is not a group (TM_299), or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  caller lacks edit permission (TM_291)
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ResponseDto<ChatResponse>> updateGroup(
            @PathVariable("id") String uuid,
            @Valid @RequestBody UpdateGroupRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.updateGroup(uuid, request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Group updated", "TM_281"));
    }

    /**
     * List active members of a group (former members excluded), enriched with role and presence.
     *
     * @param uuid         UUID of the group
     * @param userDetails  the authenticated caller (must be a member)
     * @return the active member list wrapped in a success envelope
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException chat is not a group (TM_299), or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  caller is not a member
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<ResponseDto<List<GroupMemberResponse>>> getMembers(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<GroupMemberResponse> members = groupService.getMembers(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(members));
    }

    /**
     * Add members to a group; targets whose privacy disallows a direct add get an invite instead.
     *
     * @param uuid         UUID of the group
     * @param body         map with {@code memberIds} (list of user UUIDs); defaults to empty
     * @param userDetails  the authenticated caller (needs the group's whoCanAddMembers role)
     * @return the updated chat as a ChatResponse (TM_282)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException member limit reached (TM_297), not a group
     *                                                       (TM_299), or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  caller lacks add permission (TM_291), or the
     *                                                       group only allows friends (TM_306)
     */
    @PostMapping("/{id}/members")
    public ResponseEntity<ResponseDto<ChatResponse>> addMembers(
            @PathVariable("id") String uuid,
            @RequestBody Map<String, List<String>> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<String> memberIds = body.getOrDefault("memberIds", List.of());
        ChatResponse response = groupService.addMembers(uuid, memberIds, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Members added", "TM_282"));
    }

    /**
     * Remove a member from a group (marks them a former member, keeping read-only history).
     *
     * @param uuid         UUID of the group
     * @param userId       UUID of the member to remove
     * @param userDetails  the authenticated caller (needs at least ADMIN)
     * @return empty success envelope (TM_283)
     * @throws com.chat.talkMe.exception.NotFoundException   group, user, or membership not found
     *                                                       (TM_121 / TM_064 / TM_141)
     * @throws com.chat.talkMe.exception.BadRequestException not a group (TM_299), or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  target is the owner (TM_303), or an admin
     *                                                       removing another admin (TM_304)
     */
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<ResponseDto<Void>> removeMember(
            @PathVariable("id") String uuid,
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.removeMember(uuid, userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Member removed", "TM_283"));
    }

    /**
     * Set a member's role (MEMBER/ADMIN); owner-only. Use transfer-ownership to assign OWNER.
     *
     * @param uuid         UUID of the group
     * @param userId       UUID of the target member
     * @param body         map with {@code role} (defaults to MEMBER); parsed case-insensitively
     * @param userDetails  the authenticated caller (must be OWNER)
     * @return empty success envelope (TM_284)
     * @throws com.chat.talkMe.exception.NotFoundException   group, user, or membership not found
     *                                                       (TM_121 / TM_064 / TM_141)
     * @throws com.chat.talkMe.exception.BadRequestException role is OWNER (TM_301), not a group (TM_299),
     *                                                       or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  target is the owner (TM_305)
     * @throws IllegalArgumentException                      role value is not a valid MemberRole
     */
    @PutMapping("/{id}/members/{userId}/role")
    public ResponseEntity<ResponseDto<Void>> setRole(
            @PathVariable("id") String uuid,
            @PathVariable("userId") String userId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MemberRole role = MemberRole.valueOf(body.getOrDefault("role", "MEMBER").toUpperCase());
        groupService.setRole(uuid, userId, role, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Role updated", "TM_284"));
    }

    /**
     * Leave a group (marks the caller a former member); the owner must transfer/delete first.
     *
     * @param uuid         UUID of the group
     * @param userDetails  the authenticated caller
     * @return empty success envelope (TM_285)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException caller is the owner (TM_298), not a group
     *                                                       (TM_299), or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  caller is not a member
     */
    @PostMapping("/{id}/leave")
    public ResponseEntity<ResponseDto<Void>> leave(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.leaveGroup(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left group", "TM_285"));
    }

    /**
     * Transfer ownership to another member (caller demotes to ADMIN, target becomes OWNER).
     *
     * @param uuid         UUID of the group
     * @param body         map with {@code newOwnerId} (UUID of the new owner)
     * @param userDetails  the authenticated caller (must be OWNER)
     * @return empty success envelope (TM_286)
     * @throws com.chat.talkMe.exception.NotFoundException   group, new owner, or membership not found
     *                                                       (TM_121 / TM_064 / TM_141)
     * @throws com.chat.talkMe.exception.BadRequestException not a group (TM_299), or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  caller is not the owner
     */
    @PostMapping("/{id}/transfer-ownership")
    public ResponseEntity<ResponseDto<Void>> transferOwnership(
            @PathVariable("id") String uuid,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.transferOwnership(uuid, body.get("newOwnerId"), userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Ownership transferred", "TM_286"));
    }

    /**
     * Discover public channels/rooms. type=channel|room (omit for both).
     *
     * @param type         "channel" or "room" to narrow the type; null lists both (optional)
     * @param query        free-text name/description filter (optional)
     * @param tag          interest tag to filter by; unknown tags are ignored (optional)
     * @param userDetails  the authenticated caller
     * @return matching public discovery cards (membership-free) wrapped in a success envelope
     */
    @GetMapping("/discover")
    public ResponseEntity<ResponseDto<List<ChatResponse>>> discover(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "tag", required = false) String tag,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ChatResponse> response = groupService.discover(type, query, tag, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Join a public, open channel/room.
     *
     * @param uuid         UUID of the channel/room
     * @param userDetails  the authenticated caller
     * @return the joined chat as a ChatResponse (TM_282)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException room is full (TM_297), not a group (TM_299),
     *                                                       or bad id (TM_300)
     * @throws com.chat.talkMe.exception.ForbiddenException  chat is not open to join (TM_293)
     */
    @PostMapping("/{id}/join")
    public ResponseEntity<ResponseDto<ChatResponse>> join(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.joinChat(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Joined", "TM_282"));
    }

    /**
     * Accept a pending group invitation (join the group).
     *
     * @param uuid         UUID of the group
     * @param userDetails  the authenticated invitee
     * @return the joined chat as a ChatResponse (TM_283)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121), or no pending
     *                                                       invite (TM_307)
     * @throws com.chat.talkMe.exception.BadRequestException group is full (TM_297), not a group (TM_299),
     *                                                       or bad id (TM_300)
     */
    @PostMapping("/{id}/invite/accept")
    public ResponseEntity<ResponseDto<ChatResponse>> acceptInvite(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ChatResponse response = groupService.acceptGroupInvite(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Invite accepted", "TM_283"));
    }

    /**
     * Decline a pending group invitation (no-op if there is none).
     *
     * @param uuid         UUID of the group
     * @param userDetails  the authenticated invitee
     * @return empty success envelope (TM_284)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException not a group (TM_299), or bad id (TM_300)
     */
    @PostMapping("/{id}/invite/decline")
    public ResponseEntity<ResponseDto<Void>> declineInvite(
            @PathVariable("id") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        groupService.declineGroupInvite(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Invite declined", "TM_284"));
    }

    /**
     * Report a group/channel/room (writes an audit-log entry).
     *
     * @param uuid         UUID of the group
     * @param body         optional map with {@code reason} (default "other") and {@code details}
     * @param userDetails  the authenticated reporter
     * @return empty success envelope (TM_307)
     * @throws com.chat.talkMe.exception.NotFoundException   group not found (TM_121)
     * @throws com.chat.talkMe.exception.BadRequestException not a group (TM_299), or bad id (TM_300)
     */
    @PostMapping("/{id}/report")
    public ResponseEntity<ResponseDto<Void>> report(
            @PathVariable("id") String uuid,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String reason = body != null ? body.getOrDefault("reason", "other") : "other";
        String details = body != null ? body.get("details") : null;
        groupService.reportChat(uuid, reason, details, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Report submitted", "TM_307"));
    }
}
