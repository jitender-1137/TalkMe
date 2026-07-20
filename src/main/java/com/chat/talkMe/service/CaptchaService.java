package com.chat.talkMe.service;

public interface CaptchaService {
    /**
     * Verify a Cloudflare Turnstile token. Returns true if the request is a
     * verified human (or if CAPTCHA is disabled). Fails closed on errors.
     */
    boolean verify(String token, String remoteIp);
}
