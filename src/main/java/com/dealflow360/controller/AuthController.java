package com.dealflow360.controller;

import com.dealflow360.dto.AuthDtos.LoginResponse;
import com.dealflow360.dto.AuthDtos.SignupRequest;
import com.dealflow360.model.AppUser;
import com.dealflow360.model.Customer;
import com.dealflow360.model.Role;
import com.dealflow360.repository.AppUserRepository;
import com.dealflow360.repository.CustomerRepository;
import com.dealflow360.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A single endpoint the frontend calls right after the user types
 * username/password: if the Authorization header is valid, Spring
 * Security lets the request through and this simply reports back who is
 * logged in (and with which role), which the frontend uses to decide
 * which screens to show.
 * <p>
 * PDF A1 / "Complete Flow" step 1 - "Sales rep signs up (first time) or logs
 * in": {@link #signup} lets an internal user self-register. A self-registered
 * account is always a Sales Rep - Manager/Finance/Admin roles carry approval
 * power, so those are granted only by an Admin from the Staff Users screen.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository appUserRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthController(AppUserRepository appUserRepository, CustomerRepository customerRepository,
                          PasswordEncoder passwordEncoder, AuditService auditService) {
        this.appUserRepository = appUserRepository;
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @GetMapping("/me")
    public LoginResponse me(Authentication authentication) {
        String username = authentication.getName();

        return appUserRepository.findByUsername(username)
                .map(u -> new LoginResponse(u.getUsername(), u.getFullName(), u.getRole().name(), null))
                .orElseGet(() -> {
                    Customer customer = customerRepository.findByPortalUsername(username)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
                    return new LoginResponse(customer.getPortalUsername(), customer.getName(), "CUSTOMER", customer.getId());
                });
    }

    /** Public self-registration for internal sales reps (no login required to call this). */
    @PostMapping("/signup")
    public LoginResponse signup(@RequestBody SignupRequest request) {
        String username = request.username == null ? "" : request.username.trim();
        if (username.length() < 3 || !username.matches("[A-Za-z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be at least 3 characters (letters, digits, . _ - only)");
        }
        if (request.password == null || request.password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        if (request.fullName == null || request.fullName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name is required");
        }
        if (appUserRepository.findByUsername(username).isPresent() || customerRepository.findByPortalUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken");
        }
        AppUser user = new AppUser(username, passwordEncoder.encode(request.password), request.fullName.trim(),
                request.email == null ? "" : request.email.trim(), Role.SALES_REP);
        user = appUserRepository.save(user);
        auditService.log("AppUser", user.getId(), "USER_SIGNED_UP", username, "Self-registered as Sales Rep");
        return new LoginResponse(user.getUsername(), user.getFullName(), user.getRole().name(), null);
    }
}
