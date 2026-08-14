package com.neo.chat.crypto;

import com.neo.chat.domain.ChatKey;
import com.neo.chat.repository.ChatKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ChatKeyService} — resolves/creates + caches the per-chat
 * AES data key. Covers: in-memory cache hit, existing-row unwrap, first-use generate+wrap+save,
 * the concurrent-first-use race (DataIntegrityViolation → reuse the winning row), and the
 * race-where-the-winner-vanished failure. A real 32-byte key is used so wrap/unwrap are exercised
 * end-to-end through a real {@link MasterKeyService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatKeyService (unit)")
class ChatKeyServiceTest {

    private static final long CHAT_ID = 7L;

    @Mock
    private ChatKeyRepository chatKeyRepository;
    @Mock
    private MasterKeyService masterKeyService;

    private ChatKeyService service;

    /**
     * Real 32-byte AES key material used as the "unwrapped" value the master returns.
     */
    private final byte[] rawKey = "0123456789abcdef0123456789abcdef".getBytes();

    @BeforeEach
    void setUp() {
        service = new ChatKeyService(chatKeyRepository, masterKeyService);
    }

    @Nested
    @DisplayName("getOrCreateSecretKey")
    class GetOrCreate {

        @Test
        @DisplayName("existing row → unwraps and returns the stored key, then caches it")
        void existingRow() {
            ChatKey row = ChatKey.builder().chatId(CHAT_ID).wrappedKey("wrapped-blob").build();
            when(chatKeyRepository.findByChatId(CHAT_ID)).thenReturn(Optional.of(row));
            when(masterKeyService.unwrap("wrapped-blob")).thenReturn(rawKey);

            SecretKey first = service.getOrCreateSecretKey(CHAT_ID);
            assertThat(first.getEncoded()).isEqualTo(rawKey);

            // Second call is served from the in-memory cache — no repo/unwrap re-hit.
            SecretKey second = service.getOrCreateSecretKey(CHAT_ID);
            assertThat(second).isSameAs(first);
            verify(chatKeyRepository, times(1)).findByChatId(CHAT_ID);
            verify(masterKeyService, times(1)).unwrap("wrapped-blob");
            verify(chatKeyRepository, never()).save(any());
        }

        @Test
        @DisplayName("no row → generates, wraps and persists a new key")
        void firstUseGenerates() {
            when(chatKeyRepository.findByChatId(CHAT_ID)).thenReturn(Optional.empty());
            when(masterKeyService.wrap(any())).thenReturn("newly-wrapped");

            SecretKey key = service.getOrCreateSecretKey(CHAT_ID);

            assertThat(key).isNotNull();
            assertThat(key.getEncoded()).hasSize(32);

            ArgumentCaptor<ChatKey> saved = ArgumentCaptor.forClass(ChatKey.class);
            verify(chatKeyRepository).save(saved.capture());
            assertThat(saved.getValue().getChatId()).isEqualTo(CHAT_ID);
            assertThat(saved.getValue().getWrappedKey()).isEqualTo("newly-wrapped");
        }

        @Test
        @DisplayName("generated key is cached — second call does not re-generate")
        void firstUseThenCached() {
            when(chatKeyRepository.findByChatId(CHAT_ID)).thenReturn(Optional.empty());
            when(masterKeyService.wrap(any())).thenReturn("newly-wrapped");

            SecretKey first = service.getOrCreateSecretKey(CHAT_ID);
            SecretKey second = service.getOrCreateSecretKey(CHAT_ID);

            assertThat(second).isSameAs(first);
            verify(chatKeyRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("concurrent first-use race → reuses the winning row instead of failing")
        void raceReusesWinner() {
            ChatKey winner = ChatKey.builder().chatId(CHAT_ID).wrappedKey("winner-wrapped").build();
            when(chatKeyRepository.findByChatId(CHAT_ID))
                    .thenReturn(Optional.empty())   // initial lookup: nothing yet
                    .thenReturn(Optional.of(winner)); // after the save collision: the row that won
            when(masterKeyService.wrap(any())).thenReturn("mine-wrapped");
            when(chatKeyRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_chat_keys_chat"));
            when(masterKeyService.unwrap("winner-wrapped")).thenReturn(rawKey);

            SecretKey key = service.getOrCreateSecretKey(CHAT_ID);

            assertThat(key.getEncoded()).isEqualTo(rawKey);
            verify(masterKeyService).unwrap("winner-wrapped");
        }

        @Test
        @DisplayName("race where the winning row cannot be re-read → rethrows the original violation")
        void raceWinnerVanished() {
            when(chatKeyRepository.findByChatId(CHAT_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(masterKeyService.wrap(any())).thenReturn("mine-wrapped");
            DataIntegrityViolationException violation = new DataIntegrityViolationException("dup");
            when(chatKeyRepository.save(any())).thenThrow(violation);

            assertThatThrownBy(() -> service.getOrCreateSecretKey(CHAT_ID))
                    .isSameAs(violation);
        }
    }

    @Nested
    @DisplayName("getRawKeyBase64")
    class RawKey {

        @Test
        @DisplayName("returns base64 of the resolved key's encoded bytes")
        void returnsBase64() {
            ChatKey row = ChatKey.builder().chatId(CHAT_ID).wrappedKey("wrapped-blob").build();
            when(chatKeyRepository.findByChatId(CHAT_ID)).thenReturn(Optional.of(row));
            when(masterKeyService.unwrap("wrapped-blob")).thenReturn(rawKey);

            String b64 = service.getRawKeyBase64(CHAT_ID);

            assertThat(Base64.getDecoder().decode(b64)).isEqualTo(rawKey);
        }
    }
}
