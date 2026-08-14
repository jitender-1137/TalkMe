package com.neo.chat.controller;

import com.neo.chat.dto.response.FeatureAccessResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.FeatureKey;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.FeatureAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the client refresh its effective feature set without a full re-login (e.g.
 * right after email verification or consent unlocks a feature), and toggle features
 * the user is entitled to on/off. Served at {@code /api/v1/features}.
 */
@RestController
@RequestMapping("/features")
@RequiredArgsConstructor
public class FeatureController {

    private final FeatureAccessService featureAccessService;

    /**
     * Returns the current user's effective feature set (resolved wire names) so the client can
     * refresh entitlements without re-login.
     *
     * @param userDetails the authenticated user whose entitlements are resolved
     * @return 200 with a {@link FeatureAccessResponse} listing the enabled feature wire names
     */
    @GetMapping
    public ResponseEntity<ResponseDto<FeatureAccessResponse>> getFeatures(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FeatureAccessResponse res = FeatureAccessResponse.builder()
                .features(featureAccessService.effectiveWireNames(userDetails.getUser()))
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(res));
    }

    /**
     * Sets the current user's self opt-in/opt-out preference for a feature they are entitled to,
     * then returns the recomputed effective feature set.
     *
     * @param key         the feature wire name to toggle
     * @param enabled     true to opt in (clear any self opt-out), false to opt out
     * @param userDetails the authenticated user whose preference is updated
     * @return 200 with the recomputed {@link FeatureAccessResponse} and success code TM_066
     * @throws com.neo.chat.exception.BadRequestException if the key is not a known feature (TM_002)
     */
    @PutMapping("/{key}")
    public ResponseEntity<ResponseDto<FeatureAccessResponse>> toggleFeature(
            @PathVariable("key") String key,
            @RequestParam("enabled") boolean enabled,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FeatureKey fk = FeatureKey.fromWire(key);
        if (fk == null) {
            throw new BadRequestException("Unknown feature: " + key, "TM_002");
        }
        featureAccessService.setSelfPreference(userDetails.getUser(), fk, enabled);
        FeatureAccessResponse res = FeatureAccessResponse.builder()
                .features(featureAccessService.effectiveWireNames(userDetails.getUser()))
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(res, "Feature preference updated", "TM_066"));
    }
}
