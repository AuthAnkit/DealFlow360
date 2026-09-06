package com.dealflow360.repository;

import com.dealflow360.model.CustomerTier;
import com.dealflow360.model.PriceListEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PriceListEntryRepository extends JpaRepository<PriceListEntry, Long> {
    Optional<PriceListEntry> findByTierAndProductId(CustomerTier tier, Long productId);
    List<PriceListEntry> findByProductId(Long productId);
}
