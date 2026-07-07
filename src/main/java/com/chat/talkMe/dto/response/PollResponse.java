package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollResponse {
    private String id; // poll uuid
    private String question;
    private long totalVotes;
    private String myVoteOptionId; // uuid of the option the current user voted for, or null
    private List<PollOptionResponse> options;
}
