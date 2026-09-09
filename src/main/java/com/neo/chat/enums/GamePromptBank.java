package com.neo.chat.enums;


import java.util.List;
import java.util.Map;

/**
 * Static, in-code prompt bank for the conversation games (feature #13). No DB, no
 * config — deliberately simple and self-contained so the game engine has zero
 * external dependencies. Each {@link GameType} carries ~8 ordered prompts; the
 * engine walks them by round index and ends the session once exhausted.
 *
 * <p>The {@code adult} decks (flirty/spicy) are only reachable when Flirt Mode is active for both
 * participants (enforced in {@code GameServiceImpl.start}). Their prompts are deliberately
 * suggestive/romantic rather than explicit.
 *
 * <p>Lives in the {@code enums} slice (beside {@link GameType}, which keys it) rather than
 * {@code service}: a dependency-free constant bank kept low avoids a {@code dto -> service}
 * cycle via {@code GameSessionResponse} (BootUI ARCH-PKG-001).
 */
public final class GamePromptBank {

    private GamePromptBank() {
    }

    private static final Map<GameType, List<String>> PROMPTS = Map.ofEntries(
            Map.entry(GameType.TWO_TRUTHS, List.of(
                    "Share two truths and one lie about your childhood. Can they spot the lie?",
                    "Two truths and a lie about your travel history — go!",
                    "Two truths and a lie about your hidden talents.",
                    "Two truths and a lie about your food preferences.",
                    "Two truths and a lie about your first job.",
                    "Two truths and a lie about your celebrity encounters.",
                    "Two truths and a lie about your school days.",
                    "Two truths and a lie about your weekend habits."
            )),
            Map.entry(GameType.WOULD_YOU_RATHER, List.of(
                    "Would you rather be able to fly or be invisible?",
                    "Would you rather always be 10 minutes late or 20 minutes early?",
                    "Would you rather live without music or without movies?",
                    "Would you rather explore space or the deep ocean?",
                    "Would you rather never use social media again or never watch TV again?",
                    "Would you rather have unlimited money or unlimited time?",
                    "Would you rather read minds or predict the future?",
                    "Would you rather travel to the past or the future?"
            )),
            Map.entry(GameType.THIS_OR_THAT, List.of(
                    "Coffee or tea?",
                    "Beach or mountains?",
                    "Early bird or night owl?",
                    "Sweet or savory?",
                    "Texting or calling?",
                    "Cats or dogs?",
                    "Books or movies?",
                    "Summer or winter?"
            )),
            Map.entry(GameType.NEVER_HAVE_I_EVER, List.of(
                    "Never have I ever pulled an all-nighter.",
                    "Never have I ever gone skydiving.",
                    "Never have I ever sung karaoke in public.",
                    "Never have I ever traveled solo.",
                    "Never have I ever eaten something really weird.",
                    "Never have I ever forgotten someone's name mid-conversation.",
                    "Never have I ever binged an entire series in one day.",
                    "Never have I ever gotten lost in a new city."
            )),
            Map.entry(GameType.RAPID_FIRE, List.of(
                    "Quick — favorite movie of all time?",
                    "First thing you do every morning?",
                    "Dream travel destination?",
                    "Go-to comfort food?",
                    "Last song you had on repeat?",
                    "One word to describe your week?",
                    "Cats, dogs, or something else?",
                    "Best advice you ever got?"
            )),
            Map.entry(GameType.TRUTH, List.of(
                    "What's a small thing that instantly makes your day better?",
                    "What's something you're secretly proud of?",
                    "What's the most spontaneous thing you've ever done?",
                    "What's a fear you've overcome?",
                    "What's your idea of a perfect day off?",
                    "What's something you've always wanted to learn?",
                    "What's a memory that always makes you smile?",
                    "What's the best compliment you've ever received?"
            )),
            Map.entry(GameType.FINISH_THE_SENTENCE, List.of(
                    "The one thing I can't start my day without is...",
                    "If I had a free weekend, I would...",
                    "The song that always lifts my mood is...",
                    "My guilty pleasure is...",
                    "The place I feel most at peace is...",
                    "Something that always makes me laugh is...",
                    "If I could master one skill overnight, it would be...",
                    "The best meal I've ever had was..."
            )),

            // ── Flirty / spicy decks (Flirt Mode active only — suggestive, never explicit) ──
            Map.entry(GameType.FLIRTY_TRUTH_OR_DARE, List.of(
                    "Truth: what was your very first impression of me?",
                    "Dare: send a voice note saying goodnight in your most flirty voice.",
                    "Truth: what's the most attractive quality someone can have?",
                    "Dare: send a selfie with your best smile right now.",
                    "Truth: describe your idea of a perfect date with me.",
                    "Dare: pay me a compliment that would make me blush.",
                    "Truth: what's your biggest turn-on in someone's personality?",
                    "Dare: describe what you find attractive about me using only emojis."
            )),
            Map.entry(GameType.SPICY_WOULD_YOU_RATHER, List.of(
                    "Would you rather a slow dance or a long cuddle?",
                    "Would you rather a surprise kiss or a handwritten love note?",
                    "Would you rather a candlelit dinner or a midnight walk holding hands?",
                    "Would you rather be the little spoon or the big spoon?",
                    "Would you rather forehead kisses or a hand on the small of your back?",
                    "Would you rather a weekend getaway together or a cozy night in?",
                    "Would you rather flirt over text or whisper it in person?",
                    "Would you rather be teased a little or spoiled completely?"
            )),
            Map.entry(GameType.SPICY_NEVER_HAVE_I_EVER, List.of(
                    "Never have I ever had a crush on someone I was only texting.",
                    "Never have I ever sent a flirty text and immediately regretted it.",
                    "Never have I ever kissed someone on a first date.",
                    "Never have I ever fallen for someone way too fast.",
                    "Never have I ever re-read someone's messages just to smile.",
                    "Never have I ever caught feelings for a friend.",
                    "Never have I ever stayed up all night talking to someone I liked.",
                    "Never have I ever daydreamed about someone in the middle of the day."
            )),
            Map.entry(GameType.TRUTH_ABOUT_US, List.of(
                    "What first made you want to flirt with me?",
                    "What's your love language — words, touch, time, gifts, or acts?",
                    "What's the most romantic thing anyone has ever done for you?",
                    "What does a perfect goodnight look like to you?",
                    "What's something about me you find hard to resist?",
                    "Describe your dream first kiss.",
                    "What little thing instantly makes you fall for someone?",
                    "What would our perfect weekend away look like?"
            ))
    );

    /**
     * Ordered prompt list for a game type (never null).
     */
    public static List<String> promptsFor(GameType type) {
        return PROMPTS.getOrDefault(type, List.of());
    }

    public static int size(GameType type) {
        return promptsFor(type).size();
    }

    /**
     * Prompt at the given round index, or null when out of range.
     */
    public static String promptAt(GameType type, int index) {
        List<String> list = promptsFor(type);
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }
}
