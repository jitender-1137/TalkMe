package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PollRequest {

    @NotBlank(message = "Poll question is required")
    @Size(max = 120, message = "Poll question must be at most 120 characters")
    private String question;

    @Size(min = 2, max = 10, message = "A poll needs between 2 and 10 options")
    private List<@NotBlank @Size(max = 120) String> options;
}
