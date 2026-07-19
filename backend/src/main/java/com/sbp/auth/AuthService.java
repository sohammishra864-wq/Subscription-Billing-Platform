package com.sbp.auth;

import com.sbp.auth.dto.*;
import com.sbp.common.exception.BadRequestException;
import com.sbp.common.exception.ConflictException;
import com.sbp.config.JwtConfig;
import com.sbp.security.JwtTokenProvider;
import com.sbp.user.Role;
import com.sbp.user.User;
import com.sbp.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider, JwtConfig jwtConfig) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtConfig = jwtConfig;
    }

    @Transactional
    public UUID register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already registered");
        }
        var user = new User(request.email(), passwordEncoder.encode(request.password()), Role.CUSTOMER);
        return userRepository.save(user).getId();
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadRequestException("Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Invalid credentials");
        }
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        String hash = hashToken(request.refreshToken());
        var stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Refresh token expired or revoked");
        }
        stored.revoke();
        refreshTokenRepository.save(stored);

        var user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BadRequestException("User not found"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        String hash = hashToken(refreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(t -> {
            t.revoke();
            refreshTokenRepository.save(t);
        });
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        var entity = new RefreshToken(
                user.getId(),
                hashToken(refreshToken),
                Instant.now().plusMillis(jwtConfig.getRefreshTokenExpiry())
        );
        refreshTokenRepository.save(entity);
        return new TokenResponse(accessToken, refreshToken);
    }

    private String hashToken(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
