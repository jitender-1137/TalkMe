package com.neo.chat.service.impl;

import com.neo.chat.cache.FeatureAccessCache;
import com.neo.chat.config.ConsentProperties;
import com.neo.chat.domain.ConsentAcceptance;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ConsentStatusResponse;
import com.neo.chat.enums.ConsentType;
import com.neo.chat.repository.ConsentAcceptanceRepository;
import com.neo.chat.service.ConsentAcceptanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks per-user acceptance of versioned legal/community consents (terms, community guidelines,
 * age-18+, flirt-lobby). Compares stored acceptance versions against the currently-required
 * versions to gate age-verification and flirt-lobby readiness, evicting the feature-access cache
 * whenever an acceptance changes entitlement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentAcceptanceServiceImpl implements ConsentAcceptanceService {

    private static final int MIN_AGE = 18;

    private final ConsentAcceptanceRepository consentRepository;
    private final ConsentProperties consentProperties;
    private final FeatureAccessCache featureAccessCache;

    /**
     * Reports, per consent type, whether the user has accepted the currently-required version,
     * plus the required version map and derived age-verified / flirt-lobby-ready flags
     * (age &gt;= {@value #MIN_AGE} and the relevant consents accepted).
     *
     * @param user the user
     * @return the consent status DTO
     */
    @Override
    @Transactional(readOnly = true)
    public ConsentStatusResponse getStatus(User user) {
        Map<String, Boolean> accepted = new HashMap<>();
        Map<String, String> required = new HashMap<>();
        for (ConsentType type : ConsentType.values()) {
            accepted.put(type.name(), hasAcceptedCurrent(user, type));
            required.put(type.name(), consentProperties.requiredVersion(type));
        }
        boolean ageOk = user.getAge() != null && user.getAge() >= MIN_AGE
                && accepted.get(ConsentType.AGE_18_PLUS.name());
        boolean flirtReady = ageOk
                && accepted.get(ConsentType.COMMUNITY_GUIDELINES.name())
                && accepted.get(ConsentType.FLIRT_LOBBY.name());
        return ConsentStatusResponse.builder()
                .accepted(accepted)
                .requiredVersions(required)
                .flirtLobbyReady(flirtReady)
                .ageVerified(ageOk)
                .build();
    }

    /**
     * Records acceptance of a consent type (idempotent upsert), storing the exact version the
     * user confirmed (falling back to the current required version when blank) plus timestamp
     * and IP, then evicting the feature-access cache since acceptance can flip entitlement.
     * Transactional.
     *
     * @param user    the accepting user
     * @param type    the consent type
     * @param version the version the client confirmed (nullable/blank → current required)
     * @param ip      the client IP recorded for audit
     * @return the refreshed consent status DTO
     */
    @Override
    @Transactional
    public ConsentStatusResponse accept(User user, ConsentType type, String version, String ip) {
        // Store the version the user actually saw/confirmed (audit-correct). If the client
        // sent nothing, fall back to current. The gate (hasAcceptedCurrent) compares stored
        // == required, so accepting a now-superseded version simply re-prompts — it never
        // wrongly attests to a version the user never saw.
        String effective = (version != null && !version.isBlank())
                ? version.trim()
                : consentProperties.requiredVersion(type);
        ConsentAcceptance record = consentRepository.findByUserAndConsentType(user, type)
                .orElseGet(() -> ConsentAcceptance.builder().user(user).consentType(type).build());
        record.setConsentVersion(effective);
        record.setAcceptedAt(Instant.now());
        record.setIpAddress(ip);
        consentRepository.save(record);
        // Consent can flip age-verification / flirt-lobby entitlement — invalidate cache.
        featureAccessCache.evict(user.getId());
        log.info("Consent accepted: user={} type={} version={}", user.getId(), type, effective);
        return getStatus(user);
    }

    /**
     * Whether the user's stored acceptance for a type matches the currently-required version.
     *
     * @param user the user
     * @param type the consent type
     * @return true when accepted at the required version
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasAcceptedCurrent(User user, ConsentType type) {
        String required = consentProperties.requiredVersion(type);
        return consentRepository.findByUserAndConsentType(user, type)
                .map(c -> required.equals(c.getConsentVersion()))
                .orElse(false);
    }
}
