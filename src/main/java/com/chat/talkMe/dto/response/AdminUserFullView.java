package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * The COMPLETE persisted record for a user — every stored column plus their
 * settings and presence rows — as ordered key/value groups. For the SuperAdmin
 * "everything about this user" view. The password hash is never exposed (only
 * whether one is set).
 */
@Data
@Builder
public class AdminUserFullView {
    private Map<String, Object> account;   // User table columns
    private Map<String, Object> settings;  // UserSetting row (or null note)
    private Map<String, Object> presence;  // UserPresence row (or null note)
}
