package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ReferralSummaryResponse;
import com.neo.chat.dto.response.ReferredUserResponse;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.ReferralService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only referral reporting: the exact count of a user's non-deleted referred joiners plus a
 * capped list of the most recent ones. Attribution only — no rewards.
 */
@Service
@RequiredArgsConstructor
public class ReferralServiceImpl implements ReferralService {

    /**
     * Cap the returned joiner list; the count is always exact.
     */
    private static final int MAX_LISTED = 50;

    private final UserRepository userRepository;

    /**
     * Builds the current user's referral summary: the exact non-deleted referral count plus up to
     * {@code MAX_LISTED} most-recent joiners mapped to DTOs. Read-only.
     *
     * @param currentUser the referrer
     * @return the referral summary (count + recent joiners)
     */
    @Override
    @Transactional(readOnly = true)
    public ReferralSummaryResponse getMySummary(User currentUser) {
        long count = userRepository.countByReferredByAndIsDeletedFalse(currentUser);
        List<ReferredUserResponse> recent = userRepository
                .findByReferredByAndIsDeletedFalseOrderByCreatedAtDesc(
                        currentUser, PageRequest.of(0, MAX_LISTED))
                .stream()
                .map(u -> ReferredUserResponse.builder()
                        .id(u.getUuid() != null ? u.getUuid().toString() : null)
                        .name(u.getName())
                        .username(u.getUsername())
                        .avatar(u.getProfileImage())
                        .joinedAt(u.getCreatedAt() != null ? u.getCreatedAt().toString() : null)
                        .build())
                .toList();

        return ReferralSummaryResponse.builder()
                .username(currentUser.getUsername())
                .referralCount(count)
                .referrals(recent)
                .build();
    }
}
