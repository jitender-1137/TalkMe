package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightOwlDashboardResponse;
import com.chat.talkMe.dto.response.NightUserCard;
import com.chat.talkMe.dto.response.TrendingRoomCard;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NightOwlService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the Night Owl Lobby dashboard from cheap, already-maintained signals: the
 * Redis presence set (who's online) and a recent-joins query. "Trending topics" are
 * the most common interests across the current night crowd. (A per-user-timezone
 * "night cohort" refinement can layer on later; the lobby is itself shown at night.)
 */
@Service
@RequiredArgsConstructor
public class NightOwlServiceImpl implements NightOwlService {

    private static final int ONLINE_SAMPLE = 12;
    private static final int RECENT_LIMIT = 8;
    private static final int TRENDING_LIMIT = 6;
    private static final int TRENDING_ROOMS_MAX = 30;

    private final PresenceService presenceService;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;

    @Override
    @Transactional(readOnly = true)
    public NightOwlDashboardResponse getDashboard(User currentUser) {
        Set<String> onlineUsernames = presenceService.getOnlineUsernames();
        int nightUsersOnline = onlineUsernames.size();

        // Sample of who's online right now (exclude self), as light cards.
        List<String> sample = onlineUsernames.stream()
                .filter(u -> !u.equals(currentUser.getUsername()))
                .limit(ONLINE_SAMPLE)
                .collect(Collectors.toList());
        List<User> onlineUsers = sample.isEmpty()
                ? List.of()
                : userRepository.findByUsernameIn(sample);
        List<NightUserCard> onlineNow = onlineUsers.stream()
                .filter(u -> !u.isGuest() && !u.isBanned())
                .map(u -> toCard(u, "online"))
                .collect(Collectors.toList());

        // Recently joined real accounts.
        List<User> recent = userRepository
                .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                        PageRequest.of(0, RECENT_LIMIT + 1))
                .stream()
                .filter(u -> !u.getId().equals(currentUser.getId()))
                .limit(RECENT_LIMIT)
                .collect(Collectors.toList());
        List<NightUserCard> recentlyJoined = recent.stream()
                .map(u -> toCard(u, null))
                .collect(Collectors.toList());

        // Trending topics = most common interests across the night crowd.
        List<String> trendingTopics = trending(onlineUsers, recent);

        return NightOwlDashboardResponse.builder()
                .nightUsersOnline(nightUsersOnline)
                .onlineNow(onlineNow)
                .recentlyJoined(recentlyJoined)
                .trendingTopics(trendingTopics)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrendingRoomCard> trendingRooms(int limit) {
        int capped = Math.min(Math.max(1, limit), TRENDING_ROOMS_MAX);
        List<Chat> rooms = chatRepository.findTrendingRooms(PageRequest.of(0, capped));
        return rooms.stream().map(this::toRoomCard).collect(Collectors.toList());
    }

    private TrendingRoomCard toRoomCard(Chat c) {
        return TrendingRoomCard.builder()
                .id(c.getUuid().toString())
                .name(c.getName())
                .description(c.getDescription())
                .avatar(c.getImageUrl())
                .category(c.getCategory())
                .tags(c.getTags() == null ? List.of()
                        : c.getTags().stream().map(Enum::name).collect(Collectors.toList()))
                .memberCount((int) chatMemberRepository.countActiveMembers(c))
                .curated(c.isRoomCurated())
                .cityLocation(c.getCityLocation() != null ? c.getCityLocation().getSlug() : null)
                .build();
    }

    private NightUserCard toCard(User u, String presence) {
        return NightUserCard.builder()
                .id(u.getUuid().toString())
                .name(u.getName())
                .username(u.getUsername())
                .avatar(u.getProfileImage())
                .mood(u.getMood() != null ? u.getMood().name() : null)
                .country(u.getCountry())
                .presence(presence)
                .build();
    }

    private List<String> trending(List<User> a, List<User> b) {
        Map<Interest, Integer> counts = new EnumMap<>(Interest.class);
        for (User u : a) tally(counts, u);
        for (User u : b) tally(counts, u);
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Interest, Integer>comparingByValue().reversed())
                .limit(TRENDING_LIMIT)
                .map(e -> e.getKey().name())
                .collect(Collectors.toList());
    }

    private void tally(Map<Interest, Integer> counts, User u) {
        if (u.getInterests() == null) return;
        for (Interest i : u.getInterests()) counts.merge(i, 1, Integer::sum);
    }
}
