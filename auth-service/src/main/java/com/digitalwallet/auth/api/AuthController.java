package com.digitalwallet.auth.api;

import com.digitalwallet.auth.api.dto.AuthDtos.LoginRequest;
import com.digitalwallet.auth.api.dto.AuthDtos.LogoutRequest;
import com.digitalwallet.auth.api.dto.AuthDtos.RefreshRequest;
import com.digitalwallet.auth.api.dto.AuthDtos.RegisterRequest;
import com.digitalwallet.auth.api.dto.AuthDtos.RegisterResponse;
import com.digitalwallet.auth.api.dto.AuthDtos.TokenResponse;
import com.digitalwallet.auth.token.AccessTokenIssuer;
import com.digitalwallet.auth.token.AccessTokenIssuer.IssuedAccessToken;
import com.digitalwallet.auth.token.RefreshTokenService;
import com.digitalwallet.auth.token.RefreshTokenService.RotationResult;
import com.digitalwallet.auth.user.AppUser;
import com.digitalwallet.auth.user.UserService;
import com.digitalwallet.auth.wallet.WalletClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration, login and token lifecycle")
public class AuthController {

    private final UserService userService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final WalletClient walletClient;

    public AuthController(UserService userService,
                          AccessTokenIssuer accessTokenIssuer,
                          RefreshTokenService refreshTokenService,
                          WalletClient walletClient) {
        this.userService = userService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.walletClient = walletClient;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account and provision its wallet")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = userService.register(
                request.handle(), request.email(), request.password(), request.fullName());

        // Registration has already committed. If this fails the user still has an account, and
        // wallet-service provisions the wallet on first access.
        boolean walletProvisioned = walletClient.provisionWallet(user.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(
                user.getId(), user.getHandle(), user.getEmail(), walletProvisioned));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access token and a refresh token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = userService.authenticate(request.email(), request.password());
        IssuedAccessToken accessToken = accessTokenIssuer.issue(user);
        String refreshToken = refreshTokenService.issue(user);
        return TokenResponse.of(accessToken.value(), accessToken.expiresInSeconds(), refreshToken);
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>Public rather than authenticated, deliberately: the whole point of refreshing is that the
     * access token has expired, so requiring a valid one would make the endpoint unusable exactly
     * when it is needed. The refresh token is itself the credential.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token and get a new access token")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        RotationResult rotation = refreshTokenService.rotate(request.refreshToken());
        IssuedAccessToken accessToken = accessTokenIssuer.issue(rotation.user());
        return TokenResponse.of(accessToken.value(), accessToken.expiresInSeconds(),
                rotation.rawRefreshToken());
    }

    /**
     * Ends the session the refresh token belongs to.
     *
     * <p>Also public, for the same reason: a user whose access token has expired must still be able
     * to invalidate their session. Possession of the refresh token is the authorisation, and
     * revoking a token you already hold gains an attacker nothing.
     *
     * <p>Returns 204 whether or not the token existed — confirming which tokens are real would let
     * a caller probe them.
     */
    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
