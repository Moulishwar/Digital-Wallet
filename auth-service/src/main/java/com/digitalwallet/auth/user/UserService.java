package com.digitalwallet.auth.user;

import com.digitalwallet.auth.config.AdminProperties;
import com.digitalwallet.common.error.ApiException;
import com.digitalwallet.common.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and credential checking.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * A valid BCrypt hash of a value nobody knows, used to burn the same CPU time on a login for
     * an address that does not exist as one that does. Without it, "no such user" returns
     * measurably faster than "wrong password", and that difference is enough to enumerate which
     * email addresses are registered.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7uYMEZ2LkuBLKAlgtmOfDrxOOFDvnLm";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Set<String> adminEmails;

    public UserService(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                       AdminProperties adminProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmails = adminProperties.normalizedEmails();
    }

    @Transactional
    public AppUser register(String handle, String email, String rawPassword, String fullName) {
        String normalizedHandle = AppUser.normalizeHandle(handle);
        String normalizedEmail = AppUser.normalizeEmail(email);

        // Handles are checked explicitly because they are a public identifier — you send money to
        // one, and there is a lookup endpoint for them — so "that handle is taken" reveals nothing
        // a determined caller could not already discover, and vague failures here are hostile UX.
        if (userRepository.existsByHandle(normalizedHandle)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That handle is already taken");
        }

        // Email is treated differently. Saying "this email is already registered" to an
        // unauthenticated caller confirms that a given person has an account here, which is an
        // account-enumeration vector, so the message stays deliberately vague. The real fix is to
        // always return 201 and resolve it over email; that needs a mail pipeline this project
        // does not have, and pretending otherwise would be worse than saying so.
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Registration could not be completed with the details provided");
        }

        AppUser user = AppUser.register(normalizedHandle, normalizedEmail,
                passwordEncoder.encode(rawPassword), fullName);

        try {
            AppUser saved = userRepository.save(user);
            log.info("Registered user {} ({})", saved.getId(), saved.getHandle());
            return saved;
        } catch (DataIntegrityViolationException raced) {
            // Two registrations for the same handle or email at the same instant. The unique
            // indexes settle it; this just turns the database error into a sensible response.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Registration could not be completed with the details provided", raced);
        }
    }

    /**
     * Verifies credentials.
     *
     * <p>Every failure — unknown address, wrong password, suspended account — returns the same
     * message. Distinguishing them would tell an attacker which addresses are worth attacking.
     *
     * <p>A successful login is also where configured administrators are promoted — see
     * {@link AdminProperties}. Doing it here rather than at registration means an address can be
     * added to the list after the account already exists.
     */
    @Transactional
    public AppUser authenticate(String email, String rawPassword) {
        AppUser user = userRepository.findByEmail(AppUser.normalizeEmail(email)).orElse(null);

        if (user == null) {
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!user.getStatus().canAuthenticate()) {
            throw invalidCredentials();
        }
        if (adminEmails.contains(user.getEmail()) && user.grantRole(Role.ROLE_ADMIN)) {
            log.info("Granted ROLE_ADMIN to user {} from the configured admin list", user.getId());
        }
        return user;
    }

    @Transactional(readOnly = true)
    public AppUser requireById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Unknown user"));
    }

    @Transactional(readOnly = true)
    public AppUser requireByHandle(String handle) {
        return userRepository.findByHandle(AppUser.normalizeHandle(handle))
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "No user with that handle"));
    }

    private ApiException invalidCredentials() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Invalid email or password");
    }
}
