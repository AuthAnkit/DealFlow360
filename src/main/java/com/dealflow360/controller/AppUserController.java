package com.dealflow360.controller;

import com.dealflow360.model.AppUser;
import com.dealflow360.model.Role;
import com.dealflow360.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Internal user management (Admin - "User management, role assignment,
 * permission updates"). Staff accounts (Sales Rep / Sales Manager /
 * Finance / Admin) are created and maintained here; this is the backend
 * counterpart of the "Add staff user" screen in admin-users.html - before
 * that screen existed, creating a login for a new teammate required
 * calling this API directly, since {@code DataSeeder} only ever creates
 * the four demo accounts.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','FINANCE')")
public class AppUserController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AppUserController(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<AppUser> list() {
        return appUserRepository.findAll();
    }

    @GetMapping("/sales-reps")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_MANAGER','FINANCE','SALES_REP')")
    public List<AppUser> salesReps() {
        return appUserRepository.findByRole(Role.SALES_REP);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AppUser create(@RequestBody AppUser user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
        }
        if (appUserRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username already exists");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required");
        }
        user.setId(null);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return appUserRepository.save(user);
    }

    /**
     * Edits an existing staff account: full name, email, role and active
     * status always; the password only if a new one was actually typed
     * (so re-saving the form without touching the password field never
     * wipes it). Username is intentionally immutable here - it is what
     * every past {@code AuditEntry}/{@code ApprovalLog} action was logged
     * against, so silently renaming it would make old history harder to
     * read for no real benefit.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AppUser update(@PathVariable Long id, @RequestBody AppUser updated) {
        AppUser existing = appUserRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        existing.setFullName(updated.getFullName());
        existing.setEmail(updated.getEmail());
        if (updated.getRole() != null) {
            existing.setRole(updated.getRole());
        }
        existing.setActive(updated.isActive());
        if (updated.getPassword() != null && !updated.getPassword().isBlank()) {
            existing.setPassword(passwordEncoder.encode(updated.getPassword()));
        }
        return appUserRepository.save(existing);
    }
}
