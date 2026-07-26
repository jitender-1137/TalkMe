package com.chat.talkMe.controller;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WingmanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * AI Wingman + Icebreakers (features #11/#12). Heuristic-backed today
 * ({@link WingmanService}) behind a provider-agnostic seam. Gated by the AI_WINGMAN
 * feature entitlement.
 */
@RestController
@RequestMapping("/match/wingman")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class WingmanController {

    private static final int DEFAULT_MAX = 5;
    private static final int HARD_CAP = 10;

    private final WingmanService wingmanService;
    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final BlockUserRepository blockUserRepository;

    /**
     * Icebreakers between the current user and the target user. Because the suggestions are
     * derived from the target's own profile signals (interests / languages / mood via the
     * compatibility highlights), this is gated to real relationships: not yourself, neither side
     * has blocked the other, and you are friends. Otherwise anyone could harvest a stranger's
     * profile traits by UUID (IDOR).
     */
    @GetMapping("/icebreakers/{userUuid}")
    @PreAuthorize("@featureGuard.check('AI_WINGMAN')")
    public ResponseEntity<ResponseDto<List<String>>> icebreakers(
            @PathVariable("userUuid") String userUuid,
            @RequestParam(value = "max", defaultValue = "5") int max,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User me = userDetails.getUser();
        User other = userRepository.findByUuid(UUID.fromString(userUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        if (other.getId().equals(me.getId())) {
            throw new BadRequestException("Icebreakers need another person", "TM_025");
        }
        // Don't leak block state — a blocked pair looks the same as a missing user.
        if (blockUserRepository.existsByUserAndBlocked(me, other)
                || blockUserRepository.existsByUserAndBlocked(other, me)) {
            throw new NotFoundException("User not found", "TM_024");
        }
        boolean friends = friendRepository.findByUserAndFriend(me, other)
                .map(f -> !f.isDeleted())
                .orElse(false);
        if (!friends) {
            throw new ForbiddenException("You can only get icebreakers for your friends", "TM_026");
        }

        List<String> suggestions = wingmanService.icebreakers(me, other, clamp(max));
        return ResponseEntity.ok(SuccessResponseDto.success(suggestions));
    }

    /** Reply suggestions given the other person's last message. */
    @PostMapping("/suggest")
    @PreAuthorize("@featureGuard.check('AI_WINGMAN')")
    public ResponseEntity<ResponseDto<List<String>>> suggest(
            @RequestBody SuggestRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String lastMessage = request == null ? null : request.lastMessage();
        int max = request == null || request.max() == null ? DEFAULT_MAX : request.max();
        List<String> suggestions = wingmanService.replySuggestions(lastMessage, clamp(max));
        return ResponseEntity.ok(SuccessResponseDto.success(suggestions));
    }

    /**
     * Rewrite the caller's own draft into polished variants in a chosen tone. Operates only
     * on the text the caller supplies (their own composer draft) — no other user's data is
     * read — so it needs no relationship gate beyond the feature entitlement.
     */
    @PostMapping("/rewrite")
    @PreAuthorize("@featureGuard.check('AI_WINGMAN')")
    public ResponseEntity<ResponseDto<List<String>>> rewrite(
            @RequestBody RewriteRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        if (request == null || request.draft() == null || request.draft().isBlank()) {
            throw new BadRequestException("Nothing to rewrite", "TM_027");
        }
        if (request.draft().length() > 1000) {
            throw new BadRequestException("Draft is too long to rewrite", "TM_028");
        }
        int max = request.max() == null ? DEFAULT_MAX : request.max();
        List<String> variants = wingmanService.rewrite(request.draft(), request.tone(), clamp(max));
        return ResponseEntity.ok(SuccessResponseDto.success(variants));
    }

    private static int clamp(int max) {
        if (max <= 0) return DEFAULT_MAX;
        return Math.min(max, HARD_CAP);
    }

    /** Request body for {@link #suggest}. {@code max} is optional (defaults to 5). */
    public record SuggestRequest(String lastMessage, Integer max) {
    }

    /** Request body for {@link #rewrite}. {@code tone} and {@code max} are optional. */
    public record RewriteRequest(String draft, String tone, Integer max) {
    }
}
