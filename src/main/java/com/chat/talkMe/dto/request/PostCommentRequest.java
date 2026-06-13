package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostCommentRequest {

    @NotBlank(message = "Comment content cannot be blank")
    private String content;

    private String parentId; // optional parent comment uuid
}
