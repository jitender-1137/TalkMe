package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.FeatureAccessCache;
import com.chat.talkMe.config.ConsentProperties;
import com.chat.talkMe.domain.ConsentAcceptance;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStatusResponse;
import com.chat.talkMe.enums.ConsentType;
import com.chat.talkMe.repository.ConsentAcceptanceRepository;
import com.chat.talkMe.service.ConsentAcceptanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentAcceptanceServiceImpl implements ConsentAcceptanceService {

    private static final int MIN_AGE = 18;

    private final ConsentAcceptanceRepository consentRepository;
    private final ConsentProperties consentProperties;
    private final FeatureAccessCache featureAccessCache;

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

    @Override
    @Transactional(readOnly = true)
    public boolean hasAcceptedCurrent(User user, ConsentType type) {
        String required = consentProperties.requiredVersion(type);
        return consentRepository.findByUserAndConsentType(user, type)
                .map(c -> required.equals(c.getConsentVersion()))
                .orElse(false);
    }
}
