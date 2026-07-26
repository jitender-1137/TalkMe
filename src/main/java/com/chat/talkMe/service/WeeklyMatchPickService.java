package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.WeeklyMatchPickResponse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * Weekly Match Picks (feature #28) — a curated, ranked list of the most compatible users,
 * regenerated once a week. Generation is deterministic (reuses {@code CompatibilityService})
 * and persisted per-user for the ISO week so the list is stable across a user's sessions.
 */
public interface WeeklyMatchPickService {

    /** Current week's persisted picks for the user (empty if none generated yet). */
    List<WeeklyMatchPickResponse> getCurrent(User user);

    /** (Re)generate and persist the top picks for the current week for a single user. */
    void generateFor(User user);

    /** Delete picks from weeks before {@code weekStart} (runs in a transaction). */
    void pruneOlderThan(LocalDate weekStart);

    /** Most recent Monday — the canonical week key for all picks. */
    static LocalDate weekStart() {
        return LocalDate.now().with(DayOfWeek.MONDAY);
    }
}
