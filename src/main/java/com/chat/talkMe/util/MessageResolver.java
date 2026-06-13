package com.chat.talkMe.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Static-accessible message resolver backed by Spring's MessageSource.
 * Provides a centralized way to resolve messages from messages.properties
 * without requiring Spring bean injection at the call site.
 *
 * Usage: MessageResolver.get("TM_210") → "Post created successfully."
 */
@Component
public class MessageResolver {

    private static MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        MessageResolver.messageSource = messageSource;
    }

    /**
     * Resolve message by code. Falls back to the code itself if no entry is found.
     */
    public static String get(String code) {
        if (messageSource == null) {
            return code;
        }
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }

    /**
     * Resolve message by code with argument substitution.
     * e.g. messages.properties: TM_064=User {0} not found.
     *      MessageResolver.get("TM_064", "johndoe") → "User johndoe not found."
     */
    public static String get(String code, Object... args) {
        if (messageSource == null) {
            return code;
        }
        return messageSource.getMessage(code, args, code, LocaleContextHolder.getLocale());
    }
}
