package com.neo.chat.controller;

import com.neo.chat.dto.request.UpdateExperiencesRequest;
import com.neo.chat.dto.response.ExperienceResponse;
import com.neo.chat.dto.response.KnowledgePersonResponse;
import com.neo.chat.dto.response.KnowledgeSearchPageResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.ExperienceCategory;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.KnowledgeNetworkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Human Knowledge Network — "ask someone who has done it". Users tag real experiences and
 * others search for and message people who have that experience. Gated per-method by the
 * {@code KNOWLEDGE_NETWORK} entitlement.
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class KnowledgeNetworkController {

    private final KnowledgeNetworkService knowledgeNetworkService;

    /**
     * The caller's own experience tags (their full, editable set).
     */
    @GetMapping("/mine")
    @PreAuthorize("@featureGuard.check('KNOWLEDGE_NETWORK')")
    public ResponseEntity<ResponseDto<List<ExperienceResponse>>> getMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ExperienceResponse> mine = knowledgeNetworkService.getMine(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(mine));
    }

    /**
     * Replace the caller's whole set of experience tags.
     *
     * @throws com.neo.chat.exception.BadRequestException on a blank/too-long tag (TM_942) or
     *                                                     when the count cap is exceeded (TM_943)
     */
    @PutMapping("/mine")
    @PreAuthorize("@featureGuard.check('KNOWLEDGE_NETWORK')")
    public ResponseEntity<ResponseDto<List<ExperienceResponse>>> updateMine(
            @Valid @RequestBody UpdateExperiencesRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ExperienceResponse> saved =
                knowledgeNetworkService.updateExperiences(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(saved, "Experiences updated", "TM_000"));
    }

    /**
     * Find people who have an experience matching {@code query} and/or {@code category} and are
     * open to questions. Ranked available/online first. Cursor-paginated.
     */
    @GetMapping("/search")
    @PreAuthorize("@featureGuard.check('KNOWLEDGE_NETWORK')")
    public ResponseEntity<ResponseDto<KnowledgeSearchPageResponse>> search(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category", required = false) ExperienceCategory category,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        KnowledgeSearchPageResponse page =
                knowledgeNetworkService.search(userDetails.getUser(), query, category, cursor, limit);
        return ResponseEntity.ok(SuccessResponseDto.success(page));
    }

    /**
     * Resolve a person to open a 1:1 chat with (the "ask" action). Returns their public info;
     * the client opens the draft conversation.
     *
     * @throws com.neo.chat.exception.BadRequestException if the UUID is invalid (TM_944)
     * @throws com.neo.chat.exception.NotFoundException   if no user has that UUID (TM_946)
     * @throws com.neo.chat.exception.ForbiddenException  if a block exists either way (TM_945)
     */
    @PostMapping("/{userUuid}/ask")
    @PreAuthorize("@featureGuard.check('KNOWLEDGE_NETWORK')")
    public ResponseEntity<ResponseDto<KnowledgePersonResponse>> ask(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        KnowledgePersonResponse person =
                knowledgeNetworkService.askPerson(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(person));
    }
}
