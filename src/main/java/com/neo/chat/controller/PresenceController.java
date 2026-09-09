package com.neo.chat.controller;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserPresence;
import com.neo.chat.dto.response.PresenceResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.lookup.UserLookupSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * User presence: live status plus the privacy toggles (Ghost, Invisible, Hide-last-seen) and presence lookup.
 * The single privacy rule lives in the service so every consumer masks status/last-seen consistently.
 */
@Slf4j
@RestController
@RequestMapping("/presence")
@RequiredArgsConstructor
@Tag(name = "Presence", description = "Live presence status, privacy toggles (Ghost, Invisible, Hide-last-seen) and presence lookup")
public class PresenceController {

    private final PresenceService presenceService;
    private final UserLookupSupport userLookup;

    /**
     * Sets the caller's presence status (ONLINE, OFFLINE, AWAY, IDLE, INVISIBLE).
     *
     * @param statusStr   the status name (case-insensitive)
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.BadRequestException if the status value is not one of the allowed names
     */
    @Operation(summary = "Sets the caller's presence status (ONLINE, OFFLINE, AWAY, IDLE, INVISIBLE)")
    @PutMapping("/status")
    public ResponseEntity<ResponseDto<Void>> setStatus(
            @RequestParam("status") String statusStr,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        PresenceStatus status;
        try {
            status = PresenceStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid presence status value. Allowed values are ONLINE, OFFLINE, AWAY, IDLE, INVISIBLE.", "TM_PRESENCE_INVALID_STATUS");
        }

        presenceService.setStatus(userDetails.getUser(), status);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Presence status updated successfully", "TM_PRESENCE_001"));
    }

    /**
     * Toggles Ghost Mode (receipts-only; suppresses outbound presence) for the caller.
     *
     * @param enabled     whether to enable Ghost Mode
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @Operation(summary = "Toggles Ghost Mode (receipts-only; suppresses outbound presence) for the caller")
    @PutMapping("/ghost")
    public ResponseEntity<ResponseDto<Void>> toggleGhostMode(
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.toggleGhostMode(userDetails.getUser(), enabled);
        String msg = enabled ? "Ghost Mode enabled" : "Ghost Mode disabled";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, "TM_PRESENCE_002"));
    }

    /**
     * Toggles Invisible Mode (appear offline to others) for the caller.
     *
     * @param enabled     whether to enable Invisible Mode
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @Operation(summary = "Toggles Invisible Mode (appear offline to others) for the caller")
    @PutMapping("/invisible")
    public ResponseEntity<ResponseDto<Void>> toggleInvisibleMode(
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.toggleInvisibleMode(userDetails.getUser(), enabled);
        String msg = enabled ? "Invisible Mode enabled" : "Invisible Mode disabled";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, "TM_PRESENCE_003"));
    }

    /**
     * Toggles Hide-last-seen (others cannot see the caller's last-seen timestamp) for the caller.
     *
     * @param enabled     whether to hide last-seen
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @Operation(summary = "Toggles Hide-last-seen (others cannot see the caller's last-seen timestamp) for the caller")
    @PutMapping("/hide-last-seen")
    public ResponseEntity<ResponseDto<Void>> toggleHideLastSeen(
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.toggleHideLastSeen(userDetails.getUser(), enabled);
        String msg = enabled ? "Hide Last Seen enabled" : "Hide Last Seen disabled";
        return ResponseEntity.ok(SuccessResponseDto.success(null, msg, "TM_PRESENCE_005"));
    }

    /**
     * Resets the caller's presence privacy properties back to defaults.
     *
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @Operation(summary = "Resets the caller's presence privacy properties back to defaults")
    @DeleteMapping("/reset")
    public ResponseEntity<ResponseDto<Void>> resetPresence(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        presenceService.resetPresence(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Presence properties reset successfully", "TM_PRESENCE_004"));
    }

    /**
     * Returns presence for a user by username. The owner sees their true live status and durable privacy flags;
     * other viewers see the privacy-masked apparent status/last-seen with flags hidden.
     *
     * @param username    the target user's username
     * @param userDetails authenticated caller
     * @return the {@link PresenceResponse}, viewer-relative
     * @throws com.neo.chat.exception.NotFoundException if no user has that username
     */
    @Operation(summary = "Returns presence for a user by username")
    @GetMapping("/{username}")
    public ResponseEntity<ResponseDto<PresenceResponse>> getPresence(
            @PathVariable("username") String username,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        User currentUser = userDetails.getUser();
        User targetUser = userLookup.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found with username: " + username, "TM_USER_NOT_FOUND"));

        UserPresence targetPresence = presenceService.getUserPresence(targetUser);

        PresenceResponse.PresenceResponseBuilder builder = PresenceResponse.builder()
                .username(targetUser.getUsername());

        // Privacy filters:
        if (currentUser.getId().equals(targetUser.getId())) {
            // Owner sees their TRUE live status + durable settings. Status/last-seen come
            // from Redis (the DB values are only written on OFFLINE and are otherwise stale);
            // flags come from the durable DB record. This is what the client uses to hydrate
            // the privacy toggles correctly on load (incl. a fresh browser/device).
            Instant lastSeen = presenceService.getLastSeen(targetUser);
            builder.status(presenceService.getRawStatus(targetUser).name())
                    .lastSeenAt(lastSeen != null ? lastSeen.toString() : null)
                    .ghostModeEnabled(targetPresence.isGhostModeEnabled())
                    .invisibleModeEnabled(targetPresence.isInvisibleModeEnabled())
                    .hideLastSeenEnabled(targetPresence.isHideLastSeenEnabled());
        } else {
            // Other users see apparent (Invisible-masked) status + apparent last-seen
            // (null by Invisible / Hide-last-seen) — the single privacy rule lives in
            // the service so every consumer is consistent.
            PresenceStatus apparentStatus = presenceService.getStatus(targetUser);
            builder.status(apparentStatus.name());
            Instant lastSeen = presenceService.getApparentLastSeen(targetUser);
            builder.lastSeenAt(lastSeen != null ? lastSeen.toString() : null);

            // Hide configuration flags for other users
            builder.ghostModeEnabled(false)
                    .invisibleModeEnabled(false)
                    .hideLastSeenEnabled(false);
        }

        return ResponseEntity.ok(SuccessResponseDto.success(builder.build()));
    }
}
