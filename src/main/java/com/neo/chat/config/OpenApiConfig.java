package com.neo.chat.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc/OpenAPI (Swagger) definition for the backend API. Sets the API title,
 * version and description, and declares a global {@code bearerAuth} HTTP-bearer
 * security scheme (JWT) so the generated docs and Swagger UI can carry a token.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Builds the {@link OpenAPI} document: title "NeoChatHub Social Messaging API",
     * version {@code 1.0.0}, and a global requirement plus component definition for the
     * {@code bearerAuth} scheme (HTTP {@code bearer}, format {@code JWT}).
     *
     * @return the fully-populated {@link OpenAPI} model.
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NeoChatHub Social Messaging API")
                        .version("1.0.0")
                        .description("Complete API specifications for the NeoChatHub social messaging application backend."))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .name("bearerAuth")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
