package com.chat.talkMe.repository;

import com.chat.talkMe.domain.ListenerShift;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for volunteer listener shifts (features #26/#27). The DB is the source of truth for
 * fair-queue ordering (oldest AVAILABLE first); the {@code listeners:available} Redis set is only a
 * fast, fail-open availability hint layered on top.
 */
@Repository
public interface ListenerShiftRepository extends JpaRepository<ListenerShift, Long> {

    /** A listener's current live shift (anything not yet ENDED), if any. */
    Optional<ListenerShift> findFirstByListenerAndStatusNotOrderByStartedAtDesc(User listener, ShiftStatus status);

    /** Fair-queue pick: the oldest-waiting shift in the given status, excluding a specific listener (self). */
    Optional<ListenerShift> findFirstByStatusAndListenerNotOrderByStartedAtAsc(ShiftStatus status, User listener);

    /** Fair-queue head of a status (oldest first). */
    Optional<ListenerShift> findFirstByStatusOrderByStartedAtAsc(ShiftStatus status);

    /** All shifts in a status, oldest first — the live queue for listings. */
    List<ListenerShift> findByStatusOrderByStartedAtAsc(ShiftStatus status);
}
