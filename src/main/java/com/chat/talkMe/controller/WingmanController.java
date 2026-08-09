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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     *
     * @param userUuid    the target user's UUID
     * @param max         requested number of suggestions (clamped to 1..10, default 5)
     * @param userDetails the authenticated principal
     * @return 200 with the list of icebreaker suggestions
     * @throws com.chat.talkMe.exception.NotFoundException   if the target is missing or a block hides it
     * @throws com.chat.talkMe.exception.BadRequestException if the target is the caller themselves
     * @throws com.chat.talkMe.exception.ForbiddenException  if the two users are not friends
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

    /**
     * Reply suggestions given the other person's last message. Feature-gated by AI_WINGMAN.
     *
     * @param request     body carrying the last message and optional max (default 5)
     * @param userDetails the authenticated principal
     * @return 200 with the list of reply suggestions
     */
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
     *
     * @param request     body carrying the draft, optional tone, and optional max (default 5)
     * @param userDetails the authenticated principal
     * @return 200 with the list of rewritten variants
     * @throws com.chat.talkMe.exception.BadRequestException if the draft is blank or longer than 1000 chars
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

    /**
     * Request body for {@link #suggest}. {@code max} is optional (defaults to 5).
     */
    public record SuggestRequest(String lastMessage, Integer max) {
    }

    /**
     * Request body for {@link #rewrite}. {@code tone} and {@code max} are optional.
     */
    public record RewriteRequest(String draft, String tone, Integer max) {
    }
}
