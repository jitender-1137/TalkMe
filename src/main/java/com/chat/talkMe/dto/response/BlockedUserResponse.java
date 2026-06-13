package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockedUserResponse {
    private String id; // blocked user uuid
    private String name;
    private String avatar; // blocked user profile image
    private String blockedAt; // when the block record was created
}
