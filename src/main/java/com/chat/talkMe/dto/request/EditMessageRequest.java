package com.chat.talkMe.dto.request;

import lombok.Data;

/** Body for editing a message's text. {@code content} is client-encrypted for encrypted chats. */
@Data
public class EditMessageRequest {
    private String content;
}
