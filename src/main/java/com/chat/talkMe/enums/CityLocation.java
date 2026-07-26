package com.chat.talkMe.enums;

/**
 * Curated themed districts of the "Virtual Night City" (feature #25). Each maps to a set of
 * seeded ROOM {@link com.chat.talkMe.domain.Chat}s and a live presence set keyed on {@link #slug}
 * (WS topic {@code /topic/city/{slug}}). Purely a discovery/ambience layer over the ROOM model —
 * it grants no capability and gates nothing.
 *
 * <p>{@code slug} is the stable wire identifier (safe in URLs/topics); {@code label}/{@code emoji}/
 * {@code tagline} are display copy for the city map.
 */
public enum CityLocation {
    NEON_DISTRICT("neon-district", "Neon District", "🌆", "Bright lights, fast talk, night energy"),
    MIDNIGHT_CAFE("midnight-cafe", "Midnight Café", "☕", "Slow chats over late coffee"),
    ROOFTOP_LOUNGE("rooftop-lounge", "Rooftop Lounge", "🌃", "Skyline views and easy conversation"),
    MOONLIT_PARK("moonlit-park", "Moonlit Park", "🌙", "Quiet walks and honest talks"),
    JAZZ_BAR("jazz-bar", "Jazz Bar", "🎷", "Smooth tunes and smoother company"),
    ARCADE("arcade", "The Arcade", "👾", "Games, banter, and high scores"),
    OBSERVATORY("observatory", "Observatory", "🔭", "Stargazing and big questions"),
    HARBOR("harbor", "Night Harbor", "⚓", "Calm water, calmer minds"),
    LATE_NIGHT_DINER("late-night-diner", "Late-Night Diner", "🍔", "Comfort food and comfort talk"),
    DREAM_PLAZA("dream-plaza", "Dream Plaza", "✨", "Where the city gathers to unwind");

    private final String slug;
    private final String label;
    private final String emoji;
    private final String tagline;

    CityLocation(String slug, String label, String emoji, String tagline) {
        this.slug = slug;
        this.label = label;
        this.emoji = emoji;
        this.tagline = tagline;
    }

    public String getSlug() {
        return slug;
    }

    public String getLabel() {
        return label;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getTagline() {
        return tagline;
    }

    /** Case-insensitive lookup by slug; null when unknown. */
    public static CityLocation fromSlug(String slug) {
        if (slug == null) return null;
        for (CityLocation loc : values()) {
            if (loc.slug.equalsIgnoreCase(slug.trim())) {
                return loc;
            }
        }
        return null;
    }
}
