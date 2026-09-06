package com.dealflow360.controller;

import com.dealflow360.model.Role;
import com.dealflow360.repository.AppUserRepository;
import com.dealflow360.repository.CustomerRepository;
import com.dealflow360.repository.ProductRepository;
import com.dealflow360.repository.QuotationRepository;
import com.dealflow360.service.DemoDataService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only switch for the bulk demo data set so it can be loaded onto a database that was created
 * before this feature existed - {@code DataSeeder} only runs on an empty database, and builds only
 * the small default batch. Everything past that is added manually, in whatever amount you ask for,
 * via {@link #load}: quotations, extra products, extra customers and extra reps are all independent,
 * additive counts you control - nothing is ever generated unless you ask for it.
 */
@RestController
@RequestMapping("/api/admin/demo-data")
@PreAuthorize("hasRole('ADMIN')")
public class DemoDataController {

    private final DemoDataService demoDataService;
    private final ProductRepository productRepository;
    private final QuotationRepository quotationRepository;
    private final CustomerRepository customerRepository;
    private final AppUserRepository appUserRepository;

    public DemoDataController(DemoDataService demoDataService, ProductRepository productRepository,
                              QuotationRepository quotationRepository, CustomerRepository customerRepository,
                              AppUserRepository appUserRepository) {
        this.demoDataService = demoDataService;
        this.productRepository = productRepository;
        this.quotationRepository = quotationRepository;
        this.customerRepository = customerRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", demoDataService.isLoaded());
        m.put("products", productRepository.count());
        m.put("quotations", quotationRepository.count());
        m.put("customers", customerRepository.count());
        m.put("salesReps", appUserRepository.findByRole(Role.SALES_REP).size());
        return m;
    }

    /**
     * Adds a batch: {@code quotations} more quotations (default 300, max 5000 per call),
     * {@code extraProducts} more generated products (default 0, max 5000), {@code extraCustomers}
     * more generated companies (default 0, max 5000) and {@code extraReps} more generated sales reps
     * (default 0, max 500). Every count is independent and purely additive - call it as often as you
     * like, with whatever numbers you like, to grow the data set to any size.
     */
    @PostMapping("/load")
    public DemoDataService.Summary load(@RequestParam(defaultValue = "300") int quotations,
                                        @RequestParam(defaultValue = "0") int extraProducts,
                                        @RequestParam(defaultValue = "0") int extraCustomers,
                                        @RequestParam(defaultValue = "0") int extraReps,
                                        Authentication auth) {
        return demoDataService.load(auth.getName(), quotations, extraProducts, extraCustomers, extraReps);
    }
}
