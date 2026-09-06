package com.dealflow360.controller;

import com.dealflow360.model.Customer;
import com.dealflow360.repository.CustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Customer master (Admin backend setup) + portal credential management. */
@RestController
@RequestMapping("/api/customers")
@PreAuthorize("hasAnyRole('ADMIN','SALES_REP','SALES_MANAGER','FINANCE')")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    public CustomerController(CustomerRepository customerRepository, PasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<Customer> list() {
        return customerRepository.findAll();
    }

    @GetMapping("/{id}")
    public Customer get(@PathVariable Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Customer create(@RequestBody Customer customer) {
        customer.setId(null);
        if (customer.getPortalPassword() != null && !customer.getPortalPassword().isBlank()) {
            customer.setPortalPassword(passwordEncoder.encode(customer.getPortalPassword()));
        }
        return customerRepository.save(customer);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Customer update(@PathVariable Long id, @RequestBody Customer updated) {
        Customer existing = get(id);
        existing.setName(updated.getName());
        existing.setEmail(updated.getEmail());
        existing.setTier(updated.getTier());
        if (updated.getPortalUsername() != null && !updated.getPortalUsername().isBlank()) {
            existing.setPortalUsername(updated.getPortalUsername());
        }
        if (updated.getPortalPassword() != null && !updated.getPortalPassword().isBlank()) {
            existing.setPortalPassword(passwordEncoder.encode(updated.getPortalPassword()));
        }
        return customerRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        customerRepository.deleteById(id);
    }
}
