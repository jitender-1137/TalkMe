package com.neo.chat.service.lookup;

import com.neo.chat.domain.User;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.FriendRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Pairwise relationship checks (block state, active friendship) for controllers that gate a
 * response on the relationship between two users. Mirrors the repository queries exactly so
 * semantics are unchanged; it exists only so the web layer stops depending on
 * {@link FriendRepository} / {@link BlockUserRepository} directly.
 */
@Service
@RequiredArgsConstructor
public class RelationshipLookupService {

    private final FriendRepository friendRepository;
    private final BlockUserRepository blockUserRepository;

    /**
     * Whether a block exists in either direction between the two users. Short-circuits: the reverse
     * direction is only queried when the forward direction is not blocked.
     *
     * @param a one user
     * @param b the other user
     * @return true when {@code a} blocked {@code b} or {@code b} blocked {@code a}
     */
    public boolean isBlockedEitherWay(User a, User b) {
        return blockUserRepository.existsByUserAndBlocked(a, b)
                || blockUserRepository.existsByUserAndBlocked(b, a);
    }

    /**
     * Whether {@code user} has a non-soft-deleted friend row pointing at {@code friend}.
     *
     * @param user   the owner side of the friendship row
     * @param friend the friend side of the friendship row
     * @return true when the row exists and is not soft-deleted
     */
    public boolean areActiveFriends(User user, User friend) {
        return friendRepository.findByUserAndFriend(user, friend)
                .map(f -> !f.isDeleted())
                .orElse(false);
    }
}
