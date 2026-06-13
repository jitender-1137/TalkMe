package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {
    private String id; // maps session.uuid
    private String userAgent;
    private String ipAddress;
    private String location;
    private String lastActiveAt;
    private boolean isCurrent;
}
