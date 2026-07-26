package com.chat.talkMe.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;

/**
 * Jackson 3 tuning for the HTTP request/response mapper.
 *
 * <p>Jackson 3 defaults to REJECTING an absent/null JSON primitive during constructor-based
 * binding, so a text-only {@code SendMessageRequest} (which never sends the optional boolean flags
 * {@code allowDownload}/{@code forwarded}) failed with "Cannot map null into type boolean". This
 * customizer restores the lenient behavior the DTOs expect — an absent/null primitive falls back to
 * its Java default ({@code false}/{@code 0}) — for every request DTO, not just one field.
 *
 * <p>Registered as a {@link JsonMapperBuilderCustomizer} bean so it is applied to the exact
 * auto-configured {@code JsonMapper} used by Spring MVC (a bare {@code spring.jackson.*} property is
 * not reliably applied under every test slice).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer primitiveNullLeniencyCustomizer() {
        return builder -> builder.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
    }
}
