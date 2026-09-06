package com.dealflow360.repository;

import com.dealflow360.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByPortalUsername(String portalUsername);
}
