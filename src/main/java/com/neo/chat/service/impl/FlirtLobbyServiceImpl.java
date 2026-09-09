package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.NightUserCard;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.FlirtLobbyService;
import com.neo.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Default {@link FlirtLobbyService} implementation. Lobby membership is a Redis set keyed by
 * username; the roster is capped, self-excluded, filtered to currently-online non-guest/non-banned
 * users, and prunes members that have gone offline so it stays live.
 */
@Service
@RequiredArgsConstructor
public class FlirtLobbyServiceImpl implements FlirtLobbyService {

    private static final String KEY = "flirt-lobby:users";
    private static final int ROSTER_CAP = 60;

    private final PresenceService presenceService;
    private final UserRepository userRepository;
    private final StringRedisTemplate redis;

    /**
     * Add the user to the Redis lobby set and return the current roster from their viewpoint.
     *
     * @param user the entering user
     * @return the visible roster cards
     */
    @Override
    @Transactional(readOnly = true)
    public List<NightUserCard> enter(User user) {
        redis.opsForSet().add(KEY, user.getUsername());
        return computeRoster(user);
    }

    /**
     * Remove the user from the Redis lobby set.
     *
     * @param user the leaving user
     */
    @Override
    public void leave(User user) {
        redis.opsForSet().remove(KEY, user.getUsername());
    }

    /**
     * Build the viewer's lobby roster: exclude self, prune offline members from Redis, cap at
     * {@code ROSTER_CAP}, and drop guests/banned users, returning night-user cards.
     *
     * @param viewer the requesting user (excluded from the result)
     * @return up to {@code ROSTER_CAP} online, eligible roster cards
     */
    @Override
    @Transactional(readOnly = true)
    public List<NightUserCard> roster(User viewer) {
        return computeRoster(viewer);
    }

    /** Proxy-free counterpart of {@link #roster} for same-bean callers (BootUI ARCH-SPRING-004). */
    private List<NightUserCard> computeRoster(User viewer) {
        Set<String> members = redis.opsForSet().members(KEY);
        if (members == null || members.isEmpty()) return List.of();
        Set<String> online = presenceService.getOnlineUsernames();

        List<String> visible = new ArrayList<>();
        for (String m : members) {
            if (m == null || m.equals(viewer.getUsername())) continue;
            if (!online.contains(m)) {
                // Prune members who have gone offline so the roster stays live.
                redis.opsForSet().remove(KEY, m);
                continue;
            }
            visible.add(m);
            if (visible.size() >= ROSTER_CAP) break;
        }
        if (visible.isEmpty()) return List.of();

        List<NightUserCard> cards = new ArrayList<>();
        for (User u : userRepository.findByUsernameIn(visible)) {
            if (u.isGuest() || u.isBanned()) continue;
            cards.add(NightUserCard.builder()
                    .id(u.getUuid().toString())
                    .name(u.getName())
                    .username(u.getUsername())
                    .avatar(u.getProfileImage())
                    .mood(u.getMood() != null ? u.getMood().name() : null)
                    .country(u.getCountry())
                    .presence("online")
                    .build());
        }
        return cards;
    }
}
