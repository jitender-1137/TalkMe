package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostMediaRequest {
    private String mediaUrl;
    private String mediaType; // IMAGE, VIDEO
}
