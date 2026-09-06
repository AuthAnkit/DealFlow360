package com.dealflow360.repository;

import com.dealflow360.model.StockLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {
    List<StockLevel> findByProductIdOrderByWarehouse_ShippingCostWeightAsc(Long productId);
    Optional<StockLevel> findByWarehouseIdAndProductId(Long warehouseId, Long productId);
}
