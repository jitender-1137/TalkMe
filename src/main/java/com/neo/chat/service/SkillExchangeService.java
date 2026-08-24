package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.UpdateSkillsRequest;
import com.neo.chat.dto.response.SkillMatchResponse;
import com.neo.chat.dto.response.SkillProfileResponse;

import java.util.List;

/**
 * Skill / Study Exchange (feature SKILL_EXCHANGE). Peer-to-peer teach/learn matching:
 * a user who WANTs a skill is matched to users who OFFER it, with reciprocal exchanges
 * ("you can teach each other") ranked highest.
 */
public interface SkillExchangeService {

    /**
     * The caller's own skill profile — the skills they offer and the skills they want.
     */
    SkillProfileResponse getMine(User user);

    /**
     * Replace the caller's entire skill set. Each item's name is trimmed, blank-rejected, capped
     * at 60 chars and de-duplicated case-insensitively, with at most 20 entries per direction; the
     * optional {@code level} is persisted as-is (may be null). Returns the resulting profile.
     */
    SkillProfileResponse updateSkills(User user, List<UpdateSkillsRequest.SkillItem> offers,
                                      List<UpdateSkillsRequest.SkillItem> wants);

    /**
     * Find users who OFFER something the caller WANTs. Reciprocal matches (they also WANT
     * something the caller OFFERs) rank highest, with compatibility as a tie-break. Skips the
     * caller, blocked users (either direction), and deleted/banned/guest users.
     */
    List<SkillMatchResponse> findMatches(User user);

    /**
     * Validate that {@code otherUserUuid} resolves to a real, non-blocked user and return their
     * public match card so the client can open a 1:1 chat with them.
     */
    SkillMatchResponse startStudySession(User user, String otherUserUuid);
}
