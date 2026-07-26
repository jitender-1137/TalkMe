package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for POST /chats/{chatId}/bucket-list/items (feature #18). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BucketItemRequest {

    @NotBlank
    @Size(max = 1000)
    private String text;
}
