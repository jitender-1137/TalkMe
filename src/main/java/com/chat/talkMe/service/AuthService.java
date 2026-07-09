package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.LoginRequest;
import com.chat.talkMe.dto.request.SignupRequest;
import com.chat.talkMe.dto.request.GuestLoginRequest;
import com.chat.talkMe.dto.request.ForgotPasswordRequest;
import com.chat.talkMe.dto.request.ResetPasswordRequest;
import com.chat.talkMe.dto.request.ChangePasswordRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.JwtTokensResponse;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.dto.response.SessionResponse;

import java.util.List;

public interface AuthService {
    LoginResponse login(LoginRequest request, String userAgent, String ip, jakarta.servlet.http.HttpServletRequest httpRequest);
    LoginResponse signup(SignupRequest request, String userAgent, jakarta.servlet.http.HttpServletRequest httpRequest);
    LoginResponse loginAsGuest(GuestLoginRequest request, String userAgent, jakarta.servlet.http.HttpServletRequest httpRequest);

    /**
     * Create or link a full account from an external identity provider (Google) and
     * issue a session. Links by provider id first, then by email, otherwise creates a
     * new verified user. Profile image / name / age / gender are backfilled from the
     * provider when the local account is missing them.
     */
    LoginResponse oauthLogin(com.chat.talkMe.dto.OAuthUserInfo info, String userAgent,
                             jakarta.servlet.http.HttpServletRequest httpRequest);
    JwtTokensResponse refresh(String refreshToken, String userAgent, String ip);
    void logout(String refreshToken);
    List<SessionResponse> getSessions(User currentUser);
    void revokeSession(String sessionUuid, User currentUser);
    void revokeAllSessions(User currentUser);
    void forgotPassword(ForgotPasswordRequest request);
    void resetPassword(ResetPasswordRequest request);

    /**
     * Confirm an email-verification token: marks the account verified and sends the
     * welcome email. One-time use; invalid/expired tokens are rejected.
     */
    void verifyEmail(String token);

    /** Re-send the verification email to the authenticated (still-unverified) user. */
    void resendVerificationEmail(User currentUser);

    void changePassword(ChangePasswordRequest request, User currentUser);
    com.chat.talkMe.dto.response.AuthUserResponse getCurrentUser(User currentUser);
    com.chat.talkMe.dto.response.AuthUserResponse updateProfile(UpdateProfileRequest request, User currentUser);

    /**
     * Soft-delete the account: it is hidden/locked immediately but recoverable by
     * simply logging in again within the configured window. All refresh tokens are
     * revoked so other devices are signed out.
     */
    void requestAccountDeletion(User currentUser);

    /**
     * Permanently purge accounts whose deletion window has elapsed (irreversible
     * anonymization + credential destruction). Driven by a scheduled reaper.
     * @return number of accounts purged.
     */
    int purgeExpiredDeletedAccounts();
}
