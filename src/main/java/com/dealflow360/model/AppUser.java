package com.dealflow360.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

/**
 * Internal application user (Sales Rep, Sales Manager, Finance, Admin).
 * Customers use a separate, restricted login on {@link Customer} - see
 * "B8) Customer Portal Negotiation Screen": it must be a real, separate,
 * restricted view, not just another internal screen with a different label.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /**
     * BCrypt-encoded password. WRITE_ONLY (not the previous @JsonIgnore) is
     * deliberate: @JsonIgnore on a field hides it from Jackson in BOTH
     * directions, so the admin "create/edit user" screens could never
     * actually set a password - the incoming JSON's "password" would be
     * silently dropped during deserialization and this field would stay
     * null, which is exactly the bug this fixes. WRITE_ONLY still keeps the
     * hash out of every outgoing API response.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String fullName;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    public AppUser() {
    }

    public AppUser(String username, String password, String fullName, String email, Role role) {
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
