package com.neo.chat.controller;

import com.neo.chat.config.WebPushProperties;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.SavePushSubscriptionRequest;
import com.neo.chat.dto.request.UpdateInstallationRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.security.JwtTokenProvider;
import com.neo.chat.service.ChatService;
import com.neo.chat.service.NotificationDispatchService;
import com.neo.chat.service.WebPushService;
import com.neo.chat.service.lookup.UserLookupSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Web Push subscription lifecycle plus installation reporting, unread recount, and service-worker delivery acks.
 */
@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
@Tag(name = "Push", description = "Web Push subscription lifecycle, installation reporting, unread recount and service-worker delivery acks")
public class PushController {

    private final WebPushService webPushService;
    private final WebPushProperties webPushProperties;
    private final UserLookupSupport userLookup;
    private final NotificationDispatchService notificationDispatchService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ChatService chatService;

    /**
     * VAPID public key the browser needs to create a push subscription.
     *
     * @return a map holding the {@code publicKey}
     */
    @Operation(summary = "VAPID public key the browser needs to create a push subscription")
    @GetMapping("/vapid-public-key")
    public ResponseEntity<ResponseDto<Map<String, String>>> getVapidPublicKey() {
        return ResponseEntity.ok(SuccessResponseDto.success(
                Map.of("publicKey", webPushProperties.getVapid().getPublicKey())));
    }

    /**
     * Saves (or updates) a browser push subscription for the caller. The endpoint is SSRF-guarded.
     *
     * @param request     the push subscription (endpoint, p256dh, auth, installation type)
     * @param userDetails authenticated caller
     * @return an empty success response
     * @throws com.neo.chat.exception.BadRequestException if the subscription endpoint fails the SSRF safe-https
     *                                                       check
     */
    @Operation(summary = "Saves (or updates) a browser push subscription for the caller")
    @PostMapping(value = "/subscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> subscribe(
            @Valid @RequestBody SavePushSubscriptionRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        webPushService.saveSubscription(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Push subscription saved", "TM_280"));
    }

    /**
     * Removes a push subscription by its endpoint.
     *
     * @param endpoint the push subscription endpoint to remove
     * @return an empty success response
     */
    @Operation(summary = "Removes a push subscription by its endpoint")
    @DeleteMapping("/subscribe")
    public ResponseEntity<ResponseDto<Void>> unsubscribe(@RequestParam("endpoint") String endpoint,
                                                         @AuthenticationPrincipal CustomUserDetails userDetails) {
        webPushService.removeSubscription(userDetails.getUser(), endpoint);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Push subscription removed", "TM_281"));
    }

    /**
     * Reports how the user is accessing the app (BROWSER / PWA / IOS_HOME) and persists it on the user.
     *
     * @param request     the installation type payload
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @Operation(summary = "Reports how the user is accessing the app (BROWSER / PWA / IOS_HOME) and persists it on the user")
    @PutMapping(value = "/installation", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> updateInstallation(
            @Valid @RequestBody UpdateInstallationRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User user = userDetails.getUser();
        user.setInstallationType(request.getInstallationType());
        userLookup.save(user);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Installation type updated", "TM_282"));
    }

    /**
     * Authoritative unread total (recomputed) — used on load and after reconnect/offline.
     *
     * @param userDetails authenticated caller
     * @return a map holding {@code totalUnread}
     */
    @Operation(summary = "Authoritative unread total (recomputed) — used on load and after reconnect/offline")
    @GetMapping("/unread-count")
    public ResponseEntity<ResponseDto<Map<String, Integer>>> getUnreadCount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        int count = notificationDispatchService.recomputeUnread(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(Map.of("totalUnread", count)));
    }

    /**
     * Delivery acknowledgement from the service worker, fired when a push is
     * RECEIVED on the recipient's device (even while their tab/app is
     * backgrounder and the WebSocket is closed). Marks the chat delivered for the
     * recipient and broadcasts a DELIVERED receipt to the sender — the WhatsApp
     * "double tick" without the recipient having to reopen the app.
     * <p>
     * Public (no Bearer auth): the signed, short-lived delivery token in the body
     * IS the authorization — it only grants "mark this one chat delivered for this
     * one user". Best-effort: always returns 200 so the SW never retries noisily.
     *
     * @param body request body carrying the signed {@code token} (subject username + {@code chatUuid} claim)
     * @return an empty success response (always 200, even when the token is missing or invalid)
     */
    @Operation(summary = "Service-worker delivery acknowledgement: marks the chat delivered for the recipient (public, token-authorized, always 200)")
    @PostMapping(value = "/delivered", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ResponseDto<Void>> ackDelivered(@RequestBody Map<String, String> body) {
        String token = body != null ? body.get("token") : null;
        if (token != null) {
            Claims claims = jwtTokenProvider.parseDeliveryToken(token);
            if (claims != null) {
                String username = claims.getSubject();
                String chatUuid = claims.get("chatUuid", String.class);
                if (username != null && chatUuid != null) {
                    userLookup.findByUsername(username).ifPresent(user ->
                            chatService.markDelivered(chatUuid, user));
                }
            }
        }
        return ResponseEntity.ok(SuccessResponseDto.success(null, "ok", "TM_283"));
    }
}
