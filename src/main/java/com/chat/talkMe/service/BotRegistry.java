package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * In-memory cache of the AI bot accounts, so hot paths (quick-match relay, lobby chat)
 * can answer "is this a bot?" without a DB hit. Also holds the curated sub-pools that
 * decide which bots circulate in the <b>lobby</b> vs <b>quick-match</b> (configurable via
 * {@code app.bot.lobby-usernames} / {@code app.bot.match-usernames}). Refreshed on startup
 * and periodically by {@code BotPresenceHeartbeat}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotRegistry {

    private final UserRepository userRepository;

    /** Which bots appear in the lobby. Order preserved; unknown names ignored. */
    @Value("${app.bot.lobby-usernames:sonal,ruchi,annu,seema,parvathy,fatima,arti,aryan,rohan,karan,nikhil,aditya,rahul,arjun,sameer,vikram,manish}")
    private String lobbyUsernamesCsv;

    /** Which bots are eligible for quick-match. */
    @Value("${app.bot.match-usernames:sia,neetu,ekta,anjali,asiya,sonal,annu,parvathy,aryan,rohan,karan,nikhil,aditya,rahul,arjun,sameer,vikram,manish}")
    private String matchUsernamesCsv;

    /** username → bot User (detached; only simple fields are read afterwards). */
    private volatile Map<String, User> bots = Map.of();
    /** Configured lobby pool ∩ existing bots. */
    private volatile Set<String> lobbyPool = Set.of();
    /** Configured quick-match pool ∩ existing bots (as Users, for random pick). */
    private volatile List<User> matchPool = List.of();

    @PostConstruct
    public void refresh() {
        try {
            List<User> all = userRepository.findAllBots();
            Map<String, User> map = all.stream()
                    .collect(Collectors.toUnmodifiableMap(User::getUsername, u -> u));
            this.bots = map;

            Set<String> lobby = new LinkedHashSet<>();
            for (String u : parseCsv(lobbyUsernamesCsv)) {
                if (map.containsKey(u)) lobby.add(u);
            }
            this.lobbyPool = Set.copyOf(lobby);

            List<User> match = new ArrayList<>();
            for (String u : parseCsv(matchUsernamesCsv)) {
                User bot = map.get(u);
                if (bot != null) match.add(bot);
            }
            this.matchPool = List.copyOf(match);

            log.debug("[bot] registry refreshed: {} bot(s), lobby={}, match={}",
                    map.size(), lobbyPool.size(), matchPool.size());
        } catch (Exception e) {
            log.debug("[bot] registry refresh failed: {}", e.getMessage());
        }
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean isBot(String username) {
        return username != null && bots.containsKey(username);
    }

    public User get(String username) {
        return username == null ? null : bots.get(username);
    }

    public Set<String> usernames() {
        return bots.keySet();
    }

    /** Bots that should be visible in the lobby (falls back to all bots if none configured). */
    public Set<String> lobbyUsernames() {
        return lobbyPool.isEmpty() ? bots.keySet() : lobbyPool;
    }

    /** A random quick-match-eligible bot, or null if none. Falls back to all bots if unconfigured. */
    public User pickRandomMatchBot() {
        List<User> pool = matchPool.isEmpty() ? List.copyOf(bots.values()) : matchPool;
        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
