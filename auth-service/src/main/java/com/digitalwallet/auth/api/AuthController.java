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
import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
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
    private final RefreshTokenCookie refreshCookie;

    public AuthController(UserService userService,
                          AccessTokenIssuer accessTokenIssuer,
                          RefreshTokenService refreshTokenService,
                          WalletClient walletClient,
                          RefreshTokenCookie refreshCookie) {
        this.userService = userService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.walletClient = walletClient;
        this.refreshCookie = refreshCookie;
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

    /**
     * Exchanges credentials for an access token and a refresh token.
     *
     * <p>With {@code X-Token-Transport: cookie} the refresh token is set as an httpOnly cookie and
     * left out of the body, so browser code never holds it. Without the header, both tokens come
     * back in the body as before.
     */
    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for an access token and a refresh token")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest http) {
        AppUser user = userService.authenticate(request.email(), request.password());
        return tokens(user, refreshTokenService.issue(user), http);
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>Public rather than authenticated, deliberately: the whole point of refreshing is that the
     * access token has expired, so requiring a valid one would make the endpoint unusable exactly
     * when it is needed. The refresh token is itself the credential.
     *
     * <p>The token is read from the body, or — when the request carries
     * {@code X-Token-Transport: cookie} — from the refresh cookie.
     */
    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token and get a new access token")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody(required = false) RefreshRequest request,
                                                 HttpServletRequest http) {
        String presented = presentedToken(request == null ? null : request.refreshToken(), http)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "A refresh token is required"));
        RotationResult rotation = refreshTokenService.rotate(presented);
        return tokens(rotation.user(), rotation.rawRefreshToken(), http);
    }

    /**
     * Ends the session the refresh token belongs to.
     *
     * <p>Also public, for the same reason: a user whose access token has expired must still be able
     * to invalidate their session. Possession of the refresh token is the authorisation, and
     * revoking a token you already hold gains an attacker nothing.
     *
     * <p>Returns 204 whether or not the token existed — confirming which tokens are real would let
     * a caller probe them. A cookie-mode logout also deletes the cookie.
     */
    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) LogoutRequest request,
                                       HttpServletRequest http) {
        presentedToken(request == null ? null : request.refreshToken(), http)
                .ifPresent(refreshTokenService::revoke);

        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        if (refreshCookie.requested(http)) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString());
        }
        return response.build();
    }

    /**
     * A token in the body wins. The cookie is consulted only on requests that opted in with the
     * transport header — never on its own, since the browser attaches it to any request to this
     * path, including ones another site triggers.
     */
    private Optional<String> presentedToken(String fromBody, HttpServletRequest http) {
        if (fromBody != null && !fromBody.isBlank()) {
            return Optional.of(fromBody);
        }
        return refreshCookie.requested(http) ? refreshCookie.read(http) : Optional.empty();
    }

    private ResponseEntity<TokenResponse> tokens(AppUser user, String rawRefreshToken, HttpServletRequest http) {
        IssuedAccessToken accessToken = accessTokenIssuer.issue(user);

        if (refreshCookie.requested(http)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(rawRefreshToken).toString())
                    .body(TokenResponse.of(accessToken.value(), accessToken.expiresInSeconds(), null));
        }
        return ResponseEntity.ok(
                TokenResponse.of(accessToken.value(), accessToken.expiresInSeconds(), rawRefreshToken));
    }
}
