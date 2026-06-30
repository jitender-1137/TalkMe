package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.ProfileView;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ProfileViewCountResponse;
import com.chat.talkMe.dto.response.ProfileViewResponse;
import com.chat.talkMe.enums.ProfileViewType;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.ProfileViewRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.ProfileViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileViewServiceImpl implements ProfileViewService {

    private final ProfileViewRepository profileViewRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final SimpMessagingTemplate messagingTemplate;

    /** Cap on the "who viewed me" list returned to the client. */
    private static final int MAX_VIEWERS = 100;

    @Override
    @Transactional
    public void recordView(User viewer, String viewedUuid, ProfileViewType type) {
        if (viewer == null || viewedUuid == null) {
            return;
        }
        User viewed;
        try {
            viewed = userRepository.findByUuid(UUID.fromString(viewedUuid)).orElse(null);
        } catch (IllegalArgumentException badUuid) {
            return;
        }
        if (viewed == null || viewed.getId().equals(viewer.getId())) {
            return; // unknown target, or self-view — never recorded
        }

        try {
            ProfileView pv = profileViewRepository.findByViewerAndViewed(viewer, viewed).orElse(null);
            if (pv == null) {
                pv = ProfileView.builder()
                        .viewer(viewer)
                        .viewed(viewed)
                        .lastViewType(type)
                        .viewCount(1)
                        .lastViewedAt(Instant.now())
                        .seen(false)
                        .build();
            } else {
                bump(pv, type);
            }
            profileViewRepository.save(pv);
        } catch (DataIntegrityViolationException raceCreatedRow) {
            // Concurrent first view created the row — increment the existing one instead.
            profileViewRepository.findByViewerAndViewed(viewer, viewed).ifPresent(existing -> {
                bump(existing, type);
                profileViewRepository.save(existing);
            });
        }

        // Real-time nudge to the viewed user: refreshed counts + who just looked.
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("total", profileViewRepository.countByViewed(viewed));
            payload.put("unseen", profileViewRepository.countUnseenByViewed(viewed));
            payload.put("viewType", type.name());
            payload.put("viewer", userMapper.toAuthUserResponse(viewer));
            messagingTemplate.convertAndSendToUser(viewed.getUsername(), "/queue/profile-views", payload);
        } catch (Exception e) {
            log.warn("[ProfileView] failed to notify {}", viewed.getUsername(), e);
        }
    }

    private static void bump(ProfileView pv, ProfileViewType type) {
        pv.setViewCount(pv.getViewCount() + 1);
        pv.setLastViewedAt(Instant.now());
        pv.setLastViewType(type);
        pv.setSeen(false); // a fresh view re-surfaces in the badge
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProfileViewResponse> getViewers(User currentUser) {
        return profileViewRepository.findRecentByViewed(currentUser, PageRequest.of(0, MAX_VIEWERS)).stream()
                .map(pv -> ProfileViewResponse.builder()
                        .viewer(userMapper.toAuthUserResponse(pv.getViewer()))
                        .lastViewedAt(pv.getLastViewedAt() != null ? pv.getLastViewedAt().toString() : null)
                        .viewCount(pv.getViewCount())
                        .viewType(pv.getLastViewType() != null ? pv.getLastViewType().name() : ProfileViewType.PROFILE.name())
                        .seen(pv.isSeen())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileViewCountResponse getCounts(User currentUser) {
        return ProfileViewCountResponse.builder()
                .total(profileViewRepository.countByViewed(currentUser))
                .unseen(profileViewRepository.countUnseenByViewed(currentUser))
                .build();
    }

    @Override
    @Transactional
    public void markAllSeen(User currentUser) {
        profileViewRepository.markAllSeen(currentUser);
    }
}
