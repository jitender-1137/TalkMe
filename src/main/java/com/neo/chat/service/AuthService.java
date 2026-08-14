package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.OAuthUserInfo;
import com.neo.chat.dto.request.ChangePasswordRequest;
import com.neo.chat.dto.request.ForgotPasswordRequest;
import com.neo.chat.dto.request.GuestLoginRequest;
import com.neo.chat.dto.request.LoginRequest;
import com.neo.chat.dto.request.ResetPasswordRequest;
import com.neo.chat.dto.request.SignupRequest;
import com.neo.chat.dto.request.UpdateProfileRequest;
import com.neo.chat.dto.response.AuthUserResponse;
import com.neo.chat.dto.response.JwtTokensResponse;
import com.neo.chat.dto.response.LoginResponse;
import com.neo.chat.dto.response.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Authentication and account lifecycle: password/guest/OAuth login, signup, token refresh,
 * session management, email verification, password reset, profile updates and account deletion.
 */
public interface AuthService {
    LoginResponse login(LoginRequest request, String userAgent, String ip, HttpServletRequest httpRequest);

    LoginResponse signup(SignupRequest request, String userAgent, HttpServletRequest httpRequest);

    LoginResponse loginAsGuest(GuestLoginRequest request, String userAgent, HttpServletRequest httpRequest);

    /**
     * Create or link a full account from an external identity provider (Google) and
     * issue a session. Links by provider id first, then by email, otherwise creates a
     * new verified user. Profile image / name / age / gender are backfilled from the
     * provider when the local account is missing them.
     */
    LoginResponse oauthLogin(OAuthUserInfo info, String userAgent,
                             HttpServletRequest httpRequest);

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

    /**
     * Re-send the verification email to the authenticated (still-unverified) user.
     */
    void resendVerificationEmail(User currentUser);

    void changePassword(ChangePasswordRequest request, User currentUser);

    AuthUserResponse getCurrentUser(User currentUser);

    AuthUserResponse updateProfile(UpdateProfileRequest request, User currentUser);

    /**
     * Soft-delete the account: it is hidden/locked immediately but recoverable by
     * simply logging in again within the configured window. All refresh tokens are
     * revoked so other devices are signed out.
     */
    void requestAccountDeletion(User currentUser, String password);

    /**
     * Permanently purge accounts whose deletion window has elapsed (irreversible
     * anonymization + credential destruction). Driven by a scheduled reaper.
     *
     * @return number of accounts purged.
     */
    int purgeExpiredDeletedAccounts();
}
