package com.chat.talkMe.schedule;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.BotRegistry;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps the AI bots present: shows them ONLINE (presence liveness is a Redis heartbeat
 * ZSET that {@code PresenceWatchdog} prunes after 60s — bots have no client to beat, so
 * this refreshes them well within that window), keeps them in the lobby, and refreshes
 * the {@link BotRegistry} cache. Cheap — only the handful of bot rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotPresenceHeartbeat {

    private final UserRepository userRepository;
    private final PresenceService presenceService;
    private final BotRegistry botRegistry;
    private final StringRedisTemplate redisTemplate;

    /** Same set the lobby uses (WebSocketController) — membership makes a user appear in the lobby. */
    private static final String LOBBY_USERS_KEY = "lobby:users";

    /** Chance per heartbeat that a present lobby bot steps away. */
    @Value("${app.bot.lobby-away-chance:0.04}")
    private double lobbyAwayChance;
    /** How long a bot stays away from the lobby (random within this range). */
    @Value("${app.bot.lobby-away-min-ms:600000}")
    private long lobbyAwayMinMs;
    @Value("${app.bot.lobby-away-max-ms:1200000}")
    private long lobbyAwayMaxMs;

    /** username → time the bot returns; present while absent from this map (feels like a real user coming/going). */
    private final Map<String, Instant> lobbyAwayUntil = new ConcurrentHashMap<>();

    /** Discover realism: keep a rotating 3-7 bots OFFLINE, each for a random 30-60 min. */
    @Value("${app.bot.offline-min:3}")
    private int offlineMin;
    @Value("${app.bot.offline-max:7}")
    private int offlineMax;
    @Value("${app.bot.offline-min-ms:1800000}")
    private long offlineMinMs;
    @Value("${app.bot.offline-max-ms:3600000}")
    private long offlineMaxMs;

    /** username → time the bot comes back online. While present here the bot is shown OFFLINE. */
    private final Map<String, Instant> offlineUntil = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${app.bot.presence-heartbeat-ms:45000}")
    public void beat() {
        botRegistry.refresh();

        List<User> bots;
        try {
            bots = userRepository.findAllBots();
        } catch (Exception e) {
            log.debug("[bot] presence heartbeat: could not load bots: {}", e.getMessage());
            return;
        }
        // Only the curated lobby pool circulates in the lobby; the rest stay online
        // (searchable / DM-able) but out of the lobby list.
        java.util.Set<String> lobbyPool = botRegistry.lobbyUsernames();
        manageOfflineRotation(bots);
        for (User bot : bots) {
            try {
                String username = bot.getUsername();
                // Discover realism: a rotating subset is OFFLINE for a while → not online, not in lobby.
                if (offlineUntil.containsKey(username)) {
                    presenceService.setStatus(bot, PresenceStatus.OFFLINE);
                    redisTemplate.opsForSet().remove(LOBBY_USERS_KEY, username);
                    continue;
                }
                // Otherwise ONLINE. An "away" bot has only left the LOBBY (still online → DMs/search work).
                presenceService.setStatus(bot, PresenceStatus.ONLINE);
                if (lobbyPool.contains(username) && !isLobbyAway(username)) {
                    redisTemplate.opsForSet().add(LOBBY_USERS_KEY, username);
                } else {
                    redisTemplate.opsForSet().remove(LOBBY_USERS_KEY, username);
                }
            } catch (Exception e) {
                log.debug("[bot] presence heartbeat failed for {}: {}", bot.getUsername(), e.getMessage());
            }
        }
    }

    /**
     * Keep a rotating set of 3-7 bots OFFLINE for a random 30-60 min each (Discover realism).
     * Expires bots whose window elapsed (they return online), and tops the set back up to a
     * random target when it drops below the minimum — so the offline set keeps changing.
     */
    private void manageOfflineRotation(List<User> bots) {
        Instant now = Instant.now();
        offlineUntil.entrySet().removeIf(e -> {
            if (now.isBefore(e.getValue())) return false; // still offline
            log.info("[bot] {} came back online (discover)", e.getKey());
            return true;
        });
        if (offlineUntil.size() >= offlineMin) return; // enough already offline

        int target = ThreadLocalRandom.current().nextInt(Math.max(1, offlineMin), Math.max(offlineMin, offlineMax) + 1);
        List<String> candidates = new ArrayList<>();
        for (User b : bots) if (!offlineUntil.containsKey(b.getUsername())) candidates.add(b.getUsername());
        Collections.shuffle(candidates);
        for (int i = 0; i < candidates.size() && offlineUntil.size() < target; i++) {
            long lo = Math.max(60000, offlineMinMs);
            long hi = Math.max(lo + 1, offlineMaxMs);
            long ms = ThreadLocalRandom.current().nextLong(lo, hi + 1);
            offlineUntil.put(candidates.get(i), now.plusMillis(ms));
            log.info("[bot] {} went offline for ~{} min (discover)", candidates.get(i), Math.round(ms / 60000.0));
        }
    }

    /**
     * Whether a lobby bot is currently "away". A present bot has a small random chance each
     * heartbeat to leave the lobby for a random 10-20 min, then it returns automatically — so
     * the lobby roster changes over time like real users coming and going.
     */
    private boolean isLobbyAway(String username) {
        Instant now = Instant.now();
        Instant until = lobbyAwayUntil.get(username);
        if (until != null) {
            if (now.isBefore(until)) return true;          // still away
            lobbyAwayUntil.remove(username);               // timer up → back in the lobby
            log.info("[bot] {} returned to the lobby", username);
            return false;
        }
        // Present — occasionally step away.
        if (ThreadLocalRandom.current().nextDouble() < lobbyAwayChance) {
            long lo = Math.max(1000, lobbyAwayMinMs);
            long hi = Math.max(lo + 1, lobbyAwayMaxMs);
            long awayMs = ThreadLocalRandom.current().nextLong(lo, hi + 1);
            lobbyAwayUntil.put(username, now.plusMillis(awayMs));
            log.info("[bot] {} left the lobby for ~{} min", username, Math.round(awayMs / 60000.0));
            return true;
        }
        return false;
    }
}
