package com.chat.talkMe.service;

import com.chat.talkMe.domain.MediaAsset;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.MediaContext;
import com.chat.talkMe.repository.MediaAssetRepository;
import com.chat.talkMe.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MediaAssetService} — the fail-open bookkeeping that
 * records an admin-only ownership row for every upload. Verifies context/contextId
 * derivation from the stored key, the upsert-by-key path, the null-key skip, and the
 * fail-open guarantee (a repository/DB error is swallowed, never propagated). The static
 * {@code MediaKeys.key(...)} helper is exercised for real; no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaAssetService (unit)")
class MediaAssetServiceTest {

    @Mock
    private MediaAssetRepository repository;
    @Mock
    private StorageProperties storageProperties;

    private MediaAssetService service;

    @BeforeEach
    void setUp() {
        service = new MediaAssetService(repository, storageProperties);
        lenient().when(storageProperties.getMediaRoot()).thenReturn("/media");
    }

    private User owner(String username) {
        User u = User.builder().username(username).name(username + " N").email(username + "@x.com").build();
        u.setId(7L);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private MediaAsset captureSaved() {
        ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("conversation upload → CONVERSATION context with the chat id as contextId")
        void recordsConversationWithContextId() {
            User u = owner("alice");
            when(repository.findByStorageKey("conversations/chat-abc/f.jpg")).thenReturn(Optional.empty());

            service.record("/media/conversations/chat-abc/f.jpg", u, "image", "photo.jpg", "image/jpeg", 123L);

            MediaAsset saved = captureSaved();
            assertThat(saved.getStorageKey()).isEqualTo("conversations/chat-abc/f.jpg");
            assertThat(saved.getReference()).isEqualTo("/media/conversations/chat-abc/f.jpg");
            assertThat(saved.getContext()).isEqualTo(MediaContext.CONVERSATION);
            assertThat(saved.getContextId()).isEqualTo("chat-abc");
            assertThat(saved.getOwner()).isSameAs(u);
            assertThat(saved.getUploadType()).isEqualTo("image");
            assertThat(saved.getOriginalFileName()).isEqualTo("photo.jpg");
            assertThat(saved.getContentType()).isEqualTo("image/jpeg");
            assertThat(saved.getFileSize()).isEqualTo(123L);
        }

        @Test
        @DisplayName("stranger upload → STRANGER context, no contextId (flat anonymous folder)")
        void recordsStrangerWithoutContextId() {
            when(repository.findByStorageKey("strangers/rand.jpg")).thenReturn(Optional.empty());

            service.record("/media/strangers/rand.jpg", owner("bob"), "image", "s.jpg", "image/jpeg", 10L);

            MediaAsset saved = captureSaved();
            assertThat(saved.getContext()).isEqualTo(MediaContext.STRANGER);
            assertThat(saved.getContextId()).isNull();
        }

        @Test
        @DisplayName("re-store of the same key updates the existing row (upsert, not a duplicate insert)")
        void upsertsExistingRow() {
            MediaAsset existing = MediaAsset.builder().storageKey("lobby/u/f.jpg").build();
            existing.setId(99L);
            when(repository.findByStorageKey("lobby/u/f.jpg")).thenReturn(Optional.of(existing));

            service.record("/media/lobby/u/f.jpg", owner("carol"), "image", "f.jpg", "image/jpeg", 5L);

            MediaAsset saved = captureSaved();
            assertThat(saved).isSameAs(existing);                 // reused, not a new instance
            assertThat(saved.getContext()).isEqualTo(MediaContext.LOBBY);
            assertThat(saved.getFileSize()).isEqualTo(5L);
        }

        @Test
        @DisplayName("unresolvable reference (no derivable key) → nothing is persisted")
        void skipsWhenKeyUnresolvable() {
            service.record("not-a-real-path", owner("dave"), "image", "x.jpg", "image/jpeg", 1L);

            verify(repository, never()).findByStorageKey(any());
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a DB/flush error is swallowed — an upload is never broken by bookkeeping")
        void failsOpenOnSaveError() {
            when(repository.findByStorageKey(any())).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any())).thenThrow(new RuntimeException("db down"));

            assertThatCode(() ->
                    service.record("/media/strangers/z.jpg", owner("erin"), "image", "z.jpg", "image/jpeg", 1L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("over-long filename / content-type are truncated to the column caps (512 / 100)")
        void truncatesLongFields() {
            when(repository.findByStorageKey(any())).thenReturn(Optional.empty());
            String longName = "n".repeat(600);
            String longType = "t".repeat(150);

            service.record("/media/strangers/z.jpg", owner("fay"), "image", longName, longType, 1L);

            MediaAsset saved = captureSaved();
            assertThat(saved.getOriginalFileName()).hasSize(512);
            assertThat(saved.getContentType()).hasSize(100);
        }

        @Test
        @DisplayName("null owner is tolerated (anonymous flow) and still recorded")
        void toleratesNullOwner() {
            when(repository.findByStorageKey(any())).thenReturn(Optional.empty());

            service.record("/media/strangers/z.jpg", null, "image", "z.jpg", "image/jpeg", 1L);

            assertThat(captureSaved().getOwner()).isNull();
        }
    }
}
