package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightUserCard;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.FlirtLobbyService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FlirtLobbyServiceImpl implements FlirtLobbyService {

    private static final String KEY = "flirt-lobby:users";
    private static final int ROSTER_CAP = 60;

    private final PresenceService presenceService;
    private final UserRepository userRepository;
    private final StringRedisTemplate redis;

    @Override
    @Transactional(readOnly = true)
    public List<NightUserCard> enter(User user) {
        redis.opsForSet().add(KEY, user.getUsername());
        return roster(user);
    }

    @Override
    public void leave(User user) {
        redis.opsForSet().remove(KEY, user.getUsername());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NightUserCard> roster(User viewer) {
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
