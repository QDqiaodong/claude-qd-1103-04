package com.milktea.bar.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.milktea.bar.entity.CupOrderItem;

public interface CupOrderItemRepository extends JpaRepository<CupOrderItem, Long> {
}
