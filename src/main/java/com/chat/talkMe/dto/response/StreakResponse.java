package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Public-facing daily-streak snapshot (feature #31, STREAKS). Purely cosmetic display data.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreakResponse {

    private int currentStreak;
    private int longestStreak;
    private LocalDate lastCheckInDay;
    private int freezeTokens;
}
