package com.neo.chat.service.lookup;

import com.neo.chat.domain.Friend;
import com.neo.chat.domain.User;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.FriendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RelationshipLookupService}: pins the exact block-direction and
 * soft-deleted-friend semantics the {@code WingmanController} icebreaker gate relied on when it
 * queried the repositories inline.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RelationshipLookupService")
class RelationshipLookupServiceTest {

    @Mock
    private FriendRepository friendRepository;
    @Mock
    private BlockUserRepository blockUserRepository;

    @InjectMocks
    private RelationshipLookupService service;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = User.builder().username("me").build();
        me.setId(1L);
        other = User.builder().username("other").build();
        other.setId(2L);
    }

    // ── isBlockedEitherWay ─────────────────────────────────────────────────────

    @Test
    void blockedWhenIBlockedThemAndShortCircuitsReverseLookup() {
        when(blockUserRepository.existsByUserAndBlocked(me, other)).thenReturn(true);

        assertThat(service.isBlockedEitherWay(me, other)).isTrue();
        verify(blockUserRepository, never()).existsByUserAndBlocked(other, me);
        verifyNoInteractions(friendRepository);
    }

    @Test
    void blockedWhenTheyBlockedMe() {
        when(blockUserRepository.existsByUserAndBlocked(me, other)).thenReturn(false);
        when(blockUserRepository.existsByUserAndBlocked(other, me)).thenReturn(true);

        assertThat(service.isBlockedEitherWay(me, other)).isTrue();
        verifyNoInteractions(friendRepository);
    }

    @Test
    void notBlockedWhenNeitherDirectionHasARow() {
        when(blockUserRepository.existsByUserAndBlocked(any(), any())).thenReturn(false);

        assertThat(service.isBlockedEitherWay(me, other)).isFalse();
        verify(blockUserRepository).existsByUserAndBlocked(me, other);
        verify(blockUserRepository).existsByUserAndBlocked(other, me);
    }

    // ── areActiveFriends ───────────────────────────────────────────────────────

    @Test
    void friendsWhenLiveFriendRowExists() {
        Friend friend = Friend.builder().user(me).friend(other).build();
        friend.setDeleted(false);
        when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.of(friend));

        assertThat(service.areActiveFriends(me, other)).isTrue();
        verifyNoInteractions(blockUserRepository);
    }

    @Test
    void notFriendsWhenFriendRowIsSoftDeleted() {
        Friend deleted = Friend.builder().user(me).friend(other).build();
        deleted.setDeleted(true);
        when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.of(deleted));

        assertThat(service.areActiveFriends(me, other)).isFalse();
    }

    @Test
    void notFriendsWhenNoFriendRow() {
        when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());

        assertThat(service.areActiveFriends(me, other)).isFalse();
    }

    @Test
    void areActiveFriendsIsDirectional() {
        // Only the (user, friend) row is consulted — never the reverse — matching the original inline query.
        when(friendRepository.findByUserAndFriend(me, other)).thenReturn(Optional.empty());

        service.areActiveFriends(me, other);
        verify(friendRepository).findByUserAndFriend(me, other);
        verify(friendRepository, never()).findByUserAndFriend(other, me);
    }
}
