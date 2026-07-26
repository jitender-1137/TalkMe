package com.chat.talkMe.domain;

import com.chat.talkMe.enums.GameState;
import com.chat.talkMe.enums.GameType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

/**
 * A single conversation-game session (feature #13) bound to one private chat.
 * The engine is server-authoritative but client-driven over REST: the client
 * calls /start, /{uuid}/next and /{uuid}/end and re-reads state via /active.
 *
 * Prompts themselves are not persisted — they come from the static in-code
 * {@code GamePromptBank}. Only the cursor ({@link #currentRound}) and the
 * resolved prompt id ({@link #currentPromptId}) are stored.
 */
@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameSession extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false, length = 30)
    private GameType gameType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    @ColumnDefault("'LOBBY'")
    @Builder.Default
    private GameState state = GameState.LOBBY;

    /** UUID (as String) of the chat this game runs in. */
    @Column(name = "chat_id", nullable = false, length = 64)
    private String chatId;

    @Column(name = "current_round", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int currentRound = 0;

    /** Identifier of the currently-served prompt (game type + index into the bank). */
    @Column(name = "current_prompt_id", length = 64)
    private String currentPromptId;
}
