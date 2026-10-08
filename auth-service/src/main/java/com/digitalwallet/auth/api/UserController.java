package com.digitalwallet.auth.api;

import com.digitalwallet.auth.api.dto.AuthDtos.UserLookupResponse;
import com.digitalwallet.auth.api.dto.AuthDtos.UserResponse;
import com.digitalwallet.auth.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Profile and handle lookup")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * The caller's own profile.
     *
     * <p>Identity comes from the verified token's subject claim, never from a parameter, so there
     * is nothing for a caller to tamper with.
     */
    @GetMapping("/me")
    @Operation(summary = "The authenticated user's profile")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.requireById(UUID.fromString(jwt.getSubject())));
    }

    /**
     * Resolves a handle so a sender can confirm who they are about to pay.
     *
     * <p>Authenticated, and returns only handle and display name. It is still an oracle for "does
     * this handle exist", which is unavoidable (you cannot send money to someone without being
     * able to find them), but it deliberately exposes nothing that would help contact or target
     * them elsewhere.
     */
    @GetMapping("/lookup")
    @Operation(summary = "Find a user by handle, to confirm a transfer recipient")
    public UserLookupResponse lookup(@RequestParam String handle) {
        return UserLookupResponse.from(userService.requireByHandle(handle));
    }
}
