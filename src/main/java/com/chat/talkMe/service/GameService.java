package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.GameSessionResponse;
import com.chat.talkMe.enums.GameType;

/**
 * Conversation Games engine (feature #13). Server holds a lightweight session and
 * serves prompts from the static {@code GamePromptBank}; the client drives it over
 * REST (start / next / end / read active). Deliberately decoupled from chat WS.
 */
public interface GameService {

    /** Start a new game in a chat → IN_PROGRESS with the first prompt. */
    GameSessionResponse start(User user, String chatId, GameType gameType);

    /** Advance to the next round/prompt; ENDs the session once the bank is exhausted. */
    GameSessionResponse next(User user, String gameSessionUuid);

    /** End the session (ENDED). */
    GameSessionResponse end(User user, String gameSessionUuid);

    /** The current non-ended session for a chat, or null when none is active. */
    GameSessionResponse active(User user, String chatId);
}
