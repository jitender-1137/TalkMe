package com.neo.chat.service.impl;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatFlirtMode;
import com.neo.chat.repository.ChatFlirtModeRepository;
import com.neo.chat.repository.ChatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit test for the extracted REQUIRES_NEW insert helper. */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlirtModeRowCreator")
class FlirtModeRowCreatorTest {

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatFlirtModeRepository flirtModeRepository;

    private static final Long CHAT_PK = 100L;
    private static final Long LOW_ID = 10L;
    private static final Long HIGH_ID = 20L;

    @Test
    @DisplayName("builds a fresh row keyed low/high, all flags disabled")
    void buildsFreshRow() {
        FlirtModeRowCreator creator = new FlirtModeRowCreator(chatRepository, flirtModeRepository);
        Chat ref = mock(Chat.class);
        when(chatRepository.getReferenceById(CHAT_PK)).thenReturn(ref);
        when(flirtModeRepository.save(any(ChatFlirtMode.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatFlirtMode created = creator.createInNewTx(CHAT_PK, LOW_ID, HIGH_ID);

        assertThat(created.getLowUserId()).isEqualTo(LOW_ID);
        assertThat(created.getHighUserId()).isEqualTo(HIGH_ID);
        assertThat(created.isEnabledByLow()).isFalse();
        assertThat(created.isEnabledByHigh()).isFalse();
        assertThat(created.isActive()).isFalse();
        assertThat(created.getChat()).isSameAs(ref);
        verify(flirtModeRepository).save(any(ChatFlirtMode.class));
    }
}
