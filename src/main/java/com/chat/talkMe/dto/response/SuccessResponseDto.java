package com.chat.talkMe.dto.response;

public class SuccessResponseDto<T> extends ResponseDto<T> {
    public SuccessResponseDto(T data, String message, String messageCode) {
        super(true, message, messageCode, data, null, java.time.Instant.now().toString());
    }

    public SuccessResponseDto(T data) {
        this(data, "Success", "TM_000");
    }
}
