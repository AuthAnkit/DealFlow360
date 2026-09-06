package com.dealflow360.repository;

import com.dealflow360.model.CustomerTier;
import com.dealflow360.model.DiscountCeiling;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscountCeilingRepository extends JpaRepository<DiscountCeiling, Long> {
    Optional<DiscountCeiling> findByTierAndCategory(CustomerTier tier, String category);
    List<DiscountCeiling> findByTier(CustomerTier tier);
}
