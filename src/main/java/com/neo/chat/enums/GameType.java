package com.neo.chat.enums;

/**
 * The lightweight conversation games (feature #13) playable inside a private chat.
 * Each type maps to a static prompt bank in {@code GamePromptBank}.
 *
 * <p>Types flagged {@code adult} are the flirty/spicy decks: they are only startable in a 1:1 chat
 * where Flirt Mode is ACTIVE for both participants (mutual consent), which — because Flirt Mode is
 * itself age- and verification-gated — keeps them behind an 18+, both-opted-in boundary. The engine
 * enforces this in {@code GameServiceImpl.start}; the prompts themselves stay suggestive/romantic,
 * never explicit.
 */
public enum GameType {
    TWO_TRUTHS(false),
    WOULD_YOU_RATHER(false),
    THIS_OR_THAT(false),
    NEVER_HAVE_I_EVER(false),
    RAPID_FIRE(false),
    TRUTH(false),
    FINISH_THE_SENTENCE(false),

    // ── Flirty / spicy decks (require Flirt Mode active — 18+, verified, mutual) ──
    FLIRTY_TRUTH_OR_DARE(true),
    SPICY_WOULD_YOU_RATHER(true),
    SPICY_NEVER_HAVE_I_EVER(true),
    TRUTH_ABOUT_US(true);

    private final boolean adult;

    GameType(boolean adult) {
        this.adult = adult;
    }

    /**
     * Whether this is a flirty/spicy deck that may only be started when Flirt Mode is active for
     * both participants of the chat.
     */
    public boolean isAdult() {
        return adult;
    }
}
