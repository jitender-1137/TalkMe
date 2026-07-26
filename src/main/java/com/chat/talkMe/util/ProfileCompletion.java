package com.chat.talkMe.util;

import com.chat.talkMe.domain.User;

/**
 * Computes a 0–100 profile-completion score from a weighted checklist. Stored on the
 * user (recomputed on profile writes) and surfaced on the Smart Profile Card + fed to
 * gamification (profile-completed reputation event). Weights sum to 100.
 */
public final class ProfileCompletion {

    private ProfileCompletion() {}

    public static int compute(User user) {
        if (user == null) return 0;
        int score = 0;
        if (notBlank(user.getProfileImage())) score += 20;
        if (notBlank(user.getBio())) score += 15;
        if (user.getInterests() != null && user.getInterests().size() >= 3) score += 15;
        if (user.getMood() != null) score += 10;
        if (user.getConversationEnergy() != null) score += 10;
        if (user.getLanguages() != null && !user.getLanguages().isEmpty()) score += 10;
        if (user.getLookingFor() != null && !user.getLookingFor().isEmpty()) score += 10;
        if (notBlank(user.getVoiceIntroUrl())) score += 10;
        return Math.min(100, score);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
