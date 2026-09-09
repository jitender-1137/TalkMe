package com.neo.chat.service.lookup;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ChatMembershipLookupService}: each lookup must hit exactly the repository
 * query the controllers used inline before (plain vs fetch-join) and translate membership presence
 * to a boolean without any extra filtering.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMembershipLookupService")
class ChatMembershipLookupServiceTest {

    private static final UUID CHAT_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;

    @InjectMocks
    private ChatMembershipLookupService service;

    @Test
    void findByUuidUsesPlainQuery() {
        Chat chat = mock(Chat.class);
        when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.of(chat));

        assertThat(service.findByUuid(CHAT_UUID)).containsSame(chat);
        verify(chatRepository).findByUuid(CHAT_UUID);
        verifyNoMoreInteractions(chatRepository);
        verifyNoInteractions(chatMemberRepository);
    }

    @Test
    void findByUuidReturnsEmptyWhenMissing() {
        when(chatRepository.findByUuid(CHAT_UUID)).thenReturn(Optional.empty());

        assertThat(service.findByUuid(CHAT_UUID)).isEmpty();
    }

    @Test
    void findByUuidWithMembersUsesFetchJoinQuery() {
        Chat chat = mock(Chat.class);
        when(chatRepository.findByUuidWithMembers(CHAT_UUID)).thenReturn(Optional.of(chat));

        assertThat(service.findByUuidWithMembers(CHAT_UUID)).containsSame(chat);
        verify(chatRepository).findByUuidWithMembers(CHAT_UUID);
        verifyNoMoreInteractions(chatRepository);
        verifyNoInteractions(chatMemberRepository);
    }

    @Test
    void findByUuidWithMembersReturnsEmptyWhenMissing() {
        when(chatRepository.findByUuidWithMembers(CHAT_UUID)).thenReturn(Optional.empty());

        assertThat(service.findByUuidWithMembers(CHAT_UUID)).isEmpty();
    }

    @Test
    void isMemberIsTrueWhenMembershipRowExists() {
        Chat chat = mock(Chat.class);
        User user = User.builder().username("alice").build();
        when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.of(mock(ChatMember.class)));

        assertThat(service.isMember(chat, user)).isTrue();
        verify(chatMemberRepository).findByChatAndUser(chat, user);
        verifyNoInteractions(chatRepository);
    }

    @Test
    void isMemberIsFalseWhenNoMembershipRow() {
        Chat chat = mock(Chat.class);
        User user = User.builder().username("alice").build();
        when(chatMemberRepository.findByChatAndUser(chat, user)).thenReturn(Optional.empty());

        assertThat(service.isMember(chat, user)).isFalse();
    }
}
