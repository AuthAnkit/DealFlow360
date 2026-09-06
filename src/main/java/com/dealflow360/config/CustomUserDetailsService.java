package com.dealflow360.config;

import com.dealflow360.model.AppUser;
import com.dealflow360.model.Customer;
import com.dealflow360.repository.AppUserRepository;
import com.dealflow360.repository.CustomerRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * One login mechanism serves both internal staff (AppUser) and the
 * Customer Portal (Customer.portalUsername/portalPassword) - internal
 * users get their configured role (ADMIN / SALES_REP / SALES_MANAGER /
 * FINANCE), portal users always get ROLE_CUSTOMER. This keeps
 * authentication simple (plain HTTP Basic) while still giving the
 * customer portal a genuinely separate, restricted set of endpoints
 * (enforced in SecurityConfig / @PreAuthorize), matching the PDF's
 * requirement that the portal be "a real, separate, restricted view".
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;
    private final CustomerRepository customerRepository;

    public CustomUserDetailsService(AppUserRepository appUserRepository, CustomerRepository customerRepository) {
        this.appUserRepository = appUserRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return appUserRepository.findByUsername(username)
                .map(this::toUserDetails)
                .orElseGet(() -> customerRepository.findByPortalUsername(username)
                        .map(this::toUserDetails)
                        .orElseThrow(() -> new UsernameNotFoundException("No user or customer with username " + username)));
    }

    private UserDetails toUserDetails(AppUser user) {
        return new User(user.getUsername(), user.getPassword(),
                user.isActive(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    private UserDetails toUserDetails(Customer customer) {
        return new User(customer.getPortalUsername(), customer.getPortalPassword(),
                true, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
