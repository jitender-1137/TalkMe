package com.chat.talkMe.dto.response;

import com.chat.talkMe.util.MessageResolver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseDto<T> {
    private boolean success;
    private String message;
    private String messageCode;
    private T data;
    private Object errors;
    private String timestamp;

    /**
     * Build a success response with an explicit message string and code.
     * Use this only when the message cannot be resolved from messages.properties
     * (e.g. dynamic/conditional messages).
     */
    public static <T> ResponseDto<T> success(T data, String message, String messageCode) {
        return ResponseDto.<T>builder()
                .success(true)
                .message(message)
                .messageCode(messageCode)
                .data(data)
                .timestamp(Instant.now().toString())
                .build();
    }

    /**
     * Build a success response by resolving the message from messages.properties
     * using the given messageCode. This is the preferred factory method.
     */
    public static <T> ResponseDto<T> success(T data, String messageCode) {
        return ResponseDto.<T>builder()
                .success(true)
                .message(MessageResolver.get(messageCode))
                .messageCode(messageCode)
                .data(data)
                .timestamp(Instant.now().toString())
                .build();
    }

    public static <T> ResponseDto<T> success(T data) {
        return success(data, "Success", "TM_000");
    }

    public static <T> ResponseDto<T> error(String message, String messageCode, Object errors) {
        return ResponseDto.<T>builder()
                .success(false)
                .message(message)
                .messageCode(messageCode)
                .errors(errors)
                .timestamp(Instant.now().toString())
                .build();
    }

    public static <T> ResponseDto<T> error(String message, String messageCode) {
        return error(message, messageCode, null);
    }
}

