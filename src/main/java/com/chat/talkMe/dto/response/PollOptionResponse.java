package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollOptionResponse {
    private String id; // option uuid
    private String text;
    private long votes;
    private boolean votedByMe;
}
