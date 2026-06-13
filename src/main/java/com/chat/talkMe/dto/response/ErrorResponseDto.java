package com.chat.talkMe.dto.response;

import java.util.List;

public class ErrorResponseDto extends ResponseDto<Void> {
    public ErrorResponseDto(String message, String messageCode, Object errors) {
        super(false, message, messageCode, null, errors, java.time.Instant.now().toString());
    }

    public ErrorResponseDto(String message, String messageCode) {
        this(message, messageCode, null);
    }
}
