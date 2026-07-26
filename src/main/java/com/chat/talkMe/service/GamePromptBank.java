package com.chat.talkMe.service;

import com.chat.talkMe.enums.GameType;

import java.util.List;
import java.util.Map;

/**
 * Static, in-code prompt bank for the conversation games (feature #13). No DB, no
 * config — deliberately simple and self-contained so the game engine has zero
 * external dependencies. Each {@link GameType} carries ~8 ordered prompts; the
 * engine walks them by round index and ends the session once exhausted.
 */
public final class GamePromptBank {

    private GamePromptBank() {}

    private static final Map<GameType, List<String>> PROMPTS = Map.of(
            GameType.TWO_TRUTHS, List.of(
                    "Share two truths and one lie about your childhood. Can they spot the lie?",
                    "Two truths and a lie about your travel history — go!",
                    "Two truths and a lie about your hidden talents.",
                    "Two truths and a lie about your food preferences.",
                    "Two truths and a lie about your first job.",
                    "Two truths and a lie about your celebrity encounters.",
                    "Two truths and a lie about your school days.",
                    "Two truths and a lie about your weekend habits."
            ),
            GameType.WOULD_YOU_RATHER, List.of(
                    "Would you rather be able to fly or be invisible?",
                    "Would you rather always be 10 minutes late or 20 minutes early?",
                    "Would you rather live without music or without movies?",
                    "Would you rather explore space or the deep ocean?",
                    "Would you rather never use social media again or never watch TV again?",
                    "Would you rather have unlimited money or unlimited time?",
                    "Would you rather read minds or predict the future?",
                    "Would you rather travel to the past or the future?"
            ),
            GameType.THIS_OR_THAT, List.of(
                    "Coffee or tea?",
                    "Beach or mountains?",
                    "Early bird or night owl?",
                    "Sweet or savory?",
                    "Texting or calling?",
                    "Cats or dogs?",
                    "Books or movies?",
                    "Summer or winter?"
            ),
            GameType.NEVER_HAVE_I_EVER, List.of(
                    "Never have I ever pulled an all-nighter.",
                    "Never have I ever gone skydiving.",
                    "Never have I ever sung karaoke in public.",
                    "Never have I ever traveled solo.",
                    "Never have I ever eaten something really weird.",
                    "Never have I ever forgotten someone's name mid-conversation.",
                    "Never have I ever binged an entire series in one day.",
                    "Never have I ever gotten lost in a new city."
            ),
            GameType.RAPID_FIRE, List.of(
                    "Quick — favorite movie of all time?",
                    "First thing you do every morning?",
                    "Dream travel destination?",
                    "Go-to comfort food?",
                    "Last song you had on repeat?",
                    "One word to describe your week?",
                    "Cats, dogs, or something else?",
                    "Best advice you ever got?"
            ),
            GameType.TRUTH, List.of(
                    "What's a small thing that instantly makes your day better?",
                    "What's something you're secretly proud of?",
                    "What's the most spontaneous thing you've ever done?",
                    "What's a fear you've overcome?",
                    "What's your idea of a perfect day off?",
                    "What's something you've always wanted to learn?",
                    "What's a memory that always makes you smile?",
                    "What's the best compliment you've ever received?"
            ),
            GameType.FINISH_THE_SENTENCE, List.of(
                    "The one thing I can't start my day without is...",
                    "If I had a free weekend, I would...",
                    "The song that always lifts my mood is...",
                    "My guilty pleasure is...",
                    "The place I feel most at peace is...",
                    "Something that always makes me laugh is...",
                    "If I could master one skill overnight, it would be...",
                    "The best meal I've ever had was..."
            )
    );

    /** Ordered prompt list for a game type (never null). */
    public static List<String> promptsFor(GameType type) {
        return PROMPTS.getOrDefault(type, List.of());
    }

    public static int size(GameType type) {
        return promptsFor(type).size();
    }

    /** Prompt at the given round index, or null when out of range. */
    public static String promptAt(GameType type, int index) {
        List<String> list = promptsFor(type);
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }
}
