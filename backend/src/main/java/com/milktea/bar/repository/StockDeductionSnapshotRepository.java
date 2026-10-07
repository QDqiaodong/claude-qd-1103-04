package com.milktea.bar.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.StockDeductionSnapshot;

public interface StockDeductionSnapshotRepository extends JpaRepository<StockDeductionSnapshot, Long> {

    List<StockDeductionSnapshot> findByOrderId(Long orderId);
}
