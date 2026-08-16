package com.digitalwallet.auth.api.dto;

import com.digitalwallet.auth.user.AppUser;
import com.digitalwallet.auth.user.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Wire types for the auth API.
 *
 * <p>No response type here carries {@code passwordHash} or a token hash. That is not an oversight
 * to be careful about — the fields simply do not exist on these records, so there is no code path
 * that could serialize them.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    // ---------------------------------------------------------------- requests

    public record RegisterRequest(
            @NotBlank
            @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$",
                    message = "handle must be 3-32 characters: letters, digits or underscore")
            @Schema(description = "Public identifier others use to send money. Stored lowercase.",
                    example = "alice_r")
            String handle,

            @NotBlank @Email @Size(max = 255)
            String email,

            @NotBlank
            @Size(min = 12, max = 128,
                    message = "password must be at least 12 characters")
            @Schema(description = "Minimum 12 characters. Length beats complexity rules for "
                    + "resisting guessing, and it is far less annoying than symbol requirements.")
            String password,

            @NotBlank @Size(max = 120)
            String fullName) {
    }

    public record LoginRequest(@NotBlank @Email String email,
                               @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(@NotBlank String refreshToken) {
    }

    // --------------------------------------------------------------- responses

    public record RegisterResponse(UUID userId,
                                   String handle,
                                   String email,
                                   @Schema(description = "False if wallet-service could not be reached; "
                                           + "the wallet is then created on first access")
                                   boolean walletProvisioned) {
    }

    @Schema(description = "A short-lived access token plus the refresh token that replaces it.")
    public record TokenResponse(
            @Schema(description = "RS256 JWT. Send as: Authorization: Bearer <token>")
            String accessToken,

            String tokenType,

            @Schema(description = "Seconds until the access token expires", example = "900")
            long expiresIn,

            @Schema(description = "Opaque. Single use — refreshing returns a new one and kills this one.")
            String refreshToken) {

        public static TokenResponse of(String accessToken, long expiresIn, String refreshToken) {
            return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken);
        }
    }

    public record UserResponse(UUID id,
                               String handle,
                               String email,
                               String fullName,
                               String status,
                               List<String> roles) {

        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getHandle(), user.getEmail(),
                    user.getFullName(), user.getStatus().name(),
                    user.getRoles().stream().map(Role::name).sorted().toList());
        }
    }

    /**
     * What one user may see about another.
     *
     * <p>Handle and display name only — enough to confirm you are sending money to the right
     * person, and no email address, which would turn the lookup into an address harvester.
     */
    public record UserLookupResponse(UUID userId, String handle, String fullName) {

        public static UserLookupResponse from(AppUser user) {
            return new UserLookupResponse(user.getId(), user.getHandle(), user.getFullName());
        }
    }
}
