package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.InstallationType;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A W3C PushSubscription serialized from the browser, sent to the backend so it
 * can deliver Web Push notifications to this installed PWA instance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavePushSubscriptionRequest {

    @NotBlank(message = "endpoint is required")
    private String endpoint;

    @NotBlank(message = "p256dh key is required")
    private String p256dh;

    @NotBlank(message = "auth key is required")
    private String auth;

    /** Optional — PWA or IOS_HOME. Defaults to PWA when omitted. */
    private InstallationType installationType;
}
