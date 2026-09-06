package com.dealflow360.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

/**
 * A B2B customer / account. Also doubles as the Customer Portal login
 * (magic-link style is out of scope for a hackathon build - a simple
 * separate username/password on the Customer record satisfies "portal
 * login (magic link, or email and password)" from the PDF, A1).
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerTier tier;

    /** Portal login credentials (separate from internal AppUser accounts). */
    @Column(unique = true)
    private String portalUsername;

    /**
     * WRITE_ONLY (not @JsonIgnore) so the admin "Add/edit customer" screen can
     * actually set this from the request body - @JsonIgnore on a field hides
     * it from Jackson in both directions, which silently dropped every
     * incoming portalPassword and left new customer-portal accounts with no
     * usable password. WRITE_ONLY still keeps the hash out of every response.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String portalPassword;

    public Customer() {
    }

    public Customer(String name, String email, CustomerTier tier, String portalUsername, String portalPassword) {
        this.name = name;
        this.email = email;
        this.tier = tier;
        this.portalUsername = portalUsername;
        this.portalPassword = portalPassword;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public CustomerTier getTier() {
        return tier;
    }

    public void setTier(CustomerTier tier) {
        this.tier = tier;
    }

    public String getPortalUsername() {
        return portalUsername;
    }

    public void setPortalUsername(String portalUsername) {
        this.portalUsername = portalUsername;
    }

    public String getPortalPassword() {
        return portalPassword;
    }

    public void setPortalPassword(String portalPassword) {
        this.portalPassword = portalPassword;
    }
}
