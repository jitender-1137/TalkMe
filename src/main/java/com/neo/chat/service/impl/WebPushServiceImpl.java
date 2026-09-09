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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final WebPushProperties properties;
    private final WebPushDelivery delivery;

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
    /**
     * Async fan-out: gated on the feature flag, then delegates to the transactional
     * {@link WebPushDelivery#deliverToUser(Long, String)} on the async worker thread.
     */
    @Async
    @Override
    public void sendToUser(Long userId, String payloadJson) {
        if (!properties.isEnabled()) return;
        delivery.deliverToUser(userId, payloadJson);
    }
}
