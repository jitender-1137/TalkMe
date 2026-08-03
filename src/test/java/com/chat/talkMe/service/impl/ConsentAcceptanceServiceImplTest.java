package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.FeatureAccessCache;
import com.chat.talkMe.config.ConsentProperties;
import com.chat.talkMe.domain.ConsentAcceptance;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStatusResponse;
import com.chat.talkMe.enums.ConsentType;
import com.chat.talkMe.repository.ConsentAcceptanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ConsentAcceptanceServiceImpl} — user-level consent gate that
 * powers the Flirt-Lobby / age-verification entitlements.
 *
 * <p>Covers: the {@code getStatus} truth table (each consent accepted-at-current-version flag,
 * plus the {@code ageVerified} and {@code flirtLobbyReady} derivations across age null / under-18 /
 * missing-consent combinations), the {@code accept} version-resolution logic (client version vs
 * blank/null fall-back to the required version) with the save + feature-cache evict side effects,
 * update-in-place of an existing row, and the {@code hasAcceptedCurrent} stored-vs-required
 * comparison (match / stale / absent).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentAcceptanceServiceImpl (unit)")
class ConsentAcceptanceServiceImplTest {

    private static final long USER_ID = 1L;
    private static final String V_AGE = "age-v1";
    private static final String V_GUIDE = "guide-v1";
    private static final String V_FLIRT = "flirt-v1";

    @Mock private ConsentAcceptanceRepository consentRepository;
    @Mock private ConsentProperties consentProperties;
    @Mock private FeatureAccessCache featureAccessCache;

