package com.neo.chat.service.impl;

import com.neo.chat.config.WebPushProperties;
import com.neo.chat.domain.PushSubscription;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.SavePushSubscriptionRequest;
import com.neo.chat.enums.InstallationType;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.PushSubscriptionRepository;
import com.neo.chat.service.WebPushService;
import com.neo.chat.util.SsrfGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Web Push (VAPID) subscription store and dispatcher.
 *
 * <p>Persists per-device {@link PushSubscription} rows (SSRF-guarded endpoints) and delivers
 * encrypted payloads to every subscription of a user. Dispatch runs asynchronously behind a
 * "webpush" circuit breaker, prunes endpoints reported gone (HTTP 404/410), and is a no-op when
 * the feature is disabled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebPushServiceImpl implements WebPushService {

    /** Max push subscriptions retained per user; the oldest are evicted past this. */
    private static final int MAX_SUBSCRIPTIONS_PER_USER = 20;


    private final PushSubscriptionRepository subscriptionRepository;
    private final PushService pushService;
    private final WebPushProperties properties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Create or update (upsert by endpoint) a push subscription for the user. The endpoint is
     * SSRF-guarded (must be a public https push-service host) before it is stored.
     *
     * @param user    the owner of the subscription
     * @param request the subscription (endpoint, p256dh, auth, installation type)
     * @throws com.neo.chat.exception.BadRequestException (TM_PUSH_ENDPOINT) on an unsafe endpoint
     */
    @Override
    @Transactional
    public void saveSubscription(User user, SavePushSubscriptionRequest request) {
        // SSRF guard: the server POSTs to this endpoint on every push. A legitimate
        // push endpoint is always a https URL on a public push-service host — reject
        // internal/loopback/link-local/private targets so a client can't turn the
        // dispatcher into an internal-request primitive.
        try {
            SsrfGuard.assertSafeHttps(request.getEndpoint());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Invalid push subscription endpoint", "TM_PUSH_ENDPOINT");
        }
        PushSubscription existing = subscriptionRepository.findByEndpoint(request.getEndpoint()).orElse(null);
        if (existing == null) {
            // NEW endpoint: cap the number of subscriptions a single account can register so one
            // user can't create unbounded rows and fan the dispatcher out to thousands of targets.
            List<PushSubscription> mine = subscriptionRepository.findByUser_Id(user.getId());
            int overBy = (mine == null ? 0 : mine.size()) - (MAX_SUBSCRIPTIONS_PER_USER - 1);
            if (mine != null && overBy > 0) {
                mine.stream()
                        .sorted(java.util.Comparator.comparing(
                                PushSubscription::getId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                        .limit(overBy)
                        .forEach(old -> subscriptionRepository.deleteByEndpoint(old.getEndpoint()));
            }
        }
        PushSubscription sub = existing != null ? existing : new PushSubscription();
        sub.setUser(user);
        sub.setEndpoint(request.getEndpoint());
        sub.setP256dh(request.getP256dh());
        sub.setAuth(request.getAuth());
        sub.setInstallationType(
                request.getInstallationType() != null ? request.getInstallationType() : InstallationType.PWA);
        subscriptionRepository.save(sub);
        log.debug("[WebPush] Saved subscription for user {} ({} total)", user.getId(), request.getEndpoint());
    }

    /**
     * Delete the subscription with the given endpoint (e.g. on logout / unsubscribe).
     *
     * @param endpoint the push endpoint to remove
     */
    @Override
    @Transactional
    public void removeSubscription(User user, String endpoint) {
        // Owner-scoped: a caller may only delete a subscription they own (prevents deleting another
        // user's endpoint by guessing/observing it).
        subscriptionRepository.findByEndpoint(endpoint)
                .filter(s -> s.getUser() != null && user != null && user.getId().equals(s.getUser().getId()))
                .ifPresent(s -> subscriptionRepository.deleteByEndpoint(endpoint));
    }

    /**
     * Delete all push subscriptions for a user (single-device login sweep) so a superseded
     * device stops receiving pushes.
     *
     * @param userId the user whose subscriptions are cleared
     */
    @Override
    @Transactional
    public void removeAllSubscriptionsForUser(Long userId) {
        int removed = subscriptionRepository.deleteByUserId(userId);
        if (removed > 0) {
            log.info("[WebPush] Cleared {} push subscription(s) for user {} on new login", removed, userId);
        }
    }

    /**
     * Asynchronously deliver a payload to every subscription of the user. No-op when the feature
     * is disabled or the user has no subscriptions; prunes endpoints returning 404/410 and logs
     * (never rethrows) per-subscription send failures.
     *
     * @param userId      the recipient user
     * @param payloadJson the JSON push payload
     */
    @Async
    @Override
    @Transactional
    public void sendToUser(Long userId, String payloadJson) {
        if (!properties.isEnabled()) return;

        List<PushSubscription> subs = subscriptionRepository.findByUser_Id(userId);
        if (subs.isEmpty()) {
            log.debug("[WebPush] No subscriptions for user {} — nothing to send", userId);
            return;
        }
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
        for (PushSubscription sub : subs) {
            try {
                int status = sendOne(sub, payload);
                if (status == 404 || status == 410) {
                    subscriptionRepository.delete(sub);
                    log.info("[WebPush] Pruned expired subscription {} (status {})", sub.getEndpoint(), status);
                } else if (status >= 400) {
                    log.warn("[WebPush] Push FAILED status={} endpoint={}", status, sub.getEndpoint());
                } else {
                    log.info("[WebPush] Push sent (status {}) to {}", status, sub.getEndpoint());
                }
            } catch (Exception e) {
                log.error("[WebPush] Error sending push to {}", sub.getEndpoint(), e);
            }
        }
    }

    /**
     * Build and send a single Web Push (HIGH urgency, 24h TTL) through the "webpush" circuit
     * breaker so failing/slow relays fail fast instead of tying up async threads.
     *
     * @param sub     the target subscription
     * @param payload the encrypted push payload bytes
     * @return the push service HTTP status code
     * @throws Exception on send failure or when the breaker is open
     *                   ({@code io.github.resilience4j.circuitbreaker.CallNotPermittedException})
     */
    private int sendOne(PushSubscription sub, byte[] payload) throws Exception {
        Notification notification = Notification.builder()
                .endpoint(sub.getEndpoint())
                .userPublicKey(sub.getP256dh())
                .userAuth(sub.getAuth())
                .payload(payload)
                // HIGH urgency + a TTL so push services still deliver to a
                // closed/dozing device instead of dropping the message.
                .urgency(Urgency.HIGH)
                .ttl((int) TimeUnit.HOURS.toSeconds(24))
                .build();
        // Guard the outbound push with a circuit breaker: if the push relays are
        // failing/slow, the breaker opens and these calls fail fast (throwing
        // CallNotPermittedException, handled by the per-subscription catch in the
        // caller) instead of tying up async threads.
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("webpush");
        HttpResponse response = breaker.executeCallable(() -> pushService.send(notification));
        return response.getStatusLine().getStatusCode();
    }
}
