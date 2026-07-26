package com.chat.talkMe.dto.request;

import com.chat.talkMe.enums.GameType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for POST /games/start (feature #13). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameStartRequest {

    /** UUID (as String) of the private chat the game runs in. */
    @NotBlank
    private String chatId;

    @NotNull
    private GameType gameType;
}
