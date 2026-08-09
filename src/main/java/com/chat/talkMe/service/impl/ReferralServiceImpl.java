package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ReferralSummaryResponse;
import com.chat.talkMe.dto.response.ReferredUserResponse;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ReferralService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReferralServiceImpl implements ReferralService {

    /**
     * Cap the returned joiner list; the count is always exact.
     */
    private static final int MAX_LISTED = 50;

    private final UserRepository userRepository;

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
