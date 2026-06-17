package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.RefreshToken;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.Session;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.ChangePasswordRequest;
import com.chat.talkMe.dto.request.ForgotPasswordRequest;
import com.chat.talkMe.dto.request.GuestLoginRequest;
import com.chat.talkMe.dto.request.LoginRequest;
import com.chat.talkMe.dto.request.ResetPasswordRequest;
import com.chat.talkMe.dto.request.SignupRequest;
import com.chat.talkMe.dto.request.UpdateProfileRequest;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.JwtTokensResponse;
import com.chat.talkMe.dto.response.LoginResponse;
import com.chat.talkMe.dto.response.SessionResponse;
import com.chat.talkMe.exception.ConflictException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.exception.UnauthorizedException;
import com.chat.talkMe.mapper.SessionMapper;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.PermissionRepository;
import com.chat.talkMe.repository.RefreshTokenRepository;
import com.chat.talkMe.repository.RoleRepository;
import com.chat.talkMe.repository.SessionRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.service.AuthService;
import com.chat.talkMe.dto.response.CountryDetectionResult;
import com.chat.talkMe.service.CountryDetectionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final UserMapper userMapper;
    private final SessionMapper sessionMapper;
    private final CountryDetectionService countryDetectionService;

    @Value("${security.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${security.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @Value("${security.jwt.guest-refresh-token-expiration-ms}")
    private long guestRefreshTokenExpirationMs;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String userAgent, String ip) {
        User user = userRepository.findByUsername(request.getEmail())
                .or(() -> userRepository.findByEmail(request.getEmail()))
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password", "TM_024"));

        if (user.isGuest()) {
            throw new ForbiddenException("Guest accounts must use Guest Login flow", "TM_029");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid username or password", "TM_024");
        }

        return generateLoginResponse(user, userAgent, ip);
    }

    @Override
    @Transactional
    public LoginResponse signup(SignupRequest request, String userAgent, HttpServletRequest httpRequest) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("TM_047");
        }

        // Generate username from email prefix
        String username = request.getUsername();

        Role userRole = getOrCreateRole("ROLE_USER");

        CountryDetectionResult detectionResult = countryDetectionService.detectCountry(httpRequest);

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isGuest(false)
                .isVerified(false)
                .roles(Set.of(userRole))
                .age(request.getAge())
                .gender(request.getGender())
                .country(detectionResult.getCountry())
                .build();

        user = userRepository.save(user);
        log.info("User registered successfully: {}. Country detected: {} (Source: {}, IP: {})", 
                username, detectionResult.getCountry(), detectionResult.getSource(), detectionResult.getClientIp());

        return generateLoginResponse(user, userAgent, detectionResult.getClientIp());
    }

    @Override
    @Transactional
    public LoginResponse loginAsGuest(GuestLoginRequest request, String userAgent, HttpServletRequest httpRequest) {
        String username = "guest_" + UUID.randomUUID().toString().substring(0, 8);
        Role guestRole = getOrCreateRole("ROLE_GUEST");

        CountryDetectionResult detectionResult = countryDetectionService.detectCountry(httpRequest);

        User guest = User.builder()
                .name(request.getName())
                .username(username)
                .age(request.getAge())
                .gender(request.getGender())
                .isGuest(true)
                .isVerified(true)
                .roles(Set.of(guestRole))
                .country(detectionResult.getCountry())
                .build();

        guest = userRepository.save(guest);
        log.info("Guest user logged in: {}. Country detected: {} (Source: {}, IP: {})", 
                username, detectionResult.getCountry(), detectionResult.getSource(), detectionResult.getClientIp());

        return generateLoginResponse(guest, userAgent, detectionResult.getClientIp());
    }

    @Override
    @Transactional
    public JwtTokensResponse refresh(String tokenStr, String userAgent, String ip) {
        RefreshToken token = refreshTokenRepository.findByToken(tokenStr)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token", "TM_026"));

        User user = token.getUser();

        // REUSE DETECTION LOGIC
        if (token.isRevoked()) {
            log.warn("Token reuse detected! Token was already revoked. Revoking all active tokens for user: {}", user.getUsername());
            refreshTokenRepository.revokeAllUserTokens(user);
            throw new ForbiddenException("Token reuse detected. All sessions revoked.", "TM_058");
        }

        if (token.isExpired()) {
            throw new UnauthorizedException("Refresh token has expired", "TM_026");
        }

        // Invalidate old token and replace
        token.setRevoked(true);
        
        // Generate new access token
        String newAccessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest());
        
        // Generate rotated refresh token
        long expiryMs = user.isGuest() ? guestRefreshTokenExpirationMs : refreshTokenExpirationMs;
        String newRefreshTokenStr = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .token(newRefreshTokenStr)
                .expiresAt(Instant.now().plusMillis(expiryMs))
                .build();

        token.setReplacedByToken(newRefreshTokenStr);
        refreshTokenRepository.save(token);
        refreshTokenRepository.save(newRefreshToken);

        // Update session active timestamp
        List<Session> sessions = sessionRepository.findByUserAndIsDeletedFalse(user);
        for (Session session : sessions) {
            if (ip.equals(session.getIpAddress()) && userAgent.equals(session.getUserAgent())) {
                session.setLastActiveAt(Instant.now());
                sessionRepository.save(session);
            }
        }

        return JwtTokensResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshTokenStr)
                .expiresIn(accessTokenExpirationMs / 1000)
                .build();
    }

    @Override
    @Transactional
    public void logout(String refreshTokenStr) {
        RefreshToken token = refreshTokenRepository.findByToken(refreshTokenStr).orElse(null);
        if (token != null) {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            log.info("Logout successful for user: {}", token.getUser().getUsername());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionResponse> getSessions(User currentUser) {
        return sessionRepository.findByUserAndIsDeletedFalse(currentUser).stream()
                .map(sessionMapper::toSessionResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void revokeSession(String sessionUuid, User currentUser) {
        Session session = sessionRepository.findByUuid(UUID.fromString(sessionUuid))
                .orElseThrow(() -> new NotFoundException("Session not found", "TM_053"));

        if (!session.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot revoke session of another user", "TM_103");
        }

        session.setDeleted(true);
        sessionRepository.save(session);
    }

    @Override
    @Transactional
    public void revokeAllSessions(User currentUser) {
        List<Session> sessions = sessionRepository.findByUserAndIsDeletedFalse(currentUser);
        for (Session session : sessions) {
            if (!session.isCurrent()) {
                session.setDeleted(true);
                sessionRepository.save(session);
            }
        }
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        // Safe check: do not expose email presence state
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);
        if (user != null) {
            String token = UUID.randomUUID().toString();
            log.info("Safe mock: reset token generated for user {}: {}", user.getUsername(), token);
        }
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        // Mock token validation for reset
        User user = userRepository.findAll().stream().filter(u -> !u.isGuest()).findFirst()
                .orElseThrow(() -> new UnauthorizedException("Reset token invalid or expired", "TM_038"));

        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        // Revoke all tokens on password reset
        refreshTokenRepository.revokeAllUserTokens(user);
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request, User currentUser) {
        if (!passwordEncoder.matches(request.getCurrentPassword(), currentUser.getPasswordHash())) {
            throw new UnauthorizedException("Current password incorrect", "TM_042");
        }

        currentUser.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(currentUser);

        // Revoke all tokens
        refreshTokenRepository.revokeAllUserTokens(currentUser);
    }

    private LoginResponse generateLoginResponse(User user, String userAgent, String ip) {
        // Generate Token pair
        String accessToken = tokenProvider.generateToken(user.getUsername(), user.isGuest());
        String refreshTokenStr = UUID.randomUUID().toString();

        long expiryMs = user.isGuest() ? guestRefreshTokenExpirationMs : refreshTokenExpirationMs;

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(refreshTokenStr)
                .expiresAt(Instant.now().plusMillis(expiryMs))
                .build();

        refreshTokenRepository.save(refreshToken);

        // Save session
        Session session = Session.builder()
                .user(user)
                .userAgent(userAgent)
                .ipAddress(ip)
                .isCurrent(true)
                .build();
        sessionRepository.save(session);

        AuthUserResponse authUser = userMapper.toAuthUserResponse(user);

        JwtTokensResponse jwtTokens = JwtTokensResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenStr)
                .expiresIn(accessTokenExpirationMs / 1000)
                .build();

        return LoginResponse.builder()
                .user(authUser)
                .tokens(jwtTokens)
                .build();
    }

    private Role getOrCreateRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleName).build()));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthUserResponse getCurrentUser(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        return userMapper.toAuthUserResponse(user);
    }

    @Override
    @Transactional
    public AuthUserResponse updateProfile(UpdateProfileRequest request, User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));

        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getProfileImage() != null) {
            user.setProfileImage(request.getProfileImage());
        }
        if (request.getCountry() != null) {
            user.setCountry(request.getCountry());
        }
        if (request.getCity() != null) {
            user.setCity(request.getCity());
        }
        if (request.getMobileNumber() != null) {
            user.setMobileNumber(request.getMobileNumber());
        }
        if (request.getPhone() != null) {
            user.setMobileNumber(request.getPhone());
        }
        if (request.getAge() != null) {
            user.setAge(request.getAge());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getOccupation() != null) {
            user.setOccupation(request.getOccupation());
        }
        if (request.getEducation() != null) {
            user.setEducation(request.getEducation());
        }
        if (request.getInterests() != null) {
            user.getInterests().clear();
            user.getInterests().addAll(request.getInterests());
        }

        user = userRepository.save(user);
        log.info("User profile updated successfully for: {}", user.getUsername());
        return userMapper.toAuthUserResponse(user);
    }
}
