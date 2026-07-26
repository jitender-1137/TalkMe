package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ListenerShiftResponse;

import java.util.List;

/**
 * Volunteer listener queue for the "Someone Is Listening" experience (features #26/#27).
 *
 * <p>A listener goes {@link #goAvailable AVAILABLE}, is fair-queue-matched to a requester who
 * needs to talk ({@link #requestListener}), and eventually clocks off ({@link #endShift}). The
 * match spins up a LISTENING-mode room whose messages are never recorded. Completing help trends
 * the listener toward the Great Listener badge (via the reputation ledger).
 */
public interface ListenerService {

    /** Put the current user on duty (idempotent): create or re-arm an AVAILABLE shift. */
    ListenerShiftResponse goAvailable(User user);

    /** Take the current user off duty (idempotent). An ENGAGED shift is credited before ending. */
    void endShift(User user);

    /**
     * Match the requester with the oldest-waiting AVAILABLE listener, spin up a LISTENING-mode
     * room, and place the requester in it. The optional {@code reason} shapes the room title so
     * the volunteer has light context before joining. Returns the matched shift.
     */
    ListenerShiftResponse requestListener(User requester, com.chat.talkMe.enums.ListenerReason reason);

    /**
     * Credit a listener for helping one person: increment peopleHelped, trend toward the Great
     * Listener badge at threshold, and re-arm the shift AVAILABLE for the next person. Intended
     * to be called when a listening session concludes.
     */
    ListenerShiftResponse completeShift(User listener);

    /** The current live queue of AVAILABLE listeners. */
    List<ListenerShiftResponse> listAvailable();
}
