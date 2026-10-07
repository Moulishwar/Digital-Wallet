package com.digitalwallet.auth.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A registered user.
 *
 * <p>{@code passwordHash} has a getter because the login path needs to compare against it, but it
 * never leaves this service: response DTOs are separate classes and none of them carries it, so
 * there is no path by which it can be serialized.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "handle", nullable = false, length = 32, updatable = false)
    private String handle;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    /**
     * Eagerly fetched because every token issued for this user embeds their roles, so there is no
     * meaningful case where the user is loaded without them.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
        // for JPA
    }

    private AppUser(String handle, String email, String passwordHash, String fullName) {
        this.id = UUID.randomUUID();
        this.handle = handle;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = UserStatus.ACTIVE;
        this.roles = EnumSet.of(Role.ROLE_USER);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * @param passwordHash already hashed — this constructor never sees a plaintext password, so
     *                     there is no way to accidentally persist one
     */
    public static AppUser register(String handle, String email, String passwordHash, String fullName) {
        return new AppUser(normalizeHandle(handle), normalizeEmail(email), passwordHash, fullName);
    }

    /** Handles are matched case-insensitively by storing them folded, as the schema requires. */
    public static String normalizeHandle(String handle) {
        return handle == null ? null : handle.trim().toLowerCase();
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getHandle() {
        return handle;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    /** @return true if the role was newly granted */
    public boolean grantRole(Role role) {
        return roles.add(role);
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