    private ConsentAcceptanceServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new ConsentAcceptanceServiceImpl(consentRepository, consentProperties, featureAccessCache);
        user = User.builder().username("alice").age(25).build();
        user.setId(USER_ID);
        lenient().when(consentProperties.requiredVersion(ConsentType.AGE_18_PLUS)).thenReturn(V_AGE);
        lenient().when(consentProperties.requiredVersion(ConsentType.COMMUNITY_GUIDELINES)).thenReturn(V_GUIDE);
        lenient().when(consentProperties.requiredVersion(ConsentType.FLIRT_LOBBY)).thenReturn(V_FLIRT);
    }

    private ConsentAcceptance record(ConsentType type, String version) {
        return ConsentAcceptance.builder()
                .user(user)
                .consentType(type)
                .consentVersion(version)
                .acceptedAt(Instant.now())
                .build();
    }

    /** Stub the stored acceptance for a given type (null version ⇒ no row). */
    private void stub(ConsentType type, String storedVersion) {
        when(consentRepository.findByUserAndConsentType(user, type))
                .thenReturn(storedVersion == null ? Optional.empty() : Optional.of(record(type, storedVersion)));
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("all consents current + age 18+ → everything accepted, flirt-ready, age-verified")
        void allAcceptedAndAdult() {
            stub(ConsentType.AGE_18_PLUS, V_AGE);
            stub(ConsentType.COMMUNITY_GUIDELINES, V_GUIDE);
            stub(ConsentType.FLIRT_LOBBY, V_FLIRT);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.getAccepted())
                    .containsEntry("AGE_18_PLUS", true)
                    .containsEntry("COMMUNITY_GUIDELINES", true)
                    .containsEntry("FLIRT_LOBBY", true);
            assertThat(res.getRequiredVersions())
                    .containsEntry("AGE_18_PLUS", V_AGE)
                    .containsEntry("COMMUNITY_GUIDELINES", V_GUIDE)
                    .containsEntry("FLIRT_LOBBY", V_FLIRT);
            assertThat(res.isAgeVerified()).isTrue();
            assertThat(res.isFlirtLobbyReady()).isTrue();
        }

        @Test
        @DisplayName("no acceptances → nothing accepted, not age-verified, not flirt-ready")
        void noneAccepted() {
            stub(ConsentType.AGE_18_PLUS, null);
            stub(ConsentType.COMMUNITY_GUIDELINES, null);
            stub(ConsentType.FLIRT_LOBBY, null);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.getAccepted()).containsValue(false)
                    .containsEntry("AGE_18_PLUS", false);
            assertThat(res.isAgeVerified()).isFalse();
            assertThat(res.isFlirtLobbyReady()).isFalse();
        }

        @Test
        @DisplayName("age on file is null → age not verified even if AGE_18_PLUS consent is current")
        void nullAgeNotVerified() {
            user.setAge(null);
            stub(ConsentType.AGE_18_PLUS, V_AGE);
            stub(ConsentType.COMMUNITY_GUIDELINES, V_GUIDE);
            stub(ConsentType.FLIRT_LOBBY, V_FLIRT);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.getAccepted()).containsEntry("AGE_18_PLUS", true);
            assertThat(res.isAgeVerified()).isFalse();
            assertThat(res.isFlirtLobbyReady()).isFalse();
        }

        @Test
        @DisplayName("age under 18 → not age-verified even with all consents current")
        void underageNotVerified() {
            user.setAge(17);
            stub(ConsentType.AGE_18_PLUS, V_AGE);
            stub(ConsentType.COMMUNITY_GUIDELINES, V_GUIDE);
            stub(ConsentType.FLIRT_LOBBY, V_FLIRT);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.isAgeVerified()).isFalse();
            assertThat(res.isFlirtLobbyReady()).isFalse();
        }

        @Test
        @DisplayName("adult but AGE_18_PLUS consent missing → not age-verified")
        void ageConsentMissingNotVerified() {
            stub(ConsentType.AGE_18_PLUS, null);
            stub(ConsentType.COMMUNITY_GUIDELINES, V_GUIDE);
            stub(ConsentType.FLIRT_LOBBY, V_FLIRT);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.isAgeVerified()).isFalse();
            assertThat(res.isFlirtLobbyReady()).isFalse();
        }

        @Test
        @DisplayName("age-verified but flirt-lobby consent missing → age-verified yet not flirt-ready")
        void ageVerifiedButFlirtConsentMissing() {
            stub(ConsentType.AGE_18_PLUS, V_AGE);
            stub(ConsentType.COMMUNITY_GUIDELINES, V_GUIDE);
            stub(ConsentType.FLIRT_LOBBY, null);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.isAgeVerified()).isTrue();
            assertThat(res.isFlirtLobbyReady()).isFalse();
        }

        @Test
        @DisplayName("stored consent version behind the required version → treated as not accepted")
        void staleVersionNotAccepted() {
            stub(ConsentType.AGE_18_PLUS, "age-OLD");
            stub(ConsentType.COMMUNITY_GUIDELINES, V_GUIDE);
            stub(ConsentType.FLIRT_LOBBY, V_FLIRT);

            ConsentStatusResponse res = service.getStatus(user);

            assertThat(res.getAccepted()).containsEntry("AGE_18_PLUS", false);
            assertThat(res.isAgeVerified()).isFalse();
        }
    }

    @Nested
    @DisplayName("accept")
    class Accept {

        @BeforeEach
        void stubStatusReads() {
            // getStatus (called at the end of accept) reads every type; default to "no row".
            lenient().when(consentRepository.findByUserAndConsentType(any(User.class), any(ConsentType.class)))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("client version provided → stores the trimmed client version and evicts feature cache")
        void storesTrimmedClientVersion() {
            ConsentStatusResponse res = service.accept(user, ConsentType.FLIRT_LOBBY, "  2026-08  ", "1.2.3.4");

            ArgumentCaptor<ConsentAcceptance> saved = ArgumentCaptor.forClass(ConsentAcceptance.class);
            verify(consentRepository).save(saved.capture());
            ConsentAcceptance rec = saved.getValue();
            assertThat(rec.getUser()).isSameAs(user);
            assertThat(rec.getConsentType()).isEqualTo(ConsentType.FLIRT_LOBBY);
            assertThat(rec.getConsentVersion()).isEqualTo("2026-08");
            assertThat(rec.getIpAddress()).isEqualTo("1.2.3.4");
            assertThat(rec.getAcceptedAt()).isNotNull();
            verify(featureAccessCache).evict(USER_ID);
            assertThat(res).isNotNull();
        }

        @Test
        @DisplayName("null client version → falls back to the required version")
        void nullVersionFallsBackToRequired() {
            service.accept(user, ConsentType.AGE_18_PLUS, null, "ip");

            ArgumentCaptor<ConsentAcceptance> saved = ArgumentCaptor.forClass(ConsentAcceptance.class);
            verify(consentRepository).save(saved.capture());
            assertThat(saved.getValue().getConsentVersion()).isEqualTo(V_AGE);
            verify(featureAccessCache).evict(USER_ID);
        }

        @Test
        @DisplayName("blank client version → falls back to the required version")
        void blankVersionFallsBackToRequired() {
            service.accept(user, ConsentType.COMMUNITY_GUIDELINES, "   ", "ip");

            ArgumentCaptor<ConsentAcceptance> saved = ArgumentCaptor.forClass(ConsentAcceptance.class);
            verify(consentRepository).save(saved.capture());
            assertThat(saved.getValue().getConsentVersion()).isEqualTo(V_GUIDE);
        }

        @Test
        @DisplayName("existing row → updates it in place rather than creating a new record")
        void updatesExistingRow() {
            ConsentAcceptance existing = record(ConsentType.FLIRT_LOBBY, "old-version");
            existing.setIpAddress("old-ip");
            when(consentRepository.findByUserAndConsentType(user, ConsentType.FLIRT_LOBBY))
                    .thenReturn(Optional.of(existing));

            service.accept(user, ConsentType.FLIRT_LOBBY, "new-version", "new-ip");

            ArgumentCaptor<ConsentAcceptance> saved = ArgumentCaptor.forClass(ConsentAcceptance.class);
            verify(consentRepository).save(saved.capture());
            assertThat(saved.getValue()).isSameAs(existing);
            assertThat(saved.getValue().getConsentVersion()).isEqualTo("new-version");
            assertThat(saved.getValue().getIpAddress()).isEqualTo("new-ip");
        }

        @Test
        @DisplayName("null ip is stored as-is (audit is best-effort)")
        void nullIpStored() {
            service.accept(user, ConsentType.AGE_18_PLUS, "v", null);

            ArgumentCaptor<ConsentAcceptance> saved = ArgumentCaptor.forClass(ConsentAcceptance.class);
            verify(consentRepository).save(saved.capture());
            assertThat(saved.getValue().getIpAddress()).isNull();
        }
    }

    @Nested
    @DisplayName("hasAcceptedCurrent")
    class HasAcceptedCurrent {

        @Test
        @DisplayName("stored version equals required version → true")
        void trueWhenMatching() {
            when(consentRepository.findByUserAndConsentType(user, ConsentType.FLIRT_LOBBY))
                    .thenReturn(Optional.of(record(ConsentType.FLIRT_LOBBY, V_FLIRT)));

            assertThat(service.hasAcceptedCurrent(user, ConsentType.FLIRT_LOBBY)).isTrue();
        }

        @Test
        @DisplayName("stored version differs from required version → false")
        void falseWhenStale() {
            when(consentRepository.findByUserAndConsentType(user, ConsentType.FLIRT_LOBBY))
                    .thenReturn(Optional.of(record(ConsentType.FLIRT_LOBBY, "stale")));

            assertThat(service.hasAcceptedCurrent(user, ConsentType.FLIRT_LOBBY)).isFalse();
        }

        @Test
        @DisplayName("no stored acceptance → false")
        void falseWhenAbsent() {
            when(consentRepository.findByUserAndConsentType(user, ConsentType.FLIRT_LOBBY))
                    .thenReturn(Optional.empty());

            assertThat(service.hasAcceptedCurrent(user, ConsentType.FLIRT_LOBBY)).isFalse();
            verify(featureAccessCache, never()).evict(anyLong());
        }
    }
}
