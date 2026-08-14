package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One person who joined via the current user's invite link. Public, non-PII fields only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferredUserResponse {
    private String id; // uuid
    private String name;
    private String username;
    private String avatar;
    private String joinedAt; // ISO date they signed up
}
