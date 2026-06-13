package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchSessionResponse {
    private String id; // session.uuid
    private AuthUserResponse partner;
    private String chatId; // stranger chat room uuid
    private boolean isActive;
}
