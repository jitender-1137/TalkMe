package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cursor-paginated page of messages (older history). `nextCursor` is the
 * sequenceNumber of the oldest item returned — pass it back as `cursor` to fetch
 * the next older page. `hasMore` indicates whether older messages remain.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessagePageResponse {
    private List<MessageResponse> items;
    private Long nextCursor;
    private boolean hasMore;
}
