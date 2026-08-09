package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.ProfileView;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.ProfileViewCountResponse;
import com.chat.talkMe.dto.response.ProfileViewResponse;
import com.chat.talkMe.enums.ProfileViewType;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.ProfileViewRepository;
import com.chat.talkMe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ProfileViewServiceImpl} — the "who viewed my profile"
 * recorder + reader.
 *
 * <p>{@code recordView} is best-effort and throws nothing to the caller: it silently
 * returns on null args / bad UUID / unknown target / self-view, upserts one row per
 * (viewer, viewed) pair, recovers from the concurrent-insert race
 * ({@link DataIntegrityViolationException} → re-read and bump), and swallows a broadcast
 * failure. The read side ({@code getViewers}, {@code getCounts}) and {@code markAllSeen}
 * are thin repository delegations with DTO shaping. No {@code TM_###} codes are raised by
 * this service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileViewServiceImpl (unit)")
class ProfileViewServiceImplTest {

    private static final String VIEWED_UUID = "33333333-3333-3333-3333-333333333333";

    @Mock
    private ProfileViewRepository profileViewRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ProfileViewServiceImpl service;

    private User viewer;
    private User viewed;

    @BeforeEach
    void setUp() {
        service = new ProfileViewServiceImpl(profileViewRepository, userRepository, userMapper, messagingTemplate);

        viewer = User.builder().username("alice").name("Alice").build();
        viewer.setId(1L);

        viewed = User.builder().username("bob").name("Bob").build();
        viewed.setId(2L);
        viewed.setUuid(UUID.fromString(VIEWED_UUID));
    }

    @Nested
    @DisplayName("recordView")
    class RecordView {

        @Test
        @DisplayName("first view → inserts a fresh row (count 1, unseen) and broadcasts")
        void insertsNewRow() {
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(viewed));
            when(profileViewRepository.findByViewerAndViewed(viewer, viewed)).thenReturn(Optional.empty());

            service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE);

            ArgumentCaptor<ProfileView> saved = ArgumentCaptor.forClass(ProfileView.class);
            verify(profileViewRepository).save(saved.capture());
            ProfileView pv = saved.getValue();
            assertThat(pv.getViewer()).isSameAs(viewer);
            assertThat(pv.getViewed()).isSameAs(viewed);
            assertThat(pv.getViewCount()).isEqualTo(1);
            assertThat(pv.getLastViewType()).isEqualTo(ProfileViewType.PROFILE);
            assertThat(pv.isSeen()).isFalse();
            assertThat(pv.getLastViewedAt()).isNotNull();
        }

        @Test
        @DisplayName("first view → broadcasts refreshed counts + viewer to the viewed user")
        void broadcastsPayload() {
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(viewed));
            when(profileViewRepository.findByViewerAndViewed(viewer, viewed)).thenReturn(Optional.empty());
            when(profileViewRepository.countByViewed(viewed)).thenReturn(5L);
            when(profileViewRepository.countUnseenByViewed(viewed)).thenReturn(2L);
            when(userMapper.toAuthUserResponse(viewer)).thenReturn(new AuthUserResponse());

            service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE_IMAGE);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/profile-views"), payload.capture());
            Map<String, Object> body = payload.getValue();
            assertThat(body.get("total")).isEqualTo(5L);
            assertThat(body.get("unseen")).isEqualTo(2L);
            assertThat(body.get("viewType")).isEqualTo("PROFILE_IMAGE");
            assertThat(body.get("viewer")).isInstanceOf(AuthUserResponse.class);
        }

        @Test
        @DisplayName("repeat view → bumps count, refreshes type, re-flags unseen")
        void bumpsExistingRow() {
            ProfileView existing = ProfileView.builder()
                    .viewer(viewer).viewed(viewed)
                    .lastViewType(ProfileViewType.PROFILE)
                    .viewCount(5).seen(true)
                    .lastViewedAt(Instant.EPOCH)
                    .build();
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(viewed));
            when(profileViewRepository.findByViewerAndViewed(viewer, viewed)).thenReturn(Optional.of(existing));

            service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE_IMAGE);

            ArgumentCaptor<ProfileView> saved = ArgumentCaptor.forClass(ProfileView.class);
            verify(profileViewRepository).save(saved.capture());
            ProfileView pv = saved.getValue();
            assertThat(pv.getViewCount()).isEqualTo(6);
            assertThat(pv.getLastViewType()).isEqualTo(ProfileViewType.PROFILE_IMAGE);
            assertThat(pv.isSeen()).isFalse();
            assertThat(pv.getLastViewedAt()).isAfter(Instant.EPOCH);
        }

        @Test
        @DisplayName("concurrent-insert race → re-reads the winning row, bumps it, still broadcasts")
        void raceReReadsAndBumps() {
            ProfileView winner = ProfileView.builder()
                    .viewer(viewer).viewed(viewed)
                    .lastViewType(ProfileViewType.PROFILE)
                    .viewCount(1).seen(true)
                    .lastViewedAt(Instant.EPOCH)
                    .build();
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(viewed));
            when(profileViewRepository.findByViewerAndViewed(viewer, viewed))
                    .thenReturn(Optional.empty())          // first lookup: nothing yet → build new
                    .thenReturn(Optional.of(winner));       // after the collision: the row that won
            when(profileViewRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_profile_views"))
                    .thenReturn(winner);

            service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE);

            assertThat(winner.getViewCount()).isEqualTo(2);
            assertThat(winner.isSeen()).isFalse();
            verify(profileViewRepository, times(2)).save(any());
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/profile-views"), any());
        }

        @Test
        @DisplayName("race where the winning row vanished → no second save, no throw")
        void raceWinnerVanished() {
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(viewed));
            when(profileViewRepository.findByViewerAndViewed(viewer, viewed))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(profileViewRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_profile_views"));

            assertThatCode(() -> service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE))
                    .doesNotThrowAnyException();

            verify(profileViewRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("broadcast failure → swallowed, the recorded view still stands")
        void broadcastFailureSwallowed() {
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(viewed));
            when(profileViewRepository.findByViewerAndViewed(viewer, viewed)).thenReturn(Optional.empty());
            doThrow(new RuntimeException("stomp down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            assertThatCode(() -> service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE))
                    .doesNotThrowAnyException();

            verify(profileViewRepository).save(any());
        }

        @Test
        @DisplayName("null viewer → no-op, nothing touched")
        void noopOnNullViewer() {
            service.recordView(null, VIEWED_UUID, ProfileViewType.PROFILE);

            verifyNoInteractions(userRepository, profileViewRepository, messagingTemplate);
        }

        @Test
        @DisplayName("null viewed uuid → no-op, nothing touched")
        void noopOnNullUuid() {
            service.recordView(viewer, null, ProfileViewType.PROFILE);

            verifyNoInteractions(userRepository, profileViewRepository, messagingTemplate);
        }

        @Test
        @DisplayName("malformed viewed uuid → swallowed, nothing saved")
        void noopOnMalformedUuid() {
            service.recordView(viewer, "not-a-uuid", ProfileViewType.PROFILE);

            verify(profileViewRepository, never()).save(any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("unknown target → no-op, nothing saved")
        void noopWhenTargetMissing() {
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.empty());

            service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE);

            verify(profileViewRepository, never()).save(any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("self-view → never recorded")
        void noopOnSelfView() {
            User self = User.builder().username("alice").name("Alice").build();
            self.setId(1L); // same id as viewer
            self.setUuid(UUID.fromString(VIEWED_UUID));
            when(userRepository.findByUuid(UUID.fromString(VIEWED_UUID))).thenReturn(Optional.of(self));

            service.recordView(viewer, VIEWED_UUID, ProfileViewType.PROFILE);

            verify(profileViewRepository, never()).save(any());
            verifyNoInteractions(messagingTemplate);
        }
    }

    @Nested
    @DisplayName("getViewers")
    class GetViewers {

        @Test
        @DisplayName("maps each row to a ProfileViewResponse and caps the page at 100")
        void mapsRows() {
            ProfileView row = ProfileView.builder()
                    .viewer(viewer).viewed(viewed)
                    .lastViewType(ProfileViewType.PROFILE_IMAGE)
                    .viewCount(4).seen(true)
                    .lastViewedAt(Instant.EPOCH)
                    .build();
            AuthUserResponse dto = new AuthUserResponse();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(profileViewRepository.findRecentByViewed(eq(viewed), pageable.capture()))
                    .thenReturn(List.of(row));
            when(userMapper.toAuthUserResponse(viewer)).thenReturn(dto);

            List<ProfileViewResponse> result = service.getViewers(viewed);

            assertThat(result).hasSize(1);
            ProfileViewResponse r = result.get(0);
            assertThat(r.getViewer()).isSameAs(dto);
            assertThat(r.getViewCount()).isEqualTo(4);
            assertThat(r.getViewType()).isEqualTo("PROFILE_IMAGE");
            assertThat(r.isSeen()).isTrue();
            assertThat(r.getLastViewedAt()).isEqualTo(Instant.EPOCH.toString());

            assertThat(pageable.getValue().getPageNumber()).isZero();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("null lastViewedAt → null string; null lastViewType → defaults to PROFILE")
        void handlesNullFields() {
            ProfileView row = ProfileView.builder()
                    .viewer(viewer).viewed(viewed)
                    .lastViewType(null)
                    .viewCount(1).seen(false)
                    .lastViewedAt(null)
                    .build();
            when(profileViewRepository.findRecentByViewed(eq(viewed), any())).thenReturn(List.of(row));
            when(userMapper.toAuthUserResponse(viewer)).thenReturn(new AuthUserResponse());

            ProfileViewResponse r = service.getViewers(viewed).get(0);

            assertThat(r.getLastViewedAt()).isNull();
            assertThat(r.getViewType()).isEqualTo("PROFILE");
        }

        @Test
        @DisplayName("no viewers → empty list, no NPE")
        void emptyList() {
            when(profileViewRepository.findRecentByViewed(eq(viewed), any())).thenReturn(List.of());

            assertThat(service.getViewers(viewed)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getCounts")
    class GetCounts {

        @Test
        @DisplayName("returns total + unseen straight from the repository")
        void returnsCounts() {
            when(profileViewRepository.countByViewed(viewed)).thenReturn(9L);
            when(profileViewRepository.countUnseenByViewed(viewed)).thenReturn(3L);

            ProfileViewCountResponse counts = service.getCounts(viewed);

            assertThat(counts.getTotal()).isEqualTo(9L);
            assertThat(counts.getUnseen()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("markAllSeen")
    class MarkAllSeen {

        @Test
        @DisplayName("delegates to the bulk mark-seen update")
        void delegates() {
            service.markAllSeen(viewed);

            verify(profileViewRepository).markAllSeen(viewed);
        }
    }
}
