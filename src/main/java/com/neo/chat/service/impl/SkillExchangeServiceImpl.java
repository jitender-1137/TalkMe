package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSkill;
import com.neo.chat.dto.request.UpdateSkillsRequest;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.SkillMatchResponse;
import com.neo.chat.dto.response.SkillProfileResponse;
import com.neo.chat.enums.SkillDirection;
import com.neo.chat.enums.SkillLevel;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSkillRepository;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.SkillExchangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Skill / Study Exchange (feature SKILL_EXCHANGE).
 *
 * <p>Matching is directional: for each skill the caller WANTs, we look up the users who OFFER
 * that same skill (case-insensitive). A candidate is a reciprocal match when they also WANT a
 * skill the caller OFFERs. Reciprocal matches rank first, then higher compatibility, then more
 * teachable skills. Self, blocked (either direction), and deleted/banned/guest users are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SkillExchangeServiceImpl implements SkillExchangeService {

    /**
     * Max skills a user may list in a single direction (anti-spam cap).
     */
    private static final int MAX_SKILLS_PER_DIRECTION = 20;

    /**
     * Max length of a single skill name (matches the column length).
     */
    private static final int MAX_SKILL_NAME_LENGTH = 60;

    private final UserSkillRepository userSkillRepository;
    private final UserRepository userRepository;
    private final CompatibilityService compatibilityService;
    private final PresenceService presenceService;
    private final BlockUserRepository blockUserRepository;

    /**
     * Returns the caller's own offers and wants. Read-only transaction.
     */
    @Override
    @Transactional(readOnly = true)
    public SkillProfileResponse getMine(User user) {
        return SkillProfileResponse.builder()
                .offers(toItems(userSkillRepository.findByUserAndDirection(user, SkillDirection.OFFER)))
                .wants(toItems(userSkillRepository.findByUserAndDirection(user, SkillDirection.WANT)))
                .build();
    }

    /**
     * Replaces the caller's entire skill set. Each name is trimmed and rejected if blank or
     * longer than {@value #MAX_SKILL_NAME_LENGTH} chars (TM_916); each direction is de-duplicated
     * case-insensitively and capped at {@value #MAX_SKILLS_PER_DIRECTION} entries (TM_915).
     *
     * @throws BadRequestException        if any name is blank or too long (TM_916)
     * @throws TooManyRequestsException   if either direction exceeds the cap (TM_915)
     */
    @Override
    public SkillProfileResponse updateSkills(User user, List<UpdateSkillsRequest.SkillItem> offers,
                                             List<UpdateSkillsRequest.SkillItem> wants) {
        List<UserSkill> cleanOffers = normalize(user, offers, SkillDirection.OFFER);
        List<UserSkill> cleanWants = normalize(user, wants, SkillDirection.WANT);

        // Bulk delete executes immediately, so the re-insert cannot collide with the unique key.
        userSkillRepository.deleteAllByUser(user);

        List<UserSkill> toSave = new ArrayList<>();
        toSave.addAll(cleanOffers);
        toSave.addAll(cleanWants);
        userSkillRepository.saveAll(toSave);

        return SkillProfileResponse.builder()
                .offers(toItems(cleanOffers))
                .wants(toItems(cleanWants))
                .build();
    }

    /**
     * Finds users who OFFER a skill the caller WANTs, builds a match card per candidate, and
     * ranks reciprocal matches first (then compatibility, then teachable-skill count). Read-only.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SkillMatchResponse> findMatches(User user) {
        List<UserSkill> myWants = userSkillRepository.findByUserAndDirection(user, SkillDirection.WANT);
        if (myWants.isEmpty()) {
            return List.of();
        }
        // Caller's offers keyed by lowercase name → original casing (for "you can teach them X").
        Map<String, String> myOffersByLower = new LinkedHashMap<>();
        for (UserSkill s : userSkillRepository.findByUserAndDirection(user, SkillDirection.OFFER)) {
            myOffersByLower.putIfAbsent(s.getName().toLowerCase(Locale.ROOT), s.getName());
        }

        // Discover candidates: users who OFFER one of the caller's wants. Accumulate what each
        // can teach the caller, preserving first-seen order for determinism.
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        for (UserSkill want : myWants) {
            List<UserSkill> offerers = userSkillRepository
                    .findByDirectionAndNameIgnoreCaseExcludingUser(SkillDirection.OFFER, want.getName(), user.getId());
            for (UserSkill offer : offerers) {
                User other = offer.getUser();
                if (other == null || other.isDeleted() || other.isBanned() || other.isGuest()) {
                    continue;
                }
                Candidate c = candidates.computeIfAbsent(other.getId(), k -> new Candidate(other));
                c.theyCanTeachYou.add(offer.getName());
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> online = onlineUsernamesSafe();

        List<SkillMatchResponse> result = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            User other = c.user;
            // Never surface a match across a block, in either direction.
            if (blockUserRepository.existsByUserAndBlocked(user, other)
                    || blockUserRepository.existsByUserAndBlocked(other, user)) {
                continue;
            }
            // Reciprocity: skills the caller offers that this candidate wants.
            List<String> youCanTeachThem = new ArrayList<>();
            if (!myOffersByLower.isEmpty()) {
                Set<String> seen = new LinkedHashSet<>();
                for (UserSkill theirWant : userSkillRepository.findByUserAndDirection(other, SkillDirection.WANT)) {
                    String lower = theirWant.getName().toLowerCase(Locale.ROOT);
                    if (myOffersByLower.containsKey(lower) && seen.add(lower)) {
                        youCanTeachThem.add(myOffersByLower.get(lower));
                    }
                }
            }
            result.add(card(user, other,
                    new ArrayList<>(c.theyCanTeachYou),
                    youCanTeachThem,
                    online.contains(other.getUsername())));
        }

        result.sort(Comparator
                .comparing(SkillMatchResponse::isReciprocal).reversed()
                .thenComparing(Comparator.comparingInt(SkillExchangeServiceImpl::overallOf).reversed())
                .thenComparing(Comparator.comparingInt((SkillMatchResponse m) ->
                        m.getTheyCanTeachYou() == null ? 0 : m.getTheyCanTeachYou().size()).reversed())
                .thenComparing(m -> m.getUsername() == null ? "" : m.getUsername()));
        return result;
    }

    /**
     * Validates the target and returns their match card so the client can open a 1:1 chat.
     *
     * @throws BadRequestException if the uuid is malformed (TM_914) or is the caller (TM_919)
     * @throws NotFoundException   if no live user has that uuid (TM_917)
     * @throws ForbiddenException  if either party has blocked the other (TM_918)
     */
    @Override
    @Transactional(readOnly = true)
    public SkillMatchResponse startStudySession(User user, String otherUserUuid) {
        User other = resolveUser(otherUserUuid);
        if (other.getId().equals(user.getId())) {
            throw new BadRequestException("You cannot start a study session with yourself", "TM_919");
        }
        if (blockUserRepository.existsByUserAndBlocked(user, other)
                || blockUserRepository.existsByUserAndBlocked(other, user)) {
            throw new ForbiddenException("You cannot start a study session with this user", "TM_918");
        }

        // Compute the skill alignment for the card.
        Set<String> myWantLower = new LinkedHashSet<>();
        for (UserSkill s : userSkillRepository.findByUserAndDirection(user, SkillDirection.WANT)) {
            myWantLower.add(s.getName().toLowerCase(Locale.ROOT));
        }
        Map<String, String> myOffersByLower = new LinkedHashMap<>();
        for (UserSkill s : userSkillRepository.findByUserAndDirection(user, SkillDirection.OFFER)) {
            myOffersByLower.putIfAbsent(s.getName().toLowerCase(Locale.ROOT), s.getName());
        }

        List<String> theyCanTeachYou = new ArrayList<>();
        List<String> youCanTeachThem = new ArrayList<>();
        Set<String> seenTeach = new LinkedHashSet<>();
        Set<String> seenLearn = new LinkedHashSet<>();
        for (UserSkill s : userSkillRepository.findByUser(other)) {
            String lower = s.getName().toLowerCase(Locale.ROOT);
            if (s.getDirection() == SkillDirection.OFFER && myWantLower.contains(lower) && seenTeach.add(lower)) {
                theyCanTeachYou.add(s.getName());
            } else if (s.getDirection() == SkillDirection.WANT
                    && myOffersByLower.containsKey(lower) && seenLearn.add(lower)) {
                youCanTeachThem.add(myOffersByLower.get(lower));
            }
        }

        return card(user, other, theyCanTeachYou, youCanTeachThem,
                onlineUsernamesSafe().contains(other.getUsername()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Trims, blank/length-validates, dedupes (case-insensitive, keeping the first occurrence's
     * level) and caps a raw skill list, building the persistable {@link UserSkill} rows for the
     * given direction. Each item's optional {@code level} is carried through unchanged (may be null).
     */
    private List<UserSkill> normalize(User user, List<UpdateSkillsRequest.SkillItem> raw,
                                      SkillDirection direction) {
        List<UserSkill> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (UpdateSkillsRequest.SkillItem item : raw) {
            String name = item != null ? item.getName() : null;
            if (name == null || name.trim().isEmpty()) {
                throw new BadRequestException("Skill name cannot be blank", "TM_916");
            }
            String trimmed = name.trim();
            if (trimmed.length() > MAX_SKILL_NAME_LENGTH) {
                throw new BadRequestException(
                        "Skill name must be at most " + MAX_SKILL_NAME_LENGTH + " characters", "TM_916");
            }
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                SkillLevel level = item.getLevel();
                out.add(UserSkill.builder()
                        .user(user).name(trimmed).direction(direction).level(level).build());
            }
        }
        if (out.size() > MAX_SKILLS_PER_DIRECTION) {
            throw new TooManyRequestsException(
                    "You can list at most " + MAX_SKILLS_PER_DIRECTION + " skills per direction", "TM_915");
        }
        return out;
    }

    /**
     * Resolves a user uuid string to a live {@link User}.
     *
     * @throws BadRequestException if not a valid uuid (TM_914)
     * @throws NotFoundException   if no user matches or the account is deleted (TM_917)
     */
    private User resolveUser(String userUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_914");
        }
        User u = userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", "TM_917"));
        if (u.isDeleted()) {
            throw new NotFoundException("User not found", "TM_917");
        }
        return u;
    }

    private SkillMatchResponse card(User caller, User other, List<String> theyCanTeachYou,
                                    List<String> youCanTeachThem, boolean online) {
        return SkillMatchResponse.builder()
                .userUuid(other.getUuid() != null ? other.getUuid().toString() : null)
                .name(other.getName())
                .username(other.getUsername())
                .avatar(other.getProfileImage())
                .country(other.getCountry())
                .theyCanTeachYou(theyCanTeachYou)
                .youCanTeachThem(youCanTeachThem)
                .reciprocal(!youCanTeachThem.isEmpty())
                .compatibility(safeScore(caller, other))
                .online(online)
                .build();
    }

    private List<SkillProfileResponse.SkillItem> toItems(List<UserSkill> skills) {
        return skills.stream()
                .map(s -> item(s.getName(), s.getLevel() != null ? s.getLevel().name() : null))
                .toList();
    }

    private SkillProfileResponse.SkillItem item(String name, String level) {
        return SkillProfileResponse.SkillItem.builder().name(name).level(level).build();
    }

    /**
     * Compatibility score, or null if scoring throws (never breaks a match listing).
     */
    private CompatibilityScore safeScore(User a, User b) {
        try {
            return compatibilityService.score(a, b);
        } catch (Exception e) {
            log.debug("[SkillExchange] compatibility scoring skipped for {} / {}",
                    a.getUsername(), b.getUsername(), e);
            return null;
        }
    }

    /**
     * Online usernames, failing open to an empty set on any presence error.
     */
    private Set<String> onlineUsernamesSafe() {
        try {
            Set<String> s = presenceService.getOnlineUsernames();
            return s != null ? s : Set.of();
        } catch (Exception e) {
            log.debug("[SkillExchange] presence lookup failed, treating everyone as offline", e);
            return Set.of();
        }
    }

    private static int overallOf(SkillMatchResponse m) {
        return m.getCompatibility() != null ? m.getCompatibility().getOverall() : -1;
    }

    /**
     * Mutable accumulator for a discovered candidate while building the match list.
     */
    private static final class Candidate {
        private final User user;
        private final Set<String> theyCanTeachYou = new LinkedHashSet<>();

        private Candidate(User user) {
            this.user = user;
        }
    }
}
