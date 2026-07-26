package com.chat.talkMe.dto.response;

import com.chat.talkMe.domain.BucketListItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The full shared bucket list for a chat (feature #18). Also the WS broadcast payload. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BucketListResponse {

    /** UUID (as String) of the owning chat. */
    private String chatId;
    private List<BucketItemResponse> items;

    public static BucketListResponse from(String chatId, List<BucketListItem> items) {
        return BucketListResponse.builder()
                .chatId(chatId)
                .items(items.stream().map(BucketItemResponse::from).toList())
                .build();
    }
}
