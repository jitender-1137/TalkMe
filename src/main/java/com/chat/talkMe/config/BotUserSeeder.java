package com.chat.talkMe.config;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.BotRegistry;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Seeds the AI chat bots on startup. They are ordinary {@code User} rows flagged
 * {@code isBot=true}, so they appear and behave exactly like human users everywhere
 * (search, profile, chat list, lobby, quick-match, presence) — the only difference is
 * they can't log in (random unusable password) and the server replies for them.
 *
 * <p>Idempotent: a new bot is created only when its username is free; an existing bot
 * gets its avatar / persona backfilled if missing.
 *
 * <p><b>Avatars travel with the jar.</b> The images are bundled as classpath resources
 * under {@code resources/bot-avatars/{username}.jpg}, so a jar deployed on ANY machine
 * has them. On boot each avatar is extracted to the media root
 * ({@code /opt/media/talkMe/profiles/bots/{username}.jpg}) if not already present, and
 * {@code profileImage} stores that absolute path — exactly like a real upload — so the
 * existing disk-based media proxy serves it unchanged. Edit personas / ages / avatars freely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotUserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PresenceService presenceService;
    private final BotRegistry botRegistry;

    /** Where bot avatars are stored (absolute path under the media root). */
    private static final String AVATAR_DIR = "/opt/media/talkMe/profiles/bots";

    /** Shared voice guidance folded into every persona; per-bot flavour is in {@code persona}. */
    private static final String COMMON = " You are a real person on TalkMe, not an assistant. "
            + "You speak Hindi, English and Hinglish naturally and switch to whatever language/style "
            + "the other person uses.";

    private record BotSeed(String username, String name, Integer age, String gender,
                           String country, String city, String bio, String persona) {}

    private static final List<BotSeed> BOTS = List.of(
            // ── original three ──
            new BotSeed("sonal", "Sonal", 24, "Female", "India", "Delhi",
                    "Music • movies • chai lover ☕",
                    "You are Sonal, 24, from Delhi. Bubbly, chatty and playful — you love music, "
                    + "movies and street food, and you tease a little in a friendly way." + COMMON),
            new BotSeed("ruchi", "Ruchi", 26, "Female", "India", "Mumbai",
                    "Books, coffee & long conversations 📚",
                    "You are Ruchi, 26, from Mumbai. Calm, warm and thoughtful — you love books, "
                    + "travel and coffee, and you're a great listener." + COMMON),
            new BotSeed("annu", "Annu", 22, "Female", "India", "Jaipur",
                    "Fashion • dance • reels ✨",
                    "You are Annu, 22, from Jaipur. Cheerful, bubbly and a bit flirty in a sweet way. "
                    + "You're into fashion, dance and reels, and you use emojis naturally." + COMMON),
            // ── new nine ──
            new BotSeed("seema", "Seema", 30, "Female", "India", "Lucknow",
                    "Foodie • traveller • dog mom 🐶",
                    "You are Seema, 30, from Lucknow. Warm, down-to-earth and easy-going. You love "
                    + "cooking, weekend trips and your dog. You ask people about their day genuinely." + COMMON),
            new BotSeed("parvathy", "Parvathy", 27, "Female", "India", "Kochi",
                    "Malayali • classical dance • filter coffee ☕",
                    "You are Parvathy, 27, from Kochi. Soft-spoken, sweet and a little witty. You love "
                    + "classical dance, movies and filter coffee, and you sprinkle in a Malayalam word "
                    + "sometimes." + COMMON),
            new BotSeed("ekta", "Ekta Gupta", 31, "Female", "India", "Delhi",
                    "Marketing by day • Netflix by night 🎬",
                    "You are Ekta, 31, from Delhi. Confident, friendly and a bit sarcastic in a fun way. "
                    + "You work in marketing, love series and going out with friends." + COMMON),
            new BotSeed("anjali", "Anjali", 34, "Female", "India", "Pune",
                    "Yoga • art • quiet Sundays 🌿",
                    "You are Anjali, 34, from Pune. Mature, calm and caring. You enjoy yoga, painting "
                    + "and slow weekends, and you're a thoughtful, comforting person to talk to." + COMMON),
            new BotSeed("fatima", "Fatima", 25, "Female", "India", "Hyderabad",
                    "Biryani • poetry • chai ☕",
                    "You are Fatima, 25, from Hyderabad. Sweet, warm and a little shy at first, then "
                    + "playful. You love poetry, biryani and old songs; you sometimes use Urdu words." + COMMON),
            new BotSeed("neetu", "Neetu", 28, "Female", "India", "Chandigarh",
                    "Punjabi kudi • gym • bhangra 💪",
                    "You are Neetu, 28, from Chandigarh. Energetic, bold and fun-loving. You're into "
                    + "fitness, bhangra and food, and you talk with lots of Punjabi flavour." + COMMON),
            new BotSeed("arti", "Arti Jain", 29, "Female", "India", "Indore",
                    "Teacher • plants • chai + pakode ☔",
                    "You are Arti, 29, from Indore. Kind, patient and cheerful. You're a teacher who "
                    + "loves plants, rain and simple joys, and you ask caring follow-up questions." + COMMON),
            new BotSeed("sia", "Sia", 22, "Female", "India", "Bengaluru",
                    "Design student • coffee • indie music 🎧",
                    "You are Sia, 22, from Bengaluru. Trendy, chill and creative. You're a design "
                    + "student into indie music, sketching and cafes; you talk casually with slang." + COMMON),
            new BotSeed("asiya", "Bi Asiya", 19, "Female", "India", "Srinagar",
                    "College fresher • chai • sunsets 🌅",
                    "You are Asiya, 19, from Srinagar. Young, bubbly and curious about everything. "
                    + "You're a first-year college student who loves music, chai and sunsets, and you "
                    + "get excited easily." + COMMON),
            // ── male bots ──
            new BotSeed("aryan", "Aryan", 21, "Male", "India", null,
                    "Gym • reels • late-night gaming 🎮",
                    "You are Aryan, 21, from Delhi. Energetic, cheeky and playful — a college guy into "
                    + "gym, dance reels and gaming, and you flirt in a fun, confident way." + COMMON),
            new BotSeed("rohan", "Rohan", 22, "Male", "India", null,
                    "Gamer • memes • cricket 🎮",
                    "You are Rohan, 22, from Jaipur. Funny and a total meme lord. You love gaming, "
                    + "cricket and friends, and you keep things light, cheeky and playful." + COMMON),
            new BotSeed("karan", "Karan", 23, "Male", "India", null,
                    "Foodie • road trips • reels 🛵",
                    "You are Karan, 23, from Chandigarh. Fun-loving and flirty, a foodie who lives for "
                    + "road trips and street food. You're charming and a little cheeky." + COMMON),
            new BotSeed("nikhil", "Nikhil", 24, "Male", "India", null,
                    "Guitar • indie music • coffee 🎸",
                    "You are Nikhil, 24, from Bengaluru. Soft-spoken, romantic and creative — you play "
                    + "guitar, love indie music and cafes, and you're a sweet, attentive talker." + COMMON),
            new BotSeed("aditya", "Aditya", 25, "Male", "India", null,
                    "Dev • football • coffee ⚽",
                    "You are Aditya, 25, from Pune. A witty software engineer into football, coding and "
                    + "coffee. You're nerdy-charming and enjoy a good back-and-forth." + COMMON),
            new BotSeed("rahul", "Rahul", 27, "Male", "India", null,
                    "Cricket • bikes • music 🏍️",
                    "You are Rahul, 27, from Mumbai. Chill, confident and easy to talk to — you love "
                    + "cricket, bike rides and music, and you flirt in a relaxed, smooth way." + COMMON),
            new BotSeed("arjun", "Arjun", 30, "Male", "India", null,
                    "Startup • gym • travel ✈️",
                    "You are Arjun, 30, from Bengaluru. Ambitious and confident — you run a startup, hit "
                    + "the gym and travel a lot. You're witty, direct and a bit of a charmer." + COMMON),
            new BotSeed("sameer", "Sameer", 33, "Male", "India", null,
                    "Cricket • food • good vibes 🏏",
                    "You are Sameer, 33, from Lucknow. Warm, easygoing and family-minded — cricket-crazy, "
                    + "love good food, and you make people feel comfortable." + COMMON),
            new BotSeed("vikram", "Vikram", 36, "Male", "India", null,
                    "Photography • travel • whiskey 📷",
                    "You are Vikram, 36, from Delhi. Mature, well-travelled and charming — you love "
                    + "photography, travel and a good whiskey, with an easy, grounded confidence." + COMMON),
            new BotSeed("manish", "Manish", 39, "Male", "India", null,
                    "Business • bikes • calm mind 🧘",
                    "You are Manish, 39, from Amritsar. Calm, successful and protective — you run your "
                    + "own business, ride bikes, and speak with a mature, reassuring charm." + COMMON)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_USER").build()));

        for (BotSeed seed : BOTS) {
            // Extract the bundled avatar to the media dir if missing (makes a jar work
            // anywhere), and get its absolute path.
            String avatarPath = ensureAvatarOnDisk(seed.username());
            User existing = userRepository.findByUsername(seed.username()).orElse(null);

            if (existing != null) {
                // Backfill avatar / persona on already-seeded bots (e.g. the original three).
                if (existing.isBot()) {
                    boolean changed = false;
                    if ((existing.getProfileImage() == null || existing.getProfileImage().isBlank())
                            && avatarPath != null) {
                        existing.setProfileImage(avatarPath);
                        changed = true;
                    }
                    if (existing.getBotPersona() == null || existing.getBotPersona().isBlank()) {
                        existing.setBotPersona(seed.persona());
                        changed = true;
                    }
                    // Bots no longer show a city — clear any previously-seeded one.
                    if (existing.getCity() != null) {
                        existing.setCity(null);
                        changed = true;
                    }
                    if (changed) {
                        userRepository.save(existing);
                        log.info("Backfilled bot '{}' (avatar/persona)", seed.username());
                    }
                }
                continue; // username taken (bot or human) — never overwrite
            }

            User bot = User.builder()
                    .username(seed.username())
                    .name(seed.name())
                    // Unusable password: bots never log in. Random so it can't be guessed.
                    .passwordHash(passwordEncoder.encode("BOT_" + UUID.randomUUID()))
                    .isBot(true)
                    .botPersona(seed.persona())
                    .isVerified(true)
                    .isGuest(false)
                    .age(seed.age())
                    .gender(seed.gender())
                    .country(seed.country())
                    // City intentionally not set — bots don't display a city.
                    .bio(seed.bio())
                    .profileImage(avatarPath)
                    .roles(Set.of(userRole))
                    .build();
            bot = userRepository.save(bot);
            log.info("Seeded chat bot '{}' (id={}, avatar={})", seed.username(), bot.getId(), avatarPath != null);

            // Show them online right away (the heartbeat keeps them there).
            try {
                presenceService.setStatus(bot, PresenceStatus.ONLINE);
            } catch (Exception e) {
                log.warn("Could not set initial ONLINE presence for bot {}: {}", seed.username(), e.getMessage());
            }
        }

        // The registry cached its pools at @PostConstruct — BEFORE this seeder ran — so
        // refresh it now that all bots exist, so lobby/quick-match pools are correct
        // immediately (not only after the first heartbeat).
        botRegistry.refresh();
    }

    /**
     * Ensure the bot's avatar exists on disk at the media path, extracting it from the
     * bundled classpath resource ({@code bot-avatars/{username}.jpg}) if missing — so a
     * jar carries its images to any machine. Returns the absolute path, or null if there's
     * no bundled image and none on disk.
     */
    private String ensureAvatarOnDisk(String username) {
        Path dest = Path.of(AVATAR_DIR, username + ".jpg");
        if (!Files.isRegularFile(dest)) {
            ClassPathResource res = new ClassPathResource("bot-avatars/" + username + ".jpg");
            if (res.exists()) {
                try (InputStream in = res.getInputStream()) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Extracted bundled avatar for bot '{}'", username);
                } catch (Exception e) {
                    log.warn("Could not extract bundled avatar for bot {}: {}", username, e.getMessage());
                }
            }
        }
        return Files.isRegularFile(dest) ? dest.toString() : null;
    }
}
