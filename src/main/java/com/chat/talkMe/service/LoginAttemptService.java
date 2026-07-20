package com.chat.talkMe.service;

public interface LoginAttemptService {

    /** Throw TooManyRequestsException if this account or IP is currently locked out. */
    void assertNotBlocked(String username, String ip);

    /** Record a failed login attempt (increments per-username and per-IP counters). */
    void recordFailure(String username, String ip);

    /** Clear counters after a successful login. */
    void recordSuccess(String username, String ip);
}
