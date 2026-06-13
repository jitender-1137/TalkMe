package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateChatRequest {
    // Used for 1-to-1 private chat
    private String recipientId; // uuid

    // Used for group chat creation
    private String name;
    private List<String> memberIds; // list of user uuids
}
