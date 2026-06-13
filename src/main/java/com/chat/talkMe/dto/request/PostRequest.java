package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRequest {
    private String content;
    private List<PostMediaRequest> media;
}
