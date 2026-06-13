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
    LoginResponse login(LoginRequest request, String userAgent, String ip);
    LoginResponse signup(SignupRequest request, String userAgent, String ip);
    LoginResponse loginAsGuest(GuestLoginRequest request, String userAgent, String ip);
    JwtTokensResponse refresh(String refreshToken, String userAgent, String ip);
    void logout(String refreshToken);
    List<SessionResponse> getSessions(User currentUser);
    void revokeSession(String sessionUuid, User currentUser);
    void revokeAllSessions(User currentUser);
    void forgotPassword(ForgotPasswordRequest request);
    void resetPassword(ResetPasswordRequest request);
    void changePassword(ChangePasswordRequest request, User currentUser);
    com.chat.talkMe.dto.response.AuthUserResponse getCurrentUser(User currentUser);
    com.chat.talkMe.dto.response.AuthUserResponse updateProfile(UpdateProfileRequest request, User currentUser);
}
