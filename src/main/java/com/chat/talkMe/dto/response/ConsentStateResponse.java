package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsentStateResponse {
    private String chatId;
    private String status;        // NONE | PENDING | GRANTED | DECLINED
    private boolean canRequest;   // can a (re-)request start now? false if PENDING/GRANTED or decline-capped
    private boolean canRevoke;    // true if GRANTED (either party may turn it back off)
    private boolean isRequester;  // current user initiated the pending request
    private boolean awaitingMyAccept; // PENDING and current user is the other party
    private long heldMessageCount;    // how many of the current user's messages are held
    private int declineCount;         // consecutive declines (3 = no more requests, either side)
}
