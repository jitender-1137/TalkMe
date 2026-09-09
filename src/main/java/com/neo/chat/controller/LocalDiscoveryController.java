package com.neo.chat.controller;

import com.neo.chat.dto.response.LocalSearchPageResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.Interest;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.LocalDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Local Discovery (feature {@code LOCAL_DISCOVERY}) — find people in a given city, optionally
 * filtered by a shared interest, ranked available/online first. Location is a city/country
 * string only — no coordinates exist. Gated per-method by the {@code LOCAL_DISCOVERY}
 * entitlement.
 */
@RestController
@Tag(name = "Local Discovery", description = "Local Discovery (feature LOCAL_DISCOVERY) — find people in a given city, optionally filtered by a shared interest, ranked...")
@RequestMapping("/local")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class LocalDiscoveryController {

    private final LocalDiscoveryService localDiscoveryService;

    /**
     * Find people in {@code city} (case-insensitive exact match) optionally sharing
     * {@code interest}. Cursor-paginated.
     *
     * @throws com.neo.chat.exception.BadRequestException if {@code city} is blank (TM_870)
     */
    @Operation(summary = "Find people in city (case-insensitive exact match) optionally sharing interest")
    @GetMapping("/search")
    @PreAuthorize("@featureGuard.check('LOCAL_DISCOVERY')")
    public ResponseEntity<ResponseDto<LocalSearchPageResponse>> search(
            @RequestParam(name = "city") String city,
            @RequestParam(name = "interest", required = false) Interest interest,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        LocalSearchPageResponse page =
                localDiscoveryService.search(userDetails.getUser(), city, interest, cursor, limit);
        return ResponseEntity.ok(SuccessResponseDto.success(page));
    }

    /**
     * Find people in the caller's own profile city, optionally sharing {@code interest}.
     *
     * @throws com.neo.chat.exception.BadRequestException if the caller has no city set (TM_870)
     */
    @Operation(summary = "Find people in the caller's own profile city, optionally sharing interest")
    @GetMapping("/nearby")
    @PreAuthorize("@featureGuard.check('LOCAL_DISCOVERY')")
    public ResponseEntity<ResponseDto<LocalSearchPageResponse>> nearby(
            @RequestParam(name = "interest", required = false) Interest interest,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        LocalSearchPageResponse page =
                localDiscoveryService.nearbyByMyCity(userDetails.getUser(), interest);
        return ResponseEntity.ok(SuccessResponseDto.success(page));
    }
}
