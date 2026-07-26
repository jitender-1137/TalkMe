package com.chat.talkMe.dto.response;

import com.chat.talkMe.domain.GameSession;
import com.chat.talkMe.enums.GameState;
import com.chat.talkMe.service.GamePromptBank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Client-facing view of a conversation-game session (feature #13). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameSessionResponse {

    private String uuid;
    private String gameType;
    private String state;
    private int round;
    /** The prompt for the current round, or null once the session has ENDED. */
    private String prompt;

    /** Map an entity to the response, resolving the current prompt from the bank. */
    public static GameSessionResponse from(GameSession session) {
        // No prompt once the game has ended (contract: prompt is null when ENDED).
        String prompt = session.getState() == GameState.ENDED
                ? null
                : GamePromptBank.promptAt(session.getGameType(), session.getCurrentRound());
        return GameSessionResponse.builder()
                .uuid(session.getUuid().toString())
                .gameType(session.getGameType().name())
                .state(session.getState().name())
                .round(session.getCurrentRound())
                .prompt(prompt)
                .build();
    }
}
