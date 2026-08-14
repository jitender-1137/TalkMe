package com.neo.chat.dto.response;

import java.time.Instant;

public class SuccessResponseDto<T> extends ResponseDto<T> {
    public SuccessResponseDto(T data, String message, String messageCode) {
        super(true, message, messageCode, data, null, Instant.now().toString());
    }

    public SuccessResponseDto(T data) {
        this(data, "Success", "TM_000");
    }
}
