package com.neo.chat.security.oauth;

import com.neo.chat.dto.OAuthUserInfo;
import com.neo.chat.dto.response.LoginResponse;
import com.neo.chat.util.ClientRequestInfo;

/**
 * Port owned by the {@code security.oauth} slice for the OAuth login step the success
 * handler needs.
 * <p>
 * It declares exactly the {@code oauthLogin} method {@link OAuth2LoginSuccessHandler}
 * calls so that {@code security} depends on an abstraction it owns rather than importing
 * {@code com.neo.chat.service} directly — which would reintroduce a
 * {@code security → service} package cycle (BootUI ARCH-PKG-001). The concrete
 * {@code AuthServiceImpl} implements this port, so Spring injects the same bean with
 * identical runtime behavior.
 */
public interface OAuthLoginPort {

    /**
     * Creates or links the local account for an OAuth-authenticated user and issues tokens.
     */
    LoginResponse oauthLogin(OAuthUserInfo info, ClientRequestInfo client);
}
