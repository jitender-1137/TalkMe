package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.FeatureAccessResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FeatureAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ResponseEntity<ResponseDto<FeatureAccessResponse>> getFeatures(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FeatureAccessResponse res = FeatureAccessResponse.builder()
                .features(featureAccessService.effectiveWireNames(userDetails.getUser()))
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(res));
    }

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
